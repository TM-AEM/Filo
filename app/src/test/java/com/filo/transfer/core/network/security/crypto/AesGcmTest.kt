package com.filo.transfer.core.network.security.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AES-256-GCM primitive tests.
 *
 * TEST-01 uses McGrew/Viega GCM Test Case 14 (AES-256, 96-bit IV, empty AAD),
 * the same vector published in NIST CAVP-style AES-GCM 256/96/128 materials.
 */
class AesGcmTest {

    // McGrew/Viega Test Case 14 — AES-256-GCM, 12-byte IV, empty AAD
    private val tc14Key = hex("0000000000000000000000000000000000000000000000000000000000000000")
    private val tc14Nonce = hex("000000000000000000000000")
    private val tc14Plaintext = hex("00000000000000000000000000000000")
    private val tc14Ciphertext = hex("cea7403d4d606b6e074ec5d3baf39d18")
    private val tc14Tag = hex("d0d1c8a799996bf0265b98b5d48ab919")
    private val tc14Aad = ByteArray(0)

    // McGrew/Viega Test Case 16 — AES-256-GCM with 20-byte AAD, 60-byte PT
    private val tc16Key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    private val tc16Nonce = hex("cafebabefacedbaddecaf888")
    private val tc16Aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    private val tc16Plaintext = hex(
        "d9313225f88406e5a55909c5aff5269a" +
            "86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525" +
            "b16aedf5aa0de657ba637b39"
    )
    private val tc16Ciphertext = hex(
        "522dc1f099567d07f47f37a32a84427d" +
            "643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838" +
            "c5f61e6393ba7a0abcc9f662"
    )
    private val tc16Tag = hex("76fc6ece0f4e1768cddf8853bb2d551b")

    @Test
    fun `TEST-01 known AES-256-GCM test vector`() {
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, tc14Plaintext)
        assertEquals(tc14Ciphertext.size + AesGcm.TAG_SIZE, out.size)
        assertArrayEquals(tc14Ciphertext, out.copyOfRange(0, tc14Ciphertext.size))
        assertArrayEquals(tc14Tag, out.copyOfRange(tc14Ciphertext.size, out.size))
    }

    @Test
    fun `TEST-02 encrypt then decrypt returns original plaintext`() {
        val key = hex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
        val nonce = hex("0102030405060708090a0b0c")
        val aad = hex("aa55")
        val plaintext = "filo-aes-gcm-roundtrip".toByteArray()
        val out = AesGcm.encrypt(key, nonce, aad, plaintext)
        val recovered = AesGcm.decrypt(key, nonce, aad, out)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `TEST-03 empty plaintext`() {
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, ByteArray(0))
        assertEquals(AesGcm.TAG_SIZE, out.size)
        val recovered = AesGcm.decrypt(tc14Key, tc14Nonce, tc14Aad, out)
        assertEquals(0, recovered.size)
    }

    @Test
    fun `TEST-04 large plaintext`() {
        val key = hex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        val nonce = hex("ffffffffffffffffffffffff")
        val plaintext = ByteArray(64 * 1024) { i -> (i and 0xFF).toByte() }
        val out = AesGcm.encrypt(key, nonce, ByteArray(0), plaintext)
        val recovered = AesGcm.decrypt(key, nonce, ByteArray(0), out)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun `TEST-05 non-empty AAD known vector`() {
        val out = AesGcm.encrypt(tc16Key, tc16Nonce, tc16Aad, tc16Plaintext)
        assertArrayEquals(tc16Ciphertext, out.copyOfRange(0, tc16Ciphertext.size))
        assertArrayEquals(tc16Tag, out.copyOfRange(tc16Ciphertext.size, out.size))
        val recovered = AesGcm.decrypt(tc16Key, tc16Nonce, tc16Aad, out)
        assertArrayEquals(tc16Plaintext, recovered)
    }

    @Test
    fun `TEST-06 empty AAD`() {
        val combined = ByteArray(tc14Ciphertext.size + tc14Tag.size)
        tc14Ciphertext.copyInto(combined, 0)
        tc14Tag.copyInto(combined, tc14Ciphertext.size)
        val recovered = AesGcm.decrypt(
            tc14Key,
            tc14Nonce,
            tc14Aad,
            combined
        )
        assertArrayEquals(tc14Plaintext, recovered)
    }

    @Test
    fun `TEST-07 modified ciphertext causes authentication failure`() {
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, tc14Plaintext).copyOf()
        out[0] = (out[0].toInt() xor 0x01).toByte()
        assertAuthFailure(tc14Key, tc14Nonce, tc14Aad, out)
    }

    @Test
    fun `TEST-08 modified authentication tag causes authentication failure`() {
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, tc14Plaintext).copyOf()
        out[out.size - 1] = (out[out.size - 1].toInt() xor 0x01).toByte()
        assertAuthFailure(tc14Key, tc14Nonce, tc14Aad, out)
    }

    @Test
    fun `TEST-09 modified AAD causes authentication failure`() {
        val out = AesGcm.encrypt(tc16Key, tc16Nonce, tc16Aad, tc16Plaintext)
        val badAad = tc16Aad.copyOf()
        badAad[0] = (badAad[0].toInt() xor 0x01).toByte()
        assertAuthFailure(tc16Key, tc16Nonce, badAad, out)
    }

    @Test
    fun `TEST-10 wrong AES key causes authentication failure`() {
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, tc14Plaintext)
        val wrongKey = ByteArray(32) { 0x11 }
        assertAuthFailure(wrongKey, tc14Nonce, tc14Aad, out)
    }

    @Test
    fun `TEST-11 invalid key length is rejected`() {
        val nonce = ByteArray(12)
        val aad = ByteArray(0)
        val pt = ByteArray(1)
        try {
            AesGcm.encrypt(ByteArray(16), nonce, aad, pt)
            fail("16-byte key must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            AesGcm.encrypt(ByteArray(31), nonce, aad, pt)
            fail("31-byte key must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            AesGcm.decrypt(ByteArray(0), nonce, aad, ByteArray(16))
            fail("empty key must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `TEST-12 invalid nonce length is rejected`() {
        val key = ByteArray(32)
        val aad = ByteArray(0)
        val pt = ByteArray(1)
        try {
            AesGcm.encrypt(key, ByteArray(11), aad, pt)
            fail("11-byte nonce must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            AesGcm.encrypt(key, ByteArray(13), aad, pt)
            fail("13-byte nonce must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            AesGcm.decrypt(key, ByteArray(0), aad, ByteArray(16))
            fail("empty nonce must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `TEST-13 12-byte nonce succeeds`() {
        assertEquals(12, tc14Nonce.size)
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, tc14Plaintext)
        assertEquals(tc14Plaintext.size + 16, out.size)
    }

    @Test
    fun `TEST-14 authentication failure never returns plaintext`() {
        val out = AesGcm.encrypt(tc14Key, tc14Nonce, tc14Aad, tc14Plaintext).copyOf()
        out[3] = (out[3].toInt() xor 0xFF).toByte()
        var returned: ByteArray? = null
        try {
            returned = AesGcm.decrypt(tc14Key, tc14Nonce, tc14Aad, out)
            fail("modified ciphertext must not decrypt")
        } catch (e: AesGcmException) {
            assertEquals("AUTHENTICATION_FAILED", e.message)
            assertTrue(returned == null)
        }
    }

    private fun assertAuthFailure(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray) {
        try {
            AesGcm.decrypt(key, nonce, aad, data)
            fail("expected AUTHENTICATION_FAILED")
        } catch (e: AesGcmException) {
            assertEquals("AUTHENTICATION_FAILED", e.message)
        }
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
