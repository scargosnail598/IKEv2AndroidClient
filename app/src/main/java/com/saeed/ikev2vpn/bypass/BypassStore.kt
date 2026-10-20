package com.saeed.ikev2vpn.bypass

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One atomic snapshot keeps selected countries and their downloaded routes consistent. */
interface BypassRepository {
    fun load(): BypassConfig
    fun save(config: BypassConfig)
}

class BypassStore(context: Context) : BypassRepository {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "traffic-bypass.json"))
    override fun load(): BypassConfig = synchronized(LOCK) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized BypassConfig()
        file.openRead().use { input ->
            val bytes = readBounded(input, MAX_BYTES)
            require(bytes.size <= MAX_BYTES) { "Saved bypass rules are too large." }
            decode(String(bytes, Charsets.UTF_8))
        }
    }
    override fun save(config: BypassConfig) = synchronized(LOCK) {
        val json = encode(config)
        decode(json) // Validate the same invariants used after a process restart.
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Too many country routes. Select fewer countries." }
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (exception: Exception) {
            file.failWrite(stream)
            throw exception
        }
    }
    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 2 * 1024 * 1024
        fun encode(config: BypassConfig): String = JSONObject()
            .put("version", 1).put("domainsEnabled", config.domainsEnabled)
            .put("suffixes", JSONArray(config.suffixes.sorted()))
            .put("countriesEnabled", config.countriesEnabled)
            .put("countries", JSONArray(config.countries.map { country ->
                JSONObject().put("code", country.code).put("downloadedAt", country.downloadedAt)
                    .put("prefixes", JSONArray(country.prefixes.map(IpSubnet::toString)))
            })).toString()
        fun decode(text: String): BypassConfig {
            require(text.length <= MAX_BYTES)
            val json = JSONObject(text)
            require(json.getInt("version") == 1)
            val suffixes = json.getJSONArray("suffixes")
            require(suffixes.length() <= 64)
            val parsed = DomainSuffixes.parse((0 until suffixes.length()).joinToString(" ") { suffixes.getString(it) })
            val countries = json.getJSONArray("countries")
            require(countries.length() <= 16) { "Select at most 16 countries." }
            var total = 0
            val selected = (0 until countries.length()).map { index ->
                val item = countries.getJSONObject(index)
                val code = item.getString("code")
                require(code in Locale.getISOCountries().toSet())
                val entries = item.getJSONArray("prefixes")
                total += entries.length()
                require(total <= 50_000) { "Too many country prefixes." }
                val prefixes = (0 until entries.length()).map {
                    IpSubnet.parse(entries.getString(it)).also { subnet ->
                        require(subnet.prefix >= if (subnet.bits == 32) 4 else 12)
                    }
                }
                require(prefixes.isNotEmpty())
                CountryRoutes(code, prefixes, item.getLong("downloadedAt"))
            }
            require(selected.map { it.code }.distinct().size == selected.size)
            return BypassConfig(json.getBoolean("domainsEnabled"), parsed, json.getBoolean("countriesEnabled"), selected)
        }
        @JvmStatic fun loadForTunnel(context: Context): BypassConfig = try {
            BypassStore(context).load()
        } catch (_: Exception) {
            BypassRuntime.reportSettingsFailure()
            BypassConfig() // Fail closed: keep destinations in the VPN if the saved rules are unreadable.
        }
    }
}

fun interface CountryRouteSource { fun download(code: String): CountryRoutes }

class IpdenyCountrySource : CountryRouteSource {
    override fun download(code: String): CountryRoutes {
        require(code in Locale.getISOCountries().toSet()) { "Select a valid country." }
        val lower = code.lowercase(Locale.ROOT)
        val ipv4 = fetch("https://www.ipdeny.com/ipblocks/data/aggregated/$lower-aggregated.zone", 32)
        Thread.sleep(500) // Respect IPdeny's suggested interval between requests.
        val ipv6 = fetch("https://www.ipdeny.com/ipv6/ipaddresses/aggregated/$lower-aggregated.zone", 128)
        require(ipv4.isNotEmpty() || ipv6.isNotEmpty()) { "No IP list is available for this country." }
        return CountryRoutes(code, BypassRoutePlanner.normalized(ipv4 + ipv6), System.currentTimeMillis())
    }
    private fun fetch(address: String, bits: Int): List<IpSubnet> {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "IKEv2AndroidClient-CountryRoutes")
            val status = connection.responseCode
            if (status == 404) return emptyList()
            check(status == 200) { "Country IP download failed. Try again later." }
            require(connection.contentLengthLong <= 2 * 1024 * 1024) { "The country IP list is too large." }
            val bytes = connection.inputStream.use { readBounded(it, 2 * 1024 * 1024) }
            require(bytes.size <= 2 * 1024 * 1024) { "The country IP list is too large." }
            return parseList(String(bytes, Charsets.US_ASCII), bits)
        } finally { connection.disconnect() }
    }
    companion object {
        fun parseList(text: String, bits: Int): List<IpSubnet> {
            require(text.length <= 2 * 1024 * 1024)
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(lines.size in 1..50_000) { "The country IP list is empty or too large." }
            return lines.map {
                val subnet = IpSubnet.parse(it)
                require(subnet.bits == bits && subnet.prefix >= if (bits == 32) 4 else 12) { "The country IP list contains invalid ranges." }
                subnet
            }
        }
    }
}

private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(output.size() + count <= maximum) { "The bypass data is too large." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
