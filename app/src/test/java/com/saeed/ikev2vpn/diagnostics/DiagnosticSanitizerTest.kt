package com.saeed.ikev2vpn.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `redacts entire configuration links before truncation and keeps existing secret filtering`() {
        val result = DiagnosticSanitizer.sanitize("Import IKEV://import/v1/" + "sensitive".repeat(400) + " password=secret content://private/file")
        assertEquals("Import ikev://<redacted> password=<redacted> content://<redacted>", result)
    }
}
