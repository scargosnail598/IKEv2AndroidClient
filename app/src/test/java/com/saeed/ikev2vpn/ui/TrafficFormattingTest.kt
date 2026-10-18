package com.saeed.ikev2vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficFormattingTest {
    @Test
    fun `session volume uses readable binary units without truncating large totals`() {
        assertEquals("0 B", formatTrafficBytes(0))
        assertEquals("1023 B", formatTrafficBytes(1023))
        assertEquals("1.0 KiB", formatTrafficBytes(1024))
        assertEquals("1.5 MiB", formatTrafficBytes(1572864))
        assertEquals("8.0 GiB", formatTrafficBytes(8589934592))
        assertEquals("8.0 EiB", formatTrafficBytes(Long.MAX_VALUE))
    }
}
