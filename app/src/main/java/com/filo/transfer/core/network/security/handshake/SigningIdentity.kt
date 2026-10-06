package com.filo.transfer.core.network.security.handshake

import java.security.Signature

/**
 * Abstraction for a signing identity used in the handshake.
 *
 * Production: backed by Android Keystore via IdentityManager.
 * Tests: backed by in-memory P-256 keys.
 */
interface SigningIdentity {

    /** X.509 SubjectPublicKeyInfo bytes for the identity public key. */
    fun getIdentityPublicKey(): ByteArray

    /** Algorithm name: "Ed25519" or "SHA256withECDSA" (P-256). */
    fun getIdentityAlgorithm(): String

    /** Sign a transcript with the identity private key. */
    fun signTranscript(transcript: ByteArray): ByteArray
}

/**
 * Verify a signature against an identity public key (SPKI bytes).
 * Works with both "Ed25519" and "SHA256withECDSA" (P-256).
 */
object IdentityVerifier {

    fun verify(
        identityPublicKeySpki: ByteArray,
        identityAlgorithm: String,
        transcript: ByteArray,
        signature: ByteArray
    ): Boolean {
        try {
            val jcaName = if (identityAlgorithm == "SHA256withECDSA") "EC" else "Ed25519"
            val keyFactory = java.security.KeyFactory.getInstance(jcaName)
            val publicKey = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(identityPublicKeySpki)
            )
            val sig = Signature.getInstance(identityAlgorithm)
            sig.initVerify(publicKey)
            sig.update(transcript)
            return sig.verify(signature)
        } catch (e: Exception) {
            return false
        }
    }
}
