package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import com.saeed.ikev2vpn.vpn.*
import kotlin.math.roundToLong
import kotlin.math.ceil

@Composable
internal fun ServerPingRow(host: String, sessionId: String?, connected: Boolean, routing: AppRoutingConfig) {
    val owner = LocalLifecycleOwner.current
    val monitor = remember { ServerPingMonitor(AndroidServerPingProbe()) }
    var reading by remember(host, sessionId, connected, routing) {
        mutableStateOf<ServerPingReading>(if (connected) ServerPingReading.Checking else ServerPingReading.Inactive)
    }
    LaunchedEffect(host, sessionId, connected, routing, owner) {
        if (!connected) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { monitor.readings(host).collect { reading = it } }
            finally { reading = ServerPingReading.Checking }
        }
    }
    val ownPackage = LocalContext.current.packageName
    val outsideVpn = when (routing.mode) {
        AppRoutingMode.ALL -> false
        AppRoutingMode.ONLY_SELECTED -> ownPackage !in routing.packages
        AppRoutingMode.EXCLUDE_SELECTED -> ownPackage in routing.packages
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(VpnIcons.Activity, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Server ping", style = MaterialTheme.typography.labelMedium)
            Text(if (!connected) "Connect to measure" else (if (outsideVpn) "ICMP · Outside VPN · $host" else "ICMP · $host"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(formatServerPing(reading), modifier = Modifier.widthIn(max = 120.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = if (reading is ServerPingReading.Measured)
            MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun formatServerPing(reading: ServerPingReading): String = when (reading) {
    ServerPingReading.Inactive -> "—"
    ServerPingReading.Checking -> "Checking…"
    ServerPingReading.Unavailable -> "Unavailable"
    is ServerPingReading.Measured -> when {
        reading.sample.milliseconds < 1 -> "<1 ms"
        reading.sample.lessThan -> "<${ceil(reading.sample.milliseconds).toLong().coerceAtLeast(1)} ms"
        else -> "${reading.sample.milliseconds.roundToLong()} ms"
    }
}
