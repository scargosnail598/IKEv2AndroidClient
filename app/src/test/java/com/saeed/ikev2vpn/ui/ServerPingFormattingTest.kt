package com.saeed.ikev2vpn.ui

import com.saeed.ikev2vpn.vpn.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPingFormattingTest {
    @Test fun `idle checking and unavailable are distinct from zero latency`() {
        assertEquals("—", formatServerPing(ServerPingReading.Inactive))
        assertEquals("Checking…", formatServerPing(ServerPingReading.Checking))
        assertEquals("Unavailable", formatServerPing(ServerPingReading.Unavailable))
        assertEquals("<1 ms", formatServerPing(ServerPingReading.Measured(PingSample(0.0))))
        assertEquals("<1 ms", formatServerPing(ServerPingReading.Measured(PingSample(1.0, true))))
        assertEquals("<6 ms", formatServerPing(ServerPingReading.Measured(PingSample(5.2, true))))
        assertEquals("43 ms", formatServerPing(ServerPingReading.Measured(PingSample(42.8))))
    }
}
