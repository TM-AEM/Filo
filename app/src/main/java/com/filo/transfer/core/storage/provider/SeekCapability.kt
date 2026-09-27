package com.filo.transfer.core.storage.provider

/**
 * Indicates whether and how a storage URI can be opened at an offset for resume / partial transfers.
 */
enum class SeekCapability {
    /**
     * The URI provides a direct ParcelFileDescriptor with file channel seek support (fastest, zero memory overhead).
     */
    SEEKABLE_DESCRIPTOR,

    /**
     * The URI does not provide a seekable file descriptor, but the InputStream supports skip() forward.
     */
    STREAM_SKIP_ONLY,

    /**
     * The URI provider does not support offset-based reading or the stream cannot be positioned.
     */
    UNSUPPORTED
}
