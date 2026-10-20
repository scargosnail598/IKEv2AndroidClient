package com.saeed.ikev2vpn.profile

import com.google.zxing.RGBLuminanceSource
import com.saeed.ikev2vpn.certificate.CertificateLoader
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.StoredProfile
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Random
import java.util.zip.InflaterInputStream

class IkevShareEncoderTest {
    private val decoder = IkevShareDecoder(IkevProfileParser(CertificateLoader {
        Date.from(Instant.parse("2030-01-01T00:00:00Z"))
    }))

    @Test
    fun `exported profiles round trip through import for both trust modes and optional password`() {
        for (trust in listOf("public", "private")) {
            val original = stored(trust)
            for (password in listOf(null, "  رمز ' \\ secret  ")) {
                val link = IkevShareEncoder.encode(original, password)
                val imported = decoder.decode(link)
                assertEquals(original.config.profileName, imported.profile.config.profileName)
                assertEquals(original.config.serverAddress, imported.profile.config.serverAddress)
                assertEquals(original.config.username, imported.profile.config.username)
                assertEquals(password, imported.password)
                assertEquals(trust == "public", imported.profile.usesPublicCertificateTrust)
                assertEquals(original.certificate?.info?.sha256Fingerprint, imported.profile.certificate?.info?.sha256Fingerprint)
                if (trust == "private") assertArrayEquals(original.certificate!!.derBytes, imported.profile.certificate!!.derBytes)
                assertTrue(link.length <= IkevShareDecoder.MAX_SHARE_URI_BYTES)
                assertTrue(link.matches(Regex("ikev://import/v1/[A-Za-z0-9_-]+")))
            }
        }
    }

    @Test
    fun `QR output decodes to exactly the exported configuration link`() {
        val original = stored("private")
        val link = IkevShareEncoder.encode(original, "literal password")
        val matrix = QrCodeEncoder.encode(link)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else -1
        }
        val scanned = QrCodeDecoder.decode(RGBLuminanceSource(matrix.width, matrix.height, pixels))
        assertEquals(link, scanned)
        assertEquals("literal password", decoder.decode(scanned!!).password)
    }

    @Test
    fun `QR supports the maximum accepted ASCII link length`() {
        val prefix = "ikev://import/v1/"
        val atLimit = prefix + "a".repeat(IkevShareDecoder.MAX_SHARE_URI_BYTES - prefix.length)
        assertEquals(IkevShareDecoder.MAX_SHARE_URI_BYTES, atLimit.length)
        val matrix = QrCodeEncoder.encode(atLimit)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else -1
        }
        assertEquals(atLimit, QrCodeDecoder.decode(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
    }

    @Test
    fun `exports connection identity without local IDs packages or passwords by default`() {
        val original = stored("public").let { it.copy(config = it.config.copy(
            id = "local-secret-id",
            profileName = "اتصال شخصی",
            appRouting = AppRoutingConfig(AppRoutingMode.ONLY_SELECTED, setOf("private.local.app")),
        )) }
        val link = IkevShareEncoder.encode(original)
        val raw = InflaterInputStream(Base64.getUrlDecoder().decode(link.substringAfterLast('/')).inputStream())
            .reader().use { it.readText() }
        assertFalse(raw.contains("password"))
        assertFalse(raw.contains("local-secret-id"))
        assertFalse(raw.contains("private.local.app"))
        assertEquals("اتصال شخصی", decoder.decode(link).profile.config.profileName)
        assertEquals(AppRoutingConfig(), decoder.decode(link).profile.config.appRouting)
    }

    @Test
    fun `rejects passwords and usernames unsupported by v1 without echoing secrets`() {
        val original = stored("public")
        for (password in listOf("", "line\nbreak", "p".repeat(1025))) {
            reject { IkevShareEncoder.encode(original, password) }
        }
        reject { IkevShareEncoder.encode(original.copy(config = original.config.copy(username = "bad user"))) }
    }

    @Test
    fun `rejects incompressible certificates exceeding the link capacity`() {
        val original = stored("private")
        val bytes = ByteArray(5000).also { Random(17).nextBytes(it) }
        reject { IkevShareEncoder.encode(original.copy(certificate = original.certificate!!.copy(derBytes = bytes))) }
    }

    @Test
    fun `rejects JSON exceeding the decompression bound even when compressible`() {
        val original = stored("private")
        val bytes = ByteArray(IkevShareDecoder.MAX_SHARE_JSON_BYTES)
        reject { IkevShareEncoder.encode(original.copy(certificate = original.certificate!!.copy(derBytes = bytes))) }
    }

    private fun stored(trust: String): StoredProfile {
        val imported = decoder.decode(IkevShareDecoderTest.fixture(trust)).profile
        return StoredProfile(imported.config, imported.certificate, ProvisioningStatus.PROVISIONED)
    }

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Expected unsupported share to be rejected")
        } catch (exception: IkevProfileShareException) {
            assertFalse(exception.message.contains("ikev://"))
            assertFalse(exception.message.contains("line\nbreak"))
        }
    }
}
