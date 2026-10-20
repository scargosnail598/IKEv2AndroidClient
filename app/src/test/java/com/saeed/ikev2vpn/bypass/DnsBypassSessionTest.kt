package com.saeed.ikev2vpn.bypass

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.Random

class DnsBypassSessionTest {
    private var now = 0L
    private fun session() = DnsBypassSession(BypassConfig(domainsEnabled = true, suffixes = setOf("ir"))) { now }
    @Test fun `observed suffix query adds only its answer and expires at TTL`() {
        val session = session()
        session.observe(DnsWire.packet(false), true)
        session.observe(DnsWire.packet(true, ttl = 5), false)
        assertTrue(BypassRulesTest.contains(session.prefixes(), "8.8.4.4"))
        now = 4999; session.observe(null, false); assertEquals(1, session.prefixes().size)
        now = 5000; session.observe(null, false); assertTrue(session.prefixes().isEmpty())
    }
    @Test fun `unsolicited mismatched transaction flow question and unrelated suffix cannot bypass`() {
        val session = session()
        session.observe(DnsWire.packet(true), false)
        assertTrue(session.prefixes().isEmpty())
        session.observe(DnsWire.packet(false), true)
        for (packet in listOf(DnsWire.packet(true, id = 999), DnsWire.packet(true, port = 50001),
            DnsWire.packet(true, name = "example.com"), DnsWire.packet(true, server = "1.1.1.1"))) {
            session.observe(packet, false)
            assertTrue(session.prefixes().isEmpty())
        }
        session.observe(DnsWire.packet(true), false)
        assertEquals(1, session.prefixes().size)
        val other = session()
        other.observe(DnsWire.packet(false, name = "example.ir.evil.com"), true)
        other.observe(DnsWire.packet(true, name = "example.ir.evil.com"), false)
        assertTrue(other.prefixes().isEmpty())
    }
    @Test fun `supports compressed CNAME chains but ignores unrelated answer addresses`() {
        val session = session()
        session.observe(DnsWire.packet(false), true)
        session.observe(DnsWire.packet(true, cname = "edge.cdn.com", unrelated = "9.9.9.9"), false)
        assertTrue(BypassRulesTest.contains(session.prefixes(), "8.8.4.4"))
        assertFalse(BypassRulesTest.contains(session.prefixes(), "9.9.9.9"))
    }
    @Test fun `IPv6 AAAA answers get host routes and expire with their CNAME chain`() {
        val session = session()
        session.observe(DnsWire.packet(false, ip6 = true), true)
        session.observe(DnsWire.packet(true, ip6 = true, address = "2606:4700::1111", cname = "cdn.example.net", ttl = 90), false)
        assertTrue(BypassRulesTest.contains(session.prefixes(), "2606:4700::1111"))
        now = 10_000; session.observe(null, false) // CNAME TTL is 10 seconds.
        assertTrue(session.prefixes().isEmpty())
    }
    @Test fun `TTL zero private addresses error and truncated responses cannot create routes`() {
        for (response in listOf(DnsWire.packet(true, ttl = 0), DnsWire.packet(true, address = "127.0.0.1"),
            DnsWire.packet(true, address = "192.168.1.1"), DnsWire.packet(true, flags = 0x8183), DnsWire.packet(true, flags = 0x8380))) {
            val session = session(); session.observe(DnsWire.packet(false), true); session.observe(response, false)
            assertTrue(session.prefixes().isEmpty())
        }
    }
    @Test fun `long TTL is capped pending queries expire and reconnect creates no learned state`() {
        val session = session()
        session.observe(DnsWire.packet(false), true); now = 15_000
        session.observe(DnsWire.packet(true), false); assertTrue(session.prefixes().isEmpty())
        session.observe(DnsWire.packet(false), true); session.observe(DnsWire.packet(true, ttl = 86400), false)
        now += 600_000; session.observe(null, false); assertTrue(session.prefixes().isEmpty())
        assertTrue(session().prefixes().isEmpty())
    }
    @Test fun `route capacity stays bounded and new untracked hosts remain in VPN`() {
        val session = session()
        for (i in 1..129) {
            session.observe(DnsWire.packet(false, id = i), true)
            session.observe(DnsWire.packet(true, id = i, address = "8.9.0.$i"), false)
        }
        assertEquals(128L, session.prefixes().sumOf { 1L shl (it.bits - it.prefix) })
        assertFalse(BypassRulesTest.contains(session.prefixes(), "8.9.0.129"))
    }
    @Test fun `country covered DNS answers do not fill the learned address cache`() {
        BypassRuntime.clear()
        val session = DnsBypassSession(BypassConfig(true, setOf("ir"), true,
            listOf(CountryRoutes("IR", listOf(IpSubnet.parse("8.9.0.0/16")), 1))))
        for (id in 1..200) {
            session.observe(DnsWire.packet(false, id = id), true)
            session.observe(DnsWire.packet(true, id = id, address = "8.9.0.$id"), false)
        }
        assertEquals(listOf(IpSubnet.parse("8.9.0.0/16")), session.prefixes())
        assertNull(BypassRuntime.issue.value)
    }
    @Test fun `clearing a session discards learned addresses and pending DNS queries`() {
        val session = session()
        session.observe(DnsWire.packet(false), true); session.observe(DnsWire.packet(true), false)
        session.observe(DnsWire.packet(false, id = 456), true)
        session.clear()
        session.observe(DnsWire.packet(true, id = 456), false)
        assertTrue(session.prefixes().isEmpty())
    }
    @Test fun `malformed packets fragments pointer loops and random data are bounded and ignored`() {
        val session = session()
        session.observe(DnsWire.packet(false), true)
        val fragmented = DnsWire.packet(true).also { it[6] = 0x20 }
        val pointerLoop = DnsWire.packet(true).also { it[40] = 0xc0.toByte(); it[41] = 12 }
        session.observe(fragmented, false); session.observe(pointerLoop, false)
        val random = Random(22)
        repeat(3000) { session.observe(ByteArray(random.nextInt(512)).also(random::nextBytes), false) }
        assertTrue(session.prefixes().isEmpty())
    }
}

