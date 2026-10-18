package com.saeed.ikev2vpn.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class InstalledApp(val packageName: String, val versionName: String, val versionCode: Long, val debug: Boolean)
data class ApkIdentity(val packageName: String, val versionName: String?, val versionCode: Long, val minSdk: Int,
    val signers: Set<String>, val signingHistory: Set<String> = emptySet())

object ApkUpdatePolicy {
    fun validate(installed: InstalledApp, installedSigners: Set<String>, candidate: ApkIdentity, version: ReleaseVersion, sdk: Int) {
        if (candidate.packageName != installed.packageName) throw UpdateException("This APK belongs to another application.")
        if (candidate.versionName != version.toString()) throw UpdateException("The APK version does not match the release.")
        if (candidate.versionCode <= installed.versionCode) throw UpdateException("This APK is not newer than the installed build. The release must increase versionCode.")
        if (candidate.minSdk > sdk) throw UpdateException("This update requires a newer Android version.")
        val sameSigners = installedSigners.isNotEmpty() && installedSigners == candidate.signers
        val forwardRotation = installedSigners.size == 1 && candidate.signers.size == 1 && candidate.signingHistory.containsAll(installedSigners)
        if (!sameSigners && !forwardRotation) throw UpdateException(
            "This release uses a different signing key and cannot update this installation. Debug and release builds must use matching keys to update in place.")
    }
}

class AndroidApkValidator(context: Context) {
    private val context = context.applicationContext
    private val manager = context.packageManager
    @Suppress("DEPRECATION")
    private fun current(): PackageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val installed: InstalledApp get() {
        val info = current()
        return InstalledApp(info.packageName, info.versionName.orEmpty(), info.longVersionCode,
            (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    @Suppress("DEPRECATION")
    fun validate(file: File, release: AppRelease) {
        val current = current()
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException("The downloaded file is not a valid APK.")
        fun fingerprints(signatures: Array<android.content.pm.Signature>?): Set<String> = signatures.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }.toSet()
        val signers = candidate.signingInfo?.apkContentsSigners
        val history = candidate.signingInfo?.takeUnless { it.hasMultipleSigners() }?.signingCertificateHistory
        ApkUpdatePolicy.validate(installed, fingerprints(current.signingInfo?.apkContentsSigners),
            ApkIdentity(candidate.packageName, candidate.versionName, candidate.longVersionCode,
                candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, fingerprints(signers), fingerprints(history)), release.version, Build.VERSION.SDK_INT)
    }
}
