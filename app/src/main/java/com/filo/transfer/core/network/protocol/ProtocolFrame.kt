package com.filo.transfer.core.network.protocol

/**
 * In-memory representation of a validated Filo protocol binary frame.
 */
data class ProtocolFrame(
    val version: Byte = ProtocolConstants.CURRENT_PROTOCOL_VERSION,
    val type: FrameType,
    val sequence: Long = 0L,
    val payload: ByteArray = EMPTY_PAYLOAD
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolFrame) return false
        if (version != other.version) return false
        if (type != other.type) return false
        if (sequence != other.sequence) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
