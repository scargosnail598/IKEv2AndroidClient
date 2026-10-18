package com.saeed.ikev2vpn.widget

import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.RepositorySnapshot
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.VpnState

enum class WidgetStatus { CHECKING, SETUP, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR, UNKNOWN }

/** Only public display data belongs on the launcher; never credentials or diagnostics. */
data class VpnWidgetState(val status: WidgetStatus = WidgetStatus.CHECKING, val profileName: String? = null) {
    companion object {
        fun from(snapshot: RepositorySnapshot, vpn: VpnState): VpnWidgetState {
            val configured = snapshot.profile?.status == ProvisioningStatus.PROVISIONED && snapshot.pendingProfile == null
            val status = when {
                vpn.connectionState == ConnectionState.CONNECTING -> WidgetStatus.CONNECTING
                vpn.connectionState == ConnectionState.DISCONNECTING -> WidgetStatus.DISCONNECTING
                !vpn.confirmed -> WidgetStatus.CHECKING
                vpn.connectionState == ConnectionState.CONNECTED -> WidgetStatus.CONNECTED
                !configured -> WidgetStatus.SETUP
                vpn.connectionState == ConnectionState.ERROR -> WidgetStatus.ERROR
                vpn.connectionState == ConnectionState.DISCONNECTED -> WidgetStatus.DISCONNECTED
                else -> WidgetStatus.UNKNOWN
            }
            return VpnWidgetState(status, snapshot.profile?.config?.profileName)
        }
    }
}
