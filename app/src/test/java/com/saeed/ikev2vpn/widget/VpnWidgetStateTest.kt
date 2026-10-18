package com.saeed.ikev2vpn.widget

import com.saeed.ikev2vpn.data.*
import com.saeed.ikev2vpn.vpn.*
import org.junit.Assert.*
import org.junit.Test

class VpnWidgetStateTest {
    private val profile = StoredProfile(VpnProfileConfig("Work", "vpn.example.com", "private-user"), null, ProvisioningStatus.PROVISIONED)
    private val snapshot = RepositorySnapshot(profile = profile)

    @Test fun `a cold start never claims a connection or disconnection before confirmation`() {
        for (state in listOf(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)) {
            assertEquals(WidgetStatus.CHECKING, VpnWidgetState.from(snapshot, VpnState(connectionState = state)).status)
        }
    }
    @Test fun `confirmed service state and selected profile follow the active session`() {
        for ((connection, status) in listOf(ConnectionState.CONNECTED to WidgetStatus.CONNECTED,
            ConnectionState.DISCONNECTED to WidgetStatus.DISCONNECTED, ConnectionState.ERROR to WidgetStatus.ERROR,
            ConnectionState.UNKNOWN to WidgetStatus.UNKNOWN)) {
            val display = VpnWidgetState.from(snapshot, VpnState(connectionState = connection, confirmed = true))
            assertEquals(status, display.status)
            assertEquals("Work", display.profileName)
        }
        assertEquals("Home", VpnWidgetState.from(snapshot.copy(profile = profile.copy(config = profile.config.copy(profileName = "Home"))),
            VpnState(confirmed = true)).profileName)
    }
    @Test fun `a missing draft or pending profile offers setup without hiding a confirmed tunnel`() {
        for (repo in listOf(RepositorySnapshot(), snapshot.copy(profile = profile.copy(status = ProvisioningStatus.DRAFT)),
            snapshot.copy(pendingProfile = profile))) {
            assertEquals(WidgetStatus.SETUP, VpnWidgetState.from(repo, VpnState(confirmed = true)).status)
            assertEquals(WidgetStatus.CONNECTED, VpnWidgetState.from(repo,
                VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true)).status)
        }
    }
    @Test fun `local connect and disconnect requests appear as transitions`() {
        assertEquals(WidgetStatus.CONNECTING, VpnWidgetState.from(snapshot, VpnState(connectionState = ConnectionState.CONNECTING)).status)
        assertEquals(WidgetStatus.DISCONNECTING, VpnWidgetState.from(snapshot, VpnState(connectionState = ConnectionState.DISCONNECTING)).status)
    }
    @Test fun `traffic logs and errors do not change the launcher display or expose diagnostics`() {
        val state = VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true)
        assertEquals(VpnWidgetState.from(snapshot, state), VpnWidgetState.from(snapshot, state.copy(
            traffic = VpnTraffic(9000, 7000), error = "sensitive diagnostics", events = listOf(VpnLogEntry(1, "private event")))))
    }
}
