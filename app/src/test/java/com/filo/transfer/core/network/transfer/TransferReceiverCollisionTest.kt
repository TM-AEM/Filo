package com.filo.transfer.core.network.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Method

class TransferReceiverCollisionTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var receiver: TransferReceiver
    private lateinit var resolveMethod: Method

    @Before
    fun setUp() {
        receiver = TransferReceiver("TestReceiver")
        resolveMethod = TransferReceiver::class.java.getDeclaredMethod(
            "resolveCollisionSafeFilename",
            File::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        resolveMethod.isAccessible = true
    }

    private fun resolve(dir: File, name: String, isResume: Boolean): String {
        return resolveMethod.invoke(receiver, dir, name, isResume) as String
    }

    @Test
    fun newTransferNoCollisionReturnsOriginalName() {
        val dir = tempDir.root
        val result = resolve(dir, "data.bin", false)
        assertEquals("data.bin", result)
    }

    @Test
    fun newTransferWithCollisionAppendsCounter() {
        val dir = tempDir.root
        File(dir, "data.bin").createNewFile()
        val result = resolve(dir, "data.bin", false)
        assertEquals("data_1.bin", result)
    }

    @Test
    fun newTransferMultipleCollisionsIncrementsCounter() {
        val dir = tempDir.root
        File(dir, "data.bin").createNewFile()
        File(dir, "data_1.bin").createNewFile()
        File(dir, "data_2.bin").createNewFile()
        val result = resolve(dir, "data.bin", false)
        assertEquals("data_3.bin", result)
    }

    @Test
    fun newTransferPreservesExtension() {
        val dir = tempDir.root
        File(dir, "photo.jpg").createNewFile()
        val result = resolve(dir, "photo.jpg", false)
        assertEquals("photo_1.jpg", result)
    }

    @Test
    fun newTransferCompoundExtensionHandled() {
        val dir = tempDir.root
        File(dir, "archive.tar.gz").createNewFile()
        val result = resolve(dir, "archive.tar.gz", false)
        assertEquals("archive.tar_1.gz", result)
    }

    @Test
    fun newTransferNoExtensionAppendsCounter() {
        val dir = tempDir.root
        File(dir, "README").createNewFile()
        val result = resolve(dir, "README", false)
        assertEquals("README_1", result)
    }

    @Test
    fun resumeTransferReturnsExactNameRegardlessOfCollision() {
        val dir = tempDir.root
        File(dir, "data.bin").createNewFile()
        val result = resolve(dir, "data.bin", true)
        assertEquals("data.bin", result)
    }

    @Test
    fun resolvedTargetFileDoesNotExist() {
        val dir = tempDir.root
        File(dir, "file.txt").createNewFile()
        val result = resolve(dir, "file.txt", false)
        assertFalse(File(dir, result).exists())
    }

    @Test
    fun emptyDirectoryReturnsOriginalName() {
        val dir = tempDir.newFolder("empty")
        val result = resolve(dir, "anything.dat", false)
        assertEquals("anything.dat", result)
    }

    @Test
    fun existingFinalFileContentsPreservedOnCollision() {
        val dir = tempDir.root
        val existing = File(dir, "data.bin")
        val originalBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        existing.writeBytes(originalBytes)

        val result = resolve(dir, "data.bin", false)

        assertEquals("data_1.bin", result)
        assertTrue(existing.exists())
        assertEquals(originalBytes.toList(), existing.readBytes().toList())
        assertFalse(File(dir, result).exists())
    }

    @Test
    fun newTransferPhotoCollisionCascadePreservesExtension() {
        val dir = tempDir.root
        File(dir, "photo.jpg").createNewFile()
        File(dir, "photo_1.jpg").createNewFile()
        val result = resolve(dir, "photo.jpg", false)
        assertEquals("photo_2.jpg", result)
    }
}
