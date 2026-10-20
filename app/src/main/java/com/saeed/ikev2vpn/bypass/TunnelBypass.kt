package com.saeed.ikev2vpn.bypass

import android.net.VpnService
import android.net.IpPrefix
import android.os.Build
import org.strongswan.android.utils.IPRange
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One settings snapshot per connection, with expiring DNS-derived host routes. */
class TunnelBypass(val config: BypassConfig) {
    private val dns = DnsBypassSession(config)
    fun isDomainEnabled(): Boolean = dns.enabled
    fun observe(packet: ByteArray?, outbound: Boolean) = dns.observe(packet, outbound)
    fun prefixes(): List<IpSubnet> = dns.prefixes()
    fun clear() = dns.clear()
    fun applyRoutes(builder: VpnService.Builder, ranges: Iterable<IPRange>, dnsServers: List<InetAddress>, count: Int): Int {
        val base = ranges.map { IpSubnet(it.from, it.prefix) }
        val dnsRoutes = protectedDns(base, dnsServers)
        if (Build.VERSION.SDK_INT >= 33) {
            // Native exclusions avoid tens of thousands of complementary IPv6 routes.
            val included = BypassRoutePlanner.normalized(base)
            val excluded = BypassRoutePlanner.intersection(included, prefixes())
            require(count + included.size + excluded.size + dnsRoutes.size <= BypassRoutePlanner.MAX_ROUTES) {
                "These bypass rules exceed this device's route limit. Select fewer countries."
            }
            for (route in included) addRoute(builder, route)
            for (route in excluded) builder.excludeRoute(IpPrefix(route.address, route.prefix))
            for (route in dnsRoutes) addRoute(builder, route)
            return count + included.size + excluded.size + dnsRoutes.size
        }
        // API 30–32: strongSwan's included-route representation, with a bounded complement.
        val planned = BypassRoutePlanner.routes(base, prefixes())
        val routes = BypassRoutePlanner.routes(planned + dnsRoutes, emptyList(), BypassRoutePlanner.MAX_ROUTES - count)
        for (route in routes) addRoute(builder, route)
        return count + routes.size
    }
    private fun protectedDns(base: List<IpSubnet>, servers: List<InetAddress>): List<IpSubnet> =
        servers.map { IpSubnet(it, it.address.size * 8) }.filter { host ->
            base.any { it.bits == host.bits && host.start >= it.start && host.start <= it.end }
        }

    private fun addRoute(builder: VpnService.Builder, route: IpSubnet) {
        try { builder.addRoute(route.address, route.prefix) }
        catch (e: IllegalArgumentException) { if (!route.address.isMulticastAddress) throw e }
    }
}

/** Counts/status only: never domain names, resolved addresses or DNS payloads. */
object BypassRuntime {
    private val mutableIssue = MutableStateFlow<String?>(null)
    val issue = mutableIssue.asStateFlow()
    private const val ROUTE_FAILURE = "Bypass routes could not be refreshed. The previous routes remain active. Reconnect or use fewer rules."
    @JvmStatic fun reportRouteFailure() { mutableIssue.value = ROUTE_FAILURE }
    @JvmStatic fun routesRefreshed() { if (mutableIssue.value == ROUTE_FAILURE) mutableIssue.value = null }
    @JvmStatic fun reportCapacity() { mutableIssue.value = "The domain bypass cache is full. New destinations stay in the VPN until older DNS entries expire." }
    @JvmStatic fun reportSettingsFailure() { mutableIssue.value = "Saved bypass rules could not be read. Global bypass is off for this connection. Open Traffic bypass and save the settings again." }
    @JvmStatic fun clear() { mutableIssue.value = null }
}
