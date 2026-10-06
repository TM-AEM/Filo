package com.filo.transfer.core.network.security.crypto

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolConstants
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Protocol-level rejection of a secure frame whose crypto sequence violates the
 * strict monotonic order required by Task 21A (STRICT_MONOTONIC_EXPECTED_PLUS_ONE).
 *
 * Thrown BEFORE any decryption: duplicates/replays, gaps, and out-of-range
 * sequences (negative or greater than [SecureFrameCodec.CRYPTO_SEQUENCE_MAX])
 * are never passed to AES-GCM ("No out-of-order decrypt").
 *
 * The message is intentionally generic per INVALID_AUTH_RULE: it carries no
 * sequence value, key, nonce, ciphertext, or plaintext details.
 */
class SequenceViolationException : IOException("Secure frame sequence violation; terminate secure session")

/**
 * Secure-frame codec — encodes and decodes AES-256-GCM authenticated frames
 * per the Task 21A specification.
 *
 * Wire format (post-handshake, version 2):
 * ```
 * MAGIC(4) || VER(1) || TYPE(1) || CRYPTO_SEQ(8 BE) || CT_LEN(4 BE) || CT||TAG(16)
 * ```
 * CT_LEN is the length of the whole `ciphertext || tag` blob:
 * `16 <= CT_LEN <= MAX_FRAME_PAYLOAD` (the tag is 16 bytes, the ciphertext
 * may be empty). Maximum inner plaintext is therefore 262128 bytes.
 *
 * AAD (exactly 14 bytes, same encodings as on the wire):
 * ```
 * VER(1) || TYPE(1) || CRYPTO_SEQ(8 BE) || CT_LEN(4 BE)
 * ```
 * MAGIC is intentionally excluded from the AAD.
 *
 * Nonce construction: DIR4 || SEQ8 = 12 bytes (via [buildNonce]).
 *
 * Decode order (fail closed, Task 21A section 12):
 * minimum size -> MAGIC -> VERSION -> TYPE (known, not post-session handshake
 * types) -> CRYPTO_SEQ (range + strict-order against the receive state,
 * WITHOUT advancing) -> CT_LEN (16..MAX) -> exact size -> GCM
 * authenticate/decrypt -> advance receive sequence ONLY after success.
 *
 * This component is pre-integration. It does not modify TcpSender, TcpReceiver,
 * FrameCodec, SecureHandshake, or any application transfer flow.
 */
object SecureFrameCodec {

    /** Secure-frame protocol version (one header byte, value 2). */
    const val SECURE_FRAME_VERSION: Byte = 2

    /** Total secure-frame header size: 4 (MAGIC) + 1 (VER) + 1 (TYPE) + 8 (CRYPTO_SEQ) + 4 (CT_LEN) = 18 bytes */
    const val HEADER_SIZE = 18

    /** Smallest legal `ciphertext || tag` blob: the 16-byte tag with empty ciphertext. */
    const val MIN_CT_LENGTH = AesGcm.TAG_SIZE

    /** Largest legal `ciphertext || tag` blob, derived from the existing protocol constant. */
    const val MAX_CT_LENGTH = ProtocolConstants.MAX_FRAME_PAYLOAD

    /** Maximum inner plaintext per frame: 262144 - 16 = 262128 bytes. */
    const val MAX_PLAINTEXT_LENGTH = MAX_CT_LENGTH - AesGcm.TAG_SIZE

    /** Smallest legal secure frame: 18-byte header + 16-byte tag. */
    const val MIN_FRAME_SIZE = HEADER_SIZE + MIN_CT_LENGTH

    /**
     * Largest legal crypto sequence (2^63 - 2). Only values 0..MAX are legal so
     * `writeLong` stays non-negative and MAX+1 is detectable before nonce reuse.
     */
    const val CRYPTO_SEQUENCE_MAX = 9223372036854775806L

    /**
     * Handshake frame types are forbidden in post-session secure frames
     * (Task 21A section 4 / invariant 13). All three handshake types — 0x01, 0x02,
     * 0x03 (HANDSHAKE_FINISH) — are rejected on both the encode and decode side.
     */
    private val FORBIDDEN_POST_SESSION_TYPES =
        setOf(FrameType.HELLO, FrameType.HELLO_ACK, FrameType.HANDSHAKE_FINISH)

