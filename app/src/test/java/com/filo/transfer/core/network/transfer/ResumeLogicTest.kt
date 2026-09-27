package com.filo.transfer.core.network.transfer

import com.filo.transfer.core.network.model.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeLogicTest {

    private fun validateResumeOffset(offset: Long, fileSize: Long): Boolean {
        if (offset < 0 || offset > fileSize) {
            throw NetworkError.InvalidOffset(offset, fileSize)
        }
        return true
    }

    @Test
    fun testOffsetZeroIsCleanStart() {
        val valid = validateResumeOffset(0L, 1024L)
        assertTrue(valid)
    }

    @Test
    fun testOffsetPositiveWithinFileSizeIsValid() {
        val valid = validateResumeOffset(512L, 1024L)
        assertTrue(valid)
    }

    @Test
    fun testOffsetEqualToFileSizeIsValid() {
        val valid = validateResumeOffset(1024L, 1024L)
        assertTrue(valid)
    }

    @Test
    fun testOffsetGreaterThanFileSizeThrowsInvalidOffset() {
        val error = assertThrows(NetworkError.InvalidOffset::class.java) {
            validateResumeOffset(1025L, 1024L)
        }
        assertEquals(1025L, error.offset)
        assertEquals(1024L, error.fileSize)
    }

    @Test
    fun testNegativeOffsetThrowsInvalidOffset() {
        val error = assertThrows(NetworkError.InvalidOffset::class.java) {
            validateResumeOffset(-1L, 1024L)
        }
        assertEquals(-1L, error.offset)
    }
}
