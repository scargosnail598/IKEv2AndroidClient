package com.saeed.ikev2vpn.profile

import com.saeed.ikev2vpn.certificate.CertificateLoader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class IkevShareDecoderTest {
    private val decoder = IkevShareDecoder(IkevProfileParser(CertificateLoader {
        Date.from(Instant.parse("2030-01-01T00:00:00Z"))
    }))

    @Test
    fun `imports frozen server exports for both trust modes and optional credentials`() {
        for (trust in listOf("public", "private")) {
            for (withPassword in listOf(false, true)) {
                val suffix = if (withPassword) "-password" else ""
                val input = fixture("$trust$suffix")
                val result = decoder.decode(" \n$input\t ")
                assertEquals("alice", result.profile.config.username)
                assertEquals("vpn.example.com", result.profile.remoteId)
                assertEquals("stock-windows-compatible", result.profile.serverProfile)
                assertEquals(trust == "public", result.profile.usesPublicCertificateTrust)
                assertEquals(trust == "private", result.profile.certificate != null)
                assertEquals("10.254.254.1", result.profile.proxy.host)
                assertEquals(if (withPassword) PASSWORD else null, result.password)
                assertFalse(result.toString().contains(PASSWORD))
                assertTrue(input.trim().length <= 2953)
            }
        }
    }

    @Test
    fun `rejects alternate URI structures versions encoding and excessive input`() {
        val valid = fixture("public").trim()
        for (invalid in listOf(
            valid.replace("ikev://", "https://"), valid.replace("import/", "other/"),
            valid.replace("/v1/", "/v2/"), valid.replace("import/", "user@import/"),
            valid.replace("import/", "import:80/"), valid + "?x=1", valid + "#fragment",
            valid + "/extra", valid + "=", valid + "%20", valid + " "+valid,
            "ikev://import/v1/", "ikev://import/v1/A", "ikev://import/v1/" + "A".repeat(2954),
        )) reject(invalid)
    }

    @Test
    fun `rejects raw deflate gzip corrupted truncated and concatenated streams`() {
        val raw = envelope().toString().toByteArray()
        val packed = compress(raw)
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray()
        val dictionaryDeflater = Deflater().apply { setDictionary("dictionary".toByteArray()) }
        val dictionary = ByteArrayOutputStream().also { output ->
            try { DeflaterOutputStream(output, dictionaryDeflater).use { it.write(raw) } }
            finally { dictionaryDeflater.end() }
        }.toByteArray()
        for (invalid in listOf(
            gzip, compress(raw, true), dictionary, byteArrayOf(1,2,3), packed.copyOf(packed.size - 1),
            packed + byteArrayOf(0), packed + packed, packed.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
        )) reject(link(invalid))
    }

    @Test
    fun `enforces decompressed bound including exactly 64 KiB`() {
        val base = envelope().put("padding", "").toString()
        val atLimit = envelope().put("padding", "x".repeat(65536 - base.toByteArray().size)).toString().toByteArray()
        assertEquals(65536, atLimit.size)
        assertEquals("alice", decoder.decode(link(compress(atLimit))).profile.config.username)
        reject(link(compress(atLimit + byteArrayOf(32))))
    }

    @Test
    fun `rejects malformed UTF8 JSON envelope and version types`() {
        for (raw in listOf(byteArrayOf(0xFF.toByte()), "{\"format\": ".toByteArray(),
            "[]".toByteArray(), "${envelope()} trailing".toByteArray())) reject(link(compress(raw)))
        for (version in listOf<Any>(true, "1", 0, 2, JSONObject.NULL)) {
            reject(encoded(envelope().put("version", version)))
            reject(encoded(envelope().put("profile", profile().put("version", version))))
        }
        // JSONObject serialization canonicalizes 1.0 to 1; keep the original numeric token here.
        reject(link(compress(envelope().toString().replace("\"version\":1", "\"version\":1.0").toByteArray())))
        val decimalProfile = profile().toString().replace("\"version\":1", "\"version\":1.0")
        reject(link(compress("""{"format":"ikev-share","version":1,"profile":$decimalProfile}""".toByteArray())))
        reject(encoded(envelope().put("format", "other")))
        reject(encoded(envelope().put("profile", "not-an-object")))
        reject(encoded(envelope().also { it.remove("profile") }))
    }

    @Test
    fun `rejects Android lenient JSON extensions duplicate keys and excessive nesting`() {
        for (raw in listOf(
            "{format:'ikev-share',version:1}", "{\"format\":\"ikev-share\",}",
            "{\"format\":\"ikev-share\",/* comment */\"version\":1}",
            envelope().toString().replaceFirst("{", "{\"version\":1,"),
            "[".repeat(1000) + "0" + "]".repeat(1000),
        )) reject(link(compress(raw.toByteArray())))
    }

    @Test
    fun `validates optional password without trimming literal characters`() {
        assertEquals("  رمز ' \\ secret  ", decoder.decode(encoded(envelope().put("password", "  رمز ' \\ secret  "))).password)
        for (invalid in listOf<Any>("", "x".repeat(1025), "line\nbreak", 12, true, JSONObject.NULL)) {
            reject(encoded(envelope().put("password", invalid)))
        }
    }

    @Test
    fun `reuses profile validation for server identity authentication and CA fingerprint`() {
        for ((key, value) in listOf("server" to "https://vpn.example.com", "remote_id" to "other.example.com",
            "authentication" to "none", "username" to "bad user", "certificate_trust" to "unknown")) {
            reject(encoded(envelope().put("profile", profile().put(key, value))))
        }
        val shared = decodeEnvelope(fixture("private"))
        shared.getJSONObject("profile").getJSONObject("ca_certificate").put("sha256", List(32) { "00" }.joinToString(":"))
        reject(encoded(shared))
    }

    private fun reject(text: String) {
        try {
            decoder.decode(text)
            fail("Expected invalid share to be rejected")
        } catch (exception: IkevProfileImportException) {
            assertFalse(exception.message.orEmpty().contains(PASSWORD))
            assertFalse(exception.message.orEmpty().contains("ikev://import/v1/eJ"))
        }
    }

    private fun envelope() = decodeEnvelope(fixture("public"))
    private fun profile() = envelope().getJSONObject("profile")
    private fun encoded(value: JSONObject) = link(compress(value.toString().toByteArray()))
    private fun compress(raw: ByteArray, nowrap: Boolean = false): ByteArray {
        val deflater = Deflater(9, nowrap)
        return try {
            ByteArrayOutputStream().also { output -> DeflaterOutputStream(output, deflater).use { it.write(raw) } }.toByteArray()
        } finally { deflater.end() }
    }
    private fun link(packed: ByteArray) = "ikev://import/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
    private fun decodeEnvelope(text: String): JSONObject {
        val packed = Base64.getUrlDecoder().decode(text.trim().substringAfterLast('/'))
        return JSONObject(java.util.zip.InflaterInputStream(packed.inputStream()).reader().use { it.readText() })
    }

    companion object {
        const val PASSWORD = "fixture ' literal \\ رمز !"
        fun fixture(name: String): String = requireNotNull(IkevShareDecoderTest::class.java.getResource("/ikev-share/$name.uri")).readText()
    }
}
