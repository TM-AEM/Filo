package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.model.TransferProgress
import com.filo.transfer.core.network.model.TransferState
import com.filo.transfer.core.network.protocol.FramePayloads
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.security.handshake.HandshakeError
import com.filo.transfer.core.network.security.handshake.HandshakeFraming
import com.filo.transfer.core.network.security.handshake.KeystoreSigningIdentity
import com.filo.transfer.core.network.security.handshake.SecureHandshake
import com.filo.transfer.core.network.security.handshake.SigningIdentity
import com.filo.transfer.core.network.transport.SecureTransportState
import com.filo.transfer.core.network.transport.SocketConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-throughput, streaming TCP transfer engine for sending files over a [SocketConnection].
 *
 * Enforces bounded memory buffers, incremental SHA-256 calculation, deterministic file ordering,
 * real-time throughput metrics, pause/resume coordination, and immediate cancellation cleanup.
 *
 * All post-handshake application traffic runs over the Task 21G/21A secure transport:
 * the initiator completes a crypto [SecureHandshake] before any manifest or data frame is sent.
 */
class TransferSender(
    val deviceName: String = "FiloSender",
    val signingIdentity: SigningIdentity = KeystoreSigningIdentity()
) {
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val pauseMutex = Mutex()

    private var activeConnection: SocketConnection? = null

    /**
     * Pauses the active transfer without busy-waiting or thread-blocking.
     */
    suspend fun pause() {
        if (!isPaused.getAndSet(true)) {
            pauseMutex.lock()
            val current = _state.value
            if (current is TransferState.Transferring) {
                transitionTo(TransferState.Paused(current.progress))
            }
        }
    }

    /**
     * Resumes the paused transfer.
     */
    fun resume() {
        if (isPaused.getAndSet(false)) {
            if (pauseMutex.isLocked) {
                pauseMutex.unlock()
            }
        }
    }

    /**
     * Cancels the active transfer immediately, aborting blocking I/O and closing sockets.
     */
    fun cancel() {
        if (!isCancelled.getAndSet(true)) {
            // Unpause if paused so coroutine can exit
            if (pauseMutex.isLocked) {
                pauseMutex.unlock()
            }
            try {
                activeConnection?.let { conn ->
                    try {
                        conn.sendFrame(
                            ProtocolFrame(
                                type = FrameType.CANCEL,
                                payload = FramePayloads.encodeControlMessage("Sender cancelled transfer")
                            )
                        )
                    } catch (_: Throwable) {}
                    conn.close()
                }
            } finally {
                transitionTo(TransferState.Cancelled)
            }
        }
    }

    /**
     * Transfers the files defined in [manifest] to the peer connected via [connection].
     */
    suspend fun transfer(
        connection: SocketConnection,
        manifest: TransferManifest,
        fileSources: Map<String, TransferFileSource>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        activeConnection = connection
        isCancelled.set(false)
        isPaused.set(false)

        try {
            // 1. Initial State
            transitionTo(TransferState.Connecting)

            // Sort files deterministically by fileId
            val sortedFiles = manifest.files.sortedBy { it.fileId }
            val totalBytesOverall = sortedFiles.sumOf { it.size.coerceAtLeast(0L) }
            val metrics = TransferMetrics(
                totalFiles = sortedFiles.size,
                totalBytesOverall = totalBytesOverall
            )

            // 2. Handshake Phase
            transitionTo(TransferState.Handshaking)
            performHandshake(connection)

            // 3. Manifest Exchange Phase
            exchangeManifest(connection, manifest.copy(files = sortedFiles))

            // 4. File Streaming Phase
            for ((index, fileItem) in sortedFiles.withIndex()) {
                checkCancelled()

                val source = fileSources[fileItem.fileId]
                    ?: throw NetworkError.IoError("Missing file source for fileId: ${fileItem.fileId}")

                transferSingleFile(
                    connection = connection,
                    fileItem = fileItem,
                    fileSource = source,
                    fileIndex = index,
                    totalFiles = sortedFiles.size,
                    metrics = metrics
                )
            }

            // 5. Completion Phase
            checkCancelled()
            val completePayload = FramePayloads.encodeComplete(
                transferId = manifest.transferId,
                totalFiles = sortedFiles.size,
                totalBytes = metrics.totalBytesTransferred
            )
            connection.sendFrame(
                ProtocolFrame(
                    type = FrameType.COMPLETE,
                    payload = completePayload
                )
            )

            transitionTo(
                TransferState.Completed(
                    totalFiles = sortedFiles.size,
                    totalBytes = metrics.totalBytesTransferred,
                    durationMs = metrics.totalDurationMs
                )
            )

            Result.success(Unit)
        } catch (e: CancellationException) {
            cancel()
            Result.failure(NetworkError.TransferCancelled("Transfer cancelled", e))
        } catch (e: NetworkError.TransferCancelled) {
            cancel()
            Result.failure(e)
        } catch (e: Throwable) {
            val netError = if (e is NetworkError) e else NetworkError.IoError(e.message ?: "Transfer error", e)
            transitionTo(TransferState.Failed(netError))
            try {
                connection.sendFrame(
                    ProtocolFrame(
                        type = FrameType.ERROR,
                        payload = FramePayloads.encodeControlMessage(netError.message)
                    )
                )
            } catch (_: Throwable) {}
            connection.close()
            Result.failure(netError)
        } finally {
            activeConnection = null
            if (pauseMutex.isLocked) {
                pauseMutex.unlock()
            }
        }
    }

    private fun performHandshake(connection: SocketConnection) {
        try {
            val initiatorState = SecureHandshake.initiate(signingIdentity)

            connection.sendHandshakeFrame(
                FrameType.HELLO,
                HandshakeFraming.helloPayload(initiatorState.localMessage)
            )

            val ackFrame = connection.receiveHandshakeFrame()
            if (ackFrame.type != FrameType.HELLO_ACK) {
                throw NetworkError.HandshakeFailed("Expected HELLO_ACK frame, got: ${ackFrame.type}")
            }
            val split = HandshakeFraming.decodeHelloAck(ackFrame.payload)

            val completion = SecureHandshake.completeAsInitiator(
                state = initiatorState,
                peerMsg = split.message,
                peerSig = split.signature
            )

            connection.sendHandshakeFrame(FrameType.HANDSHAKE_FINISH, completion.signatureToSend)

            connection.enterSecure(SecureTransportState.forInitiator(completion.session))
        } catch (e: HandshakeError) {
            throw NetworkError.HandshakeFailed("Initiator handshake failed: ${e.category}", e)
        } catch (e: GeneralSecurityException) {
            throw NetworkError.HandshakeFailed("Cryptographic operation failed during handshake", e)
        }
    }

    private fun exchangeManifest(connection: SocketConnection, manifest: TransferManifest) {
        val manifestPayload = FramePayloads.encodeManifest(manifest)
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.MANIFEST,
                payload = manifestPayload
            )
        )

        val ackFrame = connection.receiveFrame()
        if (ackFrame.type != FrameType.MANIFEST_ACK) {
            throw NetworkError.InvalidFrame("Expected MANIFEST_ACK frame, got: ${ackFrame.type}")
        }
        val ack = FramePayloads.decodeManifestAck(ackFrame.payload)
        if (!ack.accepted) {
            throw NetworkError.HandshakeFailed("Peer rejected manifest: ${ack.reason}")
        }
    }

    private suspend fun transferSingleFile(
        connection: SocketConnection,
        fileItem: ManifestFileItem,
        fileSource: TransferFileSource,
        fileIndex: Int,
        totalFiles: Int,
        metrics: TransferMetrics
    ) {
        // Send FILE_HEADER
        val headerPayload = FramePayloads.encodeFileHeader(
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            fileId = fileItem.fileId,
            fileName = fileItem.fileName,
            fileSize = fileItem.size,
            mimeType = fileItem.mimeType
        )
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.FILE_HEADER,
                sequence = fileIndex.toLong(),
                payload = headerPayload
            )
        )

        // Receive RESUME_REQUEST
        val resumeReqFrame = connection.receiveFrame()
        if (resumeReqFrame.type != FrameType.RESUME_REQUEST) {
            throw NetworkError.InvalidFrame("Expected RESUME_REQUEST frame, got: ${resumeReqFrame.type}")
        }
        val resumeReq = FramePayloads.decodeResumeRequest(resumeReqFrame.payload)

        // Validate resume offset
        val requestedOffset = resumeReq.offset
        if (requestedOffset < 0 || requestedOffset > fileItem.size) {
            throw NetworkError.InvalidOffset(requestedOffset, fileItem.size)
        }

        // Send RESUME_RESPONSE
        val resumeRespPayload = FramePayloads.encodeResumeResponse(
            fileId = fileItem.fileId,
            confirmedOffset = requestedOffset,
            accepted = true
        )
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.RESUME_RESPONSE,
                payload = resumeRespPayload
            )
        )

        // Prepare Checksum calculation
        // If resuming at offset > 0, compute SHA-256 for the full file using fileSource stream
        // If starting at 0, we can compute it on the fly during chunk streaming
        val fullFileChecksum: String
        val incrementalCalculator: ChecksumCalculator?

        if (requestedOffset > 0) {
            transitionTo(TransferState.Verifying(fileItem.fileName, fileIndex, totalFiles))
            fileSource.openStream(0).use { isStream ->
                fullFileChecksum = ChecksumCalculator.calculate(isStream)
            }
            incrementalCalculator = null
        } else {
            incrementalCalculator = ChecksumCalculator()
            fullFileChecksum = ""
        }

        // Stream data chunks with bounded memory
        var currentOffset = requestedOffset
        var streamBytesTransferred = 0L

        fileSource.openStream(requestedOffset).use { inputStream ->
            val buffer = ByteArray(ProtocolConstants.DEFAULT_CHUNK_SIZE)
            var bytesRead: Int

            while (currentOffset < fileItem.size) {
                checkCancelled()
                checkPaused()

                val toRead = (fileItem.size - currentOffset).coerceAtMost(buffer.size.toLong()).toInt()
                bytesRead = inputStream.read(buffer, 0, toRead)
                if (bytesRead == -1) break

                incrementalCalculator?.update(buffer, 0, bytesRead)

                // Send DATA_CHUNK frame
                val chunkPayload = if (bytesRead == buffer.size) {
                    buffer
                } else {
                    buffer.copyOf(bytesRead)
                }

                connection.sendFrame(
                    ProtocolFrame(
                        type = FrameType.DATA_CHUNK,
                        sequence = currentOffset,
                        payload = chunkPayload
                    )
                )

                currentOffset += bytesRead
                streamBytesTransferred += bytesRead
                metrics.recordBytes(bytesRead.toLong())

                val progress = metrics.createProgress(
                    currentFileName = fileItem.fileName,
                    currentFileIndex = fileIndex,
                    bytesTransferredForCurrentFile = currentOffset,
                    currentFileSize = fileItem.size
                )
                transitionTo(TransferState.Transferring(progress))
            }
        }

        if (currentOffset < fileItem.size) {
            throw NetworkError.IoError(
                "Source stream ended prematurely: $currentOffset bytes sent vs declared ${fileItem.size} bytes"
            )
        }

        // Checksum verification
        transitionTo(TransferState.Verifying(fileItem.fileName, fileIndex, totalFiles))
        val sha256 = if (requestedOffset > 0) {
            fullFileChecksum
        } else {
            incrementalCalculator?.finalizeChecksum() ?: ""
        }

        val checksumPayload = FramePayloads.encodeChecksum(
            fileId = fileItem.fileId,
            sha256Hex = sha256
        )
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.CHECKSUM,
                payload = checksumPayload
            )
        )

        // Wait for CHECKSUM_RESULT from receiver
        val resultFrame = connection.receiveFrame()
        if (resultFrame.type != FrameType.CHECKSUM_RESULT) {
            throw NetworkError.InvalidFrame("Expected CHECKSUM_RESULT frame, got: ${resultFrame.type}")
        }
        val result = FramePayloads.decodeChecksumResult(resultFrame.payload)
        if (!result.matched) {
            throw NetworkError.ChecksumMismatch(
                expected = sha256,
                actual = "Receiver reported integrity mismatch"
            )
        }
    }

    private suspend fun checkPaused() {
        if (isPaused.get()) {
            pauseMutex.lock()
            pauseMutex.unlock()
            val current = _state.value
            if (current is TransferState.Paused) {
                transitionTo(TransferState.Transferring(current.progress))
            }
        }
    }

    private fun checkCancelled() {
        if (isCancelled.get()) {
            throw NetworkError.TransferCancelled("Transfer was cancelled by sender")
        }
    }

    private fun transitionTo(newState: TransferState) {
        val current = _state.value
        // Guard legal state transitions
        if (current is TransferState.Completed || current is TransferState.Cancelled || current is TransferState.Failed) {
            // Terminal states cannot transition to non-terminal states
            return
        }
        _state.value = newState
    }
}
