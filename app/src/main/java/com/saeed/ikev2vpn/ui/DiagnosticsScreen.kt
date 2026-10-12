package com.saeed.ikev2vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun DiagnosticsScreen(state: VpnUiState, onBack: () -> Unit, onClearLog: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Diagnostics", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Diagnostics never include the VPN password.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()

        DiagnosticRow("Android version", Build.VERSION.RELEASE)
        DiagnosticRow("API level", Build.VERSION.SDK_INT.toString())
        DiagnosticRow("VPN platform support", if (state.platformSupported) "Supported" else "Unsupported")
        DiagnosticRow("Profile", if (state.configured) "Configured" else "Not configured")
        DiagnosticRow("Provisioning", state.provisioningStatus?.name?.replace('_', ' ') ?: "None")
        DiagnosticRow("VPN state", state.connectionState.displayName())
        DiagnosticRow("State confirmed", if (state.stateConfirmed) "Yes" else "No")
        DiagnosticRow("State evidence", state.stateEvidence.name.replace('_', ' '))
        DiagnosticRow("State detail", state.stateDetail)
        DiagnosticRow("Server", state.serverAddress.ifBlank { "Not set" })
        DiagnosticRow("Server identity", state.serverAddress.ifBlank { "Not set" })
        DiagnosticRow("Local IKE identity", state.username.ifBlank { "Not set" })
        DiagnosticRow("Authentication", "EAP-MSCHAPv2")
        DiagnosticRow("CA subject", state.certificateInfo?.subject ?: "Not imported")
        DiagnosticRow("CA issuer", state.certificateInfo?.issuer ?: "Not imported")
        DiagnosticRow("CA SHA-256", state.certificateInfo?.sha256Fingerprint ?: "Not imported")
        DiagnosticRow("Last session ID", state.sessionId ?: "Unavailable")
        HorizontalDivider()
        DiagnosticRow("Last VPN error", state.lastVpnError ?: state.error ?: "None")
        DiagnosticRow("Technical detail", state.lastTechnicalError ?: state.technicalError ?: "None")
        DiagnosticRow(
            "Error time",
            state.lastErrorTimestamp?.let { timestamp ->
                DateFormat.getDateTimeInstance().format(Date(timestamp))
            } ?: "Unavailable",
        )
        state.repositoryError?.let { DiagnosticRow("Storage warning", it) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connection log", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(
                enabled = state.connectionEvents.isNotEmpty(),
                onClick = { copyConnectionLog(context, state) },
            ) { Text("Copy") }
            TextButton(
                enabled = state.connectionEvents.isNotEmpty(),
                onClick = onClearLog,
            ) { Text("Clear") }
        }
        Text(
            "These are VPN events reported to the app by Android. Android does not expose the full IKE packet log here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.connectionEvents.isEmpty()) {
            Text("No connection events yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            state.connectionEvents.asReversed().forEach { event ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(event.timestamp)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(event.message, style = MaterialTheme.typography.bodyMedium)
                    event.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun copyConnectionLog(context: Context, state: VpnUiState) {
    val text = state.connectionEvents.joinToString("\n") { event ->
        val timestamp = DateFormat.getDateTimeInstance().format(Date(event.timestamp))
        "$timestamp ${event.message}" + (event.detail?.let { "\n  $it" } ?: "")
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("VPN connection log", text))
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
