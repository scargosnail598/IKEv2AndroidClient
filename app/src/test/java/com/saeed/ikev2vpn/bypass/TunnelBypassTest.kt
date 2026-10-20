package com.saeed.ikev2vpn.bypass

import android.net.VpnService
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.strongswan.android.utils.IPRange
import java.net.InetAddress

class TunnelBypassTest {
    private fun routing() = TunnelBypass(BypassConfig(true, setOf("ir")))
    @Test fun `a failed TUN rebuild is retried and commits only after a successful replacement`() {
        val routing = routing()
        val updater = BypassTunUpdater(routing)
        updater.markEstablished()
        assertEquals(-1, updater.process(DnsWire.packet(false), true) { error("Query alone must not rebuild TUN") })
        var builds = 0
        assertEquals(-1, updater.process(DnsWire.packet(true), false) { builds++; -1 })
        assertEquals(1, builds)
        assertEquals(42, updater.process(null, false) { builds++; 42 })
        assertEquals(2, builds)
        assertEquals(-1, updater.process(null, false) { error("Unchanged routes must not rebuild") })
    }
    @Test fun `exceptions keep the previous routing snapshot eligible for retry`() {
        val routing = routing(); val updater = BypassTunUpdater(routing); updater.markEstablished()
        updater.process(DnsWire.packet(false), true) { error("Unexpected rebuild") }
        assertEquals(-1, updater.process(DnsWire.packet(true), false) { throw IllegalStateException("rejected") })
        assertEquals(12, updater.process(null, false) { 12 })
    }
    @Test fun `country and learned DNS bypasses reach actual builder routes while preserving DNS inside VPN`() {
        val routing = TunnelBypass(BypassConfig(true, setOf("ir"), true,
            listOf(CountryRoutes("IR", listOf(IpSubnet.parse("8.8.8.0/24")), 1))))
        routing.observe(DnsWire.packet(false), true); routing.observe(DnsWire.packet(true), false)
        val builder = mock(VpnService.Builder::class.java, RETURNS_SELF)
        val captured = mutableListOf<IpSubnet>()
        doAnswer { call -> captured += IpSubnet(call.getArgument(0), call.getArgument(1)); builder }
            .`when`(builder).addRoute(any(InetAddress::class.java), anyInt())
        routing.applyRoutes(builder, listOf(IPRange(InetAddress.getByName("0.0.0.0"), 0)), listOf(InetAddress.getByName("8.8.8.8")), 0)
        assertFalse(BypassRulesTest.contains(captured, "8.8.8.9"))
        assertFalse(BypassRulesTest.contains(captured, "8.8.4.4"))
        assertTrue(BypassRulesTest.contains(captured, "8.8.8.8"))
        assertTrue(BypassRulesTest.contains(captured, "1.1.1.1"))
    }
    @Test fun `an address already covered by country bypass does not recreate the TUN`() {
        val routing = TunnelBypass(BypassConfig(true, setOf("ir"), true,
            listOf(CountryRoutes("IR", listOf(IpSubnet.parse("8.8.0.0/16")), 1))))
        val updater = BypassTunUpdater(routing)
        updater.markEstablished()
        updater.process(DnsWire.packet(false), true) { error("Country already bypassed") }
        assertEquals(-1, updater.process(DnsWire.packet(true), false) { error("No effective route change") })
    }
    @Test fun `disabled rules produce the original default route`() {
        val routing = TunnelBypass(BypassConfig())
        val builder = mock(VpnService.Builder::class.java, RETURNS_SELF)
        assertEquals(1, routing.applyRoutes(builder, listOf(IPRange(InetAddress.getByName("0.0.0.0"), 0)), emptyList(), 0))
        verify(builder).addRoute(InetAddress.getByName("0.0.0.0"), 0)
        verifyNoMoreInteractions(builder)
    }
}
