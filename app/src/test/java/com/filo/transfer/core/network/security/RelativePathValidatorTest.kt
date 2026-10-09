package com.filo.transfer.core.network.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RelativePathValidatorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun emptyPathIsAllowedAsLegacyFlatCase() {
        assertEquals("", RelativePathValidator.validate(""))
        assertEquals("", RelativePathValidator.validate(null))
        assertEquals("", RelativePathValidator.validate("   "))
        assertTrue(RelativePathValidator.isSafe(""))
    }

    @Test
    fun simpleRelativePathIsAllowed() {
        assertEquals("docs/report.pdf", RelativePathValidator.validate("docs/report.pdf"))
        assertTrue(RelativePathValidator.isSafe("docs/report.pdf"))
    }

    @Test
    fun deepRelativePathIsAllowed() {
        val deep = List(RelativePathValidator.MAX_DEPTH - 1) { "dir$it" }.joinToString("/") + "/file.txt"
        assertEquals(deep, RelativePathValidator.validate(deep))
    }

    @Test
    fun pathExceedingMaxDepthIsRejected() {
        val deep = List(RelativePathValidator.MAX_DEPTH) { "dir" }.joinToString("/") + "/file.txt"
        assertNull(RelativePathValidator.validate(deep))
        assertFalse(RelativePathValidator.isSafe(deep))
    }

    @Test
    fun parentDirectoryTraversalIsRejected() {
        assertNull(RelativePathValidator.validate("../etc/passwd"))
        assertNull(RelativePathValidator.validate("a/b/../../../../etc/shadow"))
        assertNull(RelativePathValidator.validate("docs/../../file.txt"))
        assertNull(RelativePathValidator.validate(".."))
        assertNull(RelativePathValidator.validate("a/.."))
    }

    @Test
    fun singleDotComponentIsRejected() {
        assertNull(RelativePathValidator.validate("./file.txt"))
        assertNull(RelativePathValidator.validate("docs/./file.txt"))
    }

    @Test
    fun absolutePathIsRejected() {
        assertNull(RelativePathValidator.validate("/etc/passwd"))
        assertNull(RelativePathValidator.validate("/file.txt"))
    }

    @Test
    fun backslashPathIsRejected() {
        assertNull(RelativePathValidator.validate("dir\\file.txt"))
        assertNull(RelativePathValidator.validate("\\file.txt"))
    }

    @Test
    fun windowsDrivePrefixIsRejected() {
        assertNull(RelativePathValidator.validate("C:/file.txt"))
        assertNull(RelativePathValidator.validate("d:\\docs\\file.txt"))
        assertNull(RelativePathValidator.validate("C:file.txt"))
    }

    @Test
    fun emptyComponentsAreRejected() {
        assertNull(RelativePathValidator.validate("docs//file.txt"))
        assertNull(RelativePathValidator.validate("docs/file.txt/"))
        assertNull(RelativePathValidator.validate("/"))
    }

    @Test
    fun traversalLookingComponentIsRejected() {
        assertNull(RelativePathValidator.validate("..."))
        assertNull(RelativePathValidator.validate("docs/..hidden/file.txt"))
    }

    @Test
    fun unsafeFilenameComponentIsRejected() {
        assertNull(RelativePathValidator.validate("docs/file:name.txt"))
        assertNull(RelativePathValidator.validate("docs/sub\n dir/file.txt"))
        assertNull(RelativePathValidator.validate("docs/con/file.txt"))
        assertNull(RelativePathValidator.validate("dir*.txt"))
    }

    @Test
    fun overlyLongPathIsRejected() {
        val long = "a".repeat(RelativePathValidator.MAX_PATH_LENGTH + 1)
        assertNull(RelativePathValidator.validate(long))
    }

    @Test
    fun leadingAndTrailingSpacesAreTrimmed() {
        assertEquals("docs/file.txt", RelativePathValidator.validate("  docs/file.txt  "))
    }

    @Test
    fun resolveWithinRootPlacesFileInNestedDirectory() {
        val root = tempDir.root
        val resolved = RelativePathValidator.resolveWithinRoot(root, "docs/work/report.pdf")

        assertEquals(File(root, "docs/work/report.pdf").canonicalPath, resolved.canonicalPath)
        assertTrue(resolved.parentFile?.exists() == true)
        assertTrue(File(root, "docs").exists())
        assertTrue(File(root, "docs/work").exists())
    }

    @Test
    fun resolveWithinRootWithEmptyPathReturnsDestinationRoot() {
        val root = tempDir.root
        val resolved = RelativePathValidator.resolveWithinRoot(root, "")
        assertEquals(root.canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun resolveWithinRootRejectsTraversalThatEscapesRoot() {
        val root = tempDir.root
        File(root, "docs").mkdirs()

        assertThrows(SecurityException::class.java) {
            RelativePathValidator.resolveWithinRoot(root, "docs/../../outside.txt")
        }

        assertFalse(File(root.parentFile, "outside.txt").exists())
    }

    @Test
    fun resolveWithinRootRejectsSymlinkEscapingRoot() {
        val root = tempDir.root
        val outsideDir = File(root.parentFile, "outside-link-target")
        outsideDir.mkdirs()
        val link = File(root, "link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outsideDir.toPath())
        } catch (_: Throwable) {
            org.junit.Assume.assumeTrue("Symlinks not supported here", false)
        }
        org.junit.Assume.assumeTrue(link.exists())

        assertThrows(SecurityException::class.java) {
            RelativePathValidator.resolveWithinRoot(root, "link/file.txt")
        }
    }

    @Test
    fun resolveWithinRootIsIdempotentForExistingDirs() {
        val root = tempDir.root
        RelativePathValidator.resolveWithinRoot(root, "a/b/c/file.txt")
        val second = RelativePathValidator.resolveWithinRoot(root, "a/b/c/file.txt")
        assertTrue(second.parentFile?.exists() == true)
    }
}
