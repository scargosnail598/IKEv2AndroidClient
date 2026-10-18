package com.saeed.ikev2vpn.profile

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

object QrCodeDecoder {
    fun decode(source: LuminanceSource): String? {
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        for (candidate in listOf(source, source.invert())) {
            try {
                return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (_: ReaderException) {
                // An ordinary camera frame often has no complete QR code.
            }
        }
        return null
    }

    /** Camera Y planes may have row padding, pixel strides, and a nonzero buffer position. */
    fun copyLuminance(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0 && rowStride >= width && pixelStride > 0)
        val plane = buffer.duplicate()
        val offset = plane.position()
        return ByteArray(width * height) { index ->
            plane.get(offset + (index / width) * rowStride + (index % width) * pixelStride)
        }
    }
}
