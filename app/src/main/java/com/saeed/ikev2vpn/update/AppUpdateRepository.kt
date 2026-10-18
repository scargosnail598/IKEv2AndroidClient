package com.saeed.ikev2vpn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

class VerifiedUpdate(val release: AppRelease, val file: File, val sha256: String)

interface AppUpdateRepository {
    val installed: InstalledApp
    suspend fun latestRelease(): AppRelease?
    suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): VerifiedUpdate
    suspend fun installerIntent(update: VerifiedUpdate): Intent
    suspend fun discard(update: VerifiedUpdate)
    fun canInstall(): Boolean
    fun permissionIntent(): Intent
}

/** Public GitHub HTTPS only. No credentials or profile data are sent to the update endpoint. */
class GitHubAppUpdateRepository(
    context: Context,
    private val transport: UpdateTransport = GitHubUpdateTransport(),
) : AppUpdateRepository {
    private val context = context.applicationContext
    private val validator = AndroidApkValidator(context)
    private val files = File(context.cacheDir, "updates")
    override val installed: InstalledApp get() = validator.installed

    override suspend fun latestRelease(): AppRelease? = network {
        // Interrupted downloads from older processes are disposable cache files.
        files.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
            .forEach { it.deleteRecursively() }
        transport.open(LATEST_RELEASE_API).use { response ->
            if (response.status == 404) return@network null
            success(response)
            ReleaseMetadata.parse(readText(response, 1024 * 1024))
        }
    }

    override suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): VerifiedUpdate = network {
        val checksum = transport.open(release.checksum.url).use { response ->
            success(response)
            ReleaseMetadata.checksum(readText(response, 4096), release.apk.name)
        }
        val directory = File(files, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw UpdateException("The update download folder could not be created.")
        val partial = File(directory, "update.part")
        val apk = File(directory, "update.apk")
        var keep = false
        try {
            transport.open(release.apk.url).use { response ->
                success(response)
                if (response.length >= 0 && response.length != release.apk.size) throw UpdateException("The APK download size does not match the release.")
                val hash = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastProgress = -1
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = response.body.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > release.apk.size || total > MAX_UPDATE_BYTES) throw UpdateException("The APK download is larger than expected.")
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        val progress = (total * 100 / release.apk.size).toInt()
                        if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
                    }
                    output.fd.sync()
                }
                if (total != release.apk.size || hex(hash.digest()) != checksum) throw UpdateException("The APK checksum or size does not match. Download the update again.")
            }
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(apk)) throw UpdateException("The downloaded APK could not be saved.")
            validator.validate(apk, release)
            currentCoroutineContext().ensureActive()
            keep = true
            VerifiedUpdate(release, apk, checksum)
        } finally {
            if (!keep) directory.deleteRecursively()
        }
    }

    override suspend fun installerIntent(update: VerifiedUpdate): Intent = network {
        // Recheck after returning from settings and if another update was installed externally.
        if (!update.file.isFile || update.file.length() != update.release.apk.size) throw UpdateException("The downloaded update is no longer available. Download it again.")
        val hash = MessageDigest.getInstance("SHA-256")
        update.file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        if (hex(hash.digest()) != update.sha256) throw UpdateException("The downloaded update changed. Download it again.")
        validator.validate(update.file, update.release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", update.file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override suspend fun discard(update: VerifiedUpdate) = withContext(Dispatchers.IO) {
        if (update.file.parentFile?.parentFile?.canonicalFile == files.canonicalFile) update.file.parentFile?.deleteRecursively()
        Unit
    }
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    override fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null))

    private suspend fun readText(response: UpdateResponse, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = response.body.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > limit) throw UpdateException("The release response is too large.")
            output.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: Exception) { throw UpdateException("The release response is not valid UTF-8.") }
    }
    private fun success(response: UpdateResponse) {
        if (response.status in setOf(403, 429)) throw UpdateException("GitHub has limited update requests. Try again later.")
        if (response.status != 200) throw UpdateException("The release could not be downloaded (HTTP ${response.status}).")
    }
    private suspend fun <T> network(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (exception: UpdateException) { throw exception }
        catch (_: IOException) { throw UpdateException("Could not reach GitHub. Check your connection and try again.") }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
