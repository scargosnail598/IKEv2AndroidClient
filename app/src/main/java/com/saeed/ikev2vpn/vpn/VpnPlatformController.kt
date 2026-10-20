package com.saeed.ikev2vpn.vpn

import com.saeed.ikev2vpn.data.VpnProfileConfig
import kotlinx.coroutines.flow.StateFlow
import java.security.cert.X509Certificate

interface VpnPlatformController {
    val state: StateFlow<VpnState>

    fun isPlatformSupported(): Boolean

    fun provision(
        config: VpnProfileConfig,
        password: String,
        serverRootCa: X509Certificate?,
    ): VpnResult<ProvisioningAction>

    fun retainCurrentCredential(profileId: String) = Unit
    fun hasSavedCredential(profileId: String): Boolean = false
    fun loadProfileCredential(profileId: String): String? = null
    fun restoreProfile(config: VpnProfileConfig, serverRootCa: X509Certificate?): VpnResult<ProvisioningAction> =
        VpnResult.Failure("Enter the password for this profile.", "No saved profile credential is available.")

    fun forgetProfileCredential(profileId: String) = Unit
    fun renameProvisionedProfile(name: String): VpnResult<Unit> = VpnResult.Success(Unit)

    fun connect(): VpnResult<String?>
    fun disconnect(): VpnResult<Unit>
    fun deleteProvisionedProfile(): VpnResult<Unit>
    fun refreshState()
    fun clearEventLog() = Unit
    fun clearTransientSecrets() = Unit
}