    /**
     * Result of decoding a secure frame.
     *
     * @property plaintext authenticated decrypted bytes
     * @property type parsed frame type
     * @property cryptoSequence the cryptographic sequence number read from the frame
     */
    data class DecodedFrame(val plaintext: ByteArray, val type: FrameType, val cryptoSequence: Long)

    /**
     * Encodes [plaintext] into a secure-frame byte array.
     *
     * The caller supplies [sessionKey] (one of the directional keys from [SecureSession]),
     * [sequenceState] (a [CryptoSequence] for this direction), and [direction] (for nonce construction).
     *
     * Each call consumes exactly one sequence number from [sequenceState] BEFORE
     * encryption. If encryption subsequently fails, that sequence is lost and
     * MUST NOT be reused; the next call will use the next sequence.
     *
     * @throws IllegalArgumentException if [type] is a post-session-forbidden handshake type
     * @throws IllegalArgumentException if [plaintext] exceeds [MAX_PLAINTEXT_LENGTH]
     * @throws IllegalStateException if [sequenceState] has overflowed
     * @throws AesGcmException on crypto failure
     */
    fun encode(
        plaintext: ByteArray,
        type: FrameType,
        sessionKey: ByteArray,
        sequenceState: CryptoSequence,
        direction: CryptoDirection
    ): ByteArray {
        if (type in FORBIDDEN_POST_SESSION_TYPES) {
            throw IllegalArgumentException("${type.name} is forbidden in post-session secure frames")
        }
        if (plaintext.size > MAX_PLAINTEXT_LENGTH) {
            throw IllegalArgumentException(
                "Plaintext size ${plaintext.size} exceeds maximum $MAX_PLAINTEXT_LENGTH"
            )
        }
        // 1. Obtain sequence + nonce (atomic: sequence consumed before encryption)
        val seqAndNonce = sequenceState.nextSequence()
        val sequence = seqAndNonce.sequence
        if (sequence > CRYPTO_SEQUENCE_MAX) {
            throw IllegalStateException("Crypto sequence overflow; terminate secure session")
        }

        // 2. Build AAD deterministically from header fields (NOT including MAGIC).
        //    CT_LEN here is the wire blob length: ciphertext + 16-byte tag.
        val aad = buildAad(SECURE_FRAME_VERSION, type.code, sequence, plaintext.size + AesGcm.TAG_SIZE)

        // 3. Encrypt: output is ciphertext || 16-byte tag
        val ctAndTag = AesGcm.encrypt(sessionKey, seqAndNonce.nonce, aad, plaintext)

        // 4. Serialize frame with CT_LEN = blob length
        return serializeFrame(sequence, type, ctAndTag)
    }