internal object DnsWire {
    fun packet(response: Boolean, name: String = "example.ir", address: String = "8.8.4.4", ttl: Int = 30,
        id: Int = 123, port: Int = 50000, server: String = "1.0.0.1", ip6: Boolean = false,
        cname: String? = null, unrelated: String? = null, flags: Int = if (response) 0x8180 else 0x0100): ByteArray {
        fun encodedName(value: String): ByteArray = ByteArrayOutputStream().also { out ->
            value.split('.').forEach { label -> out.write(label.length); out.write(label.toByteArray()) }; out.write(0)
        }.toByteArray()
        val dns = ByteArrayOutputStream()
        val data = DataOutputStream(dns)
        data.writeShort(id); data.writeShort(flags); data.writeShort(1)
        data.writeShort(if (response) 1 + (if (cname != null) 1 else 0) + (if (unrelated != null) 1 else 0) else 0)
        data.writeShort(0); data.writeShort(0)
        data.write(encodedName(name)); data.writeShort(if (ip6) 28 else 1); data.writeShort(1)
        fun record(owner: ByteArray, kind: Int, seconds: Int, bytes: ByteArray) {
            data.write(owner); data.writeShort(kind); data.writeShort(1); data.writeInt(seconds); data.writeShort(bytes.size); data.write(bytes)
        }
        if (response) {
            if (cname != null) record(byteArrayOf(0xc0.toByte(), 12), 5, 10, encodedName(cname))
            record(if (cname == null) byteArrayOf(0xc0.toByte(), 12) else encodedName(cname), if (ip6) 28 else 1,
                ttl, InetAddress.getByName(if (ip6 && address == "8.8.4.4") "2606:4700::1111" else address).address)
            if (unrelated != null) record(encodedName("unrelated.com"), 1, ttl, InetAddress.getByName(unrelated).address)
        }
        val body = dns.toByteArray()
        val total = body.size + 8 + if (ip6) 40 else 20
        val raw = ByteArray(total)
        fun short(at: Int, value: Int) { raw[at] = (value ushr 8).toByte(); raw[at + 1] = value.toByte() }
        val clientBytes = InetAddress.getByName(if (ip6) "2001:db8::1" else "10.0.0.2").address
        val serverBytes = InetAddress.getByName(if (ip6) "2001:4860:4860::8888" else server).address
        val offset: Int
        if (ip6) {
            raw[0] = 0x60; short(4, total - 40); raw[6] = 17; raw[7] = 64
            (if (response) serverBytes else clientBytes).copyInto(raw, 8)
            (if (response) clientBytes else serverBytes).copyInto(raw, 24)
            offset = 40
        } else {
            raw[0] = 0x45; short(2, total); raw[8] = 64; raw[9] = 17
            (if (response) serverBytes else clientBytes).copyInto(raw, 12)
            (if (response) clientBytes else serverBytes).copyInto(raw, 16)
            offset = 20
        }
        short(offset, if (response) 53 else port); short(offset + 2, if (response) port else 53)
        short(offset + 4, body.size + 8); body.copyInto(raw, offset + 8)
        return raw
    }
}
