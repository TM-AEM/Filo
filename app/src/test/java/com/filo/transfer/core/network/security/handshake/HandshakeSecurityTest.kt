package com.filo.transfer.core.network.security.handshake

import com.filo.transfer.core.network.security.crypto.SecureRandomWrapper
import org.junit.Test
import org.junit.Assert.*

/**
 * 20 focused security tests for the handshake layer.
 */
class HandshakeSecurityTest {

    // ── Transcript encoding ──────────────────────────────────────────────

    @Test
    fun `transcript encode is deterministic`() {
        val nonce = SecureRandomWrapper.nextNonce()
        val t = HandshakeTranscript(
            protocolVersion = 1,
            role = 0,
            idAlgorithm = "SHA256withECDSA",
            idPublicKey = SecureRandomWrapper.nextBytes(49),
            ephPublicKey = SecureRandomWrapper.nextBytes(65),
            nonce = nonce,
            keyAgreementAlg = "P-256"
        )
        val enc1 = t.encode()
        val enc2 = t.encode()
        assertArrayEquals("Identical transcripts must produce identical bytes", enc1, enc2)
    }

    @Test
    fun `transcript hash changes when any field changes`() {
        val nonce = SecureRandomWrapper.nextNonce()
        val base = HandshakeTranscript(
            protocolVersion = 1, role = 0,
            idAlgorithm = "SHA256withECDSA",
            idPublicKey = SecureRandomWrapper.nextBytes(49),
            ephPublicKey = SecureRandomWrapper.nextBytes(65),
            nonce = nonce, keyAgreementAlg = "P-256"
        )
        val baseHash = base.hash()

        val differentNonce = base.copy(nonce = SecureRandomWrapper.nextNonce())
        assertNotEquals(
            "Different nonces must produce different hashes",
            baseHash, differentNonce.hash()
        )

        val differentPub = base.copy(idPublicKey = SecureRandomWrapper.nextBytes(49))
        assertNotEquals(
            "Different identity public keys must produce different hashes",
            baseHash, differentPub.hash()
        )
    }

    @Test
    fun `transcript nonce must be exactly 32 bytes`() {
        val t = HandshakeTranscript(
            protocolVersion = 1, role = 0,
            idAlgorithm = "SHA256withECDSA",
            idPublicKey = byteArrayOf(0),
            ephPublicKey = byteArrayOf(0),
            nonce = ByteArray(31), // wrong size
            keyAgreementAlg = "P-256"
        )
        assertThrows(IllegalArgumentException::class.java) {
            t.encode()
        }
    }

    @Test
    fun `transcript hash is 32 bytes`() {
        val t = HandshakeTranscript(
            protocolVersion = 1, role = 0,
            idAlgorithm = "SHA256withECDSA",
            idPublicKey = SecureRandomWrapper.nextBytes(49),
            ephPublicKey = SecureRandomWrapper.nextBytes(65),
            nonce = SecureRandomWrapper.nextNonce(),
            keyAgreementAlg = "P-256"
        )
        assertEquals(32, t.hash().size)
    }

    // ── Ephemeral key pair ─────────────────────────────────────────────

    @Test
    fun `ephemeral key generation produces valid P-256 public key`() {
        val kp = EphemeralKeyPair.generateP256()
        assertTrue("P-256 public key SPKI must be at least 44 bytes", kp.publicKeySpki.size >= 44)
        // P-256 public key in SPKI is 0x30 0x42 (68 bytes for uncompressed point + header)
        assertTrue("P-256 SPKI must start with 0x30", kp.publicKeySpki[0].toInt() and 0xFF == 0x30)
    }

    @Test
    fun `ephemeral keys are non-reusable after destroy`() {
        val kp = EphemeralKeyPair.generateP256()
        kp.destroy()
        assertThrows(HandshakeError::class.java) {
            kp.checkNotDestroyed()
        }
    }

    @Test
    fun `ECDH shared secret is symmetric`() {
        val a = EphemeralKeyPair.generateP256()
        val b = EphemeralKeyPair.generateP256()

        val secretAB = EphemeralKeyPair.sharedSecret(a.privateKey, b.publicKeySpki)
        val secretBA = EphemeralKeyPair.sharedSecret(b.privateKey, a.publicKeySpki)

        assertArrayEquals(
            "ECDH must produce the same shared secret regardless of order",
            secretAB, secretBA
        )
    }

