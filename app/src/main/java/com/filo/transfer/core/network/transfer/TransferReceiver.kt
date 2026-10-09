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
import com.filo.transfer.core.network.security.RelativePathValidator
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-throughput, streaming TCP transfer engine for receiving files over a [SocketConnection].
 *
 * Implements strict partial-file staging (.filo.part), atomic completion upon valid SHA-256
 * verification, resume negotiation, bounded memory streaming, and defense against path traversal.
 *
 * All application traffic runs over the Task 21G/21A secure transport: the responder
 * completes the crypto [SecureHandshake] before accepting any manifest or data frame.
 */
class TransferReceiver(
    val deviceName: String = "FiloReceiver",
    val signingIdentity: SigningIdentity = KeystoreSigningIdentity()
) {
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val pauseMutex = Mutex()
    private var activeConnection: SocketConnection? = null
    private var activePartialFile: File? = null

    suspend fun pause() {
        if (!isPaused.getAndSet(true)) {
            pauseMutex.lock()
            val current = _state.value
            if (current is TransferState.Transferring) {
                transitionTo(TransferState.Paused(current.progress))
            }
        }
    }

    fun resume() {
        if (isPaused.getAndSet(false)) {
            if (pauseMutex.isLocked) {
                pauseMutex.unlock()
            }
        }
    }

    /**
     * Cancels the active receive operation, immediately closing sockets and cleaning up.
     */
    fun cancel() {
        if (!isCancelled.getAndSet(true)) {
            if (pauseMutex.isLocked) {
                pauseMutex.unlock()
            }
            try {
                activePartialFile?.delete()
            } catch (_: Throwable) {}
            activePartialFile = null
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
        isPaused.set(false)
        activePartialFile = null

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
            if (isCancelled.get()) {
                cancel()
                return@withContext Result.failure(
                    NetworkError.TransferCancelled("Receive operation cancelled", e)
                )
            }
            val netError = if (e is NetworkError) e else NetworkError.IoError(e.message ?: "Receive error", e)
            transitionTo(TransferState.Failed(netError))
            val partialFile = activePartialFile
            try {
                val shouldKeep = shouldRetainResumablePartial(partialFile)
                if (partialFile != null && !shouldKeep) {
                    partialFile.delete()
                }
            } catch (_: Throwable) {}
            activePartialFile = null
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
        val helloFrame = connection.receiveHandshakeFrame()
        if (helloFrame.type != FrameType.HELLO) {
            throw NetworkError.HandshakeFailed("Expected HELLO frame, got: ${helloFrame.type}")
        }

        try {
            val initiatorMsg = HandshakeFraming.decodeHello(helloFrame.payload)

            val responderState = SecureHandshake.respond(signingIdentity, initiatorMsg)

            connection.sendHandshakeFrame(
                FrameType.HELLO_ACK,
                HandshakeFraming.helloAckPayload(responderState.localMessage, responderState.fullTranscriptSignature)
            )

            val finishFrame = connection.receiveHandshakeFrame()
            if (finishFrame.type != FrameType.HANDSHAKE_FINISH) {
                throw NetworkError.HandshakeFailed("Expected HANDSHAKE_FINISH frame, got: ${finishFrame.type}")
            }

            val session = SecureHandshake.completeAsResponder(
                state = responderState,
                peerMsg = initiatorMsg,
                peerSig = HandshakeFraming.helloFinishSignature(finishFrame.payload)
            )

            connection.enterSecure(SecureTransportState.forResponder(session))
        } catch (e: HandshakeError) {
            throw NetworkError.HandshakeFailed("Responder handshake failed: ${e.category}", e)
        } catch (e: GeneralSecurityException) {
            throw NetworkError.HandshakeFailed("Cryptographic operation failed during handshake", e)
        }
    }

    private fun receiveManifest(connection: SocketConnection): TransferManifest {
        val manifestFrame = connection.receiveFrame()
        if (manifestFrame.type != FrameType.MANIFEST) {
            throw NetworkError.InvalidFrame("Expected MANIFEST frame, got: ${manifestFrame.type}")
        }
        val manifest = FramePayloads.decodeManifest(manifestFrame.payload)

        // Resource limit validation: reject excessive file counts
        if (manifest.files.size > ProtocolConstants.MAX_FILE_COUNT) {
            val rejectPayload = FramePayloads.encodeManifestAck(
                transferId = manifest.transferId,
                accepted = false,
                reason = "Manifest contains ${manifest.files.size} files, exceeding maximum of ${ProtocolConstants.MAX_FILE_COUNT}"
            )
            connection.sendFrame(ProtocolFrame(type = FrameType.MANIFEST_ACK, payload = rejectPayload))
            throw NetworkError.OversizedPayload(
                length = manifest.files.size,
                maxAllowed = ProtocolConstants.MAX_FILE_COUNT,
                message = "Manifest contains ${manifest.files.size} files, exceeding maximum of ${ProtocolConstants.MAX_FILE_COUNT}"
            )
        }

        // Resource limit validation: reject individual file sizes exceeding limit or negative sizes
        for (file in manifest.files) {
            if (file.size < 0) {
                val rejectPayload = FramePayloads.encodeManifestAck(
                    transferId = manifest.transferId,
                    accepted = false,
                    reason = "File '${file.fileName}' has negative size ${file.size}"
                )
                connection.sendFrame(ProtocolFrame(type = FrameType.MANIFEST_ACK, payload = rejectPayload))
                throw NetworkError.OversizedPayload(
                    length = file.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    maxAllowed = 0,
                    message = "File '${file.fileName}' has negative size ${file.size}"
                )
            }
            if (file.size > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
                val rejectPayload = FramePayloads.encodeManifestAck(
                    transferId = manifest.transferId,
                    accepted = false,
                    reason = "File '${file.fileName}' size ${file.size} bytes exceeds maximum of ${ProtocolConstants.MAX_FILE_SIZE_BYTES} bytes"
                )
                connection.sendFrame(ProtocolFrame(type = FrameType.MANIFEST_ACK, payload = rejectPayload))
                throw NetworkError.OversizedPayload(
                    length = file.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    maxAllowed = ProtocolConstants.MAX_FILE_SIZE_BYTES.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    message = "File '${file.fileName}' size ${file.size} bytes exceeds maximum of ${ProtocolConstants.MAX_FILE_SIZE_BYTES} bytes"
                )
            }
        }

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
            if (!RelativePathValidator.isSafe(file.relativePath)) {
                val rejectPayload = FramePayloads.encodeManifestAck(
                    transferId = manifest.transferId,
                    accepted = false,
                    reason = "Unsafe relative path detected: ${file.relativePath}"
                )
                connection.sendFrame(ProtocolFrame(type = FrameType.MANIFEST_ACK, payload = rejectPayload))
                throw NetworkError.UnsafeRelativePath(file.relativePath)
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

    private suspend fun receiveSingleFile(
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

        // Resolve the destination directory for this file inside the trusted destination
        // root. A non-empty relative path (folder transfer) may create parent directories,
        // but can never escape [destinationDir].
        val validatedRelativePath = RelativePathValidator.validate(header.relativePath)
            ?: throw NetworkError.UnsafeRelativePath(header.relativePath)
        val targetDir = if (validatedRelativePath.isEmpty()) {
            destinationDir
        } else {
            val targetFile = RelativePathValidator.resolveWithinRoot(destinationDir, validatedRelativePath)
            targetFile.parentFile ?: destinationDir
        }

        val partialFile = File(targetDir, "$safeName${ProtocolConstants.PARTIAL_FILE_SUFFIX}")

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

        val isResume = resumeOffset > 0
        val resolvedName = resolveCollisionSafeFilename(targetDir, safeName, isResume)
        val finalFile = File(targetDir, resolvedName)

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
            activePartialFile = partialFile
            while (currentBytes < header.fileSize) {
                checkPaused()
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
            activePartialFile = null
            throw NetworkError.ChecksumMismatch(
                expected = checksumPayload.sha256Hex,
                actual = computedSha256
            )
        }

        val renamed = partialFile.renameTo(finalFile)
        if (!renamed) {
            partialFile.copyTo(finalFile, overwrite = false)
            partialFile.delete()
        }
        activePartialFile = null
    }

    private fun resolveCollisionSafeFilename(dir: File, requestedName: String, isResume: Boolean): String {
        if (isResume) return requestedName
        var candidate = requestedName
        var counter = 1
        while (File(dir, candidate).exists()) {
            val dotIndex = requestedName.lastIndexOf('.')
            if (dotIndex > 0) {
                val base = requestedName.substring(0, dotIndex)
                val ext = requestedName.substring(dotIndex)
                candidate = "${base}_${counter}${ext}"
            } else {
                candidate = "${requestedName}_${counter}"
            }
            counter++
        }
        return candidate
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
            throw NetworkError.TransferCancelled("Transfer was cancelled by receiver")
        }
    }

    private fun shouldRetainResumablePartial(
        partial: File?
    ): Boolean {
        if (partial == null || !partial.exists()) {
            return false
        }
        if (partial.length() <= 0L) {
            return false
        }
        val metaPath = File(partial.parent, "${partial.name}${ProtocolConstants.PARTIAL_FILE_SUFFIX}.meta")
        if (!metaPath.exists()) {
            return false
        }
        val lines = metaPath.readText().trim().split("\n")
        if (lines.size < 2) {
            return false
        }
        val transferId = lines[0]
        val fileId = lines[1]
        return transferId.isNotBlank() && fileId.isNotBlank()
    }

    private fun transitionTo(newState: TransferState) {
        val current = _state.value
        if (current is TransferState.Completed || current is TransferState.Cancelled || current is TransferState.Failed) {
            return
        }
        _state.value = newState
    }
}
