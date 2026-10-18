package com.saeed.ikev2vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.saeed.ikev2vpn.data.*
import com.saeed.ikev2vpn.ui.theme.Ikev2VpnTheme
import com.saeed.ikev2vpn.vpn.ConnectionState

/** Synthetic preview data lives only in debug builds. */
private fun sampleState(connected: Boolean = false): VpnUiState {
    val personal = StoredProfile(VpnProfileConfig("Personal", "vpn.example.com", "alice", "personal"), null, ProvisioningStatus.PROVISIONED)
    val work = StoredProfile(VpnProfileConfig("Work · Amsterdam", "work.example.com", "alice.work", "work"), null, ProvisioningStatus.NEEDS_REPROVISION)
    return VpnUiState(initialized = true, configured = true, platformSupported = true, screen = AppScreen.MAIN,
        profiles = listOf(personal, work), selectedProfileId = "personal", profileName = "Personal",
        username = "alice", serverAddress = "vpn.example.com", usesPublicCertificateTrust = true,
        connectionState = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
        stateConfirmed = true, sessionId = if (connected) "42" else null,
        connectedSinceMillis = if (connected) System.currentTimeMillis() - 342000 else null,
        traffic = if (connected) com.saeed.ikev2vpn.vpn.VpnTraffic(40255012, 1204500) else null)
}

@Composable
private fun PreviewFrame(dark: Boolean = false, content: @Composable () -> Unit) {
    Ikev2VpnTheme(dark) { Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } } }
}

@Preview(name = "Connection · light", widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun ConnectionPreview() {
    val state = remember { sampleState() }
    PreviewFrame { MainScreen(state, {}, {}, {}, {}, {}, false, {}, {}) }
}

@Preview(name = "Session · dark", widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun SessionPreview() {
    val state = remember { sampleState(true) }
    PreviewFrame(true) { MainScreen(state, {}, {}, {}, {}, {}, true, {}, {}) }
}

@Preview(name = "Profiles", widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun ProfilesPreview() {
    PreviewFrame { ProfilesScreen(sampleState(), {}, {}, {}, { _, _ -> }, {}, {}) }
}

@Preview(name = "Review · compact large text", widthDp = 320, heightDp = 640, fontScale = 1.3f, showBackground = true)
@Composable
private fun ReviewPreview() {
    val state = sampleState().copy(screen = AppScreen.SETUP, hasImportedPassword = true,
        importedProfileInfo = ImportedProfileUiInfo("vpn.example.com", "secure", "", 1))
    PreviewFrame { PreviewSetup(state) }
}

@Preview(name = "Add a profile", widthDp = 360, heightDp = 780, showBackground = true)
@Composable
private fun AddPreview() {
    PreviewFrame { PreviewSetup(VpnUiState(initialized = true, platformSupported = true)) }
}

@Composable
private fun PreviewSetup(state: VpnUiState) {
    SetupScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
