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
import com.filo.transfer.core.network.security.FilenameValidator
import com.filo.transfer.core.network.transport.SocketConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-throughput, streaming TCP transfer engine for receiving files over a [SocketConnection].
 *
 * Implements strict partial-file staging (.filo.part), atomic completion upon valid SHA-256
 * verification, resume negotiation, bounded memory streaming, and defense against path traversal.
 */
class TransferReceiver(
    val deviceName: String = "FiloReceiver"
) {
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)
    private var activeConnection: SocketConnection? = null

    /**
     * Cancels the active receive operation, immediately closing sockets and cleaning up.
     */
    fun cancel() {
        if (!isCancelled.getAndSet(true)) {
            try {
                activeConnection?.let { conn ->
                    try {
                        conn.sendFrame(
                            ProtocolFrame(
                                type = FrameType.CANCEL,
                                payload = FramePayloads.encodeControlMessage("Receiver cancelled transfer")
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
     * Receives incoming files from [connection] and saves them safely into [destinationDir].
     *
     * @param connection The active TCP connection to the sender.
     * @param destinationDir The directory where completed files will be saved.
     * @param allowResume Whether to attempt resuming existing partial files.
     */
    suspend fun receive(
        connection: SocketConnection,
        destinationDir: File,
        allowResume: Boolean = true
    ): Result<TransferManifest> = withContext(Dispatchers.IO) {
        activeConnection = connection
        isCancelled.set(false)

        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }

        try {
            // 1. Initial State
            transitionTo(TransferState.Connecting)

            // 2. Handshake Phase
            transitionTo(TransferState.Handshaking)
            performHandshake(connection)

            // 3. Manifest Exchange Phase
            val manifest = receiveManifest(connection)

            // Sort files deterministically
            val sortedFiles = manifest.files.sortedBy { it.fileId }
            val totalBytesOverall = sortedFiles.sumOf { it.size.coerceAtLeast(0L) }
            val metrics = TransferMetrics(
                totalFiles = sortedFiles.size,
                totalBytesOverall = totalBytesOverall
            )

            // 4. File Streaming Phase
            for ((index, fileItem) in sortedFiles.withIndex()) {
                checkCancelled()

                receiveSingleFile(
                    connection = connection,
                    fileItem = fileItem,
                    fileIndex = index,
                    totalFiles = sortedFiles.size,
                    destinationDir = destinationDir,
                    allowResume = allowResume,
                    metrics = metrics
                )
            }

            // 5. Completion Phase
            checkCancelled()
            val completeFrame = connection.receiveFrame()
            if (completeFrame.type != FrameType.COMPLETE) {
                throw NetworkError.InvalidFrame("Expected COMPLETE frame, got: ${completeFrame.type}")
            }

            transitionTo(
                TransferState.Completed(
                    totalFiles = sortedFiles.size,
                    totalBytes = metrics.totalBytesTransferred,
                    durationMs = metrics.totalDurationMs
                )
            )

            Result.success(manifest)
        } catch (e: CancellationException) {
            cancel()
            Result.failure(NetworkError.TransferCancelled("Receive operation cancelled", e))
        } catch (e: NetworkError.TransferCancelled) {
            cancel()
            Result.failure(e)
        } catch (e: Throwable) {
            val netError = if (e is NetworkError) e else NetworkError.IoError(e.message ?: "Receive error", e)
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
        }
    }

    private fun performHandshake(connection: SocketConnection) {
        val helloFrame = connection.receiveFrame()
        if (helloFrame.type != FrameType.HELLO) {
            throw NetworkError.HandshakeFailed("Expected HELLO frame, got: ${helloFrame.type}")
        }
        val hello = FramePayloads.decodeHello(helloFrame.payload)

        val versionMismatch = hello.version != ProtocolConstants.CURRENT_PROTOCOL_VERSION
        val ackPayload = FramePayloads.encodeHelloAck(
            version = ProtocolConstants.CURRENT_PROTOCOL_VERSION,
            accepted = !versionMismatch,
            deviceName = deviceName
        )
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.HELLO_ACK,
                payload = ackPayload
            )
        )

        if (versionMismatch) {
            throw NetworkError.ProtocolVersionMismatch(
                expected = ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt(),
                actual = hello.version.toInt()
            )
        }
    }

    private fun receiveManifest(connection: SocketConnection): TransferManifest {
        val manifestFrame = connection.receiveFrame()
        if (manifestFrame.type != FrameType.MANIFEST) {
            throw NetworkError.InvalidFrame("Expected MANIFEST frame, got: ${manifestFrame.type}")
        }
        val manifest = FramePayloads.decodeManifest(manifestFrame.payload)

        // Security check on all filenames
        for (file in manifest.files) {
            if (!FilenameValidator.isSafe(file.fileName)) {
                val rejectPayload = FramePayloads.encodeManifestAck(
                    transferId = manifest.transferId,
                    accepted = false,
                    reason = "Unsafe filename detected: ${file.fileName}"
                )
                connection.sendFrame(ProtocolFrame(type = FrameType.MANIFEST_ACK, payload = rejectPayload))
                throw NetworkError.UnsafeFilename(file.fileName)
            }
        }

        val ackPayload = FramePayloads.encodeManifestAck(
            transferId = manifest.transferId,
            accepted = true
        )
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.MANIFEST_ACK,
                payload = ackPayload
            )
        )

        return manifest
    }

    private fun receiveSingleFile(
        connection: SocketConnection,
        fileItem: ManifestFileItem,
        fileIndex: Int,
        totalFiles: Int,
        destinationDir: File,
        allowResume: Boolean,
        metrics: TransferMetrics
    ) {
        // 1. Receive and validate FILE_HEADER
        val headerFrame = connection.receiveFrame()
        if (headerFrame.type != FrameType.FILE_HEADER) {
            throw NetworkError.InvalidFrame("Expected FILE_HEADER frame, got: ${headerFrame.type}")
        }
        val header = FramePayloads.decodeFileHeader(headerFrame.payload)

        val safeName = FilenameValidator.sanitize(header.fileName)
        val partialFile = File(destinationDir, "$safeName${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        val finalFile = File(destinationDir, safeName)

        // 2. Partial file & resume negotiation
        var resumeOffset = 0L
        if (allowResume && partialFile.exists()) {
            val existingLen = partialFile.length()
            if (existingLen > 0 && existingLen <= header.fileSize) {
                resumeOffset = existingLen
            } else {
                partialFile.delete()
                resumeOffset = 0L
            }
        }

        val resumeReqPayload = FramePayloads.encodeResumeRequest(header.fileId, resumeOffset)
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.RESUME_REQUEST,
                payload = resumeReqPayload
            )
        )

        val resumeRespFrame = connection.receiveFrame()
        if (resumeRespFrame.type != FrameType.RESUME_RESPONSE) {
            throw NetworkError.InvalidFrame("Expected RESUME_RESPONSE frame, got: ${resumeRespFrame.type}")
        }
        val resumeResp = FramePayloads.decodeResumeResponse(resumeRespFrame.payload)
        if (!resumeResp.accepted || resumeResp.confirmedOffset != resumeOffset) {
            throw NetworkError.InvalidOffset(resumeResp.confirmedOffset, header.fileSize)
        }

        // 3. Initialize Checksum Calculator
        val checksumCalculator = ChecksumCalculator()
        if (resumeOffset > 0) {
            // Prime running checksum with existing partial file prefix
            FileInputStream(partialFile).use { fis ->
                val primeBuf = ByteArray(64 * 1024)
                var primedBytes = 0L
                while (primedBytes < resumeOffset) {
                    val toRead = (resumeOffset - primedBytes).coerceAtMost(primeBuf.size.toLong()).toInt()
                    val bytesRead = fis.read(primeBuf, 0, toRead)
                    if (bytesRead == -1) break
                    checksumCalculator.update(primeBuf, 0, bytesRead)
                    primedBytes += bytesRead
                }
            }
        }

        // 4. Stream data chunks directly to partial file
        var currentBytes = resumeOffset
        val appendMode = resumeOffset > 0

        FileOutputStream(partialFile, appendMode).use { fileOut ->
            while (currentBytes < header.fileSize) {
                checkCancelled()

                val chunkFrame = connection.receiveFrame()
                if (chunkFrame.type != FrameType.DATA_CHUNK) {
                    throw NetworkError.InvalidFrame("Expected DATA_CHUNK frame, got: ${chunkFrame.type}")
                }

                val payload = chunkFrame.payload
                fileOut.write(payload)
                checksumCalculator.update(payload, 0, payload.size)

                currentBytes += payload.size
                metrics.recordBytes(payload.size.toLong())

                val progress = metrics.createProgress(
                    currentFileName = safeName,
                    currentFileIndex = fileIndex,
                    bytesTransferredForCurrentFile = currentBytes,
                    currentFileSize = header.fileSize
                )
                transitionTo(TransferState.Transferring(progress))
            }
            fileOut.flush()
        }

        // 5. Checksum Verification Phase
        transitionTo(TransferState.Verifying(safeName, fileIndex, totalFiles))
        val checksumFrame = connection.receiveFrame()
        if (checksumFrame.type != FrameType.CHECKSUM) {
            throw NetworkError.InvalidFrame("Expected CHECKSUM frame, got: ${checksumFrame.type}")
        }
        val checksumPayload = FramePayloads.decodeChecksum(checksumFrame.payload)

        val computedSha256 = checksumCalculator.finalizeChecksum()
        val matched = computedSha256.equals(checksumPayload.sha256Hex, ignoreCase = true)

        val resultPayload = FramePayloads.encodeChecksumResult(header.fileId, matched)
        connection.sendFrame(
            ProtocolFrame(
                type = FrameType.CHECKSUM_RESULT,
                payload = resultPayload
            )
        )

        if (!matched) {
            // Delete corrupt partial file
            partialFile.delete()
            throw NetworkError.ChecksumMismatch(
                expected = checksumPayload.sha256Hex,
                actual = computedSha256
            )
        }

        // 6. Safe Atomic Completion: Rename .filo.part -> final safe filename
        if (finalFile.exists()) {
            finalFile.delete()
        }
        val renamed = partialFile.renameTo(finalFile)
        if (!renamed) {
            // Fallback for cross-mount points: copy then delete partial
            partialFile.copyTo(finalFile, overwrite = true)
            partialFile.delete()
        }
    }

    private fun checkCancelled() {
        if (isCancelled.get()) {
            throw NetworkError.TransferCancelled("Transfer was cancelled by receiver")
        }
    }

    private fun transitionTo(newState: TransferState) {
        val current = _state.value
        if (current is TransferState.Completed || current is TransferState.Cancelled || current is TransferState.Failed) {
            return
        }
        _state.value = newState
    }
}
