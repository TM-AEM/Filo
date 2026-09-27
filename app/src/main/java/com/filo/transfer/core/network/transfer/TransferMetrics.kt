package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.TransferProgress

/**
 * Calculates transfer metrics including throughput (bytes/second) and ETA (estimated time of arrival).
 *
 * Uses a time-windowed calculation to provide responsive and accurate speed estimations.
 */
class TransferMetrics(
    private val totalFiles: Int,
    private val totalBytesOverall: Long,
    private val windowDurationMs: Long = 1000L
) {
    private val startTimeMs: Long = System.currentTimeMillis()
    private var lastSampleTimeMs: Long = startTimeMs
    private var lastSampleBytes: Long = 0L

    var totalBytesTransferred: Long = 0L
        private set

    var currentSpeedBytesPerSecond: Long = 0L
        private set

    /**
     * Records [bytes] newly transferred for the active session.
     */
    @Synchronized
    fun recordBytes(bytes: Long) {
        totalBytesTransferred += bytes
        val now = System.currentTimeMillis()
        val timeDiff = now - lastSampleTimeMs

        if (timeDiff >= windowDurationMs) {
            val bytesInWindow = totalBytesTransferred - lastSampleBytes
            currentSpeedBytesPerSecond = if (timeDiff > 0) {
                (bytesInWindow * 1000L) / timeDiff
            } else {
                0L
            }
            lastSampleTimeMs = now
            lastSampleBytes = totalBytesTransferred
        }
    }

    /**
     * Builds a snapshot of the current [TransferProgress].
     */
    fun createProgress(
        currentFileName: String,
        currentFileIndex: Int,
        bytesTransferredForCurrentFile: Long,
        currentFileSize: Long
    ): TransferProgress {
        val speed = currentSpeedBytesPerSecond
        val remainingBytes = (totalBytesOverall - totalBytesTransferred).coerceAtLeast(0L)
        val etaSeconds = if (speed > 0) remainingBytes / speed else 0L

        return TransferProgress(
            currentFileName = currentFileName,
            currentFileIndex = currentFileIndex,
            totalFiles = totalFiles,
            bytesTransferredForCurrentFile = bytesTransferredForCurrentFile,
            currentFileSize = currentFileSize,
            totalBytesTransferred = totalBytesTransferred,
            totalBytesOverall = totalBytesOverall,
            speedBytesPerSecond = speed,
            etaSeconds = etaSeconds
        )
    }

    val totalDurationMs: Long
        get() = System.currentTimeMillis() - startTimeMs

    val averageThroughputBytesPerSecond: Long
        get() {
            val duration = totalDurationMs
            return if (duration > 0) (totalBytesTransferred * 1000L) / duration else 0L
        }
}
