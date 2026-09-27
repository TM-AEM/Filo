package com.filo.transfer.core.network.protocol

import com.filo.transfer.core.network.model.NetworkError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class ProtocolFrameCodecTest {

    @Test
    fun testEncodeAndDecodeValidFrame() {
        val payload = "Hello Filo Protocol".toByteArray(Charsets.UTF_8)
        val originalFrame = ProtocolFrame(
            version = ProtocolConstants.CURRENT_PROTOCOL_VERSION,
            type = FrameType.HELLO,
            sequence = 42L,
            payload = payload
        )

        val bout = ByteArrayOutputStream()
        FrameCodec.writeFrame(bout, originalFrame)

        val bin = ByteArrayInputStream(bout.toByteArray())
        val decodedFrame = FrameCodec.readFrame(bin)

        assertEquals(originalFrame.version, decodedFrame.version)
        assertEquals(originalFrame.type, decodedFrame.type)
        assertEquals(originalFrame.sequence, decodedFrame.sequence)
        assertArrayEquals(originalFrame.payload, decodedFrame.payload)
    }

    @Test
    fun testEncodeAndDecodeEmptyPayloadFrame() {
        val originalFrame = ProtocolFrame(
            version = ProtocolConstants.CURRENT_PROTOCOL_VERSION,
            type = FrameType.COMPLETE,
            sequence = 100L,
            payload = ProtocolFrame.EMPTY_PAYLOAD
        )

        val bout = ByteArrayOutputStream()
        FrameCodec.writeFrame(bout, originalFrame)

        val bin = ByteArrayInputStream(bout.toByteArray())
        val decodedFrame = FrameCodec.readFrame(bin)

        assertEquals(FrameType.COMPLETE, decodedFrame.type)
        assertEquals(0, decodedFrame.payload.size)
    }

    @Test
    fun testRejectInvalidMagicHeader() {
        val badMagic = byteArrayOf(0x00, 0x01, 0x02, 0x03, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val bin = ByteArrayInputStream(badMagic)

        val error = assertThrows(NetworkError.InvalidFrame::class.java) {
            FrameCodec.readFrame(bin)
        }
        assertTrue(error.message.contains("Invalid protocol magic"))
    }

    @Test
    fun testRejectProtocolVersionMismatch() {
        val bout = ByteArrayOutputStream()
        bout.write(ProtocolConstants.MAGIC_HEADER)
        bout.write(99) // Invalid version 99
        bout.write(FrameType.HELLO.code.toInt())
        bout.write(ByteArray(8)) // Sequence 0
        bout.write(byteArrayOf(0, 0, 0, 0)) // Length 0

        val bin = ByteArrayInputStream(bout.toByteArray())
        val error = assertThrows(NetworkError.ProtocolVersionMismatch::class.java) {
            FrameCodec.readFrame(bin)
        }
        assertEquals(1, error.expected)
        assertEquals(99, error.actual)
    }

    @Test
    fun testRejectUnknownFrameType() {
        val bout = ByteArrayOutputStream()
        bout.write(ProtocolConstants.MAGIC_HEADER)
        bout.write(ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt())
        bout.write(0x7F) // Unknown type
        bout.write(ByteArray(8))
        bout.write(byteArrayOf(0, 0, 0, 0))

        val bin = ByteArrayInputStream(bout.toByteArray())
        val error = assertThrows(NetworkError.InvalidFrame::class.java) {
            FrameCodec.readFrame(bin)
        }
        assertTrue(error.message.contains("Unknown protocol frame type"))
    }

    @Test
    fun testRejectOversizedPayloadOnWrite() {
        val hugePayload = ByteArray(ProtocolConstants.MAX_FRAME_PAYLOAD + 1)
        val frame = ProtocolFrame(
            type = FrameType.DATA_CHUNK,
            payload = hugePayload
        )

        val bout = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrame(bout, frame)
        }
    }

    @Test
    fun testRejectOversizedPayloadOnRead() {
        val bout = ByteArrayOutputStream()
        bout.write(ProtocolConstants.MAGIC_HEADER)
        bout.write(ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt())
        bout.write(FrameType.DATA_CHUNK.code.toInt())
        bout.write(ByteArray(8))
        // Length 300 KiB > 256 KiB
        val oversizedLength = ProtocolConstants.MAX_FRAME_PAYLOAD + 1024
        val lengthBytes = java.nio.ByteBuffer.allocate(4).putInt(oversizedLength).array()
        bout.write(lengthBytes)

        val bin = ByteArrayInputStream(bout.toByteArray())
        val error = assertThrows(NetworkError.OversizedPayload::class.java) {
            FrameCodec.readFrame(bin)
        }
        assertEquals(oversizedLength, error.length)
        assertEquals(ProtocolConstants.MAX_FRAME_PAYLOAD, error.maxAllowed)
    }

    @Test
    fun testTruncatedFrameThrowsEof() {
        // Only 3 bytes of magic header
        val bin = ByteArrayInputStream(byteArrayOf(0x46, 0x49, 0x4C))
        assertThrows(EOFException::class.java) {
            FrameCodec.readFrame(bin)
        }
    }

    @Test
    fun testHelloPayloadRoundTrip() {
        val encoded = FramePayloads.encodeHello(1.toByte(), "DeviceA", "session-123")
        val decoded = FramePayloads.decodeHello(encoded)
        assertEquals(1.toByte(), decoded.version)
        assertEquals("DeviceA", decoded.deviceName)
        assertEquals("session-123", decoded.sessionId)
    }

    @Test
    fun testResumePayloadRoundTrip() {
        val encodedReq = FramePayloads.encodeResumeRequest("file-1", 1024L)
        val decodedReq = FramePayloads.decodeResumeRequest(encodedReq)
        assertEquals("file-1", decodedReq.fileId)
        assertEquals(1024L, decodedReq.offset)

        val encodedResp = FramePayloads.encodeResumeResponse("file-1", 1024L, true)
        val decodedResp = FramePayloads.decodeResumeResponse(encodedResp)
        assertEquals("file-1", decodedResp.fileId)
        assertEquals(1024L, decodedResp.confirmedOffset)
        assertTrue(decodedResp.accepted)
    }

    @Test
    fun testChecksumPayloadRoundTrip() {
        val testSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val encoded = FramePayloads.encodeChecksum("file-99", testSha)
        val decoded = FramePayloads.decodeChecksum(encoded)
        assertEquals("file-99", decoded.fileId)
        assertEquals(testSha, decoded.sha256Hex)
    }
}
