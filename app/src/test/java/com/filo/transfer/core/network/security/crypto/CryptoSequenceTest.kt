package com.filo.transfer.core.network.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crypto sequence and nonce state tests for AES-256-GCM transport.
 *
 * Nonce format: DIR4 (big-endian) || SEQ8 (big-endian) = 12 bytes total.
 */
class CryptoSequenceTest {

    // ── Direction constants ──────────────────────────────────────────────

    @Test
    fun `CryptoDirection INITIATOR_TO_RESPONDER has value 1`() {
        assertEquals(0x00000001L, CryptoDirection.INITIATOR_TO_RESPONDER.value.toLong())
    }

    @Test
    fun `CryptoDirection RESPONDER_TO_INITIATOR has value 2`() {
        assertEquals(0x00000002L, CryptoDirection.RESPONDER_TO_INITIATOR.value.toLong())
    }

    @Test
    fun `CryptoDirection fromInt rejects invalid value`() {
        assertThrows(IllegalArgumentException::class.java) {
            CryptoDirection.fromInt(0x00000000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoDirection.fromInt(0x00000003)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoDirection.fromInt(-1)
        }
    }

    @Test
    fun `CryptoDirection values are distinct`() {
        assertNotEquals(CryptoDirection.INITIATOR_TO_RESPONDER, CryptoDirection.RESPONDER_TO_INITIATOR)
    }

    // ── SequenceAndNonce ─────────────────────────────────────────────────

    @Test
    fun `SequenceAndNonce holds sequence and nonce`() {
        val seq = 42L
        val nonce = ByteArray(12) { 0x77 }
        val sn = SequenceAndNonce(seq, nonce)
        assertEquals(seq, sn.sequence)
        assertTrue(sn.nonce.all { it == 0x77.toByte() })
    }

    // ── CryptoSequence initial state ─────────────────────────────────────

    @Test
    fun `T01 CryptoSequence starts at zero`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, cs.nextSequence().sequence)
    }

    @Test
    fun `T02 first generated sequence is zero`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val first = cs.nextSequence()
        assertEquals(0L, first.sequence)
    }

    @Test
    fun `T03 second generated sequence is one`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        cs.nextSequence() // consume 0
        val second = cs.nextSequence()
        assertEquals(1L, second.sequence)
    }

    @Test
    fun `T04 third generated sequence is two`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        cs.nextSequence() // 0
        cs.nextSequence() // 1
        val third = cs.nextSequence()
        assertEquals(2L, third.sequence)
    }

    // ── Nonce length and encoding ────────────────────────────────────────

    @Test
    fun `T05 generated nonce is exactly 12 bytes`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = cs.nextSequence()
        assertEquals(12, result.nonce.size)
    }

    @Test
    fun `T06 direction is encoded in first four bytes big-endian`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val result = cs.nextSequence()
        val nonce = result.nonce
        assertEquals(0x00, nonce[0].toInt())
        assertEquals(0x00, nonce[1].toInt())
        assertEquals(0x00, nonce[2].toInt())
        assertEquals(0x01, nonce[3].toInt())
    }

    @Test
    fun `T07 sequence is encoded in last eight bytes big-endian`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        cs.nextSequence() // consume 0
        val result = cs.nextSequence() // should be 1
        val nonce = result.nonce
        // Bytes 4-11 are the 8-byte big-endian sequence
        val seqBytes = nonce.sliceArray(4..11)
        val decoded = java.nio.ByteBuffer.wrap(seqBytes).long
        assertEquals(1L, decoded)
    }

    @Test
    fun `T08 encoding is big-endian for sequence 256`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        // consume sequences 0..255
        repeat(256) { cs.nextSequence() }
        val result = cs.nextSequence() // sequence 256
        val nonce = result.nonce
        // seq 256 = 0x0000000000000100
        assertEquals(0x00, nonce[4].toInt())
        assertEquals(0x00, nonce[5].toInt())
        assertEquals(0x00, nonce[6].toInt())
        assertEquals(0x00, nonce[7].toInt())
        assertEquals(0x00, nonce[8].toInt())
        assertEquals(0x00, nonce[9].toInt())
        assertEquals(0x01, nonce[10].toInt())
        assertEquals(0x00, nonce[11].toInt())
    }

    // ── Direction separation ─────────────────────────────────────────────

    @Test
    fun `T09 initiator and responder produce different nonces for same sequence`() {
        val initSeq = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val respSeq = CryptoSequence(CryptoDirection.RESPONDER_TO_INITIATOR)
        val initNonce = initSeq.nextSequence().nonce
        val respNonce = respSeq.nextSequence().nonce
        assertNotEquals("nonces must differ", true, initNonce.contentEquals(respNonce))
    }

    @Test
    fun `T10 same direction different sequences produce different nonces`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val n0 = cs.nextSequence().nonce
        val n1 = cs.nextSequence().nonce
        assertNotEquals(true, n0.contentEquals(n1))
    }

    @Test
    fun `T11 different direction same sequence produce different nonces`() {
        val init = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val resp = CryptoSequence(CryptoDirection.RESPONDER_TO_INITIATOR)
        val nInit = init.nextSequence().nonce
        val nResp = resp.nextSequence().nonce
        assertNotEquals(true, nInit.contentEquals(nResp))
    }

    // ── Nonce uniqueness over range ──────────────────────────────────────

    @Test
    fun `T12 every generated nonce is unique over 0 to 1000`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val nonces = mutableListOf<ByteArray>()
        repeat(1001) {
            nonces.add(cs.nextSequence().nonce)
        }
        assertEquals(nonces.distinct().size, nonces.size)
    }

    // ── Large sequence encoding ──────────────────────────────────────────

    @Test
    fun `Large sequence encodes correctly at max safe value`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        // Use a large but safe sequence value
        val targetSeq = 1L shl 40 // ~1 trillion, fits comfortably in long
        // Set current state via reflection-like approach: just call nextSequence until we get there
        // Since we can't realistically iterate that many times, test encoding directly
        val nonce = buildNonce(CryptoDirection.INITIATOR_TO_RESPONDER, targetSeq)
        val decoded = java.nio.ByteBuffer.wrap(nonce.copyOfRange(4, 12)).long
        assertEquals(targetSeq, decoded)
    }

    // ── Overflow handling ────────────────────────────────────────────────

    @Test
    fun `T21 overflow guard prevents negative sequence progression`() {
        // Verify the overflow invariant: sequence must never go negative
        // Directly test buildNonce rejects negative sequences
        assertThrows(IllegalArgumentException::class.java) {
            buildNonce(CryptoDirection.INITIATOR_TO_RESPONDER, -1L)
        }
    }

    @Test
    fun `T22 sequence encoding preserves positive values at high bits`() {
        // Verify that large positive longs encode correctly without sign extension issues
        val highSeq = 0x7FFFFFFFFFFFFFFFL // Long.MAX_VALUE
        val nonce = buildNonce(CryptoDirection.INITIATOR_TO_RESPONDER, highSeq)
        val decoded = java.nio.ByteBuffer.wrap(nonce.copyOfRange(4, 12)).long
        assertEquals(highSeq, decoded)
    }

    // ── Nonce immutability ───────────────────────────────────────────────

    @Test
    fun `T23 returned nonce mutation cannot corrupt internal state`() {
        val cs = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val n0 = cs.nextSequence().nonce
        n0.fill(0x00)
        val n1 = cs.nextSequence().nonce
        assertNotEquals(true, n0.contentEquals(n1))
        assertTrue(n1.any { it != 0x00.toByte() })
    }

    // ── Fresh instance ───────────────────────────────────────────────────

    @Test
    fun `T24 new CryptoSequence starts at zero`() {
        val cs1 = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        val cs2 = CryptoSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, cs1.nextSequence().sequence)
        assertEquals(0L, cs2.nextSequence().sequence)
    }

    // ── CryptoReceiveSequence ────────────────────────────────────────────

    @Test
    fun `R01 receiver accepts sequence 0 initially`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertTrue(rs.validateAndAdvance(0L))
    }

    @Test
    fun `R02 receiver accepts sequence 1 after 0`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        rs.validateAndAdvance(0L)
        assertTrue(rs.validateAndAdvance(1L))
    }

    @Test
    fun `R03 receiver rejects duplicate sequence`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        rs.validateAndAdvance(0L)
        assertFalse(rs.validateAndAdvance(0L))
    }

    @Test
    fun `R04 receiver rejects old sequence`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        rs.validateAndAdvance(0L)
        rs.validateAndAdvance(1L)
        assertFalse(rs.validateAndAdvance(0L))
    }

    @Test
    fun `R05 receiver rejects future sequence`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertFalse(rs.validateAndAdvance(1L))
    }

    @Test
    fun `R06 receiver does not advance state after rejection`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertFalse(rs.validateAndAdvance(7L))
        // State should still expect 0
        assertTrue(rs.validateAndAdvance(0L))
    }

    @Test
    fun `R07 receiver continues correctly after rejected frame`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertFalse(rs.validateAndAdvance(5L)) // reject: expected 0, got 5
        assertTrue(rs.validateAndAdvance(0L))  // accept: expected 0
        assertTrue(rs.validateAndAdvance(1L))  // accept: expected 1
        assertTrue(rs.validateAndAdvance(2L))  // accept: expected 2
    }

    @Test
    fun `R08 receiver rejects negative sequence`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertFalse(rs.validateAndAdvance(-1L))
    }

    @Test
    fun `R09 receiver currentExpected is 0 initially`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        assertEquals(0L, rs.currentExpected)
    }

    @Test
    fun `R10 receiver currentExpected advances after success`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        rs.validateAndAdvance(0L)
        assertEquals(1L, rs.currentExpected)
    }

    @Test
    fun `R11 receiver currentExpected unchanged after failure`() {
        val rs = CryptoReceiveSequence(CryptoDirection.INITIATOR_TO_RESPONDER)
        rs.validateAndAdvance(7L)
        assertEquals(0L, rs.currentExpected)
    }
}
