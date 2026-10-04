package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.Hkdf
import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * 25 focused handshake security tests.
 */
class HandshakeSecurityTest {

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    // ── Identity (tests 1-5) ────────────────────────────────────────────

    @Test
    fun `1 persistent identity remains stable across repeated retrieval`() {
        val identity = InMemorySigningIdentity.generate()
        val state1 = SecureHandshake.initiate(identity)
        val state2 = SecureHandshake.initiate(identity)
        assertArrayEquals(
            "Same identity must produce the same public key",
            state1.localMessage.idPublicKey,
            state2.localMessage.idPublicKey
        )
    }

    @Test
    fun `2 identity fingerprint remains stable`() {
        val identity = InMemorySigningIdentity.generate()
        val spki = identity.getIdentityPublicKey()
        val fp1 = sha256Hex(spki)
        val fp2 = sha256Hex(spki)
        assertEquals("Fingerprint must be stable", fp1, fp2)
        assertEquals(64, fp1.length)
        assertTrue("Fingerprint must be lowercase hex", fp1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `3 valid identity signature verifies`() {
        val identity = InMemorySigningIdentity.generate()
        val data = "test-transcript".toByteArray()
        val sig = identity.signTranscript(data)
        assertTrue(
            "Valid signature must verify",
            IdentityVerifier.verify(identity.getIdentityPublicKey(), "SHA256withECDSA", data, sig)
        )
    }

    @Test
    fun `4 modified signed data fails verification`() {
        val identity = InMemorySigningIdentity.generate()
        val data = "original-data".toByteArray()
        val sig = identity.signTranscript(data)
        val tampered = "tampered-data".toByteArray()
        assertFalse(
            "Modified data must fail signature verification",
            IdentityVerifier.verify(identity.getIdentityPublicKey(), "SHA256withECDSA", tampered, sig)
        )
    }

    @Test
    fun `5 wrong identity public key fails verification`() {
        val identityA = InMemorySigningIdentity.generate()
        val identityB = InMemorySigningIdentity.generate()
        val data = "shared-data".toByteArray()
        val sig = identityA.signTranscript(data)
        assertFalse(
            "Wrong public key must fail signature verification",
            IdentityVerifier.verify(identityB.getIdentityPublicKey(), "SHA256withECDSA", data, sig)
        )
    }

    // ── Key agreement (tests 6-8) ──────────────────────────────────────

    @Test
    fun `6 two P-256 ephemeral peers derive same shared secret`() {
        val a = EphemeralKeyPair.generateP256()
        val b = EphemeralKeyPair.generateP256()
        val secretAB = EphemeralKeyPair.sharedSecret(a.privateKey, b.publicKeySpki)
        val secretBA = EphemeralKeyPair.sharedSecret(b.privateKey, a.publicKeySpki)
        assertArrayEquals("ECDH must be symmetric", secretAB, secretBA)
    }

    @Test
    fun `7 different ephemeral keys produce different shared secrets`() {
        val fixed = EphemeralKeyPair.generateP256()
        val other1 = EphemeralKeyPair.generateP256()
        val other2 = EphemeralKeyPair.generateP256()
        val s1 = EphemeralKeyPair.sharedSecret(fixed.privateKey, other1.publicKeySpki)
        val s2 = EphemeralKeyPair.sharedSecret(fixed.privateKey, other2.publicKeySpki)
        assertFalse(
            "Different ephemeral keys must produce different shared secrets",
            s1.contentEquals(s2)
        )
    }

    @Test
    fun `8 API 30-safe P-256 path works`() {
        val kp = EphemeralKeyPair.generateP256()
        assertEquals("P-256 must be the algorithm", "P-256", kp.algorithm)
        val spki = kp.publicKeySpki
        assertTrue("SPKI must be at least 44 bytes", spki.size >= 44)
        val factory = java.security.KeyFactory.getInstance("EC")
        val pub = factory.generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        val ecPub = pub as java.security.interfaces.ECPublicKey
        assertNotNull("P-256 point must have valid coordinates", ecPub.w)
    }

    // ── Transcript (tests 9-15) ───────────────────────────────────────

    private fun buildFullTranscript(
        version: Byte = 1,
        alg: String = "SHA256withECDSA",
        keyAgreement: String = "P-256"
    ): FullHandshakeTranscript {
        val init = InMemorySigningIdentity.generate()
        val resp = InMemorySigningIdentity.generate()
        val iEph = EphemeralKeyPair.generateP256()
        val rEph = EphemeralKeyPair.generateP256()
        return FullHandshakeTranscript(
            protocolVersion = version,
            keyAgreementAlg = keyAgreement,
            initiator = HandshakeData(0.toByte(), alg, init.getIdentityPublicKey(), iEph.publicKeySpki, SecureRandomWrapper.nextNonce()),
            responder = HandshakeData(1.toByte(), alg, resp.getIdentityPublicKey(), rEph.publicKeySpki, SecureRandomWrapper.nextNonce())
        )
    }

    @Test
    fun `9 identical canonical transcript hashes identically`() {
        val t = buildFullTranscript()
        val h1 = t.hash()
        val h2 = t.hash()
        assertArrayEquals("Same transcript must produce same hash", h1, h2)
        assertEquals(32, h1.size)
    }

    @Test
    fun `10 changing protocol version changes transcript`() {
        val base = buildFullTranscript(version = 1)
        val changed = base.copy(protocolVersion = 2)
        assertFalse("Different version must produce different hash", base.hash().contentEquals(changed.hash()))
    }

    @Test
    fun `11 changing role changes transcript`() {
        val t = buildFullTranscript()
        val swapped = FullHandshakeTranscript(
            protocolVersion = t.protocolVersion,
            keyAgreementAlg = t.keyAgreementAlg,
            initiator = t.responder.copy(role = 0),
            responder = t.initiator.copy(role = 1)
        )
        assertFalse("Swapped roles must produce different hash", t.hash().contentEquals(swapped.hash()))
    }

    @Test
    fun `12 changing identity key changes transcript`() {
        val t = buildFullTranscript()
        val newKey = InMemorySigningIdentity.generate().getIdentityPublicKey()
        val changed = t.copy(initiator = t.initiator.copy(idPublicKey = newKey))
        assertFalse("Different identity key must produce different hash", t.hash().contentEquals(changed.hash()))
    }

    @Test
    fun `13 changing ephemeral key changes transcript`() {
        val t = buildFullTranscript()
        val newEph = EphemeralKeyPair.generateP256().publicKeySpki
        val changed = t.copy(initiator = t.initiator.copy(ephPublicKey = newEph))
        assertFalse("Different ephemeral key must produce different hash", t.hash().contentEquals(changed.hash()))
    }

    @Test
    fun `14 changing nonce changes transcript`() {
        val t = buildFullTranscript()
        val changed = t.copy(initiator = t.initiator.copy(nonce = SecureRandomWrapper.nextNonce()))
        assertFalse("Different nonce must produce different hash", t.hash().contentEquals(changed.hash()))
    }

    @Test
    fun `15 changing key-agreement algorithm changes transcript`() {
        val t = buildFullTranscript(keyAgreement = "P-256")
        val changed = t.copy(keyAgreementAlg = "P-384")
        assertFalse("Different key-agreement algorithm must produce different hash", t.hash().contentEquals(changed.hash()))
    }

    // ── Authentication (tests 16-20) ───────────────────────────────────

    private fun fullHandshake()
            : Triple<HandshakeInitiatorState, HandshakeResponderState, InitiatorCompletion> {
        val init = InMemorySigningIdentity.generate()
        val resp = InMemorySigningIdentity.generate()
        val iState = SecureHandshake.initiate(init)
        val rState = SecureHandshake.respond(resp, iState.localMessage)
        val iCompletion = SecureHandshake.completeAsInitiator(iState, rState.localMessage, rState.fullTranscriptSignature)
        return Triple(iState, rState, iCompletion)
    }

    @Test
    fun `16 valid complete transcript authenticates`() {
        val (iState, rState, iCompletion) = fullHandshake()
        assertNotNull("Session must be created", iCompletion.session)
        val rSession = SecureHandshake.completeAsResponder(rState, iState.localMessage, iCompletion.signatureToSend)
        assertNotNull("Responder session must be created", rSession)
        assertArrayEquals(
            "Both peers must derive the same keyA",
            iCompletion.session.keyA,
            rSession.keyA
        )
    }

    @Test
    fun `17 modified peer identity fails`() {
        val (iState, rState, _) = fullHandshake()
        val tamperedKey = ByteArray(rState.localMessage.idPublicKey.size) { i ->
            if (i == 0) (rState.localMessage.idPublicKey[0].toInt() xor 1).toByte()
            else rState.localMessage.idPublicKey[i]
        }
        val tampered = rState.localMessage.copy(idPublicKey = tamperedKey)
        assertThrows(
            "Tampered identity must cause InvalidSignature",
            HandshakeError.InvalidSignature::class.java
        ) {
            SecureHandshake.completeAsInitiator(iState, tampered, rState.fullTranscriptSignature)
        }
    }

    @Test
    fun `18 modified peer ephemeral key fails`() {
        val (iState, rState, _) = fullHandshake()
        val tamperedEph = EphemeralKeyPair.generateP256().publicKeySpki
        val tampered = rState.localMessage.copy(ephPublicKey = tamperedEph)
        assertThrows(
            "Tampered ephemeral key must cause InvalidSignature",
            HandshakeError.InvalidSignature::class.java
        ) {
            SecureHandshake.completeAsInitiator(iState, tampered, rState.fullTranscriptSignature)
        }
    }

    @Test
    fun `19 modified peer nonce fails`() {
        val (iState, rState, _) = fullHandshake()
        val tampered = rState.localMessage.copy(nonce = SecureRandomWrapper.nextNonce())
        assertThrows(
            "Tampered nonce must cause InvalidSignature",
            HandshakeError.InvalidSignature::class.java
        ) {
            SecureHandshake.completeAsInitiator(iState, tampered, rState.fullTranscriptSignature)
        }
    }

    @Test
    fun `20 role-swapped transcript fails`() {
        val t = buildFullTranscript()
        assertThrows(
            "Constructing a transcript with role=1 in initiator position must fail",
            IllegalArgumentException::class.java
        ) {
            FullHandshakeTranscript(
                protocolVersion = t.protocolVersion,
                keyAgreementAlg = t.keyAgreementAlg,
                initiator = t.responder,
                responder = t.initiator
            )
        }
    }

    // ── Session derivation (tests 21-25) ───────────────────────────────

    @Test
    fun `21 both sides derive matching session material`() {
        val (iState, rState, iCompletion) = fullHandshake()
        val rSession = SecureHandshake.completeAsResponder(rState, iState.localMessage, iCompletion.signatureToSend)
        assertArrayEquals("keyA must match", iCompletion.session.keyA, rSession.keyA)
        assertArrayEquals("keyB must match", iCompletion.session.keyB, rSession.keyB)
        assertArrayEquals("bindingKey must match", iCompletion.session.bindingKey, rSession.bindingKey)
        assertArrayEquals(
            "Transcript hash must match",
            iCompletion.session.transcriptHash,
            rSession.transcriptHash
        )
    }

    @Test
    fun `22 different ephemeral handshake produces different session material`() {
        val (_, _, iComp1) = fullHandshake()
        val (_, _, iComp2) = fullHandshake()
        assertFalse(
            "Different handshakes must produce different keyA",
            iComp1.session.keyA.contentEquals(iComp2.session.keyA)
        )
        assertFalse(
            "Different handshakes must produce different keyB",
            iComp1.session.keyB.contentEquals(iComp2.session.keyB)
        )
    }

    @Test
    fun `23 different transcript produces different session material`() {
        val secret = SecureRandomWrapper.nextBytes(32)
        val h1 = SecureRandomWrapper.nextBytes(32)
        val h2 = SecureRandomWrapper.nextBytes(32)
        val m1 = Hkdf.deriveSessionMaterial(secret, h1)
        val m2 = Hkdf.deriveSessionMaterial(secret, h2)
        assertFalse(
            "Different transcripts must produce different session material",
            m1.contentEquals(m2)
        )
    }

    @Test
    fun `24 initiator peer fingerprint is responder`() {
        val init = InMemorySigningIdentity.generate()
        val resp = InMemorySigningIdentity.generate()
        val iState = SecureHandshake.initiate(init)
        val rState = SecureHandshake.respond(resp, iState.localMessage)
        val iComp = SecureHandshake.completeAsInitiator(iState, rState.localMessage, rState.fullTranscriptSignature)
        val expectedRespFp = sha256Hex(resp.getIdentityPublicKey())
        assertEquals(
            "Initiator's peer fingerprint must be the responder's identity",
            expectedRespFp,
            iComp.session.peerFingerprint
        )
    }

    @Test
    fun `25 responder peer fingerprint is initiator`() {
        val init = InMemorySigningIdentity.generate()
        val resp = InMemorySigningIdentity.generate()
        val iState = SecureHandshake.initiate(init)
        val rState = SecureHandshake.respond(resp, iState.localMessage)
        val iComp = SecureHandshake.completeAsInitiator(iState, rState.localMessage, rState.fullTranscriptSignature)
        val rSession = SecureHandshake.completeAsResponder(rState, iState.localMessage, iComp.signatureToSend)
        val expectedInitFp = sha256Hex(init.getIdentityPublicKey())
        assertEquals(
            "Responder's peer fingerprint must be the initiator's identity",
            expectedInitFp,
            rSession.peerFingerprint
        )
    }
}
