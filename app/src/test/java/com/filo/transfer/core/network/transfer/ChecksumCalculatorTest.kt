package com.filo.transfer.core.network.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class ChecksumCalculatorTest {

    @Test
    fun testEmptyInputKnownSha256() {
        val calc = ChecksumCalculator()
        val hash = calc.finalizeChecksum()
        // SHA-256 of empty string is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun testKnownStringSha256() {
        // "Filo" SHA-256: 0a9e701985392e202976b91122ef7ce5a18a56b4685ffecfd6f9588fb71337b5 (or verify with MessageDigest)
        val data = "Filo Fast Transfer".toByteArray(Charsets.UTF_8)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        val calc = ChecksumCalculator()
        calc.update(data)
        assertEquals(expected, calc.finalizeChecksum())
    }

    @Test
    fun testStreamingUpdatesMatchAllAtOnce() {
        val data = ByteArray(100_000) { (it % 251).toByte() }

        // All at once
        val calcSingle = ChecksumCalculator()
        calcSingle.update(data)
        val singleHash = calcSingle.finalizeChecksum()

        // In streaming chunks of 1024 bytes
        val calcStream = ChecksumCalculator()
        val chunkSize = 1024
        var offset = 0
        while (offset < data.size) {
            val len = (data.size - offset).coerceAtMost(chunkSize)
            calcStream.update(data, offset, len)
            offset += len
        }
        val streamHash = calcStream.finalizeChecksum()

        assertEquals(singleHash, streamHash)
    }

    @Test
    fun testCalculateFromInputStream() {
        val data = "Streaming test content from input stream".toByteArray(Charsets.UTF_8)
        val bin = ByteArrayInputStream(data)
        val hash = ChecksumCalculator.calculate(bin)

        val directCalc = ChecksumCalculator()
        directCalc.update(data)
        assertEquals(directCalc.finalizeChecksum(), hash)
    }

    @Test
    fun testResetProducesCleanDigest() {
        val calc = ChecksumCalculator()
        calc.update("dirty data".toByteArray())
        calc.reset()

        val emptyHash = calc.finalizeChecksum()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
    }

    @Test
    fun testDifferentDataProducesDifferentHash() {
        val calc1 = ChecksumCalculator()
        calc1.update("file data A".toByteArray())
        val hash1 = calc1.finalizeChecksum()

        val calc2 = ChecksumCalculator()
        calc2.update("file data B".toByteArray())
        val hash2 = calc2.finalizeChecksum()

        assertNotEquals(hash1, hash2)
    }
}
