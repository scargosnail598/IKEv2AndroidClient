package com.saeed.ikev2vpn.notifications

import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.RepositorySnapshot
import com.saeed.ikev2vpn.vpn.*
import kotlinx.coroutines.flow.first

/** Commands are explicit, never a stale toggle. Passwords stay in the VPN controller. */
class VpnNotificationActions(private val profiles: ProfileRepository, private val vpn: VpnPlatformController) {
    suspend fun execute(action: String): String? {
        if (action !in setOf(CONNECT, DISCONNECT)) return null
        vpn.state.first { it.confirmed || it.connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.DISCONNECTING) }
        if (action == DISCONNECT) {
            if (vpn.state.value.connectionState == ConnectionState.DISCONNECTED) return null
            vpn.clearTransientSecrets()
            return message(vpn.disconnect())
        }
        val profile = profiles.snapshots.first()
        if (profile.profile?.status != ProvisioningStatus.PROVISIONED || profile.pendingProfile != null)
            return "Open the app to finish setting up a VPN profile."
        val current = vpn.state.value
        if (!current.confirmed || current.connectionState !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) return null
        return message(vpn.connect())
    }
    private fun message(result: VpnResult<*>): String? = (result as? VpnResult.Failure)?.userMessage
    companion object {
        const val CONNECT = "com.saeed.ikev2vpn.notifications.CONNECT"
        const val DISCONNECT = "com.saeed.ikev2vpn.notifications.DISCONNECT"
    }
}

data class IdleNotificationState(val visible: Boolean = false, val profileName: String? = null) {
    companion object {
        fun from(profile: RepositorySnapshot, vpn: VpnState) = IdleNotificationState(
            visible = profile.profile?.status == ProvisioningStatus.PROVISIONED && profile.pendingProfile == null &&
                vpn.confirmed && vpn.connectionState == ConnectionState.DISCONNECTED,
            profileName = profile.profile?.config?.profileName,
        )
    }
}
