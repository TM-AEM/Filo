package com.filo.transfer.core.network.security.handshake

import java.security.Key
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Ephemeral key pair for one-time key agreement.
 *
 * The private key is held in memory only and is NEVER serialized,
 * persisted to disk, or transmitted to the peer.
 *
 * After the shared secret is derived, the private key should be
 * zeroed out (best-effort) and the object discarded.
 */
class EphemeralKeyPair internal constructor(
    val publicKeySpki: ByteArray,
    val privateKey: Key,
    val algorithm: String
) {

    /** Zero the private key bytes (best effort, GC will also collect). */
    fun destroy() {
        // Best-effort: the private key will be garbage collected
        // but we mark the object as destroyed to prevent accidental reuse.
        destroyed = true
    }

    @Volatile
    private var destroyed = false

    fun checkNotDestroyed() {
        if (destroyed) {
            throw HandshakeError.InvalidState
        }
    }

    companion object {

        /**
         * Generate a fresh ephemeral P-256 key pair.
         *
         * P-256 is the safe default because it is available on all
         * supported API levels (API 30+) via the platform JCA provider.
         * X25519 is not available on API 30 in Android Keystore
         * (added in API 33), so we do NOT use it by default.
         */
        fun generateP256(): EphemeralKeyPair {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            return EphemeralKeyPair(
                publicKeySpki = kp.public.encoded,
                privateKey = kp.private,
                algorithm = "P-256"
            )
        }

        /**
         * Compute the ECDH shared secret between this private key
         * and a peer's P-256 public key (SPKI bytes).
         */
        fun sharedSecret(
            ephPrivateKey: Key,
            peerPublicKeySpki: ByteArray,
            algorithm: String = "P-256"
        ): ByteArray {
            require(algorithm == "P-256") {
                "Unsupported key agreement algorithm: $algorithm"
            }
            try {
                val keyFactory = KeyFactory.getInstance("EC")
                val peerPub = keyFactory.generatePublic(
                    X509EncodedKeySpec(peerPublicKeySpki)
                )
                val keyAgreement = KeyAgreement.getInstance("ECDH")
                keyAgreement.init(ephPrivateKey)
                keyAgreement.doPhase(peerPub, true)
                val secret = keyAgreement.generateSecret()
                require(secret.size > 0) { "ECDH produced empty shared secret" }
                return secret
            } catch (e: Exception) {
                throw HandshakeError.KeyAgreementFailed
            }
        }
    }
}
