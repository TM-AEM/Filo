package com.filo.transfer.core.storage.receive

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.filo.transfer.core.storage.model.FileMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MediaStoreReceivePublisherLogicTest {

    @Test
    fun imageUsesPicturesCollectionAndFiloPath() {
        val mime = "image/jpeg"
        val name = "photo.jpg"
        assertEquals(FileMediaType.IMAGE, FileMediaType.fromMimeAndName(mime, name))
        assertEquals(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStoreReceivePublisher.collectionFor(mime, name)
        )
        assertEquals(
            "${Environment.DIRECTORY_PICTURES}/${MediaStoreReceivePublisher.FILO_SUBDIR}",
            MediaStoreReceivePublisher.relativePathFor(mime, name)
        )
    }

    @Test
    fun videoUsesMoviesCollection() {
        val mime = "video/mp4"
        val name = "clip.mp4"
        assertEquals(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStoreReceivePublisher.collectionFor(mime, name)
        )
        assertEquals(
            "${Environment.DIRECTORY_MOVIES}/${MediaStoreReceivePublisher.FILO_SUBDIR}",
            MediaStoreReceivePublisher.relativePathFor(mime, name)
        )
    }

    @Test
    fun audioUsesMusicCollection() {
        val mime = "audio/mpeg"
        val name = "track.mp3"
        assertEquals(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStoreReceivePublisher.collectionFor(mime, name)
        )
        assertEquals(
            "${Environment.DIRECTORY_MUSIC}/${MediaStoreReceivePublisher.FILO_SUBDIR}",
            MediaStoreReceivePublisher.relativePathFor(mime, name)
        )
    }

    @Test
    fun documentUsesDownloadsCollection() {
        val mime = "application/pdf"
        val name = "report.pdf"
        val collection = MediaStoreReceivePublisher.collectionFor(mime, name)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, collection)
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/${MediaStoreReceivePublisher.FILO_SUBDIR}",
            MediaStoreReceivePublisher.relativePathFor(mime, name)
        )
    }

    @Test
    fun octetStreamFallsBackToDownloads() {
        val mime = "application/octet-stream"
        val name = "data.bin"
        assertEquals(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStoreReceivePublisher.collectionFor(mime, name)
        )
    }

    @Test
    fun collectionUriIsMediaStoreNotFilesystemPath() {
        val uri: Uri = MediaStoreReceivePublisher.collectionFor("image/png", "a.png")
        assertTrue(uri.toString().startsWith("content://"))
        assertFalse(uri.toString().contains(".."))
    }
}
