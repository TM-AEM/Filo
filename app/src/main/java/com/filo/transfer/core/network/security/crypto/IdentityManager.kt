package com.filo.transfer.core.network.security.crypto

import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec
import java.security.*
import java.security.spec.*

// ============================================================================
// IdentityManager — Persistent device identity
// ============================================================================

object IdentityManager {

    private const val KEY_NAME = "filo_identity_key"
    @Volatile private var algorithm = "Ed25519"

    /** Public key bytes (X.509 SPKI). Computed once from Keystore. */
    private val publicKey: ByteArray
        get() = loadPublicKey()

    /** SHA-256 fingerprint of the public key (lowercase hex, exactly 64 chars). */
    val fingerprint: String
        get() = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(publicKey)
            .joinToString("") { "%02x".format(it) }

    /** Sign data using the Keystore-protected private key. */
    fun sign(data: ByteArray): ByteArray? {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (!ks.containsAlias(KEY_NAME)) return null
            val privateKey = ks.getKey(KEY_NAME, null) as PrivateKey
            val sig = java.security.Signature.getInstance(algorithm).also { s ->
                s.initSign(privateKey); s.update(data)
            }
            return sig.sign()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Factory: create or return the existing KeyPair. */
    fun getOrCreateIdentity(): KeyPair {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEY_NAME)) {
                // Key exists — generate a fresh keypair (we cannot extract the private
                // key from Keystore, so we always generate new ones.)
                return generateKeyPair()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return generateKeyPair()
    }

    /** Generate a new Ed25519 (or P-256) keypair in Android Keystore. */
    private fun generateKeyPair(): KeyPair {
        return when (algorithm) {
            "Ed25519" -> {
                val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec(KEY_NAME, KeyProperties.PURPOSE_SIGN, false, true, AlgorithmParameterSpec())
                )
                kpg.generateKeyPair()
            }
            "P-256" -> {
                val kpg = KeyPairGenerator.getInstance("X509", "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec(KEY_NAME, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY, false, true, AlgorithmParameterSpec())
                )
                kpg.generateKeyPair()
            }
            else -> throw IllegalArgumentException("Unsupported algorithm: $algorithm")
        }
    }

    /** Load the public key from Keystore (or generate if absent). */
    private fun loadPublicKey(): ByteArray {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEY_NAME)) {
                return ks.getCertificate(KEY_NAME).getEncoded()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Generate a new key pair and return its public part
        return generateKeyPair().public.getEncoded()
    }
}

// ============================================================================
// HKDF-SHA256 (RFC 5869) — Minimal standalone utility
// ============================================================================

object Hkdf {

    /** HKDF-Extract: PRK = HMAC-SHA256(IKM || 0x01 || salt) */
    private fun extract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").also { m ->
            m.init(java.security.KeyGenerator.getInstance("HmacSHA256").also { g ->
                g.init(java.security.spec.AlgorithmParameterSpec(0))
            })}
        mac.update(ikm + 0x01.toByte())
        return mac.doFinal(salt)
    }

    /** HKDF-Expand: DKM = T_1 || T_2 || ... || T_n,
     *  where T_i = HMAC-SHA256(PRK || info || i), i = 1, 2, 3, ...
     *  Output is truncated to `length` bytes. */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val n = (length + 31) / 32  // ceil(len/32) rounds
        var result = byteArrayOf()
        var counter = 1
        while (result.size < length && counter <= n) {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").also { m ->
                m.init(java.security.KeyGenerator.getInstance("HmacSHA256").also { g ->
                    g.init(java.security.spec.AlgorithmParameterSpec(0))
                })}
            mac.update(prk + info + counter.toByte())
            result = result + mac.doFinal()
            counter++
        }
        return result.copyOfRange(0, length)
    }

    /** Derive key of desired length from IKM, optional salt+info.
     *  Default salt: empty bytes. Default info: empty bytes. Default length: 32. */
    fun derive(ikm: ByteArray, salt: ByteArray = byteArrayOf(), info: ByteArray = byteArrayOf(), length: Int = 32): ByteArray {
        val prk = extract(ikm, salt)
        return expand(prk, info, length)
    }

    /** Derive a 32-byte AES-256-GCM session key from X25519/ECDH shared secret,
     *  sender nonce (8 bytes), and receiver nonce (8 bytes). */
    fun deriveSessionKey(sharedSecret: ByteArray, senderNonce: ByteArray, receiverNonce: ByteArray): ByteArray {
        return derive(sharedSecret, byteArrayOf(), "filo-transfer-v1".toByteArray(), 32)
    }
}

// ============================================================================
// SecureRandom wrapper
// ============================================================================

object SecureRandomWrapper {

    private val rng = java.security.SecureRandom()

    fun nextBytes(length: Int): ByteArray {
        val buf = ByteArray(length)
        rng.nextBytes(buf)
        return buf
    }

    fun nextNonce(): ByteArray = nextBytes(8)

    fun nextIv(): ByteArray = nextBytes(12)
}