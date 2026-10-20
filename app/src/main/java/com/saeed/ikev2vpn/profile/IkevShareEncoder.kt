package com.saeed.ikev2vpn.profile

import com.saeed.ikev2vpn.data.StoredProfile
import com.saeed.ikev2vpn.validation.ProfileValidator
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Exports the connection using the same offline v1 transport accepted by the importer. */
object IkevShareEncoder {
    fun encode(stored: StoredProfile, password: String? = null): String {
        val config = stored.config
        if (!ProfileValidator.isValidProfileName(config.profileName) ||
            !ProfileValidator.isValidServerAddress(config.serverAddress) ||
            !Regex("[A-Za-z0-9._@+\\-]{1,64}").matches(config.username)
        ) {
            throw IkevProfileShareException("This profile cannot be represented by the v1 sharing format. " +
                "Use a username of up to 64 letters, digits, or . _ @ + - characters.")
        }
        if (password != null && (password.isEmpty() || password.length > ProfileValidator.MAX_PASSWORD_LENGTH ||
                password.any { it.isISOControl() })) {
            throw IkevProfileShareException("This password cannot be included in a configuration link. Share without a password instead.")
        }
        val profile = JSONObject()
            .put("format", "ikev-profile")
            .put("version", 1)
            .put("name", config.profileName)
            .put("server", config.serverAddress)
            .put("remote_id", config.serverAddress)
            .put("username", config.username)
            .put("authentication", "eap-mschapv2")
            .put("connection", JSONObject().put("mode", "full-tunnel"))
            .put("server_profile", "secure")
            .put("proxy", JSONObject().put("enabled", false))
        val certificate = stored.certificate
        if (certificate == null) {
            profile.put("certificate_trust", "public")
        } else {
            profile.put("certificate_trust", "private-ca")
            profile.put("ca_certificate", JSONObject()
                .put("encoding", "der-base64")
                .put("data", Base64.getEncoder().encodeToString(certificate.derBytes))
                .put("sha256", certificate.info.sha256Fingerprint))
        }
        val envelope = JSONObject().put("format", "ikev-share").put("version", 1).put("profile", profile)
        if (password != null) envelope.put("password", password)
        val raw = envelope.toString().toByteArray(Charsets.UTF_8)
        if (raw.size > IkevShareDecoder.MAX_SHARE_JSON_BYTES) tooLarge()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val packed = try {
            ByteArrayOutputStream().also { output ->
                DeflaterOutputStream(output, deflater).use { it.write(raw) }
            }.toByteArray()
        } finally {
            deflater.end()
        }
        val link = "ikev://import/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
        if (link.length > IkevShareDecoder.MAX_SHARE_URI_BYTES) tooLarge()
        return link
    }

    private fun tooLarge(): Nothing = throw IkevProfileShareException(
        "This configuration is too large for a v1 link or QR code. Try sharing without a password or use the original .ikev file.",
    )
}

class IkevProfileShareException(override val message: String) : Exception(message)
