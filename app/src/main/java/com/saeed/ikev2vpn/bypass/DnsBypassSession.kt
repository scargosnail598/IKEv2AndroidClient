package com.saeed.ikev2vpn.bypass

import java.net.InetAddress
import java.util.Locale

/** DNS answers are accepted only for an observed query on the same UDP flow. No browsing log is retained. */
class DnsBypassSession(private val config: BypassConfig, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Key(val client: String, val server: String, val port: Int, val id: Int, val name: String, val type: Int)
    private val pending = LinkedHashMap<Key, Long>()
    private val learned = LinkedHashMap<String, Pair<IpSubnet, Long>>()
    private val countryPrefixes = BypassRoutePlanner.normalized(config.countryPrefixes)
    private val countryBounds = countryPrefixes.groupBy { it.bits }.mapValues { (_, ranges) -> ranges.map { it.start to it.end } }
    private var prefixCache: List<IpSubnet>? = countryPrefixes
    val enabled: Boolean get() = config.activeSuffixes.isNotEmpty()

    @Synchronized fun observe(raw: ByteArray?, outbound: Boolean) {
        val now = clock()
        pending.entries.removeAll { it.value <= now }
        if (learned.entries.removeAll { it.value.second <= now }) prefixCache = null
        if (raw == null || !enabled) return
        val packet = DnsPacket.parse(raw) ?: return
        val key = Key(if (outbound) packet.source else packet.destination,
            if (outbound) packet.destination else packet.source,
            if (outbound) packet.sourcePort else packet.destinationPort,
            packet.id, packet.name, packet.type)
        if (outbound) {
            if (packet.response || packet.destinationPort != 53 || !DomainSuffixes.matches(packet.name, config.activeSuffixes)) return
            if (pending.size >= 512) pending.remove(pending.keys.first())
            pending[key] = now + 15_000
        } else {
            if (!packet.response || packet.sourcePort != 53 || pending.remove(key) == null) return
            for ((subnet, ttl) in packet.answers) {
                if (!publicAddress(subnet.address) || ttl == 0L || coveredByCountry(subnet)) continue
                val address = subnet.toString()
                // At capacity leave new destinations in the VPN; never evict an active bypass unexpectedly.
                if (address !in learned && learned.size >= 128) { BypassRuntime.reportCapacity(); continue }
                val expires = now + minOf(ttl, 600L) * 1000
                if (address !in learned) prefixCache = null
                learned[address] = subnet to maxOf(expires, learned[address]?.second ?: 0)
            }
        }
    }
    @Synchronized fun prefixes(): List<IpSubnet> {
        return prefixCache ?: BypassRoutePlanner.normalized(countryPrefixes + learned.values.map { it.first })
            .also { prefixCache = it }
    }

    @Synchronized fun clear() { pending.clear(); learned.clear(); prefixCache = countryPrefixes }
    private fun coveredByCountry(host: IpSubnet): Boolean {
        val ranges = countryBounds[host.bits] ?: return false
        val address = host.start
        var low = 0; var high = ranges.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            when {
                address < range.first -> high = middle - 1
                address > range.second -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
    companion object {
        private val reservedV4 = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3").map(IpSubnet::parse)
        private fun publicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size == 16) return (bytes[0].toInt() and 0xe0) == 0x20 &&
                !(bytes[0] == 0x20.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte())
            val subnet = IpSubnet(address, 32)
            return reservedV4.none { subnet.start >= it.start && subnet.start <= it.end }
        }
    }
}

