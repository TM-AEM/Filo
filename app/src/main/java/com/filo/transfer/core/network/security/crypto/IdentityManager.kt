package com.filo.transfer.core.network.security.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ============================================================================
// IdentityManager — Persistent device identity
// ============================================================================

object IdentityManager {

    private const val KEY_NAME = "filo_identity_key"

    @Volatile
    private var algorithm = "Ed25519"

    /** Public key bytes (X.509 SPKI). */
    val identityPublicKey: ByteArray
        get() = loadPublicKey()

    /** The identity signing algorithm in use. */
    val identityAlgorithm: String
        get() = algorithm

    /** SHA-256 fingerprint of the public key (lowercase hex, 64 chars). */
    val fingerprint: String
        get() {
            val key = loadPublicKey()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
            return digest.joinToString("") { "%02x".format(it) }
        }

    /** Sign data using the Keystore-protected private key. */
    fun sign(data: ByteArray): ByteArray? {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (!ks.containsAlias(KEY_NAME)) return null
            val privateKey = ks.getKey(KEY_NAME, null) as PrivateKey
            val sig = Signature.getInstance(algorithm)
            sig.initSign(privateKey)
            sig.update(data)
            return sig.sign()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /** Factory: create or return the existing KeyPair. */
    fun getOrCreateIdentity(): KeyPair {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEY_NAME)) {
                return generateKeyPair()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return generateKeyPair()
    }

    private fun generateKeyPair(): KeyPair {
        return when (algorithm) {
            "Ed25519" -> {
                val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                val spec = createKeyGenSpec(KEY_NAME, intArrayOf(KeyProperties.PURPOSE_SIGN))
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
            "P-256" -> {
                val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
                val spec = createKeyGenSpec(
                    KEY_NAME,
                    intArrayOf(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                )
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
            else -> throw IllegalArgumentException("Unsupported algorithm: $algorithm")
        }
    }

    /**
     * Create a KeyGenParameterSpec via reflection to work around
     * Android SDK stubs not exposing the constructor directly.
     */
    private fun createKeyGenSpec(alias: String, purposes: IntArray): java.security.spec.AlgorithmParameterSpec {
        val cls = Class.forName("android.security.keystore.KeyGenParameterSpec")
        val ctor = cls.getConstructor(
            String::class.java,
            intArrayOf(0).javaClass,
            booleanArrayOf(false).javaClass,
            booleanArrayOf(false).javaClass,
            java.security.spec.AlgorithmParameterSpec::class.java
        )
        return ctor.newInstance(alias, purposes[0], false, false, null) as java.security.spec.AlgorithmParameterSpec
    }

    private fun loadPublicKey(): ByteArray {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEY_NAME)) {
                return ks.getCertificate(KEY_NAME).encoded
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return generateKeyPair().public.encoded
    }

    /** Verify an Ed25519 or ECDSA signature using a public key. */
    fun verifySignature(
        idPublicKeyBytes: ByteArray,
        idAlgorithm: String,
        transcript: ByteArray,
        signature: ByteArray
    ): Boolean {
        try {
            val keyFactory = KeyFactory.getInstance(
                if (idAlgorithm == "Ed25519") "Ed25519" else "EC"
            )
            val publicKey = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(idPublicKeyBytes)
            )
            val sig = Signature.getInstance(idAlgorithm)
            sig.initVerify(publicKey)
            sig.update(transcript)
            return sig.verify(signature)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}

// ============================================================================
// HKDF-SHA256 (RFC 5869) — Corrected implementation
// ============================================================================

object Hkdf {

    private const val HASH_LEN = 32

    /**
     * HKDF-Extract per RFC 5869 Section 2.2.
     * PRK = HMAC-SHA256(salt, IKM)
     * If salt is empty, use zero bytes of HASH_LEN length.
     */
    fun extract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand per RFC 5869 Section 2.3.
     * DKM = T(1) || T(2) || ... || T(N), truncated to `length` bytes.
     * T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
     * where i is a single byte counter starting at 1.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "Length must be positive" }
        require(length <= 255 * HASH_LEN) { "Length too large" }
        val n = (length + HASH_LEN - 1) / HASH_LEN
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var previousT = ByteArray(0)
        val result = ByteArray(length)
        var offset = 0
        for (i in 1..n) {
            mac.reset()
            mac.update(previousT)
            if (info.isNotEmpty()) mac.update(info)
            mac.update(i.toByte())
            previousT = mac.doFinal()
            val toCopy = if (i == n) length - offset else HASH_LEN
            System.arraycopy(previousT, 0, result, offset, toCopy)
            offset += toCopy
        }
        return result
    }

    /**
     * Full HKDF: Extract then Expand.
     * @param ikm input key material
     * @param salt optional salt (default: empty)
     * @param info optional context (default: empty)
     * @param length output length in bytes (default: 32)
     */
    fun derive(
        ikm: ByteArray,
        salt: ByteArray = byteArrayOf(),
        info: ByteArray = byteArrayOf(),
        length: Int = 32
    ): ByteArray {
        val prk = extract(ikm, salt)
        return expand(prk, info, length)
    }

    /**
     * Derive directional session keys from ECDH shared secret + transcript hash.
     *
     * @param sharedSecret ECDH shared secret (32 bytes)
     * @param transcriptHash SHA-256 of the full handshake transcript (32 bytes)
     * @return 96 bytes: clientToServerKey(32) || serverToClientKey(32) || handshakeKey(32)
     */
    fun deriveSessionMaterial(sharedSecret: ByteArray, transcriptHash: ByteArray): ByteArray {
        val info = "Filo-Secure-Session-v1".toByteArray() + transcriptHash
        return derive(sharedSecret, byteArrayOf(), info, 96)
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

    fun nextNonce(): ByteArray = nextBytes(32)

    fun nextIv(): ByteArray = nextBytes(12)
}
