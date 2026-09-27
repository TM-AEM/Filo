package com.filo.transfer.core.network.transfer

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Source data provider for a file being transferred by [TransferSender].
 */
interface TransferFileSource {
    val fileId: String
    val fileName: String
    val size: Long
    val mimeType: String
    val lastModified: Long

    /**
     * Opens an [InputStream] positioned at [offset] bytes from the start of the file.
     * Caller is responsible for closing the stream.
     */
    fun openStream(offset: Long): InputStream
}

/**
 * File-backed implementation of [TransferFileSource].
 */
class LocalFileSource(
    private val file: File,
    override val fileId: String = file.name,
    override val mimeType: String = "application/octet-stream"
) : TransferFileSource {

    override val fileName: String
        get() = file.name

    override val size: Long
        get() = file.length()

    override val lastModified: Long
        get() = file.lastModified()

    override fun openStream(offset: Long): InputStream {
        val fis = FileInputStream(file)
        if (offset > 0) {
            fis.channel.position(offset)
        }
        return fis
    }
}
