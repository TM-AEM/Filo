package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.FrameCodec
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.security.crypto.AesGcmException
import com.filo.transfer.core.network.security.crypto.SecureFrameCodec
import com.filo.transfer.core.network.security.crypto.SequenceViolationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an active peer-to-peer TCP connection over a single [Socket].
 *
 * RESOURCE OWNERSHIP:
 * - This class takes exclusive ownership of the underlying [Socket].
 * - It creates, manages, and owns the buffered [InputStream] and [OutputStream].
 * - It guarantees deterministic closure of both streams and the socket on [close],
 *   errors, timeouts, or cancellation.
 */
class SocketConnection(
    private val socket: Socket
) : Closeable {

    private val isClosedFlag = AtomicBoolean(false)
    private val writeLock = Any()
    private val readLock = Any()

    init {
        configureSocket(socket)
    }

    private val rawInputStream: InputStream = socket.getInputStream()
    private val rawOutputStream: OutputStream = socket.getOutputStream()

    private val bufferedIn = BufferedInputStream(rawInputStream, ProtocolConstants.SOCKET_RCV_BUF)
    private val bufferedOut = BufferedOutputStream(rawOutputStream, ProtocolConstants.SOCKET_SND_BUF)

    val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed && !isClosedFlag.get()

    val isClosed: Boolean
        get() = isClosedFlag.get() || socket.isClosed

    val remoteAddress: String
        get() = socket.inetAddress?.hostAddress ?: "unknown"

    val remotePort: Int
        get() = socket.port

    val localPort: Int
        get() = socket.localPort

    @Volatile
    private var secureState: SecureTransportState? = null

    /** True once [enterSecure] has been called; all application frames are encrypted. */
    val isSecure: Boolean
        get() = secureState != null

    /**
     * A handshake-phase frame (Task 21G wire format: VER=2, SEQ=0).
     */
    data class HandshakeFrame(val type: FrameType, val payload: ByteArray)

    /**
     * Transitions this connection to secure mode.
     *
     * After this call, [sendFrame] encrypts/authenticates every application frame via
     * [SecureTransportState] and [receiveFrame] only accepts secure frames.
     * May be called exactly once; subsequent calls fail closed.
     */
    @Throws(NetworkError::class)
    fun enterSecure(state: SecureTransportState) {
        synchronized(writeLock) {
            if (secureState != null) {
                close()
                throw NetworkError.InvalidFrame("Secure state already established")
            }
            secureState = state
        }
    }

    /**
     * Sends a Task 21G handshake frame (HELLO / HELLO_ACK / HANDSHAKE_FINISH).
     *
     * Wire format: MAGIC(4) || VER=2(1) || TYPE(1) || SEQ=0(8) || LEN(4) || PAYLOAD.
     * Handshake frames are only valid BEFORE [enterSecure]; any other frame type,
     * oversized payload, or call after session start fails closed.
     */
    @Throws(NetworkError::class)
    fun sendHandshakeFrame(type: FrameType, payload: ByteArray) {
        if (secureState != null) {
            close()
            throw NetworkError.InvalidFrame("Handshake frames are forbidden after session start")
        }
        if (type !in HANDSHAKE_FRAME_TYPES) {
            throw NetworkError.InvalidFrame("Frame type $type is not a valid handshake frame")
        }
        if (payload.size > ProtocolConstants.MAX_FRAME_PAYLOAD) {
            throw NetworkError.OversizedPayload(payload.size, ProtocolConstants.MAX_FRAME_PAYLOAD)
        }
        ensureOpen()
        synchronized(writeLock) {
            try {
                val dataOut = if (bufferedOut is DataOutputStream) bufferedOut else DataOutputStream(bufferedOut)
                dataOut.write(ProtocolConstants.MAGIC_HEADER)
                dataOut.writeByte(SecureFrameCodec.SECURE_FRAME_VERSION.toInt())
                dataOut.writeByte(type.code.toInt())
                dataOut.writeLong(0L)
                dataOut.writeInt(payload.size)
                if (payload.isNotEmpty()) {
                    dataOut.write(payload)
                }
                dataOut.flush()
            } catch (e: NetworkError) {
                close()
                throw e
            } catch (e: SocketTimeoutException) {
                close()
                throw NetworkError.Timeout("Socket write timed out during handshake", e)
            } catch (e: SocketException) {
                close()
                throw NetworkError.ConnectionFailed("Socket connection broken during handshake send: ${e.message}", e)
            } catch (e: IOException) {
                close()
                throw NetworkError.IoError("I/O error during handshake frame send: ${e.message}", e)
            }
        }
    }

    /**
     * Reads a single Task 21G handshake frame from the peer.
     *
     * Only valid BEFORE [enterSecure]. Any version mismatch, non-handshake frame
     * type, non-zero sequence, or oversized payload fails closed and closes the
     * connection so that no application frames are processed on a broken handshake.
     */
    @Throws(NetworkError::class)
    fun receiveHandshakeFrame(): HandshakeFrame {
        if (secureState != null) {
            close()
            throw NetworkError.InvalidFrame("Handshake frames are forbidden after session start")
        }
        ensureOpen()
        synchronized(readLock) {
            val dataIn = if (bufferedIn is DataInputStream) bufferedIn else DataInputStream(bufferedIn)
            try {
                val header = ByteArray(ProtocolConstants.HEADER_SIZE)
                dataIn.readFully(header)

                if (!header.copyOfRange(0, ProtocolConstants.MAGIC_HEADER.size).contentEquals(ProtocolConstants.MAGIC_HEADER)) {
                    throw NetworkError.InvalidFrame("Invalid protocol magic header during handshake")
                }
                val version = header[4].toInt() and 0xFF
                if (version != SecureFrameCodec.SECURE_FRAME_VERSION.toInt()) {
                    throw NetworkError.ProtocolVersionMismatch(
                        expected = SecureFrameCodec.SECURE_FRAME_VERSION.toInt(),
                        actual = version
                    )
                }
                val type = FrameType.fromCode(header[5])
                    ?: throw NetworkError.InvalidFrame("Unknown handshake frame type code: 0x%02X".format(header[5].toInt() and 0xFF))
                if (type !in HANDSHAKE_FRAME_TYPES) {
                    throw NetworkError.InvalidFrame("Non-handshake frame type $type during handshake")
                }
                val sequence = ByteBuffer.wrap(header, 6, 8).long
                if (sequence != 0L) {
                    throw NetworkError.InvalidFrame("Handshake frame sequence must be 0, got $sequence")
                }
                val payloadLength = ByteBuffer.wrap(header, 14, 4).int
                if (payloadLength < 0 || payloadLength > ProtocolConstants.MAX_FRAME_PAYLOAD) {
                    throw NetworkError.OversizedPayload(payloadLength, ProtocolConstants.MAX_FRAME_PAYLOAD)
                }
                val payload = if (payloadLength > 0) {
                    ByteArray(payloadLength).also { dataIn.readFully(it) }
                } else {
                    ProtocolFrame.EMPTY_PAYLOAD
                }
                return HandshakeFrame(type, payload)
            } catch (e: NetworkError) {
                close()
                throw e
            } catch (e: SocketTimeoutException) {
                close()
                throw NetworkError.Timeout("Socket read timed out after ${ProtocolConstants.SOCKET_TIMEOUT_MS}ms during handshake", e)
            } catch (e: EOFException) {
                close()
                throw NetworkError.ConnectionFailed("Peer closed connection unexpectedly (EOF) during handshake", e)
            } catch (e: SocketException) {
                close()
                throw NetworkError.ConnectionFailed("Socket exception during handshake receive: ${e.message}", e)
            } catch (e: IOException) {
                close()
                throw NetworkError.IoError("I/O error during handshake frame receive: ${e.message}", e)
            }
        }
    }

    /**
     * Sends a binary [ProtocolFrame] to the connected peer.
     *
     * Before [enterSecure]: plaintext Task 1 protocol framing via [FrameCodec].
     * After [enterSecure]: the frame is serialized as the authenticated plaintext
     * and carried inside a secure frame via [SecureTransportState]. Plaintext
     * application traffic is never sent in secure mode.
     *
     * Synchronized to prevent interleaved frame bytes from concurrent callers.
     */
    @Throws(NetworkError::class)
    fun sendFrame(frame: ProtocolFrame) {
        val state = secureState
        ensureOpen()
        synchronized(writeLock) {
            try {
                if (state != null) {
                    val plain = ByteArrayOutputStream()
                    FrameCodec.writeFrame(plain, frame)
                    val secureBytes = state.encodeFrame(plain.toByteArray(), frame.type)
                    bufferedOut.write(secureBytes)
                } else {
                    FrameCodec.writeFrame(bufferedOut, frame)
                }
                bufferedOut.flush()
            } catch (e: NetworkError) {
                close()
                throw e
            } catch (e: IllegalArgumentException) {
                close()
                throw NetworkError.InvalidFrame("Cannot encode frame for secure transport: ${e.message}", e)
            } catch (e: AesGcmException) {
                close()
                throw NetworkError.IoError("Encryption failed during secure frame send", e)
            } catch (e: IllegalStateException) {
                close()
                throw NetworkError.InvalidFrame("Crypto sequence overflow; terminating secure session", e)
            } catch (e: SocketTimeoutException) {
                close()
                throw NetworkError.Timeout("Socket write timed out", e)
            } catch (e: SocketException) {
                close()
                throw NetworkError.ConnectionFailed("Socket connection broken during send: ${e.message}", e)
            } catch (e: IOException) {
                close()
                throw NetworkError.IoError("I/O error during frame send: ${e.message}", e)
            }
        }
    }

    /**
     * Reads a single binary [ProtocolFrame] from the incoming TCP stream.
     *
     * Before [enterSecure]: plaintext Task 1 framing via [FrameCodec].
     * After [enterSecure]: only secure frames are accepted. The 18-byte secure
     * header is read first and the ciphertext length is bounds-checked BEFORE
     * allocating the body, so no unbounded allocation occurs on hostile input.
     * Any authentication, sequence, version, or framing failure fails closed
     * and closes the connection — there is no fallback to plaintext.
     *
     * Synchronized on readLock to guarantee frame integrity.
     */
    @Throws(NetworkError::class)
    fun receiveFrame(): ProtocolFrame {
        val state = secureState
        ensureOpen()
        synchronized(readLock) {
            val dataIn = if (bufferedIn is DataInputStream) bufferedIn else DataInputStream(bufferedIn)
            try {
                if (state != null) {
                    val header = ByteArray(SecureFrameCodec.HEADER_SIZE)
                    dataIn.readFully(header)
                    if (!header.copyOfRange(0, ProtocolConstants.MAGIC_HEADER.size).contentEquals(ProtocolConstants.MAGIC_HEADER)) {
                        throw NetworkError.InvalidFrame("Invalid protocol magic header in secure frame")
                    }
                    // Version must be validated before any length-based bounds check,
                    // matching SecureFrameCodec's decode order (a VER=1 plaintext frame
                    // after session start is a version violation, not a malformed frame).
                    val version = header[4].toInt() and 0xFF
                    if (version != SecureFrameCodec.SECURE_FRAME_VERSION.toInt()) {
                        throw NetworkError.ProtocolVersionMismatch(
                            expected = SecureFrameCodec.SECURE_FRAME_VERSION.toInt(),
                            actual = version
                        )
                    }
                    val ctLength = ByteBuffer.wrap(header, 14, 4).int
                    if (ctLength < SecureFrameCodec.MIN_CT_LENGTH) {
                        throw NetworkError.InvalidFrame("Secure frame ciphertext length out of range")
                    }
                    if (ctLength > SecureFrameCodec.MAX_CT_LENGTH) {
                        throw NetworkError.OversizedPayload(ctLength, SecureFrameCodec.MAX_CT_LENGTH)
                    }
                    val body = ByteArray(ctLength)
                    dataIn.readFully(body)
                    val frameBytes = ByteArray(SecureFrameCodec.HEADER_SIZE + ctLength)
                    header.copyInto(frameBytes)
                    body.copyInto(frameBytes, SecureFrameCodec.HEADER_SIZE)

                    val decoded = state.decodeFrame(frameBytes)
                    return FrameCodec.readFrame(ByteArrayInputStream(decoded.plaintext))
                }
                return FrameCodec.readFrame(bufferedIn)
            } catch (e: NetworkError) {
                close()
                throw e
            } catch (e: SequenceViolationException) {
                close()
                throw NetworkError.InvalidFrame("Secure frame sequence violation; terminating secure session", e)
            } catch (e: AesGcmException) {
                close()
                throw NetworkError.InvalidFrame("Secure frame authentication failed; terminating secure session", e)
            } catch (e: IllegalStateException) {
                close()
                throw NetworkError.InvalidFrame("Crypto sequence overflow; terminating secure session", e)
            } catch (e: EOFException) {
                close()
                throw NetworkError.ConnectionFailed("Peer closed connection unexpectedly (EOF)", e)
            } catch (e: SocketTimeoutException) {
                close()
                throw NetworkError.Timeout("Socket read timed out after ${ProtocolConstants.SOCKET_TIMEOUT_MS}ms", e)
            } catch (e: SocketException) {
                close()
                throw NetworkError.ConnectionFailed("Socket exception during frame receive: ${e.message}", e)
            } catch (e: IOException) {
                close()
                throw NetworkError.IoError("I/O error during frame receive: ${e.message}", e)
            }
        }
    }

    /**
     * Flushes any buffered bytes to the underlying socket.
     */
    fun flush() {
        if (!isClosedFlag.get()) {
            synchronized(writeLock) {
                try {
                    bufferedOut.flush()
                } catch (_: IOException) {
                    // Ignore on flush if already broken
                }
            }
        }
    }

    private fun ensureOpen() {
        if (isClosedFlag.get() || socket.isClosed) {
            throw NetworkError.ConnectionFailed("Socket is already closed")
        }
    }

    override fun close() {
        if (isClosedFlag.compareAndSet(false, true)) {
            // Deterministic teardown of streams and socket
            try {
                bufferedOut.flush()
            } catch (_: Throwable) {}

            try {
                bufferedIn.close()
            } catch (_: Throwable) {}

            try {
                bufferedOut.close()
            } catch (_: Throwable) {}

            try {
                if (!socket.isClosed) {
                    socket.close()
                }
            } catch (_: Throwable) {}
        }
    }

    companion object {
        /** Frame types valid during the Task 21G crypto handshake phase only. */
        private val HANDSHAKE_FRAME_TYPES = setOf(
            FrameType.HELLO,
            FrameType.HELLO_ACK,
            FrameType.HANDSHAKE_FINISH
        )

        /**
         * Applies required socket options based on Filo transfer protocol specifications.
         */
        fun configureSocket(socket: Socket) {
            // TCP_NODELAY: Disable Nagle's algorithm to eliminate 200ms ACK delays for control frames
            socket.tcpNoDelay = true

            // SO_KEEPALIVE: Enable TCP keep-alive probes to detect dead connections
            socket.keepAlive = true

            // SO_TIMEOUT: Socket read timeout to prevent unbounded blocking
            socket.soTimeout = ProtocolConstants.SOCKET_TIMEOUT_MS

            // Buffer size hints for high throughput streaming
            try {
                socket.sendBufferSize = ProtocolConstants.SOCKET_SND_BUF
                socket.receiveBufferSize = ProtocolConstants.SOCKET_RCV_BUF
            } catch (_: SocketException) {
                // System buffer limits may cap this, fallback to OS defaults safely
            }
        }
    }
}
