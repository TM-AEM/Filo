package com.filo.transfer.core.network.transfer

import java.io.InputStream
import java.security.MessageDigest

/**
 * High-performance streaming SHA-256 checksum calculator.
 *
 * Guarantees zero full-file memory allocations by updating the cryptographic
 * digest in fixed, bounded buffers.
 */
class ChecksumCalculator {

    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    /**
     * Updates the running digest with a single chunk of data.
     */
    fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        digest.update(buffer, offset, length)
    }

    /**
     * Finalizes the digest and returns the lowercase 64-character hex string.
     */
    fun finalizeChecksum(): String {
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Resets the internal digest for reuse.
     */
    fun reset() {
        digest.reset()
    }

    companion object {
        private const val DIGEST_BUFFER_SIZE = 64 * 1024 // 64 KiB

        /**
         * Calculates SHA-256 digest directly from an [InputStream] using streaming reads.
         * The stream is consumed up to EOF but is NOT closed by this function.
         */
        fun calculate(inputStream: InputStream): String {
            val calculator = ChecksumCalculator()
            val buffer = ByteArray(DIGEST_BUFFER_SIZE)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                calculator.update(buffer, 0, bytesRead)
            }
            return calculator.finalizeChecksum()
        }
    }
}
