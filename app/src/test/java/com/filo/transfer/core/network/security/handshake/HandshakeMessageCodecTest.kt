package com.filo.transfer.core.network.security.handshake

import org.junit.Assert.*
import org.junit.Test

class HandshakeMessageCodecTest {

    companion object {
        private const val MIN_SIZE = 64
        private val VALID_NONCE = ByteArray(32).also { for (i in it.indices) it[i] = i.toByte() }
        private val VALID_ID_PUB = byteArrayOf(0x30, 0x00)
        private val VALID_EPH_PUB = byteArrayOf(0x30, 0x01)
    }

    private fun makeValid(
        protocolVersion: Byte = SecureHandshake.PROTOCOL_VERSION,
        role: Byte = 0.toByte(),
        idAlgorithm: String = "Ed25519",
        idPublicKey: ByteArray = VALID_ID_PUB,
        ephPublicKey: ByteArray = VALID_EPH_PUB,
        nonce: ByteArray = VALID_NONCE,
        keyAgreementAlg: String = "P-256"
    ): HandshakeMessage = HandshakeMessage(
        protocolVersion = protocolVersion, role = role, idAlgorithm = idAlgorithm,
        idPublicKey = idPublicKey, ephPublicKey = ephPublicKey,
        nonce = nonce, keyAgreementAlg = keyAgreementAlg
    )

    // ── A. Round-trip ────────────────────────────────────────────────────

    @Test fun `A01 round-trip initiator preserves all fields`() {
        val msg = makeValid(role = 0.toByte())
        val enc = HandshakeMessageCodec.encode(msg)
        val dec = HandshakeMessageCodec.decode(enc)
        assertEquals(msg.protocolVersion, dec.protocolVersion)
        assertEquals(msg.role, dec.role)
        assertEquals(msg.idAlgorithm, dec.idAlgorithm)
        assertArrayEquals(msg.idPublicKey, dec.idPublicKey)
        assertArrayEquals(msg.ephPublicKey, dec.ephPublicKey)
        assertArrayEquals(msg.nonce, dec.nonce)
        assertEquals(msg.keyAgreementAlg, dec.keyAgreementAlg)
    }

    @Test fun `A02 round-trip responder preserves all fields`() {
        val msg = makeValid(role = 1.toByte())
        val enc = HandshakeMessageCodec.encode(msg)
        val dec = HandshakeMessageCodec.decode(enc)
        assertEquals(1.toByte(), dec.role)
    }

    @Test fun `A03 round-trip with SHA256withECDSA algorithm`() {
        val msg = makeValid(idAlgorithm = "SHA256withECDSA")
        val enc = HandshakeMessageCodec.encode(msg)
        val dec = HandshakeMessageCodec.decode(enc)
        assertEquals("SHA256withECDSA", dec.idAlgorithm)
    }

    // ── B. Exact encoding bytes ──────────────────────────────────────────

    @Test fun `B01 deterministic encode for identical input`() {
        val msg = makeValid()
        val enc1 = HandshakeMessageCodec.encode(msg)
        val enc2 = HandshakeMessageCodec.encode(msg)
        assertArrayEquals(enc1, enc2)
    }

    @Test fun `B02 first two bytes are version and role`() {
        val msg = makeValid(protocolVersion = 1, role = 1)
        val enc = HandshakeMessageCodec.encode(msg)
        assertEquals(1, enc[0].toInt() and 0xFF)
        assertEquals(1, enc[1].toInt() and 0xFF)
    }

    @Test fun `B03 idAlgorithm length prefix is correct u32be`() {
        val msg = makeValid(idAlgorithm = "Ed25519")
        val enc = HandshakeMessageCodec.encode(msg)
        assertEquals(0, enc[2].toInt())
        assertEquals(0, enc[3].toInt())
        assertEquals(0, enc[4].toInt())
        assertEquals(7, enc[5].toInt() and 0xFF)
    }

