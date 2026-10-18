package com.saeed.ikev2vpn.update

import android.content.Context
import android.content.pm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class AppUpdateRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = "synthetic APK bytes checked by a fake Android package parser".toByteArray()
    private val release get() = ReleaseMetadata.parse(ReleaseMetadataTest.metadata().also {
        it.getJSONArray("assets").getJSONObject(0).put("size", content.size)
    }.toString())
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private fun repository(status: Int = 200, apk: ByteArray = content, checksum: String = hash, candidateKey: String = "same"): GitHubAppUpdateRepository {
        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageName).thenReturn("com.saeed.ikev2vpn")
        `when`(context.cacheDir).thenReturn(folder.root)
        `when`(context.packageManager).thenReturn(pm)
        fun info(version: String, code: Long, key: String): PackageInfo {
            val signature = mock(Signature::class.java)
            `when`(signature.toByteArray()).thenReturn(key.toByteArray())
            val signing = mock(SigningInfo::class.java)
            `when`(signing.apkContentsSigners).thenReturn(arrayOf(signature))
            `when`(signing.signingCertificateHistory).thenReturn(arrayOf(signature))
            val result = mock(PackageInfo::class.java).apply {
                packageName = context.packageName; versionName = version
                signingInfo = signing; applicationInfo = ApplicationInfo().apply { minSdkVersion = 0 }
            }
            `when`(result.longVersionCode).thenReturn(code)
            return result
        }
        val installedInfo = info("1.4.0", 6, "same")
        val archiveInfo = info("1.5.0", 7, candidateKey)
        `when`(pm.getPackageInfo(eq(context.packageName), anyInt())).thenReturn(installedInfo)
        `when`(pm.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archiveInfo)
        val transport = UpdateTransport { url ->
            val bytes = when (url) {
                LATEST_RELEASE_API -> ReleaseMetadataTest.metadata().toString().toByteArray()
                release.checksum.url -> "$checksum  ${release.apk.name}\n".toByteArray()
                release.apk.url -> apk
                else -> error("Unexpected URL")
            }
            UpdateResponse(status, if (url == release.apk.url) release.apk.size else bytes.size.toLong(), ByteArrayInputStream(bytes))
        }
        return GitHubAppUpdateRepository(context, transport)
    }
    @Test fun `a 404 becomes no published release while rate limits remain retriable errors`() = runTest {
        assertNull(repository(status = 404).latestRelease())
        try { repository(status = 403).latestRelease(); fail() } catch (exception: UpdateException) { assertTrue(exception.message!!.contains("limited")) }
    }
    @Test fun `download verifies checksum and archive identity before retaining an APK`() = runTest {
        val progress = mutableListOf<Int>()
        val result = repository().download(release, progress::add)
        assertArrayEquals(content, result.file.readBytes())
        assertEquals(hash, result.sha256)
        assertEquals(100, progress.last())
        assertTrue(result.file.canonicalPath.startsWith(folder.root.canonicalPath))
    }
    @Test fun `checksum corruption size mismatches and incompatible signatures delete all partial files`() = runTest {
        val variants = listOf(repository(checksum = "00".repeat(32)), repository(apk = content.copyOf(content.size - 1)), repository(apk = content + 0), repository(candidateKey = "other"))
        for (repo in variants) {
            try { repo.download(release) {}; fail("Expected download rejection") } catch (_: UpdateException) { }
            assertTrue(File(folder.root, "updates").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun `cancelling a streaming download removes the staged APK`() = runTest {
        val repo = repository()
        val job = launch {
            val task = coroutineContext[Job]!!
            repo.download(release) { task.cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(File(folder.root, "updates").listFiles().orEmpty().isEmpty())
    }
}
