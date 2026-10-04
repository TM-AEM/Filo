package com.filo.transfer.core.network.security.handshake

import java.security.KeyPair
import java.security.PublicKey
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
 * In-memory signing identity for JVM unit tests.
 * Uses P-256 EC keys generated in memory — no Android Keystore dependency.
 */
class InMemorySigningIdentity(private val keyPair: KeyPair) : SigningIdentity {

    override fun getIdentityPublicKey(): ByteArray = keyPair.public.encoded

    override fun getIdentityAlgorithm(): String = "SHA256withECDSA"

    override fun signTranscript(transcript: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(transcript)
        return sig.sign()
    }

    companion object {
        /** Generate a new P-256 key pair for testing. */
        fun generate(): InMemorySigningIdentity {
            val kpg = java.security.KeyPairGenerator.getInstance("EC")
            kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            return InMemorySigningIdentity(kpg.generateKeyPair())
        }
    }
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
