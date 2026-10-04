package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.Hkdf
import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper
import org.junit.Assert.*
import org.junit.Test

/**
 * HKDF-SHA256 tests.
 *
 * RFC 5869 test vectors from Appendix A (SHA-256).
 * Project-specific correctness tests follow.
 */
class HkdfTest {

    // ── RFC 5869 Appendix A.1 ──────────────────────────────────────────

    @Test
    fun `RFC 5869 Test Case 1`() {
        // A.1: IKM = 0x0b * 22, salt = 13 bytes, info = 10 bytes, L = 42
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, 42)

        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            toHex(prk)
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            toHex(okm)
        )
    }

    // ── RFC 5869 Appendix A.2 ──────────────────────────────────────────

    @Test
    fun `RFC 5869 Test Case 2`() {
        // A.2: IKM = 80 bytes (0x00..0x4f), salt = 80 bytes (0x60..0xaf),
        //       info = 80 bytes (0xb0..0xff), L = 82
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f"
        )
        val salt = hex(
            "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        )
        val info = hex(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
        )

        val prk = Hkdf.extract(ikm, salt)
        val okm = Hkdf.expand(prk, info, 82)

        assertEquals(
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            toHex(prk)
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            toHex(okm)
        )
    }

    // ── RFC 5869 Appendix A.3 ──────────────────────────────────────────

    @Test
    fun `RFC 5869 Test Case 3`() {
        // A.3: IKM = 0x0b * 22, empty salt, empty info, L = 42
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")

        val prk = Hkdf.extract(ikm, byteArrayOf())
        val okm = Hkdf.expand(prk, byteArrayOf(), 42)

        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            toHex(prk)
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            toHex(okm)
        )
    }

    // ── Project-specific correctness tests ─────────────────────────────

    @Test
    fun `empty salt equals zero-filled salt`() {
        val ikm = SecureRandomWrapper.nextBytes(32)
        val prkEmpty = Hkdf.extract(ikm, byteArrayOf())
        val prkZeros = Hkdf.extract(ikm, ByteArray(32))
        assertArrayEquals(
            "Empty salt and zero-filled salt must produce identical PRK",
            prkEmpty, prkZeros
        )
    }

    @Test
    fun `derive equals extract then expand`() {
        val ikm = SecureRandomWrapper.nextBytes(32)
        val salt = SecureRandomWrapper.nextBytes(16)
        val info = SecureRandomWrapper.nextBytes(8)
        val oneShot = Hkdf.derive(ikm, salt, info, 64)
        val twoStep = Hkdf.expand(Hkdf.extract(ikm, salt), info, 64)
        assertArrayEquals("derive() must equal extract()+expand()", oneShot, twoStep)
    }

    @Test
    fun `deriveSessionMaterial returns 96 bytes`() {
        val material = Hkdf.deriveSessionMaterial(
            SecureRandomWrapper.nextBytes(32),
            SecureRandomWrapper.nextBytes(32)
        )
        assertEquals(96, material.size)
    }

    @Test
    fun `directional keys are distinct`() {
        val material = Hkdf.deriveSessionMaterial(
            SecureRandomWrapper.nextBytes(32),
            SecureRandomWrapper.nextBytes(32)
        )
        val keyA = material.copyOfRange(0, 32)
        val keyB = material.copyOfRange(32, 64)
        val binding = material.copyOfRange(64, 96)
        assertFalse("keyA and keyB must differ", keyA.contentEquals(keyB))
        assertFalse("keyA and binding must differ", keyA.contentEquals(binding))
        assertFalse("keyB and binding must differ", keyB.contentEquals(binding))
    }

    @Test
    fun `different transcripts produce different keys`() {
        val secret = SecureRandomWrapper.nextBytes(32)
        val mat1 = Hkdf.deriveSessionMaterial(secret, SecureRandomWrapper.nextBytes(32))
        val mat2 = Hkdf.deriveSessionMaterial(secret, SecureRandomWrapper.nextBytes(32))
        assertFalse("Different transcripts must produce different keys", mat1.contentEquals(mat2))
    }

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
