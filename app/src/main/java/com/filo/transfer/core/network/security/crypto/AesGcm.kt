package com.filo.transfer.core.network.security.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmException(category: String) : Exception(category)

/**
 * Isolated AES-256-GCM primitive. Does not construct protocol nonces, AAD, or sequence numbers.
 *
 * Encrypt output is ciphertext followed by a 16-byte authentication tag.
 * Decrypt input is that same combined representation.
 */
object AesGcm {

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = TAG_SIZE * 8

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        validateKey(key)
        validateNonce(nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keyCopy = key.copyOf()
        val nonceCopy = nonce.copyOf()
        try {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyCopy, "AES"),
                GCMParameterSpec(TAG_BITS, nonceCopy)
            )
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            return cipher.doFinal(plaintext)
        } finally {
            keyCopy.fill(0)
            nonceCopy.fill(0)
        }
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        validateKey(key)
        validateNonce(nonce)
        if (ciphertextAndTag.size < TAG_SIZE) {
            throw AesGcmException("AUTHENTICATION_FAILED")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keyCopy = key.copyOf()
        val nonceCopy = nonce.copyOf()
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyCopy, "AES"),
                GCMParameterSpec(TAG_BITS, nonceCopy)
            )
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            return cipher.doFinal(ciphertextAndTag)
        } catch (e: AesGcmException) {
            throw e
        } catch (_: AEADBadTagException) {
            throw AesGcmException("AUTHENTICATION_FAILED")
        } catch (_: Exception) {
            throw AesGcmException("AUTHENTICATION_FAILED")
        } finally {
            keyCopy.fill(0)
            nonceCopy.fill(0)
        }
    }

    private fun validateKey(key: ByteArray) {
        require(key.size == KEY_SIZE) { "AES-256 key must be 32 bytes" }
    }

    private fun validateNonce(nonce: ByteArray) {
        require(nonce.size == NONCE_SIZE) { "GCM nonce must be 12 bytes" }
    }
}
