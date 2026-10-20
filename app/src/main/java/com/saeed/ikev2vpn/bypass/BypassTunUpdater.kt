package com.saeed.ikev2vpn.bypass

/** Commits a routing snapshot only after Android successfully creates its replacement interface. */
class BypassTunUpdater(private val routing: TunnelBypass) {
    private var installed: List<IpSubnet> = emptyList()
    fun markEstablished() { installed = routing.prefixes() }
    fun process(packet: ByteArray?, outbound: Boolean, establish: () -> Int): Int {
        routing.observe(packet, outbound)
        val proposed = routing.prefixes()
        if (proposed == installed) return -1
        val fd = try { establish() } catch (_: Exception) { -1 }
        if (fd >= 0) { installed = proposed; BypassRuntime.routesRefreshed() } else BypassRuntime.reportRouteFailure()
        return fd
    }
}
