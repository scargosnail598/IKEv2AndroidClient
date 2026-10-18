package com.saeed.ikev2vpn.vpn

import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import org.strongswan.android.data.VpnProfile

internal fun VpnProfile.applyAppRouting(config: AppRoutingConfig) {
    require(config.mode != AppRoutingMode.ONLY_SELECTED || config.packages.isNotEmpty()) {
        "Select at least one app to use the VPN."
    }
    selectedAppsHandling = when (config.mode) {
        AppRoutingMode.ALL -> VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE
        AppRoutingMode.ONLY_SELECTED -> VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY
        AppRoutingMode.EXCLUDE_SELECTED -> VpnProfile.SelectedAppsHandling.SELECTED_APPS_EXCLUDE
    }
    setSelectedApps(if (config.mode == AppRoutingMode.ALL) null as String?
        else config.packages.sorted().joinToString(" "))
}
