package com.saeed.ikev2vpn.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnTrafficTest {
    @Test
    fun `native snapshot keeps receive and send directions and totals above four GiB`() {
        assertEquals(VpnTraffic(9_000_000_000L, 6_000_000_000L), VpnTraffic.fromNative(longArrayOf(9_000_000_000L, 6_000_000_000L)))
        assertEquals(VpnTraffic(0, 0), VpnTraffic.fromNative(longArrayOf(0, 0)))
    }

    @Test
    fun `unavailable or malformed snapshots never appear as real zero traffic`() {
        for (snapshot in listOf(null, longArrayOf(), longArrayOf(1), longArrayOf(1, 2, 3), longArrayOf(-1, 0), longArrayOf(0, -1))) {
            assertNull(VpnTraffic.fromNative(snapshot))
        }
    }

    @Test
    fun `only a confirmed connected session can publish traffic`() {
        for (state in ConnectionState.entries) for (confirmed in listOf(false, true)) {
            val sample = VpnState(connectionState = state, confirmed = confirmed, traffic = VpnTraffic(500, 100))
            assertEquals(if (state == ConnectionState.CONNECTED && confirmed) VpnTraffic(500, 100) else null, sample.currentTraffic())
        }
    }
}
