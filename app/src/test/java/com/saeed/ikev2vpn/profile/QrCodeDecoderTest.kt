package com.saeed.ikev2vpn.profile

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class QrCodeDecoderTest {
    @Test
    fun `decodes dense server configuration QR as RGB and padded Y plane`() {
        val text = IkevShareDecoderTest.fixture("private-password").trim()
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1200, 1200,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L))
        val width = matrix.width
        val pixels = IntArray(width * matrix.height) { i -> if (matrix[i % width, i / width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(text, QrCodeDecoder.decode(RGBLuminanceSource(width, matrix.height, pixels)))
        val rowStride = width * 2 + 16
        val buffer = ByteBuffer.allocate(rowStride * matrix.height + 5)
        buffer.position(5)
        for (y in 0 until matrix.height) for (x in 0 until width) {
            buffer.put(5 + y * rowStride + x * 2, if (matrix[x, y]) 0 else 255.toByte())
        }
        val luma = QrCodeDecoder.copyLuminance(buffer, width, matrix.height, rowStride, 2)
        val source = PlanarYUVLuminanceSource(luma, width, matrix.height, 0, 0, width, matrix.height, false)
        assertEquals(text, QrCodeDecoder.decode(source))
        assertEquals(text, QrCodeDecoder.decode(source.invert()))
    }

    @Test
    fun `empty camera frame has no result`() {
        assertNull(QrCodeDecoder.decode(RGBLuminanceSource(64, 64, IntArray(64 * 64) { -1 })))
    }
}
