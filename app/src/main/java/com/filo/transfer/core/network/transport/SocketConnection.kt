package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.FrameCodec
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
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

    /**
     * Sends a binary [ProtocolFrame] to the connected peer.
     * Synchronized to prevent interleaved frame bytes from concurrent callers.
     */
    @Throws(NetworkError::class)
    fun sendFrame(frame: ProtocolFrame) {
        ensureOpen()
        synchronized(writeLock) {
            try {
                FrameCodec.writeFrame(bufferedOut, frame)
                bufferedOut.flush()
            } catch (e: NetworkError) {
                close()
                throw e
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
     * Synchronized on readLock to guarantee frame integrity.
     */
    @Throws(NetworkError::class)
    fun receiveFrame(): ProtocolFrame {
        ensureOpen()
        synchronized(readLock) {
            try {
                return FrameCodec.readFrame(bufferedIn)
            } catch (e: NetworkError) {
                close()
                throw e
            } catch (e: SocketTimeoutException) {
                close()
                throw NetworkError.Timeout("Socket read timed out after ${ProtocolConstants.SOCKET_TIMEOUT_MS}ms", e)
            } catch (e: EOFException) {
                close()
                throw NetworkError.ConnectionFailed("Peer closed connection unexpectedly (EOF)", e)
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
