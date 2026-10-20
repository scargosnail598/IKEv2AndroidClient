package com.saeed.ikev2vpn.bypass

import java.net.IDN
import java.net.InetAddress
import java.math.BigInteger
import java.util.Locale

/** Numeric addresses only: parsing a routing rule must never perform DNS. */
data class IpSubnet(val address: InetAddress, val prefix: Int) {
    val bits: Int get() = address.address.size * 8
    val start: BigInteger get() = BigInteger(1, address.address).shiftRight(bits - prefix).shiftLeft(bits - prefix)
    val end: BigInteger get() = start.add(BigInteger.ONE.shiftLeft(bits - prefix)).subtract(BigInteger.ONE)
    override fun toString(): String = "${address.hostAddress}/$prefix"

    companion object {
        fun parse(text: String): IpSubnet {
            val parts = text.split('/')
            require(parts.size == 2) { "Use numeric CIDR ranges." }
            val host = parts[0]
            val bytes = if (':' in host) {
                require(host.length <= 39 && host.all { it in "0123456789abcdefABCDEF:" })
                InetAddress.getByName(host).address.also { require(it.size == 16) }
            } else {
                val octets = host.split('.')
                require(octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 })
                octets.map { it.toInt().toByte() }.toByteArray()
            }
            val prefix = parts[1].toIntOrNull() ?: error("Invalid CIDR prefix.")
            require(prefix in 0..bytes.size * 8)
            val value = BigInteger(1, bytes).shiftRight(bytes.size * 8 - prefix).shiftLeft(bytes.size * 8 - prefix)
            return from(value, bytes.size * 8, prefix)
        }
        fun from(value: BigInteger, bits: Int, prefix: Int): IpSubnet {
            val raw = value.toByteArray()
            val bytes = ByteArray(bits / 8)
            val count = minOf(raw.size, bytes.size)
            raw.copyInto(bytes, bytes.size - count, raw.size - count)
            return IpSubnet(InetAddress.getByAddress(bytes), prefix)
        }
    }
}

object DomainSuffixes {
    fun parse(text: String): Set<String> {
        require(text.length <= 4096) { "Too many domain suffixes." }
        val rules = text.split(Regex("[\\s,;]+" )).filter(String::isNotBlank).map { raw ->
            val value = (if (raw.startsWith("*.")) raw.substring(2) else raw.removePrefix(".")).removeSuffix(".")
            val ascii = try { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("Enter suffixes such as .ir or .co.uk, without URLs or paths.") }
            require(ascii.length in 2..253 && ascii.split('.').all { it.length in 1..63 && LABEL.matches(it) } && ascii.any { it in 'a'..'z' }) {
                "Enter suffixes such as .ir or .co.uk, without URLs or paths."
            }
            ascii
        }.toSet()
        require(rules.size <= 64) { "Use at most 64 domain suffixes." }
        return rules
    }
    fun matches(host: String, suffixes: Set<String>): Boolean {
        val name = host.removeSuffix(".").lowercase(Locale.ROOT)
        return suffixes.any { name == it || name.endsWith(".$it") }
    }
    private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
}

/** Interval subtraction avoids the quadratic route-set operations on large country lists. */
object BypassRoutePlanner {
    const val MAX_ROUTES = 8192
    private data class Span(val first: BigInteger, val last: BigInteger)
    private fun merge(subnets: List<IpSubnet>, bits: Int): List<Span> {
        val result = mutableListOf<Span>()
        for (span in subnets.filter { it.bits == bits }.map { Span(it.start, it.end) }.sortedBy { it.first }) {
            val previous = result.lastOrNull()
            if (previous != null && span.first <= previous.last.add(BigInteger.ONE)) {
                result[result.lastIndex] = Span(previous.first, maxOf(previous.last, span.last))
            } else result += span
        }
        return result
    }
    fun routes(base: List<IpSubnet>, excluded: List<IpSubnet>, limit: Int = MAX_ROUTES): List<IpSubnet> {
        val result = mutableListOf<IpSubnet>()
        for (bits in listOf(32, 128)) {
            val holes = merge(excluded, bits)
            var hole = 0
            for (span in merge(base, bits)) {
                var cursor = span.first
                while (hole < holes.size && holes[hole].last < cursor) hole++
                var index = hole
                while (index < holes.size && holes[index].first <= span.last) {
                    if (holes[index].first > cursor) cover(cursor, holes[index].first.subtract(BigInteger.ONE), bits, result, limit)
                    cursor = maxOf(cursor, holes[index].last.add(BigInteger.ONE))
                    if (cursor > span.last) break
                    index++
                }
                if (cursor <= span.last) cover(cursor, span.last, bits, result, limit)
            }
        }
        return result
    }
    fun intersection(base: List<IpSubnet>, excluded: List<IpSubnet>, limit: Int = MAX_ROUTES): List<IpSubnet> {
        val result = mutableListOf<IpSubnet>()
        for (bits in listOf(32, 128)) {
            val left = merge(base, bits)
            val right = merge(excluded, bits)
            var i = 0; var j = 0
            while (i < left.size && j < right.size) {
                val first = maxOf(left[i].first, right[j].first)
                val last = minOf(left[i].last, right[j].last)
                if (first <= last) cover(first, last, bits, result, limit)
                if (left[i].last < right[j].last) i++ else j++
            }
        }
        return result
    }
    fun normalized(subnets: List<IpSubnet>): List<IpSubnet> = routes(subnets, emptyList(), 100_000)
    private fun cover(first: BigInteger, last: BigInteger, bits: Int, output: MutableList<IpSubnet>, limit: Int) {
        var cursor = first
        while (cursor <= last) {
            val aligned = if (cursor.signum() == 0) bits else cursor.lowestSetBit
            val length = minOf(aligned, last.subtract(cursor).add(BigInteger.ONE).bitLength() - 1)
            require(output.size < limit) { "These bypass rules exceed this device's route limit. Select fewer countries or suffixes." }
            output += IpSubnet.from(cursor, bits, bits - length)
            cursor = cursor.add(BigInteger.ONE.shiftLeft(length))
        }
    }
}

data class CountryRoutes(val code: String, val prefixes: List<IpSubnet>, val downloadedAt: Long)
data class BypassConfig(
    val domainsEnabled: Boolean = false,
    val suffixes: Set<String> = emptySet(),
    val countriesEnabled: Boolean = false,
    val countries: List<CountryRoutes> = emptyList(),
) {
    val activeSuffixes: Set<String> get() = if (domainsEnabled) suffixes else emptySet()
    val countryPrefixes: List<IpSubnet> get() = if (countriesEnabled) countries.flatMap { it.prefixes } else emptyList()
}
