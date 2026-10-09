package com.filo.transfer.core.storage.receive

import java.io.File

/**
 * Test/JVM publisher that leaves the completed file in the staging directory.
 * Production Android runtime uses [MediaStoreReceivePublisher].
 */
object NoOpCompletedFilePublisher : CompletedFilePublisher {
    override fun publish(completedFile: File, displayName: String, mimeType: String): Boolean = true
    override fun deleteUnpublished(displayName: String, mimeType: String) = Unit
}
