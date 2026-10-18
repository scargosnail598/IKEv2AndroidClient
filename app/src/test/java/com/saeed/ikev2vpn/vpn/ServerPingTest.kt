package com.saeed.ikev2vpn.vpn

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerPingTest {
    @Test fun `echo replies preserve fractional and less-than timings`() {
        assertEquals(PingSample(42.8), parsePingReply("64 bytes from 192.0.2.1: icmp_seq=1 ttl=54 time=42.8 ms"))
        assertEquals(PingSample(0.25), parsePingReply("time=0.25 ms"))
        assertEquals(PingSample(1.0, true), parsePingReply("time<1 ms"))
        for (text in listOf("100% packet loss", "time=-3 ms", "time=NaN ms", "time=Infinity ms", "rtt min/avg/max/mdev = 1/2/3/4 ms"))
            assertNull(parsePingReply(text))
    }
    private fun process(success: Boolean = true, exit: Int = 0): Process = mock(Process::class.java).also {
        `when`(it.waitFor(4, TimeUnit.SECONDS)).thenReturn(success)
        `when`(it.exitValue()).thenReturn(exit)
        `when`(it.inputStream).thenReturn(ByteArrayInputStream("64 bytes from 192.0.2.1: time=42.8 ms\n".toByteArray()))
    }
    @Test fun `the command is one bounded probe with an ASCII host and no shell`() = runTest {
        val child = process()
        var arguments = emptyList<String>()
        val probe = AndroidServerPingProbe { arguments = it; child }
        assertEquals(PingSample(42.8), probe.measure("bücher.example"))
        assertEquals(listOf("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-w", "3", "xn--bcher-kva.example"), arguments)
        verify(child).destroyForcibly()
    }
    @Test fun `invalid targets never start a process`() = runTest {
        val probe = AndroidServerPingProbe { error("Must not execute an invalid target") }
        for (host in listOf("-I", "vpn.example;id", "https://vpn.example", "vpn.example\n", "1.2.3.999", ""))
            assertNull(probe.measure(host))
    }
    @Test fun `timeouts and nonzero exits are unavailable even with misleading output`() = runTest {
        for (child in listOf(process(success = false), process(exit = 1))) {
            assertNull(AndroidServerPingProbe { child }.measure("vpn.example"))
            verify(child).destroyForcibly()
        }
        assertNull(AndroidServerPingProbe { throw IOException("ping unavailable") }.measure("vpn.example"))
    }
    @Test(timeout = 10000) fun `cancelling a blocked probe kills the child promptly`() = runTest {
        val child = process()
        val started = CountDownLatch(1)
        `when`(child.waitFor(4, TimeUnit.SECONDS)).thenAnswer {
            started.countDown()
            CountDownLatch(1).await()
            true
        }
        val job = launch(Dispatchers.Default) { AndroidServerPingProbe { child }.measure("vpn.example") }
        try { withContext(Dispatchers.IO) { assertTrue(started.await(2, TimeUnit.SECONDS)) } }
        finally { job.cancelAndJoin() }
        verify(child).destroyForcibly()
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `each request finishes after exactly one echo without periodic sampling`() = runTest {
        var calls = 0
        val monitor = ServerPingMonitor { host ->
            assertEquals("vpn.example", host)
            if (calls++ == 0) PingSample(21.0) else null
        }
        assertEquals(listOf(ServerPingReading.Checking, ServerPingReading.Measured(PingSample(21.0))),
            monitor.readings("vpn.example").toList())
        testScheduler.advanceTimeBy(60_000)
        assertEquals(1, calls)
        assertEquals(listOf(ServerPingReading.Checking, ServerPingReading.Unavailable),
            monitor.readings("vpn.example").toList())
        assertEquals(2, calls)
    }
    @Test fun `stopping sampling cancels the probe without publishing a false failure`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val readings = mutableListOf<ServerPingReading>()
        val monitor = ServerPingMonitor {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val job = launch { monitor.readings("vpn.example").toList(readings) }
        started.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(listOf(ServerPingReading.Checking), readings)
    }
    @Test fun `a new target starts with checking and never inherits an old reading`() = runTest {
        val calls = mutableListOf<String>()
        val monitor = ServerPingMonitor { host -> calls += host; PingSample(if (host == "a.example") 10.0 else 30.0) }
        for (host in listOf("a.example", "b.example")) {
            val readings = monitor.readings(host).take(2).toList()
            assertEquals(ServerPingReading.Checking, readings.first())
            assertEquals(ServerPingReading.Measured(PingSample(if (host == "a.example") 10.0 else 30.0)), readings.last())
        }
        assertEquals(listOf("a.example", "b.example"), calls)
    }
}
