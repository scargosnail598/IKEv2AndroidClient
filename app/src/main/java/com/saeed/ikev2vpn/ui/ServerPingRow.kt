package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import com.saeed.ikev2vpn.vpn.*
import kotlin.math.roundToLong
import kotlin.math.ceil

@Composable
internal fun ServerPingRow(host: String, connected: Boolean, routing: AppRoutingConfig,
    reading: ServerPingReading, onRefresh: () -> Unit) {
    val ownPackage = LocalContext.current.packageName
    val outsideVpn = when (routing.mode) {
        AppRoutingMode.ALL -> false
        AppRoutingMode.ONLY_SELECTED -> ownPackage !in routing.packages
        AppRoutingMode.EXCLUDE_SELECTED -> ownPackage in routing.packages
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = connected && reading != ServerPingReading.Checking,
        role = Role.Button, onClickLabel = "Test server ping", onClick = onRefresh).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(VpnIcons.Activity, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Server ping", style = MaterialTheme.typography.labelMedium)
            Text(if (!connected) "Connect to measure" else (if (outsideVpn) "ICMP · Outside VPN · $host" else "ICMP · $host"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (connected && reading != ServerPingReading.Checking) {
                Text("Tap to test again", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
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
