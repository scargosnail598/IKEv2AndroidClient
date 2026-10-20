package com.saeed.ikev2vpn.bypass

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.util.Random

class BypassRulesTest {
    @Test fun `suffixes normalize IDN dots wildcards and case and respect label boundaries`() {
        val rules = DomainSuffixes.parse(".IR, *.co.uk\n.ایران")
        assertTrue("ir" in rules)
        assertTrue(DomainSuffixes.matches("BANK.EXAMPLE.IR.", rules))
        assertTrue(DomainSuffixes.matches("shop.co.uk", rules))
        assertFalse(DomainSuffixes.matches("example.ir.evil.com", rules))
        assertFalse(DomainSuffixes.matches("notir", rules))
        assertFalse(DomainSuffixes.matches("evilco.uk", rules))
        assertTrue(DomainSuffixes.parse("").isEmpty())
    }
    @Test fun `rejects URL path port invalid suffixes and unbounded rules`() {
        for (value in listOf("https://example.ir", "ir/path", "ir:443", "bad..ir", "-ir", "123", "*", ".", "ir\u0000", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException::class.java) { DomainSuffixes.parse(value) }
        }
    }
    @Test fun `CIDR parser canonicalizes numeric addresses and rejects hostnames`() {
        assertEquals("8.8.8.0/24", IpSubnet.parse("8.8.8.19/24").toString())
        assertEquals(BigInteger.ZERO, IpSubnet.parse("::/0").start)
        for (value in listOf("example.com/24", "127.1/32", "999.1.1.1/24", "1.2.3.4/-1", "1.2.3.4/33", "::1/129", "fe80::1%wlan0/64", "1.2.3.4")) {
            assertThrows(Exception::class.java) { IpSubnet.parse(value) }
        }
    }
    @Test fun `IPv4 and IPv6 exclusions preserve every other address without widening base routes`() {
        val base = listOf(IpSubnet.parse("0.0.0.0/0"), IpSubnet.parse("::/0"))
        val excluded = listOf(IpSubnet.parse("8.8.8.0/24"), IpSubnet.parse("2001:4860::/32"))
        val routes = BypassRoutePlanner.routes(base, excluded)
        assertFalse(contains(routes, "8.8.8.8")); assertTrue(contains(routes, "8.8.9.1"))
        assertFalse(contains(routes, "2001:4860:1234::1")); assertTrue(contains(routes, "2606:4700::1111"))
        val v4Total = routes.filter { it.bits == 32 }.fold(BigInteger.ZERO) { n, p -> n + BigInteger.ONE.shiftLeft(32 - p.prefix) }
        assertEquals(BigInteger.ONE.shiftLeft(32) - BigInteger.valueOf(256), v4Total)
        val narrow = BypassRoutePlanner.routes(listOf(IpSubnet.parse("8.8.0.0/16")), excluded)
        assertFalse(contains(narrow, "1.1.1.1")); assertTrue(contains(narrow, "8.8.9.1"))
    }
    @Test fun `overlaps duplicates adjacent networks and total exclusion are handled exactly`() {
        val normalized = BypassRoutePlanner.normalized(listOf("8.8.8.0/25", "8.8.8.128/25", "8.8.8.0/26").map(IpSubnet::parse))
        assertEquals(listOf(IpSubnet.parse("8.8.8.0/24")), normalized)
        assertTrue(BypassRoutePlanner.routes(normalized, normalized).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            BypassRoutePlanner.routes(listOf(IpSubnet.parse("0.0.0.0/0")), listOf(IpSubnet.parse("8.8.8.8/32")), 2)
        }
    }
    @Test fun `random host exclusions match an independent address oracle`() {
        val random = Random(19)
        repeat(50) {
            val hosts = (0 until 30).map { random.nextInt(256) }.toSet()
            val routes = BypassRoutePlanner.routes(listOf(IpSubnet.parse("8.8.8.0/24")), hosts.map { IpSubnet.parse("8.8.8.$it/32") })
            for (octet in 0..255) assertEquals(octet !in hosts, contains(routes, "8.8.8.$octet"))
            assertFalse(contains(routes, "8.8.9.0"))
        }
    }
    @Test fun `native exclusions intersect negotiated routes without excluding outside traffic or losing narrow routes`() {
        val base = listOf(IpSubnet.parse("5.160.0.0/16"), IpSubnet.parse("2001:4860::/32"))
        val excluded = listOf(IpSubnet.parse("5.128.0.0/9"), IpSubnet.parse("2001::/16"))
        assertEquals(base, BypassRoutePlanner.intersection(base, excluded))
        assertFalse(contains(BypassRoutePlanner.intersection(base, excluded), "5.161.0.1"))
    }
    @Test fun `native exclusion decisions agree with legacy inclusion decisions`() {
        val base = listOf("8.8.8.0/24", "2606:4700::/120").map(IpSubnet::parse)
        val holes = listOf("8.8.8.16/28", "8.8.8.128/25", "2606:4700::80/121").map(IpSubnet::parse)
        val nativeExcluded = BypassRoutePlanner.intersection(base, holes)
        val legacy = BypassRoutePlanner.routes(base, holes)
        for (value in 0..255) {
            for (address in listOf("8.8.8.$value", "2606:4700::${value.toString(16)}")) {
                val nativeIncluded = contains(base, address) && !contains(nativeExcluded, address)
                assertEquals(nativeIncluded, contains(legacy, address))
            }
        }
    }
    @Test fun `stored settings keep independent toggles and country downloads across restart`() {
        val original = BypassConfig(false, setOf("ir"), true,
            listOf(CountryRoutes("IR", listOf(IpSubnet.parse("5.160.0.0/16"), IpSubnet.parse("2a00:5c0::/32")), 1234)))
        assertEquals(original, BypassStore.decode(BypassStore.encode(original)))
        assertTrue(original.activeSuffixes.isEmpty())
        assertEquals(2, original.countryPrefixes.size)
        assertTrue(original.copy(countriesEnabled = false).countryPrefixes.isEmpty())
    }
    @Test fun `rejects bad country data without accepting HTML empty responses or wrong address family`() {
        for (text in listOf("", "<html>error</html>", "0.0.0.0/0", "example.ir/24", "::/0")) {
            assertThrows(Exception::class.java) { IpdenyCountrySource.parseList(text, 32) }
        }
        assertEquals(1, IpdenyCountrySource.parseList("5.160.0.0/16\n", 32).size)
        assertThrows(Exception::class.java) { BypassStore.decode("{\"version\":2}") }
    }
    companion object {
        fun contains(routes: List<IpSubnet>, value: String): Boolean {
            val bytes = InetAddress.getByName(value).address
            val number = BigInteger(1, bytes)
            return routes.any { it.bits == bytes.size * 8 && number >= it.start && number <= it.end }
        }
    }
}
