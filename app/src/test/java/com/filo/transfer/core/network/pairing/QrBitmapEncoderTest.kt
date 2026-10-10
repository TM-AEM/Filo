package com.filo.transfer.core.network.pairing

import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrBitmapEncoderTest {

    private val now = 1_700_000_000L
    private val fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun validPayload() = PairingPayload(
        schemaVersion = 1,
        ip = "192.168.1.42",
        port = 50222,
        expiresAtEpochSeconds = now + 60,
        fingerprint = fingerprint,
        protocolVersion = null
    )

    @Test
    fun validPayloadProducesNonEmptyBitmapOfDocumentedSize() {
        val result = QrBitmapEncoder.encode(validPayload())
        assertTrue(result is QrEncodeResult.Success)
        val success = result as QrEncodeResult.Success
        assertEquals(QrBitmapEncoder.SIZE_PX, success.bitmap.width)
        assertEquals(QrBitmapEncoder.SIZE_PX, success.bitmap.height)
        assertTrue(success.bitmap.width > 0)
        assertTrue(hasBothBlackAndWhitePixels(success.bitmap))
    }

    @Test
    fun encodedContentsMatchCodecOutputAndAreNotTruncated() {
        val payload = validPayload()
        val expected = PairingPayloadCodec.encode(payload)
        val result = QrBitmapEncoder.encode(payload)
        assertTrue(result is QrEncodeResult.Success)
        assertEquals(expected, (result as QrEncodeResult.Success).contents)
        assertTrue(expected.toByteArray(Charsets.UTF_8).size <= PairingPayloadCodec.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun generatedQrDecodesToExactOriginalPayload() {
        val payload = validPayload()
        val result = QrBitmapEncoder.encode(payload)
        assertTrue(result is QrEncodeResult.Success)
        val success = result as QrEncodeResult.Success

        val decodedText = decodeQr(success.bitmap)
        val decoded = PairingPayloadCodec.decode(decodedText, nowEpochSeconds = now)
        assertTrue(decoded is PairingDecodeResult.Success)
        assertEquals(payload, (decoded as PairingDecodeResult.Success).payload)
    }

    @Test
    fun encoderRejectsUnvalidatedSerializedInputThatExceedsCap() {
        val oversized = "x".repeat(PairingPayloadCodec.MAX_PAYLOAD_BYTES + 1)
        val result = QrBitmapEncoder.encodeSerialized(oversized)
        assertTrue(result is QrEncodeResult.Failure)
    }

    @Test
    fun encoderRejectsBlankSerializedInput() {
        assertTrue(QrBitmapEncoder.encodeSerialized("") is QrEncodeResult.Failure)
        assertTrue(QrBitmapEncoder.encodeSerialized("   ") is QrEncodeResult.Failure)
    }

    private fun hasBothBlackAndWhitePixels(bitmap: android.graphics.Bitmap): Boolean {
        var sawBlack = false
        var sawWhite = false
        val step = 8
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == Color.BLACK) sawBlack = true
                if (pixel == Color.WHITE) sawWhite = true
                if (sawBlack && sawWhite) return true
                x += step
            }
            y += step
        }
        return sawBlack && sawWhite
    }

    private fun decodeQr(bitmap: android.graphics.Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(binary).text
    }
}
