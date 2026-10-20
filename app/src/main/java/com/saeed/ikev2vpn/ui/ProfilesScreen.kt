package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.data.StoredProfile
import com.saeed.ikev2vpn.validation.ProfileValidator

@Composable
internal fun ProfilesScreen(state: VpnUiState, onSelect: (String) -> Unit, onAdd: () -> Unit,
    onEdit: (String) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit, onDismissError: () -> Unit,
    onShare: (StoredProfile) -> Unit = {}) {
    var rename by remember { mutableStateOf<StoredProfile?>(null) }
    var remove by remember { mutableStateOf<StoredProfile?>(null) }
    val editable = state.canManageProfiles
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenHeader("Profiles", if (state.profiles.isEmpty()) "Your saved VPN connections" else "${state.profiles.size} saved connections") }
        item {
            Button(onClick = onAdd, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(VpnIcons.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add profile")
            }
        }
        if (!editable && !state.isBusy) item {
            Text("Disconnect the VPN and finish any pending setup to change profiles. You can still browse them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { error -> item { ErrorCard(error, onDismissError) } }
        if (state.isBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.profiles.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(VpnIcons.Profiles, Modifier.size(64.dp))
                Text("No profiles yet", style = MaterialTheme.typography.titleLarge)
                Text("Import a link, scan a QR, or add a profile manually.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.profiles, key = { it.config.id }) { profile ->
            var menu by remember { mutableStateOf(false) }
            val selected = profile.config.id == state.selectedProfileId
            Card(colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(if (selected) VpnIcons.Check else VpnIcons.Profiles)
                        Column(Modifier.weight(1f)) {
                            Text(profile.config.profileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(profile.config.serverAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(VpnIcons.More, "Actions for ${profile.config.profileName}") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Share link / QR") }, leadingIcon = { Icon(VpnIcons.Qr, null) },
                                    enabled = !state.isBusy,
                                    onClick = { menu = false; onShare(profile) })
                                HorizontalDivider()
                                DropdownMenuItem(enabled = editable, text = { Text("Rename") }, leadingIcon = { Icon(VpnIcons.Edit, null) },
                                    onClick = { menu = false; rename = profile })
                                DropdownMenuItem(enabled = editable, text = { Text("Edit configuration") }, leadingIcon = { Icon(VpnIcons.File, null) },
                                    onClick = { menu = false; onEdit(profile.config.id) })
                                HorizontalDivider()
                                DropdownMenuItem(enabled = editable, text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(VpnIcons.Trash, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; remove = profile })
                            }
                        }
                    }
                    Text("${profile.config.username} · ${profile.config.appRouting.summary()}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected && state.configured) {
                        Text("Selected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedButton(onClick = { onSelect(profile.config.id) }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text("Use this profile") }
                    }
                }
            }
        }
    }
    rename?.let { profile ->
        var name by remember(profile.config.id) { mutableStateOf(profile.config.profileName) }
        val valid = ProfileValidator.isValidProfileName(name.trim())
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename profile") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile name") },
                singleLine = true, isError = name.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = valid && editable, onClick = { onRename(profile.config.id, name); rename = null }) { Text("Save name") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    remove?.let { profile ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Delete profile?") },
            text = { Text("Remove “${profile.config.profileName}” and its saved password from this device? You can import it again later.") },
            confirmButton = { TextButton(enabled = editable, onClick = { onDelete(profile.config.id); remove = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
}
