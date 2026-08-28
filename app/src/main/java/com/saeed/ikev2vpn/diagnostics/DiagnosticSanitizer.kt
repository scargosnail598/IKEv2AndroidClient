package com.saeed.ikev2vpn.diagnostics

object DiagnosticSanitizer {
    private const val MAX_DIAGNOSTIC_LENGTH = 512
    private val credentialAssignment = Regex(
        pattern = "(?i)\\b(password|passphrase|passwd|pwd)\\b\\s*[:=]\\s*\\S+",
    )
    private val contentUri = Regex("(?i)content://\\S+")

    fun exceptionType(exception: Exception): String = exception.javaClass.name

    fun sanitize(value: String): String {
        return value
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString(separator = "")
            .replace(credentialAssignment) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
            .replace(contentUri, "content://<redacted>")
            .take(MAX_DIAGNOSTIC_LENGTH)
    }
}
