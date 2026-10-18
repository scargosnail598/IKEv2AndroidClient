package com.saeed.ikev2vpn.profile

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.RGBLuminanceSource
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

internal class QrImageReader(private val contentResolver: ContentResolver) {
    fun read(uri: Uri): String {
        try {
            // Bound encoded input as well as decoded pixel allocations.
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val single = input.read()
                        if (single < 0) break
                        if (output.size() == MAX_IMAGE_BYTES) {
                            throw IkevProfileImportException("The QR image exceeds 16 MiB.")
                        }
                        output.write(single)
                        continue
                    }
                    if (output.size() + count > MAX_IMAGE_BYTES) {
                        throw IkevProfileImportException("The QR image exceeds 16 MiB.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IkevProfileImportException("The QR image could not be read.")
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val scale = minOf(1.0, 2048.0 / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return QrCodeDecoder.decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
                    ?: throw IkevProfileImportException("No readable QR code was found in this image.")
            } finally {
                bitmap.recycle()
            }
        } catch (exception: IkevProfileImportException) {
            throw exception
        } catch (_: Exception) {
            throw IkevProfileImportException("The QR image could not be read. Select a PNG or JPEG image.")
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    }
}
