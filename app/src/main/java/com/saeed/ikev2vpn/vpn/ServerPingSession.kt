package com.saeed.ikev2vpn.vpn

import com.saeed.ikev2vpn.data.AppRoutingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Identifies the connection whose reading may be displayed. */
internal data class ServerPingTarget(
    val profileId: String,
    val host: String,
    val sessionId: String?,
    val routing: AppRoutingConfig,
)

/** One initial echo per connection, then explicit refreshes; owned by the VPN ViewModel. */
internal class ServerPingSession(private val scope: CoroutineScope, probe: ServerPingProbe) {
    private val monitor = ServerPingMonitor(probe)
    private val mutableReading = MutableStateFlow<ServerPingReading>(ServerPingReading.Inactive)
    val reading = mutableReading.asStateFlow()
    private var target: ServerPingTarget? = null
    private var job: Job? = null

    fun setTarget(value: ServerPingTarget?) {
        if (target == value) return
        job?.cancel()
        job = null
        target = value
        mutableReading.value = ServerPingReading.Inactive
        if (value != null) refresh()
    }

    fun refresh() {
        val current = target ?: return
        if (job?.isActive == true) return
        mutableReading.value = ServerPingReading.Checking
        job = scope.launch {
            monitor.readings(current.host).collect {
                ensureActive()
                if (target == current) mutableReading.value = it
            }
        }
    }
}
