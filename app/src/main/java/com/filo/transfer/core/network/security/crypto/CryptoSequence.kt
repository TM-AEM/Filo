package com.filo.transfer.core.network.security.crypto

/**
 * Direction identifier for AES-256-GCM nonce construction.
 *
 * Nonces are constructed as DIR4 || SEQ8 where DIR is this value encoded as
 * a 4-byte big-endian integer.
 */
enum class CryptoDirection(val value: Int) {
    INITIATOR_TO_RESPONDER(0x00000001),
    RESPONDER_TO_INITIATOR(0x00000002);

    companion object {
        fun fromInt(v: Int): CryptoDirection = values().firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Invalid direction: 0x${v.toString(16).padStart(8, '0')}")
    }
}

/**
 * Combined sequence number and derived nonce.
 *
 * @property sequence the crypto sequence number (0-based, monotonic per session)
 * @property nonce the 12-byte nonce = DIR4 || SEQ8 (big-endian)
 */
data class SequenceAndNonce(val sequence: Long, val nonce: ByteArray)

/**
 * Transmitter-side cryptographic sequence state.
 *
 * Produces nonces for AES-256-GCM encryption. Each call advances the sequence.
 *
 * - Starts at sequence 0.
 * - First call returns sequence 0, then advances to 1.
 * - Does not wrap: requesting after Long.MAX_VALUE throws IllegalStateException.
 * - Returns a fresh nonce byte array on each call; callers may mutate the returned
 *   array without affecting internal state.
 */
class CryptoSequence(private val direction: CryptoDirection) {
    private var currentSequence: Long = 0L

    fun nextSequence(): SequenceAndNonce {
        if (currentSequence < 0L) {
            throw IllegalStateException("Crypto sequence overflow: cannot advance beyond Long.MAX_VALUE")
        }
        val seq = currentSequence
        currentSequence = currentSequence + 1
        return SequenceAndNonce(seq, buildNonce(direction, seq))
    }
}

/**
 * Receiver-side cryptographic sequence validator.
 *
 * Validates incoming sequences with strict expected+1 semantics.
 * Does not advance state on rejection.
 *
 * - Initial expected = 0.
 * - Accepts only the exact expected sequence.
 * - Advances only after successful validation.
 */
class CryptoReceiveSequence(private val direction: CryptoDirection) {
    var currentExpected: Long = 0L
        private set

    fun validateAndAdvance(sequence: Long): Boolean {
        if (sequence < 0L) return false
        if (sequence != currentExpected) return false
        currentExpected++
        return true
    }
}

internal fun buildNonce(direction: CryptoDirection, sequence: Long): ByteArray {
    require(sequence >= 0L) { "Sequence must be non-negative, got $sequence" }
    val nonce = ByteArray(AesGcm.NONCE_SIZE)
    // DIR4: big-endian encoding of the direction's int value in bytes [0..3]
    nonce[0] = ((direction.value ushr 24) and 0xFF).toByte()
    nonce[1] = ((direction.value ushr 16) and 0xFF).toByte()
    nonce[2] = ((direction.value ushr 8) and 0xFF).toByte()
    nonce[3] = (direction.value and 0xFF).toByte()
    // SEQ8: big-endian encoding of the long sequence in bytes [4..11]
    for (i in 0 until 8) {
        nonce[4 + i] = ((sequence ushr (56 - 8 * i)) and 0xFF).toByte()
    }
    return nonce
}
