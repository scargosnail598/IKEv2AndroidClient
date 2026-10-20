package com.saeed.ikev2vpn.profile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

internal object ProfileShareIntents {
    fun link(link: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }

    /** Call off the main thread. Only this cache directory is exposed by the provider. */
    fun qr(context: Context, bitmap: Bitmap): Intent {
        val directory = File(context.cacheDir, "profile-shares").apply { mkdirs() }
        // Keep recently shared images readable by receiving apps; expire older temporary exports.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val file = File(directory, "configuration-${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.profile-shares", file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("VPN configuration QR", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }
}
