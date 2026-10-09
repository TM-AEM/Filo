package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.NetworkError
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

/**
 * Encapsulates bounded retry logic for transient network failures.
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 500L,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 3000L
) {

    /**
     * Determines whether a given [Throwable] is transient and eligible for retry.
     */
    fun isRetryable(throwable: Throwable): Boolean {
        // Explicit cancellations and permanent protocol violations must never be retried
        if (throwable is CancellationException) return false
        if (throwable is NetworkError.TransferCancelled) return false
        if (throwable is NetworkError.ChecksumMismatch) return false
        if (throwable is NetworkError.ProtocolVersionMismatch) return false
        if (throwable is NetworkError.InvalidFrame) return false
        if (throwable is NetworkError.OversizedPayload) return false
        if (throwable is NetworkError.UnsafeFilename) return false
        if (throwable is NetworkError.UnsafeRelativePath) return false
        if (throwable is NetworkError.InvalidOffset) return false

        // Transient exceptions eligible for retry
        if (throwable is NetworkError.Timeout) return true
        if (throwable is SocketTimeoutException) return true
        if (throwable is SocketException) return true
        if (throwable is NetworkError.ConnectionFailed) return true
        if (throwable is NetworkError.IoError) return true
        if (throwable is IOException) return true

        return false
    }

    /**
     * Executes the given suspending [block] with bounded retries and exponential backoff.
     */
    suspend fun <T> execute(block: suspend (attempt: Int) -> T): T {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null

        for (attempt in 0..maxRetries) {
            try {
                return block(attempt)
            } catch (t: Throwable) {
                lastException = t
                if (!isRetryable(t) || attempt == maxRetries) {
                    throw t
                }
                delay(currentDelay)
                currentDelay = (currentDelay * backoffMultiplier).toLong().coerceAtMost(maxDelayMs)
            }
        }

        throw lastException ?: IllegalStateException("Retry failed with unknown error")
    }
}