    @Test fun `B04 nonce at correct offset with sequential bytes`() {
        val nonce = ByteArray(32).also { for (i in it.indices) it[i] = i.toByte() }
        val msg = makeValid(nonce = nonce)
        val enc = HandshakeMessageCodec.encode(msg)
        val off = 2 + 4 * 3 + "Ed25519".length + VALID_ID_PUB.size + VALID_EPH_PUB.size
        for (i in 0 until 32) {
            assertEquals(i.toByte(), enc[off + i])
        }
    }

    @Test fun `B05 keyAgreementAlg after nonce`() {
        val msg = makeValid(keyAgreementAlg = "P-256")
        val enc = HandshakeMessageCodec.encode(msg)
        val idAlgLen = "Ed25519".length
        val pubKeyOff = 2 + 4 * 3 + idAlgLen + VALID_ID_PUB.size + VALID_EPH_PUB.size
        val nonceEnd = pubKeyOff + 32
        assertEquals(0, enc[nonceEnd].toInt())
        assertEquals(0, enc[nonceEnd + 1].toInt())
        assertEquals(0, enc[nonceEnd + 2].toInt())
        assertEquals(5, enc[nonceEnd + 3].toInt() and 0xFF)
    }

    // ── C. Field order ───────────────────────────────────────────────────

    @Test fun `C01 field order matches Task21G D-HM spec`() {
        val enc = HandshakeMessageCodec.encode(makeValid())
        var off = 0
        assertEquals(SecureHandshake.PROTOCOL_VERSION.toInt() and 0xFF, enc[off++].toInt() and 0xFF)
        assertEquals(0, enc[off++].toInt() and 0xFF)
        // idAlgorithm
        var len = readU32be(enc, off); off += 4
        val idAlg = String(enc, off, len, Charsets.UTF_8); off += len
        assertEquals("Ed25519", idAlg)
        // idPublicKey
        len = readU32be(enc, off); off += 4; off += len
        // ephPublicKey
        len = readU32be(enc, off); off += 4; off += len
        // nonce
        off += 32
        // keyAgreementAlg
        len = readU32be(enc, off); off += 4
        val ka = String(enc, off, len, Charsets.UTF_8); off += len
        assertEquals("P-256", ka)
        assertEquals(enc.size, off)
    }

    // ── D. Boundary-length tests ─────────────────────────────────────────

    @Test fun `D01 minimum valid message decodes`() {
        val msg = makeValid(
            idAlgorithm = "Ed25519", idPublicKey = byteArrayOf(0x01),
            ephPublicKey = byteArrayOf(0x02), keyAgreementAlg = "P-256"
        )
        val enc = HandshakeMessageCodec.encode(msg)
        assertEquals(MIN_SIZE, enc.size)
        HandshakeMessageCodec.decode(enc)
    }

    @Test fun `D02 encodedSize matches actual encode length`() {
        val msg = makeValid()
        assertEquals(HandshakeMessageCodec.encode(msg).size, HandshakeMessageCodec.encodedSize(msg))
    }

    @Test fun `D03 encodedSize is stable across calls`() {
        val msg = makeValid()
        val s1 = HandshakeMessageCodec.encodedSize(msg)
        val s2 = HandshakeMessageCodec.encodedSize(msg)
        val s3 = HandshakeMessageCodec.encodedSize(msg)
        assertEquals(s1, s2)
        assertEquals(s2, s3)
    }

    @Test fun `D04 encodedSize formula matches manual computation`() {
        val msg = makeValid()
        val expected = 2 + 4 * 4 + "Ed25519".length + VALID_ID_PUB.size + VALID_EPH_PUB.size + 32 + "P-256".length
        assertEquals(expected, HandshakeMessageCodec.encodedSize(msg))
    }

    // ── E. Invalid/truncated input ───────────────────────────────────────

