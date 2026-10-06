package com.filo.transfer.core.network.security.crypto

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Tests for the secure-frame codec that wraps AES-256-GCM encryption
 * into the wire format defined by Task 21A.
 */
class SecureFrameCodecTest {

    private val keyA = ByteArray(AesGcm.KEY_SIZE) { i -> i.toByte() }
    private val keyB = ByteArray(AesGcm.KEY_SIZE) { i -> (i + 1).toByte() }

    // ── Format tests ───────────────────────────────────────────────────────

    @Test
    fun `F01 correct MAGIC in encoded frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "hello".toByteArray(), keyA)
        assertArrayEquals(ProtocolConstants.MAGIC_HEADER, frame.copyOfRange(0, 4))
    }

    @Test
    fun `F02 correct VERSION in encoded frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "hello".toByteArray(), keyA)
        assertEquals(SecureFrameCodec.SECURE_FRAME_VERSION.toInt(), frame[4].toInt() and 0xFF)
    }

    @Test
    fun `F03 correct TYPE in encoded frame`() {
        val frame = encodeSimple(0L, FrameType.DATA_CHUNK, "data".toByteArray(), keyA)
        assertEquals(FrameType.DATA_CHUNK.code.toInt() and 0xFF, frame[5].toInt() and 0xFF)
    }

    @Test
    fun `F04 correct CRYPTO_SEQ in encoded frame`() {
        val seq = 0xDEADL
        val frame = encodeSimple(seq, FrameType.COMPLETE, ByteArray(0), keyA)
        val decoded = ByteBuffer.wrap(frame.copyOfRange(6, 14)).long
        assertEquals(seq, decoded)
    }

    @Test
    fun `F05 correct CT_LEN in encoded frame`() {
        val pt = "filo-test".toByteArray()
        val frame = encodeSimple(0L, FrameType.MANIFEST, pt, keyA)
        val ctLen = ByteBuffer.wrap(frame, 14, 4).int
        // CT_LEN is the whole ciphertext||tag blob: plaintext + 16-byte tag
        assertEquals(pt.size + AesGcm.TAG_SIZE, ctLen)
    }

    @Test
    fun `F06 correct ciphertext and tag separation`() {
        val pt = "separation-test".toByteArray()
        val frame = encodeSimple(0L, FrameType.COMPLETE, pt, keyA)
        val ctLen = ByteBuffer.wrap(frame, 14, 4).int
        assertEquals(pt.size + AesGcm.TAG_SIZE, ctLen)
        // Tag is the final 16 bytes of the blob; ciphertext is everything before it
        val blobStart = SecureFrameCodec.HEADER_SIZE
        val tagStart = blobStart + ctLen - AesGcm.TAG_SIZE
        val tag = frame.copyOfRange(tagStart, tagStart + AesGcm.TAG_SIZE)
        assertTrue(tag.size == AesGcm.TAG_SIZE)
        val ciphertext = frame.copyOfRange(blobStart, tagStart)
        assertEquals(pt.size, ciphertext.size)
        assertEquals(SecureFrameCodec.HEADER_SIZE + ctLen, frame.size)
    }

    @Test
    fun `F07 correct big-endian encoding`() {
        val seq = 0x0102L
        val frame = encodeSimple(seq, FrameType.CHECKSUM, ByteArray(0), keyA)
        val buf = ByteBuffer.wrap(frame.copyOfRange(6, 14)).order(java.nio.ByteOrder.BIG_ENDIAN)
        assertEquals(seq, buf.long)
    }

    @Test
    fun `F08 correct total frame length`() {
        val pt = "length-test-payload".toByteArray()
        val frame = encodeSimple(0L, FrameType.FILE_HEADER, pt, keyA)
        val ctLen = ByteBuffer.wrap(frame.copyOfRange(14, 18)).int
        val expected = SecureFrameCodec.HEADER_SIZE + pt.size + AesGcm.TAG_SIZE
        assertEquals(expected, frame.size)
    }

    @Test
    fun `F09 correct AAD construction`() {
        val seq = 7L
        val type = FrameType.RESUME_REQUEST.code
        val ctLen = 100
        val aad = SecureFrameCodec.buildAad(SecureFrameCodec.SECURE_FRAME_VERSION, type, seq, ctLen)
        assertEquals(14, aad.size)
        assertEquals(SecureFrameCodec.SECURE_FRAME_VERSION.toInt() and 0xFF, aad[0].toInt() and 0xFF)
        assertEquals(type.toInt() and 0xFF, aad[1].toInt() and 0xFF)
        val decodedSeq = ByteBuffer.wrap(aad.copyOfRange(2, 10)).long
        assertEquals(seq, decodedSeq)
        val decodedCtLen = ByteBuffer.wrap(aad.copyOfRange(10, 14)).int
        assertEquals(ctLen, decodedCtLen)
    }

    @Test
    fun `F10 MAGIC is not included in AAD`() {
        val aad = SecureFrameCodec.buildAad(SecureFrameCodec.SECURE_FRAME_VERSION, FrameType.MANIFEST.code, 0L, 0)
        // AAD should be exactly 14 bytes and contain no magic
        assertEquals(14, aad.size)
        assertFalse(aad.contentEquals(ProtocolConstants.MAGIC_HEADER) || aad.sliceArray(0..3).contentEquals(ProtocolConstants.MAGIC_HEADER))
    }

    @Test
    fun `F11 AAD size is exactly 14 bytes`() {
        val aad = SecureFrameCodec.buildAad(SecureFrameCodec.SECURE_FRAME_VERSION, FrameType.DATA_CHUNK.code, 42L, 99)
        assertEquals(14, aad.size)
    }

    // ── Encryption / decryption round-trip ─────────────────────────────────

    @Test
    fun `E01 normal round trip`() {
        val pt = "normal-roundtrip".toByteArray()
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(pt, FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(pt, result.plaintext)
        assertEquals(FrameType.MANIFEST, result.type)
        assertEquals(0L, result.cryptoSequence)
    }

    @Test
    fun `E02 empty plaintext round trip`() {
        val pt = ByteArray(0)
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(pt, FrameType.COMPLETE, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0, result.plaintext.size)
        assertEquals(FrameType.COMPLETE, result.type)
    }

    @Test
    fun `E03 small plaintext round trip`() {
        val pt = "small".toByteArray()
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(pt, FrameType.ERROR, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(pt, result.plaintext)
    }

    @Test
    fun `E04 larger reasonable plaintext round trip`() {
        val pt = ByteArray(64 * 1024) { i -> (i and 0xFF).toByte() }
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(pt, FrameType.DATA_CHUNK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(pt, result.plaintext)
        assertEquals(FrameType.DATA_CHUNK, result.type)
    }

    @Test
    fun `E05 non-ASCII binary plaintext round trip`() {
        val pt = byteArrayOf(0x00.toByte(), 0xFF.toByte(), 0x80.toByte(), 0x7F.toByte(), 0x42.toByte(), 0xFE.toByte(), 0x01.toByte(), 0x00.toByte())
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(pt, FrameType.CHECKSUM, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(pt, result.plaintext)
    }

    @Test
    fun `E06 different frame types produce independently authenticated frames`() {
        val pt = "independent".toByteArray()
        val cs1 = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val cs2 = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs1 = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs2 = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame1 = SecureFrameCodec.encode(pt, FrameType.MANIFEST, keyA, cs1, CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame2 = SecureFrameCodec.encode(pt, FrameType.FILE_HEADER, keyA, cs2, CryptoDirection.INITIATOR_TO_RESPONDER)
        // Frames must differ because TYPE is part of AAD
        assertFalse(frame1.contentEquals(frame2))
        // Each decodes correctly with its own type
        val r1 = SecureFrameCodec.decode(frame1, keyA, rs1, CryptoDirection.INITIATOR_TO_RESPONDER)
        val r2 = SecureFrameCodec.decode(frame2, keyA, rs2, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(FrameType.MANIFEST, r1.type)
        assertEquals(FrameType.FILE_HEADER, r2.type)
    }

    // ── Authentication tests ───────────────────────────────────────────────

    @Test
    fun `A01 modify MAGIC rejects frame`() {
        // Category B (protocol/framing): mutated MAGIC is rejected by the parser,
        // before any crypto work
        val frame = encodeSimple(0L, FrameType.MANIFEST, "auth".toByteArray(), keyA)
        val bad = frame.copyOf()
        bad[0] = (bad[0].toInt() xor 0xFF).toByte()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `A02 modify VERSION rejects frame`() {
        // Category B: unsupported version is a protocol-level rejection
        val frame = encodeSimple(0L, FrameType.COMPLETE, "ver".toByteArray(), keyA)
        val bad = frame.copyOf()
        bad[4] = (bad[4].toInt() xor 0xFF).toByte()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsProtocolVersionMismatch {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `A03 mutate TYPE to another valid type rejects frame via GCM authentication`() {
        // Category A (crypto auth): TYPE is part of the AAD, so mutating it to
        // ANOTHER VALID type (MANIFEST 0x10 -> DATA_CHUNK 0x30) keeps the frame
        // structurally parseable, reaches AES-GCM, and fails authentication.
        val frame = encodeSimple(0L, FrameType.MANIFEST, "type".toByteArray(), keyA)
        val bad = frame.copyOf()
        bad[5] = FrameType.DATA_CHUNK.code
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `A04 mutate CRYPTO_SEQ rejects frame`() {
        // Encode a frame at sequence 1, mutate the CRYPTO_SEQ field to 2 while
        // leaving ciphertext and tag untouched. With a fresh receiver (expected 0)
        // the frame is a gap: it is rejected by the strict-order sequence check
        // BEFORE decryption, and the receive state does not advance.
        val frame = encodeSimple(1L, FrameType.DATA_CHUNK, "seq".toByteArray(), keyA)
        val bad = frame.copyOf()
        System.arraycopy(ByteBuffer.allocate(8).putLong(2L).array(), 0, bad, 6, 8)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsSequenceViolation {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `A04b forged CRYPTO_SEQ with aligned receiver fails GCM authentication`() {
        // Category A (crypto auth): a receiver aligned to expected=2 receives a
        // frame whose header says seq 2 but whose ciphertext was authenticated
        // under seq 1 (AAD/nonce mismatch). GCM authentication fails.
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val f0 = SecureFrameCodec.encode("a".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val f1 = SecureFrameCodec.encode("b".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.decode(f0, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.decode(f1, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(2L, rs.currentExpected)
        val forged = f1.copyOf()
        System.arraycopy(ByteBuffer.allocate(8).putLong(2L).array(), 0, forged, 6, 8)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(forged, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(2L, rs.currentExpected)
    }

    @Test
    fun `A05 modify CT_LEN rejects frame`() {
        // Category B (framing integrity): CT_LEN is part of the exact-size check,
        // so inflating it makes the frame structurally inconsistent and it is
        // rejected before reaching GCM. (The CT_LEN-vs-AAD binding is documented
        // as a framing-integrity property; see the Task21D report.)
        val frame = encodeSimple(0L, FrameType.COMPLETE, "clen".toByteArray(), keyA)
        val bad = frame.copyOf()
        bad[17] = (bad[17].toInt() + 1).toByte()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `A06 modify ciphertext rejects frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "ct".toByteArray(), keyA)
        val bad = frame.copyOf()
        bad[18] = (bad[18].toInt() xor 0xFF).toByte()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
    }

    @Test
    fun `A07 modify tag rejects frame`() {
        val frame = encodeSimple(0L, FrameType.COMPLETE, "tag".toByteArray(), keyA)
        val bad = frame.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
    }

    @Test
    fun `A08 wrong key rejects frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "key".toByteArray(), keyA)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(frame, keyB, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
    }

    @Test
    fun `A09 wrong direction rejects frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "dir".toByteArray(), keyA)
        val rs = CryptoReceiveSequence(CryptoDirection.RESPONDER_TO_INITIATOR)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.RESPONDER_TO_INITIATOR)
        }
    }

    // ── Direction safety invariant ─────────────────────────────────────────

    @Test
    fun `D01 initiator frame cannot decrypt as responder`() {
        val pt = "direction-invariant".toByteArray()
        val initCs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val respRs = CryptoReceiveSequence(CryptoDirection.RESPONDER_TO_INITIATOR)
        val frame = SecureFrameCodec.encode(pt, FrameType.MANIFEST, keyA, initCs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(frame, keyA, respRs, CryptoDirection.RESPONDER_TO_INITIATOR)
        }
    }

    @Test
    fun `D02 responder frame cannot decrypt as initiator`() {
        val pt = "direction-reverse".toByteArray()
        val respCs = CryptoSequence(CryptoDirection.RESPONDER_TO_INITIATOR)
        val initRs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(pt, FrameType.MANIFEST, keyA, respCs, CryptoDirection.RESPONDER_TO_INITIATOR)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(frame, keyA, initRs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
    }

    // ── Sequence tests ─────────────────────────────────────────────────────

    @Test
    fun `S01 first transmitted frame uses sequence 0`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode("seq0".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val seq = ByteBuffer.wrap(frame.copyOfRange(6, 14)).long
        assertEquals(0L, seq)
    }

    @Test
    fun `S02 next transmitted frame uses sequence 1`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.encode("s0".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode("s1".toByteArray(), FrameType.DATA_CHUNK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val seq = ByteBuffer.wrap(frame.copyOfRange(6, 14)).long
        assertEquals(1L, seq)
    }

    @Test
    fun `S03 receiver accepts sequence 0 then 1`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val f0 = SecureFrameCodec.encode("first".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val f1 = SecureFrameCodec.encode("second".toByteArray(), FrameType.DATA_CHUNK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val r0 = SecureFrameCodec.decode(f0, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val r1 = SecureFrameCodec.decode(f1, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, r0.cryptoSequence)
        assertEquals(1L, r1.cryptoSequence)
    }

    @Test
    fun `S04 receiver rejects duplicate sequence`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode("dup".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER) // seq 0 accepted
        // Replay the same frame bytes (seq 0 again) — out-of-order, rejected
        // before decryption and the state stays at expected=1
        assertThrowsSequenceViolation {
            SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(1L, rs.currentExpected)
    }

    @Test
    fun `S05 receiver rejects future sequence`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val futureFrame = encodeWithSeq(5L, FrameType.MANIFEST, "future".toByteArray(), keyA)
        assertThrowsSequenceViolation {
            SecureFrameCodec.decode(futureFrame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `S06 receiver rejects old sequence`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val f0 = SecureFrameCodec.encode("zero".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.decode(f0, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER) // accept 0
        val oldFrame = encodeWithSeq(0L, FrameType.MANIFEST, "old".toByteArray(), keyA)
        assertThrowsSequenceViolation {
            SecureFrameCodec.decode(oldFrame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(1L, rs.currentExpected)
    }

    @Test
    fun `S07 failed authentication does NOT advance receive sequence`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val badFrame = encodeWithSeq(0L, FrameType.MANIFEST, "bad".toByteArray(), keyB) // wrong key
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(badFrame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `S08 after failed auth, valid frame with expected sequence is accepted`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        // First, attempt decode with wrong key (should fail without advancing)
        val badFrame = encodeWithSeq(0L, FrameType.MANIFEST, "fail".toByteArray(), keyB)
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(badFrame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
        // Now send the real frame with seq 0
        val realFrame = SecureFrameCodec.encode("real".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(realFrame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, result.cryptoSequence)
        assertEquals(1L, rs.currentExpected)
    }

    @Test
    fun `S09 sequence transactionality — forged expected-seq frame leaves state, then valid frame advances`() {
        // Security-critical check (Task 21D item 11):
        // 1. expected = 0
        // 2. a structurally valid but FORGED frame for seq 0 (wrong key) is presented
        // 3. authentication fails
        // 4. expected sequence remains 0
        // 5. a valid frame for seq 0 is received
        // 6. authentication succeeds
        // 7. expected sequence becomes 1
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, rs.currentExpected)
        val forged = encodeWithSeq(0L, FrameType.MANIFEST, "forged".toByteArray(), keyB) // wrong key
        assertThrowsAesGcmException {
            SecureFrameCodec.decode(forged, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
        val valid = SecureFrameCodec.encode("valid".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val r = SecureFrameCodec.decode(valid, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, r.cryptoSequence)
        assertEquals(1L, rs.currentExpected)
    }

    @Test
    fun `S10 sequence beyond CRYPTO_SEQUENCE_MAX is rejected`() {
        // Only 0..2^63-2 are legal; MAX+1 (= Long.MAX_VALUE) must be rejected
        // as out-of-range, before any decryption.
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val bad = rawFrame(type = FrameType.MANIFEST.code, seq = Long.MAX_VALUE, ctLen = SecureFrameCodec.MIN_CT_LENGTH)
        assertThrowsSequenceViolation {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `S11 negative sequence is rejected`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val bad = rawFrame(type = FrameType.MANIFEST.code, seq = -1L, ctLen = SecureFrameCodec.MIN_CT_LENGTH)
        assertThrowsSequenceViolation {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `S12 encode failure does NOT reuse a consumed sequence`() {
        // SecureFrameCodec.encode() consumes exactly one sequence per call.
        // If encryption fails AFTER allocation (bad key size here), the sequence
        // is lost and must NOT be reused; the next encode uses the next sequence.
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        try {
            SecureFrameCodec.encode("x".toByteArray(), FrameType.MANIFEST, ByteArray(31), cs, CryptoDirection.INITIATOR_TO_RESPONDER)
            fail("expected encryption to fail on a 31-byte key")
        } catch (_: IllegalArgumentException) {
            // key-size validation failure, sequence 0 already consumed
        }
        val frame = SecureFrameCodec.encode("x".toByteArray(), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val seq = ByteBuffer.wrap(frame, 6, 8).long
        assertEquals(1L, seq)
    }

    // ── Post-session protocol rules ─────────────────────────────────────────

    @Test
    fun `P01 encode rejects post-session-forbidden handshake types`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        try {
            SecureFrameCodec.encode("h".toByteArray(), FrameType.HELLO, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
            fail("HELLO must be rejected in post-session secure frames")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            SecureFrameCodec.encode("h".toByteArray(), FrameType.HELLO_ACK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
            fail("HELLO_ACK must be rejected in post-session secure frames")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            SecureFrameCodec.encode("f".toByteArray(), FrameType.HANDSHAKE_FINISH, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
            fail("HANDSHAKE_FINISH (0x03) must be rejected in post-session secure frames")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `P02 decode rejects handshake type after session start`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val bad = rawFrame(type = 0x02.toByte(), seq = 0L, ctLen = SecureFrameCodec.MIN_CT_LENGTH)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `P02b decode rejects 0x03 HANDSHAKE_FINISH type after session start`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val bad = rawFrame(type = 0x03.toByte(), seq = 0L, ctLen = SecureFrameCodec.MIN_CT_LENGTH)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `P03 decode rejects unknown frame type`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val bad = rawFrame(type = 0xEE.toByte(), seq = 0L, ctLen = SecureFrameCodec.MIN_CT_LENGTH)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `P04 empty ciphertext (CT_LEN = minimum 16) round trips`() {
        // The smallest legal blob is exactly the 16-byte tag with empty ciphertext
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = SecureFrameCodec.encode(ByteArray(0), FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0, result.plaintext.size)
    }

    @Test
    fun `P05 plaintext at maximum allowed length round trips`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val pt = ByteArray(SecureFrameCodec.MAX_PLAINTEXT_LENGTH) { 0x5A }
        val frame = SecureFrameCodec.encode(pt, FrameType.DATA_CHUNK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(pt, result.plaintext)
    }

    @Test
    fun `P06 plaintext above maximum is rejected at encode time`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val oversized = ByteArray(SecureFrameCodec.MAX_PLAINTEXT_LENGTH + 1)
        try {
            SecureFrameCodec.encode(oversized, FrameType.DATA_CHUNK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
            fail("oversized plaintext must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── Boundary / malformed input tests ───────────────────────────────────

    @Test
    fun `B01 truncated header rejects frame`() {
        val short = ByteArray(10) { 0x00 }
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(short, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
    }

    @Test
    fun `B02 truncated ciphertext rejects frame`() {
        // Category B (framing): a truncated frame is structurally invalid and is
        // rejected before GCM; it must NOT be described as an auth failure.
        val frame = encodeSimple(0L, FrameType.MANIFEST, "trunc".toByteArray(), keyA)
        val short = frame.copyOfRange(0, frame.size - 8) // chop off part of the blob
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(short, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B03 missing tag rejects frame`() {
        val frame = encodeSimple(0L, FrameType.COMPLETE, "notag".toByteArray(), keyA)
        val short = frame.copyOfRange(0, frame.size - AesGcm.TAG_SIZE)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(short, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B04 extra trailing byte rejects frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "extra".toByteArray(), keyA)
        val withExtra = frame.copyOf() + byteArrayOf(0xAA.toByte())
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(withExtra, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B05 CT_LEN mismatch rejects frame`() {
        val frame = encodeSimple(0L, FrameType.MANIFEST, "mismatch".toByteArray(), keyA)
        val bad = frame.copyOf()
        // Inflate the CT_LEN field (ciphertext||tag blob) by 1 so it no longer
        // matches the actual blob: the frame becomes size-inconsistent (Category B)
        bad[17] = (bad[17].toInt() + 1).toByte()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B06 unsupported version rejects frame`() {
        val bad = encodeWithVersion(99, 0L, FrameType.MANIFEST, "badver".toByteArray(), keyA)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsProtocolVersionMismatch {
            SecureFrameCodec.decode(bad, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B07 invalid MAGIC rejects frame`() {
        val bad = encodeSimple(0L, FrameType.MANIFEST, "badmagic".toByteArray(), keyA)
        val corrupted = bad.copyOf()
        corrupted[0] = 0x00
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(corrupted, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B08 excessive CT_LEN rejects frame`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        // A structurally-sized frame whose CT_LEN exceeds MAX_CT_LENGTH. The
        // sequence is the expected 0 so we pass the order check and reach the
        // CT_LEN range check, which must reject as OversizedPayload.
        val frame = rawFrame(
            type = FrameType.MANIFEST.code,
            seq = 0L,
            ctLen = SecureFrameCodec.MAX_CT_LENGTH + 1
        )
        assertThrowsOversizedPayload {
            SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B09 negative CT_LEN (signed interpretation) rejects frame`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = rawFrame(
            type = FrameType.MANIFEST.code,
            seq = 0L,
            ctLen = -2147483648 // 0x80000000, negative when read as a signed int
        )
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `B10 CT_LEN below minimum rejects frame`() {
        // The smallest blob is the 16-byte tag; 15 is below MIN_CT_LENGTH
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val frame = rawFrame(
            type = FrameType.MANIFEST.code,
            seq = 0L,
            ctLen = SecureFrameCodec.MIN_CT_LENGTH - 1
        )
        assertThrowsInvalidFrame {
            SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        }
        assertEquals(0L, rs.currentExpected)
    }

    // ── Isolation tests ────────────────────────────────────────────────────

    @Test
    fun `I01 existing plaintext FrameCodec behavior unchanged`() {
        val bout = java.io.ByteArrayOutputStream()
        com.filo.transfer.core.network.protocol.FrameCodec.writeFrame(
            bout,
            com.filo.transfer.core.network.protocol.ProtocolFrame(
                type = FrameType.HELLO,
                payload = "hello".toByteArray()
            )
        )
        val bin = java.io.ByteArrayInputStream(bout.toByteArray())
        val decoded = com.filo.transfer.core.network.protocol.FrameCodec.readFrame(bin)
        assertEquals(FrameType.HELLO, decoded.type)
        assertArrayEquals("hello".toByteArray(), decoded.payload)
    }

    @Test
    fun `I02 encode does not modify input plaintext array`() {
        val pt = "immutable".toByteArray()
        val original = pt.copyOf()
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.encode(pt, FrameType.MANIFEST, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(original, pt)
    }

    @Test
    fun `I03 decode does not modify input frame array`() {
        val frame = encodeSimple(0L, FrameType.COMPLETE, "read-only".toByteArray(), keyA)
        val original = frame.copyOf()
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(original, frame)
    }

    @Test
    fun `I04 returned plaintext is a copy`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val pt = "copy-test".toByteArray()
        val frame = SecureFrameCodec.encode(pt, FrameType.DATA_CHUNK, keyA, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = SecureFrameCodec.decode(frame, keyA, rs, CryptoDirection.INITIATOR_TO_RESPONDER)
        result.plaintext.fill(0x00)
        // Re-decode original frame — should still yield original plaintext
        val rs2 = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val result2 = SecureFrameCodec.decode(frame, keyA, rs2, CryptoDirection.INITIATOR_TO_RESPONDER)
        assertArrayEquals(pt, result2.plaintext)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun encodeSimple(seq: Long, type: FrameType, plaintext: ByteArray, key: ByteArray): ByteArray {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        // Advance to the desired sequence
        repeat(seq.toInt()) { cs.nextSequence() }
        return SecureFrameCodec.encode(plaintext, type, key, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
    }

    private fun encodeWithSeq(seq: Long, type: FrameType, plaintext: ByteArray, key: ByteArray): ByteArray {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        repeat(seq.toInt()) { cs.nextSequence() }
        return SecureFrameCodec.encode(plaintext, type, key, cs, CryptoDirection.INITIATOR_TO_RESPONDER)
    }

    private fun buildTestAad(version: Byte, typeCode: Byte, seq: Long, ctLen: Int): ByteArray {
        val aad = ByteArray(14)
        aad[0] = version
        aad[1] = typeCode
        ByteBuffer.wrap(aad, 2, 8).putLong(seq)
        ByteBuffer.wrap(aad, 10, 4).putInt(ctLen)
        return aad
    }

    private fun encodeWithVersion(version: Int, seq: Long, type: FrameType, plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = buildNonce(CryptoDirection.INITIATOR_TO_RESPONDER, seq)
        val aad = buildTestAad(version.toByte(), type.code, seq, plaintext.size + AesGcm.TAG_SIZE)
        val ctAndTag = AesGcm.encrypt(key, nonce, aad, plaintext)
        val headerOut = ByteArrayOutputStream()
        headerOut.write(ProtocolConstants.MAGIC_HEADER)
        headerOut.write(version and 0xFF)
        headerOut.write(type.code.toInt() and 0xFF)
        headerOut.write(ByteBuffer.allocate(8).putLong(seq).array())
        headerOut.write(ByteBuffer.allocate(4).putInt(ctAndTag.size).array())
        val header = headerOut.toByteArray()
        return header + ctAndTag
    }

    /**
     * Builds a raw 34-byte frame (18-byte header + 16 filler bytes) with the given
     * type code, crypto sequence, and CT_LEN. The filler bytes are not decrypted
     * because all tests using this helper are rejected before AES-GCM.
     */
    private fun rawFrame(type: Byte, seq: Long, ctLen: Int): ByteArray {
        val frame = ByteArray(SecureFrameCodec.MIN_FRAME_SIZE)
        System.arraycopy(ProtocolConstants.MAGIC_HEADER, 0, frame, 0, ProtocolConstants.MAGIC_HEADER.size)
        frame[4] = SecureFrameCodec.SECURE_FRAME_VERSION
        frame[5] = type
        ByteBuffer.wrap(frame, 6, 8).putLong(seq)
        ByteBuffer.wrap(frame, 14, 4).putInt(ctLen)
        return frame
    }

    private fun assertThrowsAesGcmException(block: () -> Unit) {
        try {
            block()
            fail("expected AesGcmException")
        } catch (e: AesGcmException) {
            assertEquals("AUTHENTICATION_FAILED", e.message)
        }
    }

    private fun assertThrowsSequenceViolation(block: () -> Unit) {
        try {
            block()
            fail("expected SequenceViolationException")
        } catch (_: SequenceViolationException) {
            // expected — pre-decryption strict-order rejection
        }
    }

    private fun assertThrowsInvalidFrame(block: () -> Unit) {
        try {
            block()
            fail("expected NetworkError.InvalidFrame")
        } catch (_: NetworkError.InvalidFrame) {
            // expected — protocol/parser rejection
        }
    }

    private fun assertThrowsProtocolVersionMismatch(block: () -> Unit) {
        try {
            block()
            fail("expected NetworkError.ProtocolVersionMismatch")
        } catch (_: NetworkError.ProtocolVersionMismatch) {
            // expected — version rejection
        }
    }

    private fun assertThrowsOversizedPayload(block: () -> Unit) {
        try {
            block()
            fail("expected NetworkError.OversizedPayload")
        } catch (_: NetworkError.OversizedPayload) {
            // expected — CT_LEN above maximum
        }
    }
}
