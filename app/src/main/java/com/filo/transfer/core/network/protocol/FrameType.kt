package com.filo.transfer.core.network.protocol

/**
 * Binary frame identifiers supported by the Filo protocol.
 */
enum class FrameType(val code: Byte) {
    HELLO(0x01),
    HELLO_ACK(0x02),
    HANDSHAKE_FINISH(0x03),

    MANIFEST(0x10),
    MANIFEST_ACK(0x11),

    FILE_HEADER(0x20),
    RESUME_REQUEST(0x21),
    RESUME_RESPONSE(0x22),

    DATA_CHUNK(0x30),

    CHECKSUM(0x40),
    CHECKSUM_RESULT(0x41),

    PAUSE(0x50),
    RESUME(0x51),
    CANCEL(0x52),
    ERROR(0x53),

    COMPLETE(0x60);

    companion object {
        private val CODE_MAP = entries.associateBy { it.code }

        fun fromCode(code: Byte): FrameType? = CODE_MAP[code]
    }
}
