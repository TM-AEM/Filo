package com.filo.transfer.core.network.protocol

import com.filo.transfer.core.network.model.NetworkError
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encodes and decodes Filo protocol frames with strict boundary and integrity validation.
 *
 * Guarantees that no unbounded memory allocations are performed based on incoming network length headers.
 */
object FrameCodec {

    /**
     * Encodes and writes a [ProtocolFrame] to the given [OutputStream].
     */
    fun writeFrame(out: OutputStream, frame: ProtocolFrame) {
        val payloadLength = frame.payload.size
        if (payloadLength > ProtocolConstants.MAX_FRAME_PAYLOAD) {
            throw IllegalArgumentException(
                "Payload size $payloadLength exceeds maximum allowed limit ${ProtocolConstants.MAX_FRAME_PAYLOAD}"
            )
        }

        val dataOut = if (out is DataOutputStream) out else DataOutputStream(out)

        // 1. Magic (4 bytes)
        dataOut.write(ProtocolConstants.MAGIC_HEADER)
        // 2. Version (1 byte)
        dataOut.writeByte(frame.version.toInt())
        // 3. Type (1 byte)
        dataOut.writeByte(frame.type.code.toInt())
        // 4. Sequence (8 bytes)
        dataOut.writeLong(frame.sequence)
        // 5. Payload Length (4 bytes)
        dataOut.writeInt(payloadLength)
        // 6. Payload (0 to MAX_FRAME_PAYLOAD bytes)
        if (payloadLength > 0) {
            dataOut.write(frame.payload)
        }
        dataOut.flush()
    }

    /**
     * Reads and decodes a single [ProtocolFrame] from the given [InputStream].
     *
     * @throws EOFException if stream reaches end cleanly before new frame.
     * @throws NetworkError on validation, framing, or payload constraint violations.
     */
    fun readFrame(input: InputStream): ProtocolFrame {
        val dataIn = if (input is DataInputStream) input else DataInputStream(input)

        // 1. Magic Header Validation (4 bytes)
        val magic = ByteArray(4)
        try {
            dataIn.readFully(magic)
        } catch (e: EOFException) {
            throw e
        } catch (e: Exception) {
            throw NetworkError.IoError("Failed to read frame magic header", e)
        }

        if (!magic.contentEquals(ProtocolConstants.MAGIC_HEADER)) {
            val receivedHex = magic.joinToString("") { "%02X".format(it) }
            throw NetworkError.InvalidFrame("Invalid protocol magic header: 0x$receivedHex")
        }

        // 2. Version Validation (1 byte)
        val version = dataIn.readByte()
        if (version != ProtocolConstants.CURRENT_PROTOCOL_VERSION) {
            throw NetworkError.ProtocolVersionMismatch(
                expected = ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt(),
                actual = version.toInt()
            )
        }

        // 3. Frame Type (1 byte)
        val typeCode = dataIn.readByte()
        val frameType = FrameType.fromCode(typeCode)
            ?: throw NetworkError.InvalidFrame("Unknown protocol frame type code: 0x%02X".format(typeCode))

        // 4. Sequence / Offset (8 bytes)
        val sequence = dataIn.readLong()

        // 5. Payload Length Validation (4 bytes)
        val payloadLength = dataIn.readInt()
        if (payloadLength < 0 || payloadLength > ProtocolConstants.MAX_FRAME_PAYLOAD) {
            throw NetworkError.OversizedPayload(
                length = payloadLength,
                maxAllowed = ProtocolConstants.MAX_FRAME_PAYLOAD
            )
        }

        // 6. Payload Reading (Bounded allocation)
        val payload = if (payloadLength > 0) {
            ByteArray(payloadLength).also { dataIn.readFully(it) }
        } else {
            ProtocolFrame.EMPTY_PAYLOAD
        }

        return ProtocolFrame(
            version = version,
            type = frameType,
            sequence = sequence,
            payload = payload
        )
    }
}
