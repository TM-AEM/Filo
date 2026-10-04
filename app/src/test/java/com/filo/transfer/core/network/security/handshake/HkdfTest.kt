package com.filo.transfer.core.network.security.handshake

import org.junit.Test
import org.junit.Assert.*
import com.filo.transfer.core.network.security.crypto.Hkdf
import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper

/**
 * HKDF-SHA256 tests using RFC 5869 test vectors.
 */
class HkdfTest {

    // RFC 5869 Test Case 1
    @Test
    fun rfc5869TestCase1() {
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val length = 42

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, length)

        assertEquals("e6c558ffe7417a3ff867378da2dabcbdcb43665e85371e7d74eec44aaa844b02", bytesToHex(prk))
        assertEquals(
            "16f0215be399b99aa843da50dc819e36c557512945a67875ccb7b76910e0cce79cb12fe6f5423018952a",
            bytesToHex(okm)
        )
    }

    @Test
    fun rfc5869TestCase1Full() {
        // Full RFC 5869 Test Case 1 verification
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val length = 42

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, length)

        // RFC 5869 Test Case 1 expected PRK
        assertEquals(
            "e6c558ffe7417a3ff867378da2dabcbdcb43665e85371e7d74eec44aaa844b02",
            bytesToHex(prk)
        )

        // RFC 5869 Test Case 1 expected OKM (42 bytes)
        assertEquals(
            "16f0215be399b99aa843da50dc819e36c557512945a67875ccb7b76910e0cce79cb12fe6f5423018952a",
            bytesToHex(okm)
        )
    }

    // RFC 5869 Test Case 2
    @Test
    fun rfc5869TestCase2() {
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToBytes(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c"
        )
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val length = 42

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, length)

        assertEquals(
            "c9eb30a3f76231447d6a02b9f6ab09f0c489d7cdfa65018d2c0ff85265b210e3",
            bytesToHex(prk)
        )
        assertEquals(
            "c5a0e36bdbb3c248aae7913daa273d39a8ebfdc93ca888d9b2263ba838748c184a04ec042e4a77206e55",
            bytesToHex(okm)
        )
    }

    // RFC 5869 Test Case 3
    @Test
    fun rfc5869TestCase3() {
        val ikm = hexToBytes(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d"
        )
        val salt = byteArrayOf() // empty salt
        val info = byteArrayOf() // empty info
        val length = 42

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, length)

        assertEquals(
            "0a0fe9b4f5bc91b33b5dcd66c69e83bdc6d2af72fb918e5846167dde80f9159b",
            bytesToHex(prk)
        )
        assertEquals(
            "981d22db0429b2f1c26caf71620079823a01feaeb56c152cd491ea684a98fed098a767b511cd1a24aeb7",
            bytesToHex(okm)
        )
    }

    // Test that extract with empty salt uses zero-filled salt
    @Test
    fun extractWithEmptySalt() {
        val ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val prkWithEmpty = Hkdf.extract(ikm, byteArrayOf())
        val prkWithZeros = Hkdf.extract(ikm, ByteArray(32))
        assertArrayEquals(
            "Empty salt and zero-filled salt must produce identical PRK",
            prkWithEmpty, prkWithZeros
        )
    }

    // Test that expand with empty info is valid
    @Test
    fun expandWithEmptyInfo() {
        val prk = ByteArray(32) { 0x42 }
        val okm = Hkdf.expand(prk, byteArrayOf(), 48)
        assertEquals(48, okm.size)
    }

    // Test that derive() is equivalent to extract() then expand()
    @Test
    fun deriveIsEquivalentToExtractThenExpand() {
        val ikm = SecureRandomWrapper.nextBytes(32)
        val salt = SecureRandomWrapper.nextBytes(16)
        val info = SecureRandomWrapper.nextBytes(8)
        val length = 64

        val oneShot = Hkdf.derive(ikm, salt, info, length)
        val prk = Hkdf.extract(ikm, salt)
        val twoStep = Hkdf.expand(prk, info, length)

        assertArrayEquals("derive() must equal extract()+expand()", oneShot, twoStep)
    }

    // Test that deriveSessionMaterial returns 96 bytes
    @Test
    fun deriveSessionMaterialReturns96Bytes() {
        val sharedSecret = SecureRandomWrapper.nextBytes(32)
        val transcriptHash = SecureRandomWrapper.nextBytes(32)
        val material = Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash)
        assertEquals(96, material.size)
    }

    // Test that directional keys are distinct
    @Test
    fun directionalKeysAreDistinct() {
        val sharedSecret = SecureRandomWrapper.nextBytes(32)
        val transcriptHash = SecureRandomWrapper.nextBytes(32)
        val material = Hkdf.deriveSessionMaterial(sharedSecret, transcriptHash)
        val c2s = material.copyOfRange(0, 32)
        val s2c = material.copyOfRange(32, 64)
        val hk = material.copyOfRange(64, 96)
        assertFalse("c2s and s2c must differ", c2s.contentEquals(s2c))
        assertFalse("c2s and handshake must differ", c2s.contentEquals(hk))
        assertFalse("s2c and handshake must differ", s2c.contentEquals(hk))
    }

    // Test that different transcripts produce different keys
    @Test
    fun differentTranscriptsProduceDifferentKeys() {
        val sharedSecret = SecureRandomWrapper.nextBytes(32)
        val hash1 = SecureRandomWrapper.nextBytes(32)
        val hash2 = SecureRandomWrapper.nextBytes(32)
        val mat1 = Hkdf.deriveSessionMaterial(sharedSecret, hash1)
        val mat2 = Hkdf.deriveSessionMaterial(sharedSecret, hash2)
        assertFalse("Different transcripts must produce different keys", mat1.contentEquals(mat2))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            out[i / 2] = java.lang.Integer.parseInt(hex.substring(i, i + 2), 16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
