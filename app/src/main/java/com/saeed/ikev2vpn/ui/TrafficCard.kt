package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.vpn.VpnTraffic
import java.util.Locale

@Composable
internal fun TrafficCard(traffic: VpnTraffic?, connected: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VPN traffic", style = MaterialTheme.typography.labelLarge)
                Text(when {
                    !connected -> "No active session"
                    traffic == null -> "Waiting for data…"
                    else -> "This session"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrafficValue("Download · In", VpnIcons.Download, traffic?.downloadBytes, Modifier.weight(1f))
                TrafficValue("Upload · Out", VpnIcons.Upload, traffic?.uploadBytes, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrafficValue(label: String, icon: ImageVector, bytes: Long?, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(bytes?.let(::formatTrafficBytes) ?: "—", style = MaterialTheme.typography.titleLarge)
    }
}

internal fun formatTrafficBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
