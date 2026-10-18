package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.saeed.ikev2vpn.update.*

@Composable
internal fun AboutScreen(state: AppUpdateState, onBack: () -> Unit, onCheck: () -> Unit,
    onDownload: () -> Unit, onCancelDownload: () -> Unit, onInstall: () -> Unit, onDismissError: () -> Unit) {
    val links = LocalUriHandler.current
    var notesOpen by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    fun open(url: String) {
        try { links.openUri(url) } catch (_: Exception) { linkError = true }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("About", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(VpnIcons.Shield, Modifier.size(64.dp))
            Text("IKEv2 Android Client", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${state.installed.versionName} (${state.installed.versionCode})" + if (state.installed.debug) " · Test build" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("IKEv2 / IPsec, powered by strongSwan.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("App updates", style = MaterialTheme.typography.titleMedium)
                Text(when (state.phase) {
                    UpdatePhase.IDLE -> "Check the latest official GitHub release."
                    UpdatePhase.CHECKING -> "Checking for updates…"
                    UpdatePhase.NO_RELEASE -> "No public release has been published yet."
                    UpdatePhase.CURRENT -> if (state.release?.version?.let { ReleaseVersion.parse(state.installed.versionName)?.let { installed -> it < installed } == true } == true)
                        "This build is newer than the latest published release." else "You have the latest published version."
                    UpdatePhase.AVAILABLE -> "Version ${state.release?.version} is available."
                    UpdatePhase.DOWNLOADING -> "Downloading update · ${state.progress}%"
                    UpdatePhase.READY -> "The update is verified and ready to install."
                    UpdatePhase.PREPARING_INSTALL -> "Preparing the Android installer…"
                }, style = MaterialTheme.typography.bodyMedium)
                if (state.phase == UpdatePhase.DOWNLOADING) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { ErrorCard(it, onDismissError) }
                when (state.phase) {
                    UpdatePhase.AVAILABLE -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download update") }
                    UpdatePhase.READY -> {
                        Text("Android will confirm installation and restart the app. An active VPN may disconnect; your profiles are kept.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
                        TextButton(onClick = onCheck) { Text("Check again") }
                    }
                    UpdatePhase.DOWNLOADING -> TextButton(onClick = onCancelDownload) { Text("Cancel download") }
                    UpdatePhase.CHECKING, UpdatePhase.PREPARING_INSTALL -> Unit
                    else -> Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
                }
                state.release?.takeIf { it.notes.isNotBlank() }?.let { release ->
                    TextButton(onClick = { notesOpen = !notesOpen }) { Text(if (notesOpen) "Hide release notes" else "Release notes") }
                    if (notesOpen) Text(release.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (linkError) ErrorCard("No browser is available to open this link.") { linkError = false }
        ActionRow(VpnIcons.Link, "Source code", "scargosnail598 / IKEv2AndroidClient", { open(PROJECT_URL) })
        TextButton(onClick = { open(RELEASES_URL) }, modifier = Modifier.fillMaxWidth()) { Text("View releases on GitHub") }
        Text("Project: MIT · strongSwan: GPLv2+ · OpenSSL: Apache 2.0", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}
