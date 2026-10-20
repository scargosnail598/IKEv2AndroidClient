package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.ServerPingReading
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    state: VpnUiState, onProfiles: () -> Unit, onAddProfile: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onDiagnostics: () -> Unit,
    darkMode: Boolean, onDarkModeChanged: (Boolean) -> Unit, onDismissError: () -> Unit,
    onAbout: () -> Unit = {}, onCheckUpdates: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    onTrafficBypass: () -> Unit = {},
    serverPing: ServerPingReading = ServerPingReading.Inactive,
    onRefreshPing: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val connected = state.connectionState == ConnectionState.CONNECTED && state.stateConfirmed
    val stopping = state.connectionState == ConnectionState.DISCONNECTING
    val working = state.connectionState == ConnectionState.CONNECTING
    val active = state.connectionState == ConnectionState.CONNECTED || working || state.connectionState == ConnectionState.UNKNOWN
    val elapsed by produceState(initialValue = sessionElapsed(state.connectedSinceMillis), state.connectedSinceMillis, connected) {
        while (connected && state.connectedSinceMillis != null) {
            value = sessionElapsed(state.connectedSinceMillis)
            delay(1000)
        }
    }
    val profile = state.profiles.firstOrNull { it.config.id == state.selectedProfileId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("IKEv2") {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(VpnIcons.More, "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (darkMode) "Light appearance" else "Dark appearance") },
                        leadingIcon = { Icon(VpnIcons.Moon, null) },
                        onClick = { menuOpen = false; onDarkModeChanged(!darkMode) })
                    DropdownMenuItem(text = { Text("Traffic bypass") }, leadingIcon = { Icon(VpnIcons.Link, null) },
                        onClick = { menuOpen = false; onTrafficBypass() })
                    DropdownMenuItem(text = { Text("Notifications") }, leadingIcon = { Icon(VpnIcons.Bell, null) },
                        onClick = { menuOpen = false; onNotificationSettings() })
                    DropdownMenuItem(text = { Text("Check for updates") }, leadingIcon = { Icon(VpnIcons.Download, null) },
                        onClick = { menuOpen = false; onCheckUpdates() })
                    DropdownMenuItem(text = { Text("About") }, leadingIcon = { Icon(VpnIcons.Info, null) },
                        onClick = { menuOpen = false; onAbout() })
                    DropdownMenuItem(text = { Text("Diagnostics") }, leadingIcon = { Icon(VpnIcons.Activity, null) },
                        onClick = { menuOpen = false; onDiagnostics() })
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(100.dp), shape = CircleShape,
                color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) {
                    if (working || stopping) CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 2.dp)
                    Icon(VpnIcons.Shield, null, Modifier.size(42.dp), tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(when {
                !state.configured -> "Ready to get started"
                state.connectionState == ConnectionState.CONNECTED && !state.stateConfirmed -> "Verifying connection"
                else -> state.connectionState.displayName()
            },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(when {
                !state.configured && state.profiles.isEmpty() -> "Add a profile to set up your VPN."
                !state.configured -> "Choose a saved profile to continue."
                connected && state.connectedSinceMillis != null -> "Session · ${formatSessionDuration(elapsed)}"
                connected -> "Your VPN tunnel is active."
                state.connectionState == ConnectionState.CONNECTED -> "Waiting for VPN confirmation…"
                working -> "Establishing a secure connection…"
                stopping -> "Closing the VPN connection…"
                state.connectionState == ConnectionState.UNKNOWN -> "Check the connection or stop it to try again."
                state.connectionState == ConnectionState.ERROR -> "Connection failed. You can try again."
                else -> "Connect when you need your VPN."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (profile != null) {
            Card(onClick = onProfiles, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconTile(VpnIcons.Profiles)
                    Column(Modifier.weight(1f)) {
                        Text("Selected profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(profile.config.profileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(profile.config.serverAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(profile.config.username)
                                append(" · ")
                                append(profile.config.appRouting.summary())
                                if (connected) state.connectedSinceMillis?.let {
                                    append(" · Since ")
                                    append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
                                }
                            },
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(VpnIcons.Chevron, "Manage profiles", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(
            onClick = {
                if (!state.configured) { if (state.profiles.isEmpty()) onAddProfile() else onProfiles() }
                else if (active) onDisconnect() else onConnect()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            enabled = !state.isBusy && !stopping && state.provisioningStatus != com.saeed.ikev2vpn.data.ProvisioningStatus.PENDING_CONSENT,
        ) {
            if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else {
                Icon(if (!state.configured) VpnIcons.Add else VpnIcons.Power, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(when {
                    !state.configured && state.profiles.isEmpty() -> "Add profile"
                    !state.configured -> "Choose profile"
                    working -> "Cancel connection"
                    stopping -> "Disconnecting…"
                    connected -> "Disconnect"
                    active -> "Stop connection"
                    state.connectionState == ConnectionState.ERROR -> "Try again"
                    else -> "Connect"
                })
            }
        }
        if (state.configured) TrafficCard(state.traffic.takeIf { connected }, connected, state.serverAddress,
            state.appRouting, serverPing.takeIf { connected } ?: ServerPingReading.Inactive, onRefreshPing)
        state.error?.let { ErrorCard(it, onDismissError) }
        if (state.provisioningStatus == com.saeed.ikev2vpn.data.ProvisioningStatus.PENDING_CONSENT) {
            Text("Finish the Android VPN permission request before connecting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun sessionElapsed(since: Long?): Long = since?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) } ?: 0
internal fun formatSessionDuration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0)
    return if (value >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", value / 3600, (value / 60) % 60, value % 60)
    else String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60)
}

internal fun ConnectionState.displayName(): String = when (this) {
    ConnectionState.NOT_CONFIGURED -> "Not configured"
    ConnectionState.DISCONNECTED -> "Disconnected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.DISCONNECTING -> "Disconnecting"
    ConnectionState.ERROR -> "Connection failed"
    ConnectionState.UNKNOWN -> "Checking connection"
}
