package com.saeed.ikev2vpn.update

import org.json.JSONObject
import java.net.URI

class UpdateException(message: String) : Exception(message)

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            if (!Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})").matches(value)) return null
            val parts = value.split('.').map(String::toInt)
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)
data class AppRelease(val version: ReleaseVersion, val notes: String, val apk: ReleaseAsset, val checksum: ReleaseAsset) {
    val pageUrl: String get() = "$PROJECT_URL/releases/tag/android-v$version"
}

const val PROJECT_URL = "https://github.com/scargosnail598/IKEv2AndroidClient"
const val RELEASES_URL = "$PROJECT_URL/releases"
const val LATEST_RELEASE_API = "https://api.github.com/repos/scargosnail598/IKEv2AndroidClient/releases/latest"
const val MAX_UPDATE_BYTES = 256L * 1024 * 1024

object ReleaseMetadata {
    fun parse(text: String): AppRelease {
        try {
            val json = JSONObject(text)
            if (json.opt("draft") != false || json.opt("prerelease") != false) invalid()
            val tag = json.opt("tag_name") as? String ?: invalid()
            if (!tag.startsWith("android-v")) invalid()
            val version = ReleaseVersion.parse(tag.removePrefix("android-v")) ?: invalid()
            val name = "ikev2-android-v$version.apk"
            val assets = json.getJSONArray("assets")
            fun asset(expected: String, maximum: Long): ReleaseAsset {
                val found = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.opt("name") == expected }.singleOrNull() ?: invalid()
                if (found.opt("state") != "uploaded") invalid()
                val size = found.opt("size")
                if (size !is Int && size !is Long) invalid()
                val length = (size as Number).toLong()
                if (length !in 1..maximum) invalid()
                val url = found.opt("browser_download_url") as? String ?: invalid()
                if (url != "$PROJECT_URL/releases/download/$tag/$expected") invalid()
                return ReleaseAsset(expected, url, length)
            }
            return AppRelease(version, (json.opt("body") as? String).orEmpty().take(6000),
                asset(name, MAX_UPDATE_BYTES), asset("$name.sha256", 4096))
        } catch (exception: UpdateException) { throw exception }
        catch (_: Exception) { invalid() }
    }

    fun checksum(text: String, expectedName: String): String {
        val parts = Regex("^([A-Fa-f0-9]{64})[ \\t]+\\*?([^\\r\\n]+)[\\r\\n]*$").matchEntire(text) ?: invalid()
        if (parts.groupValues[2] != expectedName) invalid()
        return parts.groupValues[1].lowercase(java.util.Locale.ROOT)
    }

    fun trustedDownloadUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    } catch (_: Exception) { false }

    private fun invalid(): Nothing = throw UpdateException("The release does not contain a valid APK and checksum for this app.")
}
