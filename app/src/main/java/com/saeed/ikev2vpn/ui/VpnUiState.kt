package com.saeed.ikev2vpn.ui

import com.saeed.ikev2vpn.certificate.CertificateInfo
import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.validation.ProfileField
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.StateEvidence
import com.saeed.ikev2vpn.vpn.VpnLogEntry

enum class AppScreen {
    SETUP,
    MAIN,
    PROFILES,
    DIAGNOSTICS,
    ABOUT,
    TRAFFIC_BYPASS,
}

data class ImportedProfileUiInfo(
    val remoteId: String,
    val serverProfile: String,
    val proxySummary: String,
    val importRevision: Int,
)

data class VpnUiState(
    val profiles: List<com.saeed.ikev2vpn.data.StoredProfile> = emptyList(),
    val credentialPromptProfileName: String? = null,
    val isEditingProfile: Boolean = false,
    val editingProfileId: String? = null,
    val canReuseSavedPassword: Boolean = false,
    val draftRevision: Int = 0,
    val selectedProfileId: String = "legacy",
    val initialized: Boolean = false,
    val screen: AppScreen = AppScreen.SETUP,
    val configured: Boolean = false,
    val provisioningStatus: ProvisioningStatus? = null,
    val connectionState: ConnectionState = ConnectionState.NOT_CONFIGURED,
    val stateEvidence: StateEvidence = StateEvidence.NONE,
    val stateConfirmed: Boolean = false,
    val stateDetail: String = "No VPN profile is configured.",
    val connectionEvents: List<VpnLogEntry> = emptyList(),
    val platformSupported: Boolean = false,
    val profileName: String = "",
    val serverAddress: String = "",
    val username: String = "",
    val appRouting: AppRoutingConfig = AppRoutingConfig(),
    val certificateInfo: CertificateInfo? = null,
    val usesPublicCertificateTrust: Boolean = false,
    val hasImportedPassword: Boolean = false,
    val importReplacementProfileId: String? = null,
    val importedProfileInfo: ImportedProfileUiInfo? = null,
    val sessionId: String? = null,
    val connectedSinceMillis: Long? = null,
    val traffic: com.saeed.ikev2vpn.vpn.VpnTraffic? = null,
    val error: String? = null,
    val technicalError: String? = null,
    val lastVpnError: String? = null,
    val lastTechnicalError: String? = null,
    val lastErrorTimestamp: Long? = null,
    val repositoryError: String? = null,
    val fieldErrors: Map<ProfileField, String> = emptyMap(),
    val isBusy: Boolean = false,
)

/** Browsing remains available while the tunnel runs; mutations require an idle engine. */
internal val VpnUiState.canManageProfiles: Boolean
    get() = !isBusy && provisioningStatus != ProvisioningStatus.PENDING_CONSENT &&
        credentialPromptProfileName == null && connectionState in setOf(
            ConnectionState.NOT_CONFIGURED, ConnectionState.DISCONNECTED, ConnectionState.ERROR,
        )
