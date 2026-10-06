package com.filo.transfer.core.network.security.handshake

import java.security.KeyPair
import java.security.Signature

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