/** Bounded parser for complete, unfragmented UDP DNS in IPv4/IPv6. Other traffic stays in the tunnel. */
internal class DnsPacket(
    val source: String, val destination: String, val sourcePort: Int, val destinationPort: Int,
    val id: Int, val name: String, val type: Int, val response: Boolean,
    val answers: List<Pair<IpSubnet, Long>>,
) {
    companion object {
        fun parse(raw: ByteArray): DnsPacket? = try { decode(raw) } catch (_: IllegalArgumentException) { null }
            catch (_: IndexOutOfBoundsException) { null }
        private fun decode(raw: ByteArray): DnsPacket {
            require(raw.size in 28..65535)
            fun u16(at: Int) = ((raw[at].toInt() and 255) shl 8) or (raw[at + 1].toInt() and 255)
            val version = (raw[0].toInt() and 255) ushr 4
            val offset: Int
            val source: ByteArray
            val destination: ByteArray
            if (version == 4) {
                offset = (raw[0].toInt() and 15) * 4
                require(offset >= 20 && raw.size >= offset + 8 && u16(2) == raw.size && u16(6) and 0x3fff == 0 && raw[9].toInt() == 17)
                source = raw.copyOfRange(12, 16); destination = raw.copyOfRange(16, 20)
            } else {
                require(version == 6 && raw.size >= 48 && raw[6].toInt() == 17 && u16(4) + 40 == raw.size)
                offset = 40; source = raw.copyOfRange(8, 24); destination = raw.copyOfRange(24, 40)
            }
            require(u16(offset + 4) == raw.size - offset)
            val sourcePort = u16(offset); val destinationPort = u16(offset + 2)
            require(sourcePort == 53 || destinationPort == 53)
            val dns = raw.copyOfRange(offset + 8, raw.size)
            val reader = Reader(dns)
            require(dns.size >= 12)
            val flags = reader.u16(2)
            // Standard single-question, non-truncated, successful queries/responses only.
            require(flags and 0x7a0f == 0 && reader.u16(4) == 1)
            val response = flags and 0x8000 != 0
            val (name, afterName) = reader.name(12)
            val type = reader.u16(afterName)
            require(type in setOf(1, 28) && reader.u16(afterName + 2) == 1)
            var cursor = afterName + 4
            val count = reader.u16(6)
            require(count <= 128)
            data class Record(val owner: String, val type: Int, val ttl: Long, val alias: String?, val address: ByteArray?)
            val records = mutableListOf<Record>()
            repeat(count) {
                val (owner, afterOwner) = reader.name(cursor)
                val kind = reader.u16(afterOwner)
                val klass = reader.u16(afterOwner + 2)
                val ttl = reader.u32(afterOwner + 4)
                val size = reader.u16(afterOwner + 8)
                val data = afterOwner + 10
                require(data + size <= dns.size)
                if (klass == 1) {
                    val alias = if (kind == 5) reader.name(data).also { require(it.second == data + size) }.first else null
                    val address = if (kind == 1 && size == 4 || kind == 28 && size == 16) dns.copyOfRange(data, data + size) else null
                    records += Record(owner, kind, ttl, alias, address)
                }
                cursor = data + size
            }
            val allowed = mutableMapOf(name to Long.MAX_VALUE)
            repeat(16) {
                for (record in records) {
                    val chainTtl = allowed[record.owner] ?: continue
                    record.alias?.let { alias -> allowed[alias] = minOf(allowed[alias] ?: Long.MAX_VALUE, chainTtl, record.ttl) }
                }
            }
            val answers = records.mapNotNull { record ->
                val chainTtl = allowed[record.owner] ?: return@mapNotNull null
                val bytes = record.address ?: return@mapNotNull null
                if (record.type != type) return@mapNotNull null
                IpSubnet(InetAddress.getByAddress(bytes), bytes.size * 8) to minOf(chainTtl, record.ttl)
            }
            return DnsPacket(source.joinToString(":"), destination.joinToString(":"), sourcePort, destinationPort,
                reader.u16(0), name, type, response, answers)
        }
    }
    private class Reader(private val bytes: ByteArray) {
        fun u16(at: Int): Int = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
        fun u32(at: Int): Long = ((u16(at).toLong() shl 16) or u16(at + 2).toLong())
        fun name(start: Int): Pair<String, Int> {
            var cursor = start
            var after = -1
            val labels = mutableListOf<String>()
            val visited = mutableSetOf<Int>()
            var length = 0
            while (true) {
                require(visited.add(cursor) && visited.size <= 128)
                val size = bytes[cursor].toInt() and 255
                if (size and 0xc0 == 0xc0) {
                    if (after < 0) after = cursor + 2
                    cursor = ((size and 63) shl 8) or (bytes[cursor + 1].toInt() and 255)
                    continue
                }
                require(size <= 63)
                cursor++
                if (size == 0) return labels.joinToString(".") to if (after < 0) cursor else after
                require(cursor + size <= bytes.size)
                val label = bytes.copyOfRange(cursor, cursor + size)
                require(label.all { val ch = it.toInt() and 255; ch in 33..126 && ch != 46 })
                labels += String(label, Charsets.US_ASCII).lowercase(Locale.ROOT)
                length += size + 1
                require(length <= 254)
                cursor += size
            }
        }
    }
}
