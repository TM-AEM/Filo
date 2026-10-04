package com.filo.transfer.core.network.protocol

/**
 * Protocol-level constants for Filo framed binary TCP communication.
 */
object ProtocolConstants {
    /** 4-byte ASCII magic header: 'F', 'I', 'L', 'O' */
    val MAGIC_HEADER = byteArrayOf(0x46, 0x49, 0x4C, 0x4F)

    /** Current protocol specification version */
    const val CURRENT_PROTOCOL_VERSION: Byte = 1

    /**
     * Fixed binary frame header size in bytes:
     * 4 (Magic) + 1 (Version) + 1 (FrameType) + 8 (Sequence) + 4 (PayloadLength) = 18 bytes
     */
    const val HEADER_SIZE = 18

    /** Maximum payload size allowed for a single frame: 256 KiB */
    const val MAX_FRAME_PAYLOAD = 256 * 1024

    /** Default chunk payload size used for streaming data frames: 128 KiB */
    const val DEFAULT_CHUNK_SIZE = 128 * 1024

    /** Default TCP port used for local Filo direct transfer */
    const val DEFAULT_PORT = 50222

    /** Connection timeout in milliseconds (15 seconds) */
    const val CONNECT_TIMEOUT_MS = 15_000

    /** Socket read/write timeout in milliseconds (30 seconds) */
    const val SOCKET_TIMEOUT_MS = 30_000

    /** Application-level socket send buffer size hint (128 KiB) */
    const val SOCKET_SND_BUF = 128 * 1024

    /** Application-level socket receive buffer size hint (128 KiB) */
    const val SOCKET_RCV_BUF = 128 * 1024

    /** Extension added to temporary partially-received files */
    const val PARTIAL_FILE_SUFFIX = ".filo.part"

    /** Maximum individual file size allowed in a manifest (4 GiB) */
    const val MAX_FILE_SIZE_BYTES: Long = 4L * 1024 * 1024 * 1024

    /** Maximum number of files allowed in a single manifest */
    const val MAX_FILE_COUNT = 1000
}