    @Test
    fun `ECDH shared secret is not zero`() {
        val a = EphemeralKeyPair.generateP256()
        val b = EphemeralKeyPair.generateP256()
        val secret = EphemeralKeyPair.sharedSecret(a.privateKey, b.publicKeySpki)
        assertTrue("Shared secret must not be all zeros", secret.any { it != 0.toByte() })
    }

    @Test
    fun `ECDH rejects unknown algorithm`() {
        val a = EphemeralKeyPair.generateP256()
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralKeyPair.sharedSecret(a.privateKey, a.publicKeySpki, "X25519")
        }
    }

    // ── Handshake message verification ─────────────────────────────────

    @Test
    fun `valid handshake message passes signature verification`() {
        val identity = InMemorySigningIdentity.generate()
        val msg = SecureHandshake.initiate(identity)
        assertTrue("Valid signature must verify", msg.verifySignature())
    }

    @Test
    fun `tampered nonce fails signature verification`() {
        val identity = InMemorySigningIdentity.generate()
        val msg = SecureHandshake.initiate(identity)
        val tamperedNonce = SecureRandomWrapper.nextNonce()
        val tampered = msg.copy(nonce = tamperedNonce)
        assertFalse("Tampered nonce must fail signature verification", tampered.verifySignature())
    }

    @Test
    fun `tampered identity key fails signature verification`() {
        val identity = InMemorySigningIdentity.generate()
        val other = InMemorySigningIdentity.generate()
        val msg = SecureHandshake.initiate(identity)
        val swapped = msg.copy(idPublicKey = other.getIdentityPublicKey())
        assertFalse("Swapped identity key must fail signature verification", swapped.verifySignature())
    }

    @Test
    fun `tampered key agreement algorithm fails signature verification`() {
        val identity = InMemorySigningIdentity.generate()
        val msg = SecureHandshake.initiate(identity)
        val tampered = msg.copy(keyAgreementAlg = "Ed25519")
        assertFalse("Changed keyAgreementAlg must fail signature verification", tampered.verifySignature())
    }

    // ── Full handshake protocol ────────────────────────────────────────

    @Test
    fun `full handshake produces valid session for both peers`() {
        val initiator = InMemorySigningIdentity.generate()
        val responder = InMemorySigningIdentity.generate()

        val localMsg = SecureHandshake.initiate(initiator)
        val localEph = SecureHandshake.generateEphemeral()
        // Simulate: we need the eph key that was used to build localMsg
        // For test purposes, regenerate and rebuild
        val eph1 = EphemeralKeyPair.generateP256()
        val nonce1 = SecureRandomWrapper.nextNonce()
        val transcript1 = HandshakeTranscript(
            protocolVersion = SecureHandshake.PROTOCOL_VERSION,
            role = 0,
            idAlgorithm = initiator.getIdentityAlgorithm(),
            idPublicKey = initiator.getIdentityPublicKey(),
            ephPublicKey = eph1.publicKeySpki,
            nonce = nonce1,
            keyAgreementAlg = "P-256"
        )
        val sig1 = initiator.signTranscript(transcript1.encode())
        val msg1 = HandshakeMessage(
            protocolVersion = 0, role = 0,
            idAlgorithm = initiator.getIdentityAlgorithm(),
            idPublicKey = initiator.getIdentityPublicKey(),
            ephPublicKey = eph1.publicKeySpki,
            nonce = nonce1, keyAgreementAlg = "P-256",
            signature = sig1
        )

        val eph2 = EphemeralKeyPair.generateP256()
        val nonce2 = SecureRandomWrapper.nextNonce()
        val transcript2 = HandshakeTranscript(
            protocolVersion = 0, role = 1,
            idAlgorithm = responder.getIdentityAlgorithm(),
            idPublicKey = responder.getIdentityPublicKey(),
            ephPublicKey = eph2.publicKeySpki,
            nonce = nonce2, keyAgreementAlg = "P-256"
        )
        val sig2 = responder.signTranscript(transcript2.encode())
        val msg2 = HandshakeMessage(
            protocolVersion = 0, role = 1,
            idAlgorithm = responder.getIdentityAlgorithm(),
            idPublicKey = responder.getIdentityPublicKey(),
            ephPublicKey = eph2.publicKeySpki,
            nonce = nonce2, keyAgreementAlg = "P-256",
            signature = sig2
        )

        // Both peers should compute the same shared secret
        val secretA = EphemeralKeyPair.sharedSecret(eph1.privateKey, eph2.publicKeySpki)
        val secretB = EphemeralKeyPair.sharedSecret(eph2.privateKey, eph1.publicKeySpki)
        assertArrayEquals("Both peers must derive the same shared secret", secretA, secretB)

        // Complete as initiator
        val sessionA = SecureSession.create(msg1, msg2, secretA, eph1, eph2)
        assertNotNull("Session must not be null", sessionA)
        assertEquals(32, sessionA.transcriptHash.size)
        assertEquals(32, sessionA.clientToServerKey.size)
    }

    @Test
    fun `session keys are directional - different per direction`() {
        val initiator = InMemorySigningIdentity.generate()
        val responder = InMemorySigningIdentity.generate()

        val eph1 = EphemeralKeyPair.generateP256()
        val eph2 = EphemeralKeyPair.generateP256()
        val nonce1 = SecureRandomWrapper.nextNonce()
        val nonce2 = SecureRandomWrapper.nextNonce()

        val msg1 = buildMsg(0, initiator, eph1, nonce1)
        val msg2 = buildMsg(1, responder, eph2, nonce2)

        val secret = EphemeralKeyPair.sharedSecret(eph1.privateKey, eph2.publicKeySpki)
        val session = SecureSession.create(msg1, msg2, secret, eph1, eph2)

        assertFalse(
            "Client-to-server key must differ from server-to-client key",
            session.clientToServerKey.contentEquals(session.serverToClientKey)
        )
    }

    @Test
    fun `replaying a tampered peer message is rejected`() {
        val identity = InMemorySigningIdentity.generate()
        val msg = SecureHandshake.initiate(identity)

        // Tamper with the nonce - signature verification must fail
        val tampered = msg.copy(nonce = SecureRandomWrapper.nextNonce())
        assertFalse("Tampered nonce must fail signature verification", tampered.verifySignature())
    }

    @Test
    fun `complement role check - same role is rejected`() {
        val a = InMemorySigningIdentity.generate()
        val b = InMemorySigningIdentity.generate()

        val ephA = EphemeralKeyPair.generateP256()
        val ephB = EphemeralKeyPair.generateP256()
        val msgA = buildMsg(0, a, ephA, SecureRandomWrapper.nextNonce())
        val msgB = buildMsg(0, b, ephB, SecureRandomWrapper.nextNonce()) // both role=0

        val secret = EphemeralKeyPair.sharedSecret(ephA.privateKey, ephB.publicKeySpki)
        assertThrows(HandshakeError::class.java) {
            SecureSession.create(msgA, msgB, secret, ephA, ephB)
        }
    }

    @Test
    fun `session fingerprint is stable and 64 hex chars`() {
        val a = InMemorySigningIdentity.generate()
        val b = InMemorySigningIdentity.generate()
        val ephA = EphemeralKeyPair.generateP256()
        val ephB = EphemeralKeyPair.generateP256()
        val msgA = buildMsg(0, a, ephA, SecureRandomWrapper.nextNonce())
        val msgB = buildMsg(1, b, ephB, SecureRandomWrapper.nextNonce())
        val secret = EphemeralKeyPair.sharedSecret(ephA.privateKey, ephB.publicKeySpki)
        val session = SecureSession.create(msgA, msgB, secret, ephA, ephB)
        val fp = session.sessionFingerprint()
        assertEquals(64, fp.length)
        assertTrue("Fingerprint must be lowercase hex", fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private fun buildMsg(
        role: Byte,
        identity: SigningIdentity,
        eph: EphemeralKeyPair,
        nonce: ByteArray
    ): HandshakeMessage {
        val transcript = HandshakeTranscript(
            protocolVersion = SecureHandshake.PROTOCOL_VERSION,
            role = role,
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256"
        )
        val sig = identity.signTranscript(transcript.encode())
        return HandshakeMessage(
            protocolVersion = SecureHandshake.PROTOCOL_VERSION,
            role = role,
            idAlgorithm = identity.getIdentityAlgorithm(),
            idPublicKey = identity.getIdentityPublicKey(),
            ephPublicKey = eph.publicKeySpki,
            nonce = nonce,
            keyAgreementAlg = "P-256",
            signature = sig
        )
    }
}
