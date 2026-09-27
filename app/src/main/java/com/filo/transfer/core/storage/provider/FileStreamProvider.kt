package com.filo.transfer.core.storage.provider

import android.content.ContentResolver
import android.net.Uri
import com.filo.transfer.core.storage.model.StorageError
import com.filo.transfer.core.storage.model.StorageResult
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Handles opening high-throughput, memory-safe InputStreams for content:// and file:// URIs.
 *
 * Supports opening streams at specified offsets for resume/partial transfer operations
 * without reading preceding bytes into RAM.
 */
class FileStreamProvider(
    private val contentResolver: ContentResolver
) {

    /**
     * Opens a standard [InputStream] for reading from the given URI.
     *
     * The caller assumes ownership of the returned stream and MUST close it
     * using [InputStream.close] or [use].
     */
    fun openInputStream(uri: Uri?): StorageResult<InputStream> {
        if (uri == null) {
            return StorageResult.Failure(StorageError.InvalidUri(null, "URI cannot be null"))
        }

        return try {
            val stream = contentResolver.openInputStream(uri)
                ?: return StorageResult.Failure(
                    StorageError.OpenFailed(uri, "ContentResolver returned null InputStream")
                )
            StorageResult.Success(stream)
        } catch (e: SecurityException) {
            StorageResult.Failure(StorageError.SecurityError(uri, e))
        } catch (e: FileNotFoundException) {
            StorageResult.Failure(StorageError.FileNotFound(uri, e))
        } catch (e: Exception) {
            StorageResult.Failure(StorageError.OpenFailed(uri, e.message, e))
        }
    }

    /**
     * Determines whether the given URI supports seek / offset operations.
     */
    fun getSeekCapability(uri: Uri?): SeekCapability {
        if (uri == null) return SeekCapability.UNSUPPORTED

        return try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.close()
                SeekCapability.SEEKABLE_DESCRIPTOR
            } else {
                SeekCapability.STREAM_SKIP_ONLY
            }
        } catch (_: Exception) {
            SeekCapability.STREAM_SKIP_ONLY
        }
    }

    /**
     * Opens an [InputStream] positioned directly at the requested [offset] in bytes.
     *
     * Uses [java.nio.channels.FileChannel.position] if a [android.os.ParcelFileDescriptor] is supported,
     * or falls back to forward [InputStream.skip] if supported by the provider.
     *
     * The caller assumes ownership of the returned stream and MUST close it.
     */
    fun openInputStreamAtOffset(uri: Uri?, offset: Long): StorageResult<InputStream> {
        if (uri == null) {
            return StorageResult.Failure(StorageError.InvalidUri(null, "URI cannot be null"))
        }

        if (offset < 0L) {
            return StorageResult.Failure(
                StorageError.InvalidUri(uri, "Offset must be non-negative, got: $offset")
            )
        }

        if (offset == 0L) {
            return openInputStream(uri)
        }

        // Method A: Attempt to use ParcelFileDescriptor with direct FileChannel positioning (Fast, 0 RAM overhead)
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fileInputStream = FileInputStream(pfd.fileDescriptor)
                val channel = fileInputStream.channel
                if (channel != null && channel.isOpen) {
                    val fileSize = channel.size()
                    if (fileSize in 0 until offset) {
                        pfd.close()
                        return StorageResult.Failure(
                            StorageError.SeekUnsupported(
                                uri,
                                "Requested offset $offset exceeds file size $fileSize"
                            )
                        )
                    }
                    channel.position(offset)
                    // Return a stream wrapper that ensures closing the stream also closes the underlying PFD
                    val wrappedStream = object : InputStream() {
                        override fun read(): Int = fileInputStream.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = fileInputStream.read(b, off, len)
                        override fun available(): Int = fileInputStream.available()
                        override fun skip(n: Long): Long = fileInputStream.skip(n)
                        override fun close() {
                            try {
                                fileInputStream.close()
                            } finally {
                                pfd.close()
                            }
                        }
                    }
                    return StorageResult.Success(wrappedStream)
                } else {
                    pfd.close()
                }
            }
        } catch (_: Exception) {
            // PFD seek failed or unsupported by provider, fall through to skip stream
        }

        // Method B: Fallback to opening InputStream and skipping bytes forward
        return try {
            val stream = contentResolver.openInputStream(uri)
                ?: return StorageResult.Failure(
                    StorageError.OpenFailed(uri, "ContentResolver returned null InputStream")
                )

            var remaining = offset
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) {
                    // Stream reached EOF or cannot skip
                    val readByte = stream.read()
                    if (readByte == -1) {
                        stream.close()
                        return StorageResult.Failure(
                            StorageError.SeekUnsupported(
                                uri,
                                "Requested offset $offset exceeds file size or stream reached EOF"
                            )
                        )
                    }
                    remaining -= 1
                } else {
                    remaining -= skipped
                }
            }

            StorageResult.Success(stream)
        } catch (e: SecurityException) {
            StorageResult.Failure(StorageError.SecurityError(uri, e))
        } catch (e: FileNotFoundException) {
            StorageResult.Failure(StorageError.FileNotFound(uri, e))
        } catch (e: IOException) {
            StorageResult.Failure(StorageError.SeekUnsupported(uri, "I/O error seeking to offset $offset", e))
        } catch (e: Exception) {
            StorageResult.Failure(StorageError.OpenFailed(uri, e.message, e))
        }
    }
}
