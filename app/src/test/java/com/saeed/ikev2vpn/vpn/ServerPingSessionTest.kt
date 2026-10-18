package com.saeed.ikev2vpn.vpn

import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerPingSessionTest {
    private val target = ServerPingTarget("profile", "vpn.example", "session", AppRoutingConfig())

    @Test fun `initial result persists until an explicit refresh with no idle probes`() = runTest {
        var calls = 0
        val session = ServerPingSession(backgroundScope) { PingSample((++calls * 10).toDouble()) }
        session.refresh()
        assertEquals(0, calls)
        session.setTarget(target)
        runCurrent()
        assertEquals(ServerPingReading.Measured(PingSample(10.0)), session.reading.value)
        repeat(3) {
            session.setTarget(target.copy())
            testScheduler.advanceTimeBy(60_000)
            runCurrent()
        }
        assertEquals(1, calls)
        assertEquals(ServerPingReading.Measured(PingSample(10.0)), session.reading.value)
        session.refresh()
        assertEquals(ServerPingReading.Checking, session.reading.value)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ServerPingReading.Measured(PingSample(20.0)), session.reading.value)
    }

    @Test fun `rapid taps do not start overlapping probes`() = runTest {
        var calls = 0
        val result = CompletableDeferred<PingSample>()
        val session = ServerPingSession(backgroundScope) { calls++; result.await() }
        session.setTarget(target)
        repeat(10) { session.refresh() }
        runCurrent()
        repeat(10) { session.refresh() }
        assertEquals(1, calls)
        result.complete(PingSample(15.0))
        runCurrent()
        assertEquals(ServerPingReading.Measured(PingSample(15.0)), session.reading.value)
    }

    @Test fun `unavailable results persist and retry only on request`() = runTest {
        var calls = 0
        val session = ServerPingSession(backgroundScope) {
            when (++calls) {
                1 -> PingSample(10.0)
                2 -> throw IllegalStateException("Probe failed")
                else -> PingSample(30.0)
            }
        }
        session.setTarget(target)
        runCurrent()
        session.refresh()
        runCurrent()
        assertEquals(ServerPingReading.Unavailable, session.reading.value)
        session.setTarget(target)
        testScheduler.advanceTimeBy(60_000)
        assertEquals(2, calls)
        session.refresh()
        runCurrent()
        assertEquals(ServerPingReading.Measured(PingSample(30.0)), session.reading.value)
    }

    @Test fun `disconnect cancels the running probe and reconnect tests once`() = runTest {
        var cancelled = false
        var calls = 0
        val session = ServerPingSession(backgroundScope) {
            if (++calls == 1) try { awaitCancellation() } finally { cancelled = true }
            else PingSample(20.0)
        }
        session.setTarget(target)
        runCurrent()
        session.setTarget(null)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(ServerPingReading.Inactive, session.reading.value)
        session.refresh()
        assertEquals(1, calls)
        session.setTarget(target.copy(sessionId = "next"))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ServerPingReading.Measured(PingSample(20.0)), session.reading.value)
    }

    @Test fun `a late cancelled reply cannot replace the new session result`() = runTest {
        val oldReply = CompletableDeferred<PingSample>()
        var calls = 0
        val session = ServerPingSession(backgroundScope) {
            if (++calls == 1) withContext(NonCancellable) { oldReply.await() } else PingSample(20.0)
        }
        session.setTarget(target)
        runCurrent()
        session.setTarget(target.copy(sessionId = "next"))
        runCurrent()
        oldReply.complete(PingSample(99.0))
        runCurrent()
        assertEquals(ServerPingReading.Measured(PingSample(20.0)), session.reading.value)
    }

    @Test fun `host profile routing and session changes invalidate the reading`() = runTest {
        val calls = mutableListOf<String>()
        val session = ServerPingSession(backgroundScope) { calls += it; PingSample(calls.size.toDouble()) }
        val targets = listOf(target, target.copy(host = "other.example"), target.copy(profileId = "other"),
            target.copy(routing = AppRoutingConfig(AppRoutingMode.EXCLUDE_SELECTED, setOf("com.example.app"))),
            target.copy(sessionId = "next"))
        targets.forEachIndexed { index, value ->
            session.setTarget(value)
            assertEquals(ServerPingReading.Checking, session.reading.value)
            runCurrent()
            assertEquals(ServerPingReading.Measured(PingSample((index + 1).toDouble())), session.reading.value)
        }
        assertEquals(targets.map { it.host }, calls)
    }
}
