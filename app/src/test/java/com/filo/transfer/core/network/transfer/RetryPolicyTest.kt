package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.NetworkError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class RetryPolicyTest {

    @Test
    fun testClassifiesRetryableExceptions() {
        val policy = RetryPolicy()
        assertTrue(policy.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(policy.isRetryable(SocketException("reset")))
        assertTrue(policy.isRetryable(NetworkError.Timeout("timeout")))
        assertTrue(policy.isRetryable(NetworkError.ConnectionFailed("reset")))
        assertTrue(policy.isRetryable(IOException("stream broken")))
    }

    @Test
    fun testClassifiesNonRetryableExceptions() {
        val policy = RetryPolicy()
        assertFalse(policy.isRetryable(CancellationException("cancelled")))
        assertFalse(policy.isRetryable(NetworkError.TransferCancelled("cancelled")))
        assertFalse(policy.isRetryable(NetworkError.ChecksumMismatch("a", "b")))
        assertFalse(policy.isRetryable(NetworkError.ProtocolVersionMismatch(1, 2)))
        assertFalse(policy.isRetryable(NetworkError.InvalidFrame("bad frame")))
        assertFalse(policy.isRetryable(NetworkError.OversizedPayload(300, 200)))
        assertFalse(policy.isRetryable(NetworkError.UnsafeFilename("..")))
        assertFalse(policy.isRetryable(NetworkError.UnsafeRelativePath("a/../b")))
        assertFalse(policy.isRetryable(NetworkError.InvalidOffset(10, 5)))
        assertFalse(
            policy.isRetryable(
                NetworkError.PeerFingerprintMismatch("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            )
        )
    }

    @Test
    fun testExecuteSucceedsFirstTry() = runBlocking {
        val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 10L)
        var count = 0
        val result = policy.execute {
            count++
            "success"
        }
        assertEquals("success", result)
        assertEquals(1, count)
    }

    @Test
    fun testExecuteRetriesAndSucceeds() = runBlocking {
        val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 10L)
        var count = 0
        val result = policy.execute { attempt ->
            count++
            if (attempt < 2) {
                throw SocketTimeoutException("flaky socket")
            }
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, count)
    }

    @Test
    fun testExecuteFailsAfterMaxRetries() = runBlocking {
        val policy = RetryPolicy(maxRetries = 2, initialDelayMs = 10L)
        var count = 0
        assertThrows(SocketException::class.java) {
            runBlocking {
                policy.execute {
                    count++
                    throw SocketException("persistently broken")
                }
            }
        }
        assertEquals(3, count) // attempt 0, 1, 2
    }

    @Test
    fun testExecuteNonRetryableFailsImmediatelyWithoutRetry() = runBlocking {
        val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 10L)
        var count = 0
        assertThrows(NetworkError.ChecksumMismatch::class.java) {
            runBlocking {
                policy.execute {
                    count++
                    throw NetworkError.ChecksumMismatch("expected", "actual")
                }
            }
        }
        assertEquals(1, count)
    }
}
