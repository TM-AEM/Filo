package com.filo.transfer.core.network.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameValidatorSecurityTest {

    @Test
    fun testRejectsPathTraversal() {
        assertFalse(FilenameValidator.isSafe("../etc/passwd"))
        assertFalse(FilenameValidator.isSafe("..\\boot.ini"))
        assertFalse(FilenameValidator.isSafe("folder/../../secret.txt"))
        assertFalse(FilenameValidator.isSafe("subfolder/file.png"))
        assertFalse(FilenameValidator.isSafe("subfolder\\file.png"))
    }

    @Test
    fun testRejectsAbsolutePaths() {
        assertFalse(FilenameValidator.isSafe("/data/user/0/com.app/databases/secret.db"))
        assertFalse(FilenameValidator.isSafe("C:\\Windows\\system32\\cmd.exe"))
    }

    @Test
    fun testRejectsIllegalCharacters() {
        assertFalse(FilenameValidator.isSafe("photo<1>.jpg"))
        assertFalse(FilenameValidator.isSafe("file>out.txt"))
        assertFalse(FilenameValidator.isSafe("file:name.txt"))
        assertFalse(FilenameValidator.isSafe("file\"quote.txt"))
        assertFalse(FilenameValidator.isSafe("file|pipe.txt"))
        assertFalse(FilenameValidator.isSafe("file?query.txt"))
        assertFalse(FilenameValidator.isSafe("file*wildcard.txt"))
        assertFalse(FilenameValidator.isSafe("file\u0000null.txt"))
    }

    @Test
    fun testRejectsReservedWindowsNames() {
        assertFalse(FilenameValidator.isSafe("con"))
        assertFalse(FilenameValidator.isSafe("CON.txt"))
        assertFalse(FilenameValidator.isSafe("prn"))
        assertFalse(FilenameValidator.isSafe("aux.dat"))
        assertFalse(FilenameValidator.isSafe("nul"))
        assertFalse(FilenameValidator.isSafe("com1"))
        assertFalse(FilenameValidator.isSafe("com9.mp3"))
        assertFalse(FilenameValidator.isSafe("lpt1.log"))
    }

    @Test
    fun testAcceptsSafeFilenames() {
        assertTrue(FilenameValidator.isSafe("photo.jpg"))
        assertTrue(FilenameValidator.isSafe("Document_Final_2026.pdf"))
        assertTrue(FilenameValidator.isSafe("video-presentation-1080p.mp4"))
        assertTrue(FilenameValidator.isSafe("archive.tar.gz"))
        assertTrue(FilenameValidator.isSafe("file 1 with spaces.docx"))
    }

    @Test
    fun testSanitizeStripsTraversalAndIllegalChars() {
        val sanitized = FilenameValidator.sanitize("../../secret<name>:1.txt")
        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.contains("<"))
        assertFalse(sanitized.contains(">"))
        assertFalse(sanitized.contains(":"))
        assertTrue(FilenameValidator.isSafe(sanitized))
    }

    @Test
    fun testSanitizeReservedNameAddsPrefix() {
        val sanitized = FilenameValidator.sanitize("con.txt")
        assertTrue(sanitized.startsWith("safe_"))
        assertTrue(FilenameValidator.isSafe(sanitized))
    }

    @Test
    fun testSanitizeEmptyOrNullFallback() {
        val sanitized = FilenameValidator.sanitize("")
        assertTrue(sanitized.startsWith("file_"))
        assertTrue(FilenameValidator.isSafe(sanitized))
    }
}
