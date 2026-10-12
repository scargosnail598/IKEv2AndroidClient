package com.saeed.ikev2vpn.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.saeed.ikev2vpn.vpn.ConnectionState

@Composable
fun ProfileSelector(state: VpnUiState, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = !state.isBusy && state.provisioningStatus != com.saeed.ikev2vpn.data.ProvisioningStatus.PENDING_CONSENT &&
        state.connectionState in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR, ConnectionState.NOT_CONFIGURED)
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && state.profiles.isNotEmpty()) {
                Text(state.profiles.firstOrNull { it.config.id == state.selectedProfileId }?.config?.profileName ?: "Profiles")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.profiles.forEach { profile ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(profile.config.profileName)
                            Text("${profile.config.username} · ${profile.config.serverAddress}", style = MaterialTheme.typography.bodySmall)
                        }
                    }, enabled = enabled,
                        trailingIcon = { if (profile.config.id == state.selectedProfileId) Text("✓") },
                        onClick = { expanded = false; onSelect(profile.config.id) })
                }
            }
        }
        OutlinedButton(onClick = onAdd, enabled = enabled) { Text("Add Profile") }
    }
}
