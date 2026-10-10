package com.filo.transfer.core.network.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

sealed interface QrEncodeResult {
    data class Success(
        val bitmap: Bitmap,
        val contents: String
    ) : QrEncodeResult

    data class Failure(val reason: String) : QrEncodeResult
}

/**
 * Encodes a pairing payload as a high-contrast QR bitmap.
 *
 * Size is [SIZE_PX] square with a quiet zone of [QUIET_ZONE_MODULES]
 * modules. Contents are always the exact [PairingPayloadCodec] JSON;
 * fields are never truncated or rewritten.
 */
object QrBitmapEncoder {

    const val SIZE_PX = 512
    const val QUIET_ZONE_MODULES = 4

    fun encode(payload: PairingPayload): QrEncodeResult {
        return encodeSerialized(PairingPayloadCodec.encode(payload))
    }

    fun encodeSerialized(contents: String): QrEncodeResult {
        if (contents.isBlank()) {
            return QrEncodeResult.Failure("Pairing payload is blank")
        }
        val byteSize = contents.toByteArray(Charsets.UTF_8).size
        if (byteSize > PairingPayloadCodec.MAX_PAYLOAD_BYTES) {
            return QrEncodeResult.Failure("Pairing payload exceeds ${PairingPayloadCodec.MAX_PAYLOAD_BYTES} bytes")
        }
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            var offset = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[offset++] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            QrEncodeResult.Success(bitmap, contents)
        } catch (_: Throwable) {
            QrEncodeResult.Failure("QR encoding failed")
        }
    }
}
