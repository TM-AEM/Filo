package com.filo.transfer.core.network.protocol

import com.filo.transfer.core.network.model.ManifestFileItem
import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.model.TransferManifest
import com.filo.transfer.core.network.security.FilenameValidator
import com.filo.transfer.core.network.security.RelativePathValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * High-performance binary serialization for Filo protocol frame payloads.
 *
 * Guarantees strict input validation, length bounds, and safe deserialization.
 */
object FramePayloads {

    // --- 1. HELLO & HELLO_ACK ---

    data class HelloPayload(
        val version: Byte,
        val deviceName: String,
        val sessionId: String
    )

    fun encodeHello(version: Byte, deviceName: String, sessionId: String): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeByte(version.toInt())
            dos.writeUTF(deviceName.take(255))
            dos.writeUTF(sessionId.take(255))
        }
        return bout.toByteArray()
    }

    fun decodeHello(payload: ByteArray): HelloPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val version = dis.readByte()
                val deviceName = dis.readUTF()
                val sessionId = dis.readUTF()
                return HelloPayload(version, deviceName, sessionId)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode HELLO payload", e)
        }
    }

    data class HelloAckPayload(
        val version: Byte,
        val accepted: Boolean,
        val deviceName: String
    )

    fun encodeHelloAck(version: Byte, accepted: Boolean, deviceName: String): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeByte(version.toInt())
            dos.writeBoolean(accepted)
            dos.writeUTF(deviceName.take(255))
        }
        return bout.toByteArray()
    }

    fun decodeHelloAck(payload: ByteArray): HelloAckPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val version = dis.readByte()
                val accepted = dis.readBoolean()
                val deviceName = dis.readUTF()
                return HelloAckPayload(version, accepted, deviceName)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode HELLO_ACK payload", e)
        }
    }

    // --- 2. MANIFEST & MANIFEST_ACK ---

    fun encodeManifest(manifest: TransferManifest): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(manifest.transferId.take(255))
            dos.writeUTF(manifest.senderDeviceName.take(255))
            dos.writeInt(manifest.files.size)
            for (file in manifest.files) {
                dos.writeUTF(file.fileId.take(255))
                // Ensure only safe base filename is transmitted, never real local path
                val safeName = FilenameValidator.sanitize(file.fileName)
                dos.writeUTF(safeName.take(255))
                dos.writeLong(file.size)
                dos.writeUTF(file.mimeType.take(255))
                dos.writeLong(file.lastModified)
                // Optional trailing field: legacy peers simply stop reading here.
                writeOptional(dos, file.relativePath.take(RelativePathValidator.MAX_PATH_LENGTH))
            }
        }
        return bout.toByteArray()
    }

    fun decodeManifest(payload: ByteArray): TransferManifest {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val transferId = dis.readUTF()
                val senderDeviceName = dis.readUTF()
                val fileCount = dis.readInt()
                if (fileCount < 0 || fileCount > 10_000) {
                    throw NetworkError.InvalidFrame("Invalid manifest file count: $fileCount")
                }
                val files = ArrayList<ManifestFileItem>(fileCount)
                for (i in 0 until fileCount) {
                    val fileId = dis.readUTF()
                    val fileName = dis.readUTF()
                    val size = dis.readLong()
                    val mimeType = dis.readUTF()
                    val lastModified = dis.readLong()

                    if (size < 0) {
                        throw NetworkError.InvalidFrame("Invalid negative file size ($size) in manifest")
                    }
                    if (!FilenameValidator.isSafe(fileName)) {
                        throw NetworkError.UnsafeFilename(fileName)
                    }

                    // Optional trailing field: absent when the payload ends here (legacy peers).
                    val relativePath = readOptional(dis)
                    if (!RelativePathValidator.isSafe(relativePath)) {
                        throw NetworkError.UnsafeRelativePath(relativePath)
                    }

                    files.add(ManifestFileItem(fileId, fileName, size, mimeType, lastModified, relativePath))
                }
                return TransferManifest(transferId, senderDeviceName, files)
            }
        } catch (e: NetworkError) {
            throw e
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode MANIFEST payload", e)
        }
    }

    data class ManifestAckPayload(
        val transferId: String,
        val accepted: Boolean,
        val reason: String = ""
    )

    fun encodeManifestAck(transferId: String, accepted: Boolean, reason: String = ""): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(transferId.take(255))
            dos.writeBoolean(accepted)
            dos.writeUTF(reason.take(255))
        }
        return bout.toByteArray()
    }

    fun decodeManifestAck(payload: ByteArray): ManifestAckPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val transferId = dis.readUTF()
                val accepted = dis.readBoolean()
                val reason = dis.readUTF()
                return ManifestAckPayload(transferId, accepted, reason)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode MANIFEST_ACK payload", e)
        }
    }

    // --- 3. FILE_HEADER ---

    data class FileHeaderPayload(
        val fileIndex: Int,
        val totalFiles: Int,
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val relativePath: String = ""
    )

    fun encodeFileHeader(
        fileIndex: Int,
        totalFiles: Int,
        fileId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        relativePath: String = ""
    ): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeInt(fileIndex)
            dos.writeInt(totalFiles)
            dos.writeUTF(fileId.take(255))
            val safeName = FilenameValidator.sanitize(fileName)
            dos.writeUTF(safeName.take(255))
            dos.writeLong(fileSize)
            dos.writeUTF(mimeType.take(255))
            // Optional trailing field: legacy peers simply stop reading here.
            writeOptional(dos, relativePath.take(RelativePathValidator.MAX_PATH_LENGTH))
        }
        return bout.toByteArray()
    }

    fun decodeFileHeader(payload: ByteArray): FileHeaderPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val fileIndex = dis.readInt()
                val totalFiles = dis.readInt()
                val fileId = dis.readUTF()
                val fileName = dis.readUTF()
                val fileSize = dis.readLong()
                val mimeType = dis.readUTF()

                if (fileSize < 0) {
                    throw NetworkError.InvalidFrame("Negative file size: $fileSize")
                }
                if (!FilenameValidator.isSafe(fileName)) {
                    throw NetworkError.UnsafeFilename(fileName)
                }

                // Optional trailing field: absent when the payload ends here (legacy peers).
                val relativePath = readOptional(dis)
                if (!RelativePathValidator.isSafe(relativePath)) {
                    throw NetworkError.UnsafeRelativePath(relativePath)
                }

                return FileHeaderPayload(fileIndex, totalFiles, fileId, fileName, fileSize, mimeType, relativePath)
            }
        } catch (e: NetworkError) {
            throw e
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode FILE_HEADER payload", e)
        }
    }

    // --- 4. RESUME REQUEST & RESPONSE ---

    data class ResumeRequestPayload(
        val fileId: String,
        val offset: Long
    )

    fun encodeResumeRequest(fileId: String, offset: Long): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(fileId.take(255))
            dos.writeLong(offset)
        }
        return bout.toByteArray()
    }

    fun decodeResumeRequest(payload: ByteArray): ResumeRequestPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val fileId = dis.readUTF()
                val offset = dis.readLong()
                return ResumeRequestPayload(fileId, offset)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode RESUME_REQUEST payload", e)
        }
    }

    data class ResumeResponsePayload(
        val fileId: String,
        val confirmedOffset: Long,
        val accepted: Boolean
    )

    fun encodeResumeResponse(fileId: String, confirmedOffset: Long, accepted: Boolean): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(fileId.take(255))
            dos.writeLong(confirmedOffset)
            dos.writeBoolean(accepted)
        }
        return bout.toByteArray()
    }

    fun decodeResumeResponse(payload: ByteArray): ResumeResponsePayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val fileId = dis.readUTF()
                val confirmedOffset = dis.readLong()
                val accepted = dis.readBoolean()
                return ResumeResponsePayload(fileId, confirmedOffset, accepted)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode RESUME_RESPONSE payload", e)
        }
    }

    // --- 5. CHECKSUM & CHECKSUM_RESULT ---

    data class ChecksumPayload(
        val fileId: String,
        val sha256Hex: String
    )

    fun encodeChecksum(fileId: String, sha256Hex: String): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(fileId.take(255))
            dos.writeUTF(sha256Hex.take(128))
        }
        return bout.toByteArray()
    }

    fun decodeChecksum(payload: ByteArray): ChecksumPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val fileId = dis.readUTF()
                val sha256Hex = dis.readUTF()
                return ChecksumPayload(fileId, sha256Hex)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode CHECKSUM payload", e)
        }
    }

    data class ChecksumResultPayload(
        val fileId: String,
        val matched: Boolean
    )

    fun encodeChecksumResult(fileId: String, matched: Boolean): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(fileId.take(255))
            dos.writeBoolean(matched)
        }
        return bout.toByteArray()
    }

    fun decodeChecksumResult(payload: ByteArray): ChecksumResultPayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val fileId = dis.readUTF()
                val matched = dis.readBoolean()
                return ChecksumResultPayload(fileId, matched)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode CHECKSUM_RESULT payload", e)
        }
    }

    // --- 6. COMPLETE ---

    data class CompletePayload(
        val transferId: String,
        val totalFiles: Int,
        val totalBytes: Long
    )

    fun encodeComplete(transferId: String, totalFiles: Int, totalBytes: Long): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(transferId.take(255))
            dos.writeInt(totalFiles)
            dos.writeLong(totalBytes)
        }
        return bout.toByteArray()
    }

    fun decodeComplete(payload: ByteArray): CompletePayload {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val transferId = dis.readUTF()
                val totalFiles = dis.readInt()
                val totalBytes = dis.readLong()
                return CompletePayload(transferId, totalFiles, totalBytes)
            }
        } catch (e: Exception) {
            throw NetworkError.InvalidFrame("Failed to decode COMPLETE payload", e)
        }
    }

    // --- 7. CONTROL (CANCEL / ERROR / PAUSE) ---

    fun encodeControlMessage(reason: String): ByteArray {
        val bout = ByteArrayOutputStream()
        DataOutputStream(bout).use { dos ->
            dos.writeUTF(reason.take(255))
        }
        return bout.toByteArray()
    }

    fun decodeControlMessage(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { it.readUTF() }
        } catch (e: Exception) {
            ""
        }
    }

    // --- BACKWARD-COMPATIBLE OPTIONAL FIELDS ---

    /**
     * Writes [value] as a trailing optional UTF field. A `null` or blank value is encoded
     * as an empty string so peers can distinguish "not present" from "present but empty".
     */
    private fun writeOptional(dos: DataOutputStream, value: String?) {
        dos.writeUTF(value ?: "")
    }

    /**
     * Reads a trailing optional UTF field, returning `""` when the payload has no bytes
     * left (i.e. a legacy peer that never wrote the field).
     */
    private fun readOptional(dis: DataInputStream): String {
        if (dis.available() <= 0) return ""
        return dis.readUTF()
    }
}