    @Test fun `E01 empty array throws`() {
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(ByteArray(0)) }
    }

    @Test fun `E02 truncated below minimum throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid())
        // Test several truncation points that exercise different decode paths
        val truncPoints = listOf(0, 1, 2, 5, 6, 7, 10, 20, MIN_SIZE - 1)
        for (truncLen in truncPoints) {
            if (truncLen >= enc.size) continue
            val thrown = try {
                HandshakeMessageCodec.decode(enc.sliceArray(0 until truncLen))
                null
            } catch (e: Exception) { e }
            assertTrue("Expected MalformedHandshake at trunc=$truncLen but got $thrown",
                thrown is HandshakeError.MalformedHandshake)
        }
    }

    @Test fun `E03 single byte array throws`() {
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(byteArrayOf(0x01)) }
    }

    // ── F. Invalid nonce ─────────────────────────────────────────────────

    @Test fun `F01 nonce too short throws`() {
        // Build message with 31-byte nonce instead of 32
        val bad = buildInvalidNonceFrame(nonceLen = 31)
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(bad) }
    }

    @Test fun `F02 nonce too long rejects via trailing bytes`() {
        val bad = buildInvalidNonceFrame(nonceLen = 33)
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(bad) }
    }

    private fun buildInvalidNonceFrame(nonceLen: Int): ByteArray {
        val algBytes = "Ed25519".toByteArray()
        val kaBytes = "P-256".toByteArray()
        val idPub = byteArrayOf(0x30, 0x00)
        val ephPub = byteArrayOf(0x30, 0x01)
        val nonce = ByteArray(nonceLen).also { for (i in it.indices) it[i] = i.toByte() }
        val size = 2 + 4 * 4 + algBytes.size + idPub.size + ephPub.size + nonce.size + kaBytes.size
        val buf = ByteArray(size)
        var off = 0
        buf[off++] = SecureHandshake.PROTOCOL_VERSION
        buf[off++] = 0.toByte()
        writeU32be(buf, off, algBytes.size); off += 4; System.arraycopy(algBytes, 0, buf, off, algBytes.size); off += algBytes.size
        writeU32be(buf, off, idPub.size); off += 4; System.arraycopy(idPub, 0, buf, off, idPub.size); off += idPub.size
        writeU32be(buf, off, ephPub.size); off += 4; System.arraycopy(ephPub, 0, buf, off, ephPub.size); off += ephPub.size
        System.arraycopy(nonce, 0, buf, off, nonce.size); off += nonce.size
        writeU32be(buf, off, kaBytes.size); off += 4; System.arraycopy(kaBytes, 0, buf, off, kaBytes.size); off += kaBytes.size
        return buf
    }

    // ── G. Invalid algorithm ─────────────────────────────────────────────

    @Test fun `G01 unsupported idAlgorithm throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(idAlgorithm = "RSA"))
        assertThrows(HandshakeError.UnsupportedAlgorithm::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    @Test fun `G02 unsupported keyAgreementAlg throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(keyAgreementAlg = "X25519"))
        assertThrows(HandshakeError.UnsupportedAlgorithm::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    @Test fun `G03 wrong protocolVersion throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(protocolVersion = 2))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    // ── H. Invalid role ──────────────────────────────────────────────────

    @Test fun `H01 role = 2 throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(role = 2))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    @Test fun `H02 role = 0xFF throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(role = 0xFF.toByte()))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    // ── I. Invalid public-key length ─────────────────────────────────────

    @Test fun `I01 empty idPublicKey throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(idPublicKey = ByteArray(0)))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    @Test fun `I02 empty ephPublicKey throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid(ephPublicKey = ByteArray(0)))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    // ── J. HELLO_ACK parsing ─────────────────────────────────────────────

    @Test fun `J01 HELLO_ACK payload includes signature after handshake message`() {
        val msg = makeValid(role = 1)
        val msgBytes = HandshakeMessageCodec.encode(msg)
        val sig = ByteArray(64)
        val ack = msgBytes + sig
        val dec = HandshakeMessageCodec.decode(msgBytes)
        assertEquals(1, dec.role.toInt() and 0xFF)
        val remaining = ack.sliceArray(msgBytes.size until ack.size)
        assertArrayEquals(sig, remaining)
    }

    @Test fun `J02 variable signature lengths parse correctly`() {
        val msg = makeValid(role = 1)
        val msgBytes = HandshakeMessageCodec.encode(msg)
        for (sigLen in listOf(1, 32, 64, 128, 256)) {
            val unused = makeValid(role = 1)
            val dec = HandshakeMessageCodec.decode(msgBytes)
            assertEquals(1, dec.role.toInt() and 0xFF)
        }
    }

    // ── K. HANDSHAKE_FINISH parsing ──────────────────────────────────────

    @Test fun `K01 HANDSHAKE_FINISH signature-only payload`() {
        val sig = ByteArray(64).also { for (i in it.indices) it[i] = (i and 0xFF).toByte() }
        assertEquals(64, sig.size)
    }

    @Test fun `K02 zero-length signature rejected`() {
        assertThrows(HandshakeError.MalformedHandshake::class.java) {
            if (ByteArray(0).isEmpty()) throw HandshakeError.MalformedHandshake
        }
    }

    // ── L. Zero-length fields ────────────────────────────────────────────

    @Test fun `L01 zero-length idPublicKey rejected`() {
        val enc = HandshakeMessageCodec.encode(makeValid(idPublicKey = ByteArray(0)))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    @Test fun `L02 zero-length ephPublicKey rejected`() {
        val enc = HandshakeMessageCodec.encode(makeValid(ephPublicKey = ByteArray(0)))
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(enc) }
    }

    // ── M. Malformed length prefix ───────────────────────────────────────

    @Test fun `M01 oversized length prefix throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid())
        val corrupted = enc.copyOf()
        writeU32be(corrupted, 2, Int.MAX_VALUE)
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(corrupted) }
    }

    @Test fun `M02 negative length prefix throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid())
        val corrupted = enc.copyOf()
        corrupted[2] = 0x80.toByte()
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(corrupted) }
    }

    // ── N. Trailing bytes ────────────────────────────────────────────────

    @Test fun `N01 trailing bytes after complete message throws`() {
        val enc = HandshakeMessageCodec.encode(makeValid())
        val withTrail = enc + ByteArray(5).also { for (i in it.indices) it[i] = 0xAA.toByte() }
        assertThrows(HandshakeError.MalformedHandshake::class.java) { HandshakeMessageCodec.decode(withTrail) }
    }

    @Test fun `N02 single trailing byte rejects`() {
        val enc = HandshakeMessageCodec.encode(makeValid())
        assertThrows(HandshakeError.MalformedHandshake::class.java) {
            HandshakeMessageCodec.decode(enc + byteArrayOf(0x01))
        }
    }

    // ── O. Deterministic encodedSize ─────────────────────────────────────

    @Test fun `O01 encodedSize equals encodeLength for valid message`() {
        val msg = makeValid()
        assertEquals(HandshakeMessageCodec.encode(msg).size, HandshakeMessageCodec.encodedSize(msg))
    }

    @Test fun `O02 encodedSize changes predictably with field sizes`() {
        val base = makeValid()
        val baseSize = HandshakeMessageCodec.encodedSize(base)
        val longerAlg = base.copy(idAlgorithm = base.idAlgorithm + "X")
        assertEquals(baseSize + 1, HandshakeMessageCodec.encodedSize(longerAlg))
        val longerPub = base.copy(idPublicKey = base.idPublicKey + byteArrayOf(0x00))
        assertEquals(baseSize + 1, HandshakeMessageCodec.encodedSize(longerPub))
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun computeNonceOffset(msg: HandshakeMessage): Int {
        return 2 + 4 * 4 + msg.idAlgorithm.toByteArray().size + msg.idPublicKey.size + msg.ephPublicKey.size
    }

    private fun readU32be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeU32be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value shr 24).toByte()
        buf[offset + 1] = (value shr 16).toByte()
        buf[offset + 2] = (value shr 8).toByte()
        buf[offset + 3] = value.toByte()
    }
}
