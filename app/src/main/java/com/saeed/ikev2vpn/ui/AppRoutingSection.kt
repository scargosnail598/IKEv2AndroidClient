package com.saeed.ikev2vpn.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import com.saeed.ikev2vpn.validation.ProfileField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppRoutingSection(
    state: VpnUiState,
    onModeChanged: (AppRoutingMode) -> Unit,
    onToggleApplication: (String) -> Unit,
) {
    var pickerOpen by rememberSaveable(state.draftRevision) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Per-app VPN", style = MaterialTheme.typography.titleMedium)
            Text("Choose which apps use this profile. Save the profile before reconnecting.",
                style = MaterialTheme.typography.bodySmall)
            AppRoutingMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = state.appRouting.mode == mode,
                        enabled = !state.isBusy,
                        role = Role.RadioButton,
                        onClick = { onModeChanged(mode) },
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.appRouting.mode == mode, onClick = null,
                        enabled = !state.isBusy)
                    Text(when (mode) {
                        AppRoutingMode.ALL -> "All apps"
                        AppRoutingMode.ONLY_SELECTED -> "Only selected apps use VPN"
                        AppRoutingMode.EXCLUDE_SELECTED -> "Selected apps bypass VPN"
                    }, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (state.appRouting.mode != AppRoutingMode.ALL) {
                Text(if (state.appRouting.mode == AppRoutingMode.ONLY_SELECTED) {
                    "Other apps use your regular internet connection."
                } else {
                    "All other apps use the VPN."
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { pickerOpen = true }, enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Select apps (${state.appRouting.packages.size} selected)")
                }
            }
            state.fieldErrors[ProfileField.APPLICATIONS]?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (pickerOpen) {
        ApplicationPicker(state.appRouting, onToggleApplication) { pickerOpen = false }
    }
}

private data class SelectableApplication(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val installed: Boolean = true,
)

@Composable
private fun ApplicationPicker(
    routing: AppRoutingConfig,
    onToggleApplication: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var apps by remember { mutableStateOf<List<SelectableApplication>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(context, reload) {
        error = null
        apps = null
        try {
            apps = withContext(Dispatchers.IO) {
                val manager = context.packageManager
                manager.getInstalledPackages(PackageManager.GET_PERMISSIONS).mapNotNull { info ->
                    val application = info.applicationInfo ?: return@mapNotNull null
                    if (info.packageName == context.packageName ||
                        Manifest.permission.INTERNET !in info.requestedPermissions.orEmpty()) {
                        return@mapNotNull null
                    }
                    SelectableApplication(info.packageName,
                        manager.getApplicationLabel(application).toString(),
                        application.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            error = "Apps could not be loaded. Try again."
        }
    }
    val available = apps.orEmpty()
    val knownPackages = available.map { it.packageName }.toSet()
    val missing = routing.packages.filter { it !in knownPackages }.sorted().map {
        SelectableApplication(it, "Unavailable app", false, installed = false)
    }
    val visible = (available + missing).filter {
        (showSystem || !it.system || it.packageName in routing.packages) &&
            (query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${routing.packages.size} selected • Changes are saved with the profile.")
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text("Search apps or package names") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().toggleable(value = showSystem,
                    role = Role.Checkbox, onValueChange = { showSystem = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = null)
                    Text("Show system apps", Modifier.padding(start = 8.dp))
                }
                when {
                    error != null -> {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { reload += 1 }) { Text("Retry") }
                    }
                    apps == null -> CircularProgressIndicator()
                    visible.isEmpty() -> Text("No matching apps.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            Row(Modifier.fillMaxWidth().toggleable(
                                value = app.packageName in routing.packages,
                                role = Role.Checkbox,
                                onValueChange = { onToggleApplication(app.packageName) },
                            ).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = app.packageName in routing.packages,
                                    onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!app.installed) Text("Uninstalled or unavailable; deselect to remove",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
