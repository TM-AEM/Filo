package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.IdentityManager
import java.security.GeneralSecurityException

/**
 * Production [SigningIdentity] backed by the Android Keystore via [IdentityManager].
 *
 * This is a thin adapter: it exposes the Keystore-backed public key, algorithm,
 * and signing capability through the [SigningIdentity] interface consumed by
 * [SecureHandshake]. It never exports the private key and never falls back to
 * in-memory or plaintext keys.
 *
 * Fail-closed: if the Keystore cannot sign, a [GeneralSecurityException] is
 * thrown so the handshake terminates without producing application frames.
 */
class KeystoreSigningIdentity : SigningIdentity {

    override fun getIdentityPublicKey(): ByteArray = IdentityManager.identityPublicKey

    override fun getIdentityAlgorithm(): String = IdentityManager.identityAlgorithm

    override fun signTranscript(transcript: ByteArray): ByteArray {
        val signature = IdentityManager.sign(transcript)
        if (signature == null) {
            throw GeneralSecurityException("Keystore identity signing unavailable")
        }
        return signature
    }
}
