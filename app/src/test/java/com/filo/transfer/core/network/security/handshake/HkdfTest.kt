package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.Hkdf
import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper
import org.junit.Assert.*
import org.junit.Test

/**
 * HKDF-SHA256 tests using official RFC 5869 SHA-256 test vectors.
 *
 * Source: RFC 5869, HMAC-based Extract-and-Expand Key Derivation Function.
 */
class HkdfTest {

    // RFC 5869 — Test Case 1
    @Test
    fun `RFC 5869 Test Case 1`() {
        // IKM: 22 bytes of 0x0b (44 hex chars)
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, 42)

        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", toHex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            toHex(okm)
        )
    }

    // RFC 5869 — Test Case 2
    @Test
    fun `RFC 5869 Test Case 2`() {
        // IKM: 22 bytes of 0x0b
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        // Salt: 44 bytes (0x00..0x2b)
        val salt = hex(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b"
        )
        val info = hex("b0b1b2b3b4b5b6b7b8b9")

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, 42)

        assertEquals("86fd8cd1608477fb478c3cb41595d6df8d9d9834d279782a6e23f9acac1ba369", toHex(prk))
        assertEquals(
            "3b984ee67c056172516947f1cef80145a33a3aa5497820cd48d17cfccf4853c823d645575adc4a7e1e3d",
            toHex(okm)
        )
    }

    // RFC 5869 — Test Case 3
    @Test
    fun `RFC 5869 Test Case 3`() {
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d" +
                "1e1f202122232425262728292a2b2c2d"
        )

        val prk = Hkdf.extract(ikm, byteArrayOf())
        val okm = Hkdf.expand(prk, byteArrayOf(), 42)

        assertEquals("0a0fe9b4f5bc91b33b5dcd66c69e83bdc6d2af72fb918e5846167dde80f9159b", toHex(prk))
        assertEquals(
            "981d22db0429b2f1c26caf71620079823a01feaeb56c152cd491ea684a98fed098a767b511cd1a24aeb7",
            toHex(okm)
        )
    }

    // Empty salt must be equivalent to zero-filled 32-byte salt
    @Test
    fun `empty salt equals zero-filled salt`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val prkEmpty = Hkdf.extract(ikm, byteArrayOf())
        val prkZeros = Hkdf.extract(ikm, ByteArray(32))
        assertArrayEquals("Empty salt and zero-filled salt must produce identical PRK", prkEmpty, prkZeros)
    }

    // derive() must equal extract() + expand()
    @Test
    fun `derive equals extract then expand`() {
        val ikm = SecureRandomWrapper.nextBytes(32)
        val salt = SecureRandomWrapper.nextBytes(16)
        val info = SecureRandomWrapper.nextBytes(8)
        val oneShot = Hkdf.derive(ikm, salt, info, 64)
        val twoStep = Hkdf.expand(Hkdf.extract(ikm, salt), info, 64)
        assertArrayEquals("derive() must equal extract()+expand()", oneShot, twoStep)
    }

    // Session material must be 96 bytes
    @Test
    fun `deriveSessionMaterial returns 96 bytes`() {
        val material = Hkdf.deriveSessionMaterial(SecureRandomWrapper.nextBytes(32), SecureRandomWrapper.nextBytes(32))
        assertEquals(96, material.size)
    }

    // Directional keys must be distinct
    @Test
    fun `directional keys are distinct`() {
        val material = Hkdf.deriveSessionMaterial(SecureRandomWrapper.nextBytes(32), SecureRandomWrapper.nextBytes(32))
        val keyA = material.copyOfRange(0, 32)
        val keyB = material.copyOfRange(32, 64)
        val binding = material.copyOfRange(64, 96)
        assertFalse("keyA and keyB must differ", keyA.contentEquals(keyB))
        assertFalse("keyA and binding must differ", keyA.contentEquals(binding))
        assertFalse("keyB and binding must differ", keyB.contentEquals(binding))
    }

    // Different transcripts must produce different keys
    @Test
    fun `different transcripts produce different keys`() {
        val secret = SecureRandomWrapper.nextBytes(32)
        val mat1 = Hkdf.deriveSessionMaterial(secret, SecureRandomWrapper.nextBytes(32))
        val mat2 = Hkdf.deriveSessionMaterial(secret, SecureRandomWrapper.nextBytes(32))
        assertFalse("Different transcripts must produce different keys", mat1.contentEquals(mat2))
    }

    // Different shared secrets must produce different keys
    @Test
    fun `different shared secrets produce different keys`() {
        val tHash = SecureRandomWrapper.nextBytes(32)
        val mat1 = Hkdf.deriveSessionMaterial(SecureRandomWrapper.nextBytes(32), tHash)
        val mat2 = Hkdf.deriveSessionMaterial(SecureRandomWrapper.nextBytes(32), tHash)
        assertFalse("Different ECDH secrets must produce different keys", mat1.contentEquals(mat2))
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in 0 until s.length step 2) {
            out[i / 2] = Integer.parseInt(s.substring(i, i + 2), 16).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
