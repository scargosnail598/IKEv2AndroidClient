package com.saeed.ikev2vpn.vpn

import com.saeed.ikev2vpn.validation.ProfileValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.net.IDN
import java.util.concurrent.TimeUnit

/** An ICMP echo RTT to the selected profile's server, not a tunnel-health verdict. */
data class PingSample(val milliseconds: Double, val lessThan: Boolean = false) {
    init { require(milliseconds.isFinite() && milliseconds >= 0) }
}

sealed interface ServerPingReading {
    data object Inactive : ServerPingReading
    data object Checking : ServerPingReading
    data object Unavailable : ServerPingReading
    data class Measured(val sample: PingSample) : ServerPingReading
}

fun interface ServerPingProbe { suspend fun measure(host: String): PingSample? }

class ServerPingMonitor(private val probe: ServerPingProbe) {
    fun readings(host: String): Flow<ServerPingReading> = flow {
        emit(ServerPingReading.Checking)
        val result = try { probe.measure(host) }
        catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { null }
        emit(result?.let(ServerPingReading::Measured) ?: ServerPingReading.Unavailable)
    }
}

/** One echo, bounded DNS/process lifetime, no shell, no root or extra permissions. */
class AndroidServerPingProbe(
    private val startProcess: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
    },
) : ServerPingProbe {
    override suspend fun measure(host: String): PingSample? = runInterruptible(Dispatchers.IO) {
        if (!ProfileValidator.isValidServerAddress(host)) return@runInterruptible null
        val target = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        val process = try {
            startProcess(listOf("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-w", "3", target))
        } catch (_: java.io.IOException) { return@runInterruptible null }
        catch (_: SecurityException) { return@runInterruptible null }
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS) || process.exitValue() != 0) return@runInterruptible null
            parsePingReply(readOutput(process.inputStream))
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
    private fun readOutput(input: InputStream): String {
        val bytes = ByteArray(4096)
        var total = 0
        while (total < bytes.size) {
            val count = input.read(bytes, total, bytes.size - total)
            if (count < 0) break
            if (count > 0) total += count
        }
        return String(bytes, 0, total, Charsets.UTF_8)
    }
}

internal fun parsePingReply(output: String): PingSample? {
    val match = Regex("\\btime\\s*([=<])\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms\\b").find(output) ?: return null
    val milliseconds = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
    return PingSample(milliseconds, match.groupValues[1] == "<")
}
