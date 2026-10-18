package com.saeed.ikev2vpn.notifications

import com.saeed.ikev2vpn.data.*
import com.saeed.ikev2vpn.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class VpnNotificationActionsTest {
    private val profile = StoredProfile(VpnProfileConfig("Work", "vpn.example.com", "private-user"), null, ProvisioningStatus.PROVISIONED)
    private val snapshots = MutableStateFlow(RepositorySnapshot(profile = profile))
    private val states = MutableStateFlow(VpnState(confirmed = true))
    private val repository = mock(ProfileRepository::class.java).also { `when`(it.snapshots).thenReturn(snapshots) }
    private val vpn = mock(VpnPlatformController::class.java).also {
        `when`(it.state).thenReturn(states)
        `when`(it.connect()).thenAnswer {
            states.value = VpnState(connectionState = ConnectionState.CONNECTING)
            VpnResult.Success(null)
        }
        `when`(it.disconnect()).thenReturn(VpnResult.Success(Unit))
    }
    private val commands = VpnNotificationActions(repository, vpn)

    @Test fun `connect uses the provisioned profile and repeated taps do not restart the tunnel`() = runTest {
        assertNull(commands.execute(VpnNotificationActions.CONNECT))
        assertNull(commands.execute(VpnNotificationActions.CONNECT))
        verify(vpn, times(1)).connect()
    }
    @Test fun `a stale connect action cannot replace a connected session or use incomplete configuration`() = runTest {
        states.value = VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true)
        assertNull(commands.execute(VpnNotificationActions.CONNECT))
        states.value = VpnState(confirmed = true)
        for (snapshot in listOf(RepositorySnapshot(), snapshots.value.copy(pendingProfile = profile),
            snapshots.value.copy(profile = profile.copy(status = ProvisioningStatus.DRAFT)))) {
            snapshots.value = snapshot
            assertNotNull(commands.execute(VpnNotificationActions.CONNECT))
        }
        verify(vpn, never()).connect()
    }
    @Test fun `disconnect stops the active tunnel without needing a selected profile`() = runTest {
        snapshots.value = RepositorySnapshot()
        states.value = VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true)
        assertNull(commands.execute(VpnNotificationActions.DISCONNECT))
        verify(vpn).clearTransientSecrets()
        verify(vpn).disconnect()
        verify(vpn, never()).connect()
    }
    @Test fun `cold state is awaited and a startup or consent failure is returned without an activity`() = runTest {
        states.value = VpnState()
        doReturn(VpnResult.Failure("VPN permission is required", "technical")).`when`(vpn).connect()
        val request = async { commands.execute(VpnNotificationActions.CONNECT) }
        runCurrent()
        verify(vpn, never()).connect()
        states.value = VpnState(confirmed = true)
        assertEquals("VPN permission is required", request.await())
    }
    @Test fun `unknown actions and an already disconnected stop are harmless`() = runTest {
        assertNull(commands.execute("untrusted action"))
        assertNull(commands.execute(VpnNotificationActions.DISCONNECT))
        verify(vpn, never()).connect()
        verify(vpn, never()).disconnect()
    }
    @Test fun `idle controls appear only for a confirmed disconnected provisioned profile`() {
        val snapshot = snapshots.value
        for (connection in ConnectionState.entries) for (confirmed in listOf(false, true)) {
            val state = VpnState(connectionState = connection, confirmed = confirmed)
            assertEquals(confirmed && connection == ConnectionState.DISCONNECTED, IdleNotificationState.from(snapshot, state).visible)
        }
        assertFalse(IdleNotificationState.from(RepositorySnapshot(), VpnState(confirmed = true)).visible)
        assertFalse(IdleNotificationState.from(snapshot.copy(pendingProfile = profile), VpnState(confirmed = true)).visible)
    }
    @Test fun `counter and diagnostic updates leave idle notification content unchanged`() {
        val initial = VpnState(confirmed = true)
        assertEquals(IdleNotificationState.from(snapshots.value, initial), IdleNotificationState.from(snapshots.value,
            initial.copy(traffic = VpnTraffic(100, 200), error = "private detail", events = listOf(VpnLogEntry(1,"private")))))
        assertEquals("Work", IdleNotificationState.from(snapshots.value, initial).profileName)
    }
}
