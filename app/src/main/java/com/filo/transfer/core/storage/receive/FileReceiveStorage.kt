package com.filo.transfer.core.storage.receive

import com.filo.transfer.core.network.protocol.ProtocolConstants
import java.io.File

/**
 * App-private filesystem staging for receive transfers.
 *
 * Partial files use the existing [ProtocolConstants.PARTIAL_FILE_SUFFIX] naming
 * so resume identity (transferId + fileId metadata) remains compatible with TASK 23.
 */
class FileReceiveStorage(
    private val destinationDir: File
) : ReceiveStorage {

    init {
        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }
    }

    override fun createPartialDestination(safeFileName: String): PartialDestination {
        val partial = File(destinationDir, "$safeFileName${ProtocolConstants.PARTIAL_FILE_SUFFIX}")
        val metadata = File(destinationDir, "$safeFileName${ProtocolConstants.PARTIAL_FILE_SUFFIX}.meta")
        return FilePartialDestination(partial, metadata)
    }

    override fun finalizeDestination(partial: PartialDestination, finalName: String): File? {
        val finalFile = File(destinationDir, finalName)
        val renamed = partial.path.renameTo(finalFile)
        if (!renamed) {
            if (finalFile.exists()) {
                return null
            }
            try {
                partial.path.copyTo(finalFile, overwrite = false)
                partial.path.delete()
            } catch (_: Exception) {
                return null
            }
        }
        partial.metadataPath.delete()
        return if (finalFile.exists()) finalFile else null
    }

    override fun cleanupDestination(partial: PartialDestination): Boolean {
        val partialDeleted = !partial.path.exists() || partial.path.delete()
        val metadataDeleted = !partial.metadataPath.exists() || partial.metadataPath.delete()
        return partialDeleted && metadataDeleted
    }

    private class FilePartialDestination(
        override val path: File,
        override val metadataPath: File
    ) : PartialDestination {

        override val resumeOffset: Long
            get() = if (path.exists()) path.length() else 0L

        override fun markMetadata(transferId: String, fileId: String): Boolean {
            return try {
                metadataPath.writeText("$transferId\n$fileId\n")
                true
            } catch (_: Exception) {
                false
            }
        }

        override fun readMetadata(): Pair<String, String>? {
            return try {
                if (!metadataPath.exists()) return null
                val lines = metadataPath.readText().trim().split("\n")
                if (lines.size < 2) return null
                val transferId = lines[0]
                val fileId = lines[1]
                if (transferId.isBlank() || fileId.isBlank()) null else Pair(transferId, fileId)
            } catch (_: Exception) {
                null
            }
        }
    }
}
