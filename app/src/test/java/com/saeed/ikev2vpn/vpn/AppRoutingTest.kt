package com.saeed.ikev2vpn.vpn

import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.strongswan.android.data.VpnProfile

class AppRoutingTest {
    @Test
    fun `all apps clears previous restrictions on the reused strongSwan profile`() {
        val profile = VpnProfile()
        profile.applyAppRouting(AppRoutingConfig(AppRoutingMode.ONLY_SELECTED, setOf("com.example.browser")))
        profile.applyAppRouting(AppRoutingConfig(AppRoutingMode.ALL, setOf("com.example.browser")))
        assertEquals(VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE, profile.selectedAppsHandling)
        assertNull(profile.selectedApps)
    }

    @Test
    fun `include and exclude populate the engine profile`() {
        val profile = VpnProfile()
        val packages = setOf("com.example.z", "com.example.a")
        profile.applyAppRouting(AppRoutingConfig(AppRoutingMode.ONLY_SELECTED, packages))
        assertEquals(VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY, profile.selectedAppsHandling)
        assertEquals("com.example.a com.example.z", profile.selectedApps)
        profile.applyAppRouting(AppRoutingConfig(AppRoutingMode.EXCLUDE_SELECTED, packages))
        assertEquals(VpnProfile.SelectedAppsHandling.SELECTED_APPS_EXCLUDE, profile.selectedAppsHandling)
        assertEquals("com.example.a com.example.z", profile.selectedApps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty include list cannot be provisioned`() {
        VpnProfile().applyAppRouting(AppRoutingConfig(AppRoutingMode.ONLY_SELECTED))
    }
}
