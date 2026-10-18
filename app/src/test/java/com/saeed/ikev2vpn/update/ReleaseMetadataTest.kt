package com.saeed.ikev2vpn.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseMetadataTest {
    @Test
    fun `selects exact APK and checksum names from the official release`() {
        val release = ReleaseMetadata.parse(metadata().toString())
        assertEquals(ReleaseVersion(1, 5, 0), release.version)
        assertEquals("ikev2-android-v1.5.0.apk", release.apk.name)
        assertEquals(1234L, release.apk.size)
        assertTrue(release.pageUrl.startsWith(PROJECT_URL))
    }
    @Test
    fun `numeric versions handle multiple digits and reject malformed tags`() {
        assertTrue(ReleaseVersion.parse("1.10.0")!! > ReleaseVersion.parse("1.9.9")!!)
        for (invalid in listOf("1.2", "01.2.3", "1.2.3-beta", "1.2.-1", "1.2.99999999999999", " 1.2.3")) assertNull(ReleaseVersion.parse(invalid))
    }
    @Test
    fun `rejects drafts prereleases missing checksums and incomplete uploads`() {
        val samples = listOf(metadata().put("draft", true), metadata().put("prerelease", true), metadata().put("tag_name", "other-v1.5.0"),
            metadata().put("assets", JSONArray()), metadata().also { it.getJSONArray("assets").getJSONObject(0).put("state", "new") })
        for (sample in samples) invalid(sample.toString())
    }
    @Test
    fun `rejects external asset URLs duplicate assets and unbounded or noninteger sizes`() {
        for (size in listOf<Any>(0, -1, MAX_UPDATE_BYTES + 1, "1234", 1.5)) invalid(metadata().also { it.getJSONArray("assets").getJSONObject(0).put("size", size) }.toString())
        invalid(metadata().also { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/update.apk") }.toString())
        invalid(metadata().also { val assets = it.getJSONArray("assets"); assets.put(assets.getJSONObject(0)) }.toString())
        invalid("not JSON")
    }
    @Test
    fun `checksums must cover exactly the selected asset`() {
        val hash = "AB".repeat(32)
        assertEquals(hash.lowercase(), ReleaseMetadata.checksum("$hash  ikev2-android-v1.5.0.apk\n", "ikev2-android-v1.5.0.apk"))
        for (text in listOf("$hash  other.apk\n", "bad  ikev2-android-v1.5.0.apk", "$hash  ikev2-android-v1.5.0.apk\nother")) {
            try { ReleaseMetadata.checksum(text, "ikev2-android-v1.5.0.apk"); fail() } catch (_: UpdateException) { }
        }
    }
    @Test
    fun `redirect policy allows GitHub HTTPS but rejects downgrade userinfo ports and lookalike hosts`() {
        assertTrue(ReleaseMetadata.trustedDownloadUrl("https://release-assets.githubusercontent.com/asset?signature=test"))
        for (url in listOf("http://github.com/file", "https://github.com.evil.example/file", "https://evil.example/github.com", "https://user@github.com/file", "https://github.com:8443/file", "file:///tmp/update.apk", "https://github.com/file#fragment")) assertFalse(url, ReleaseMetadata.trustedDownloadUrl(url))
    }
    private fun invalid(text: String) { try { ReleaseMetadata.parse(text); fail("Expected invalid release") } catch (_: UpdateException) { } }
    companion object {
        fun metadata(): JSONObject {
            val name = "ikev2-android-v1.5.0.apk"
            fun asset(name: String, size: Int) = JSONObject().put("name", name).put("state", "uploaded").put("size", size)
                .put("browser_download_url", "$PROJECT_URL/releases/download/android-v1.5.0/$name")
            return JSONObject().put("tag_name", "android-v1.5.0").put("draft", false).put("prerelease", false).put("body", "Test release")
                .put("assets", JSONArray().put(asset(name, 1234)).put(asset("$name.sha256", 98)))
        }
    }
}