    /**
     * Decodes a secure-frame byte array, authenticates it, and returns the plaintext.
     *
     * Structural validation (size, MAGIC, VERSION, TYPE, CRYPTO_SEQ range and
     * strict order, CT_LEN, exact size) happens BEFORE any AES-GCM work.
     * The receive sequence is advanced only after successful authentication;
     * on any failure the [receiveSequence] state is left unchanged.
     *
     * @throws NetworkError.InvalidFrame on magic/type/CT_LEN/bounds/malformation errors
     * @throws NetworkError.ProtocolVersionMismatch on unsupported version
     * @throws NetworkError.OversizedPayload if CT_LEN exceeds [MAX_CT_LENGTH]
     * @throws SequenceViolationException on out-of-range or out-of-order CRYPTO_SEQ
     * @throws AesGcmException("AUTHENTICATION_FAILED") on GCM authentication failure
     */
    fun decode(
        frame: ByteArray,
        sessionKey: ByteArray,
        receiveSequence: CryptoReceiveSequence,
        direction: CryptoDirection
    ): DecodedFrame {
        // 1. Structural: minimum size (header + smallest blob)
        if (frame.size < MIN_FRAME_SIZE) {
            throw NetworkError.InvalidFrame("Frame too short: ${frame.size} bytes, need at least $MIN_FRAME_SIZE")
        }

        // 2. Validate MAGIC
        if (!frame.copyOfRange(0, ProtocolConstants.MAGIC_HEADER.size).contentEquals(ProtocolConstants.MAGIC_HEADER)) {
            throw NetworkError.InvalidFrame("Invalid protocol magic header")
        }

        // 3. Validate VERSION
        val version = frame[4].toInt() and 0xFF
        if (version != SECURE_FRAME_VERSION.toInt()) {
            throw NetworkError.ProtocolVersionMismatch(
                expected = SECURE_FRAME_VERSION.toInt(),
                actual = version
            )
        }

        // 4. Parse TYPE: must be a known type and not a post-session handshake type
        val frameType = FrameType.fromCode(frame[5])
            ?: throw NetworkError.InvalidFrame("Unknown secure frame type code")
        if (frameType in FORBIDDEN_POST_SESSION_TYPES) {
            throw NetworkError.InvalidFrame("Handshake frame type forbidden after session start")
        }

        // 5. Parse CRYPTO_SEQ: range + strict-order check BEFORE any decryption
        val cryptoSequence = ByteBuffer.wrap(frame, 6, 8).long
        if (cryptoSequence < 0L || cryptoSequence > CRYPTO_SEQUENCE_MAX) {
            throw SequenceViolationException()
        }
        if (cryptoSequence != receiveSequence.currentExpected) {
            // Duplicate / replay / gap: no out-of-order decrypt, state untouched
            throw SequenceViolationException()
        }

        // 6. Parse CT_LEN (blob = ciphertext || tag): 16..MAX_FRAME_PAYLOAD
        val ctLen = ByteBuffer.wrap(frame, 14, 4).int
        if (ctLen < MIN_CT_LENGTH) {
            throw NetworkError.InvalidFrame("Ciphertext length out of range")
        }
        if (ctLen > MAX_CT_LENGTH) {
            throw NetworkError.OversizedPayload(
                length = ctLen,
                maxAllowed = MAX_CT_LENGTH
            )
        }

        // 7. Exact size consistency (rejects truncated frames and trailing extra bytes)
        if (frame.size != HEADER_SIZE + ctLen) {
            throw NetworkError.InvalidFrame("Frame size mismatch")
        }

        // 8. Extract the ciphertext || tag blob
        val ctAndTag = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + ctLen)

        // 9. Reconstruct nonce and AAD from the (validated) header fields
        val nonce = buildNonce(direction, cryptoSequence)
        val aad = buildAad(SECURE_FRAME_VERSION, frameType.code, cryptoSequence, ctLen)

        // 10. Authenticate and decrypt (GCM verifies ciphertext + AAD before
        //     releasing plaintext; any mismatch throws AesGcmException)
        val plaintext = AesGcm.decrypt(sessionKey, nonce, aad, ctAndTag)

        // 11. Advance the receive sequence ONLY after successful authentication
        if (!receiveSequence.validateAndAdvance(cryptoSequence)) {
            // Defensive: the pre-check above makes this unreachable, fail closed anyway
            throw SequenceViolationException()
        }

        return DecodedFrame(plaintext, frameType, cryptoSequence)
    }

    /**
     * Constructs the 14-byte AAD from header fields.
     * [ctLen] is the wire `ciphertext || tag` blob length, exactly as it appears
     * in the CT_LEN field. MAGIC is intentionally excluded per Task 21A.
     */
    internal fun buildAad(version: Byte, typeCode: Byte, cryptoSequence: Long, ctLen: Int): ByteArray {
        val aad = ByteArray(AAD_SIZE)
        aad[0] = version
        aad[1] = typeCode
        ByteBuffer.wrap(aad, 2, 8).putLong(cryptoSequence)
        ByteBuffer.wrap(aad, 10, 4).putInt(ctLen)
        return aad
    }

    /**
     * Serializes header + ciphertext||tag blob into the wire frame format.
     * [ctAndTag] is the full GCM output; its size becomes the CT_LEN field.
     */
    private fun serializeFrame(sequence: Long, type: FrameType, ctAndTag: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(HEADER_SIZE + ctAndTag.size)
        output.write(ProtocolConstants.MAGIC_HEADER)
        output.write(SECURE_FRAME_VERSION.toInt() and 0xFF)
        output.write(type.code.toInt() and 0xFF)
        output.write(ByteBuffer.allocate(8).putLong(sequence).array())
        output.write(ByteBuffer.allocate(4).putInt(ctAndTag.size).array())
        output.write(ctAndTag)
        return output.toByteArray()
    }

    private const val AAD_SIZE = 14
}
