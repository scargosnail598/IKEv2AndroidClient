package com.saeed.ikev2vpn.profile

import com.saeed.ikev2vpn.validation.ProfileValidator
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Deliberately avoids a generated toString/copy/component API for credentials. */
class ImportedIkevShare(val profile: ImportedIkevProfile, val password: String?) {
    override fun toString(): String = "ImportedIkevShare(<redacted>)"
}

class IkevShareDecoder(private val profileParser: IkevProfileParser) {
    fun decode(text: String): ImportedIkevShare {
        val uri = text.trim()
        if (uri.length > MAX_SHARE_URI_BYTES || !LINK.matches(uri)) {
            fail("Use a valid ikev://import/v1/ configuration link (up to 2953 bytes).")
        }
        val packed = try {
            Base64.getUrlDecoder().decode(uri.substring(PREFIX.length))
        } catch (_: IllegalArgumentException) {
            fail("The configuration link contains invalid Base64 data.")
        }
        val raw = inflate(packed)
        val envelope = try {
            val json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
            ShareJsonValidator(json).validate()
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) {
                fail("The configuration link contains invalid JSON.")
            }
            value
        } catch (exception: IkevProfileImportException) {
            throw exception
        } catch (_: Exception) {
            fail("The configuration link contains invalid UTF-8 or JSON.")
        }
        val version = envelope.opt("version")
        if (envelope.opt("format") != "ikev-share" ||
            (version !is Int && version !is Long) || (version as Number).toLong() != 1L
        ) {
            fail("This configuration link uses an unsupported share format or version.")
        }
        val profile = envelope.opt("profile") as? JSONObject
            ?: fail("The configuration link has no valid profile.")
        val profileVersion = profile.opt("version")
        if (profile.opt("format") != "ikev-profile" ||
            (profileVersion !is Int && profileVersion !is Long) || (profileVersion as Number).toLong() != 1L
        ) {
            fail("This configuration link uses an unsupported profile format or version.")
        }
        val password = if (envelope.has("password")) {
            val value = envelope.opt("password") as? String
                ?: fail("The configuration link contains an invalid password.")
            if (value.isEmpty() || value.length > ProfileValidator.MAX_PASSWORD_LENGTH ||
                value.any { it.isISOControl() }
            ) {
                fail("The configuration link contains an invalid password.")
            }
            value
        } else null
        return ImportedIkevShare(profileParser.parse(profile.toString().toByteArray(StandardCharsets.UTF_8)), password)
    }

    private fun inflate(packed: ByteArray): ByteArray {
        val inflater = Inflater() // The server uses zlib, not raw DEFLATE or gzip.
        try {
            inflater.setInput(packed)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (output.size() + count > MAX_SHARE_JSON_BYTES) {
                    fail("The configuration link expands beyond 64 KiB.")
                }
                output.write(buffer, 0, count)
                if (count == 0 && !inflater.finished()) {
                    fail("The configuration link contains an incomplete or invalid zlib stream.")
                }
            }
            if (inflater.remaining != 0) fail("The configuration link contains trailing compressed data.")
            return output.toByteArray()
        } catch (_: DataFormatException) {
            fail("The configuration link contains invalid zlib data.")
        } finally {
            inflater.end()
        }
    }

    private fun fail(message: String): Nothing = throw IkevProfileImportException(message)

    companion object {
        const val MAX_SHARE_URI_BYTES = 2953
        const val MAX_SHARE_JSON_BYTES = 64 * 1024
        private const val PREFIX = "ikev://import/v1/"
        private val LINK = Regex("ikev://import/v1/[A-Za-z0-9_-]+")
    }
}
