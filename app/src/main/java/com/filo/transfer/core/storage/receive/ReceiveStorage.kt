package com.filo.transfer.core.storage.receive

import java.io.File

/**
 * Staging storage for incoming transfers.
 *
 * Partial files live in app-private filesystem state until checksum verification
 * succeeds. Completed files are then published through [CompletedFilePublisher]
 * (MediaStore on device, or a no-op in JVM tests).
 */
interface ReceiveStorage {
    fun createPartialDestination(safeFileName: String): PartialDestination

    fun finalizeDestination(partial: PartialDestination, finalName: String): File?

    fun cleanupDestination(partial: PartialDestination): Boolean
}

interface PartialDestination {
    val path: File
    val metadataPath: File
    val resumeOffset: Long

    fun markMetadata(transferId: String, fileId: String): Boolean
    fun readMetadata(): Pair<String, String>?
}

/**
 * Publishes a completed, checksum-verified file to user-visible storage.
 * Incomplete files must never be published.
 */
interface CompletedFilePublisher {
    fun publish(completedFile: File, displayName: String, mimeType: String): Boolean
    fun deleteUnpublished(displayName: String, mimeType: String)
}
