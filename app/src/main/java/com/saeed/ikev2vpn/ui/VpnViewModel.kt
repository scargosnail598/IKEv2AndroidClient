package com.saeed.ikev2vpn.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saeed.ikev2vpn.certificate.CertificateImporter
import com.saeed.ikev2vpn.certificate.CertificateLoadException
import com.saeed.ikev2vpn.certificate.LoadedCertificate
import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.RepositorySnapshot
import com.saeed.ikev2vpn.data.VpnProfileConfig
import com.saeed.ikev2vpn.diagnostics.DiagnosticSanitizer
import com.saeed.ikev2vpn.profile.IkevProfileImportException
import com.saeed.ikev2vpn.profile.IkevProfileImporter
import com.saeed.ikev2vpn.profile.ImportedProxyMetadata
import com.saeed.ikev2vpn.validation.ProfileField
import com.saeed.ikev2vpn.validation.ProfileValidator
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.ProvisioningAction
import com.saeed.ikev2vpn.vpn.StateEvidence
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import com.saeed.ikev2vpn.vpn.VpnResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VpnUiEffect {
    data class RequestVpnConsent(val intent: Intent) : VpnUiEffect
}

class VpnViewModel(
    private val profileRepository: ProfileRepository,
    private val certificateImporter: CertificateImporter,
    private val ikevProfileImporter: IkevProfileImporter,
    private val vpnController: VpnPlatformController,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        VpnUiState(platformSupported = vpnController.isPlatformSupported()),
    )
    private val effectChannel = Channel<VpnUiEffect>(Channel.BUFFERED)
    private var latestSnapshot = RepositorySnapshot()
    private var selectedCertificate: LoadedCertificate? = null
    private var draftInitialized = false
    private var initialNavigationComplete = false
    private var setupImportGeneration = 0
    private var consentRequestActive = false

    val uiState: StateFlow<VpnUiState> = mutableUiState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            profileRepository.snapshots.collect(::applyRepositorySnapshot)
        }
        viewModelScope.launch {
            vpnController.state.collect { platformState ->
                val configured = latestSnapshot.profile?.status == ProvisioningStatus.PROVISIONED &&
                    latestSnapshot.pendingProfile == null
                mutableUiState.update { current ->
                    if (!configured) {
                        current.copy(
                            configured = false,
                            connectionState = ConnectionState.NOT_CONFIGURED,
                            stateEvidence = StateEvidence.NONE,
                            stateConfirmed = false,
                            stateDetail = "Provision the profile before connecting.",
                        )
                    } else {
                        current.copy(
                            configured = true,
                            connectionState = platformState.connectionState,
                            stateEvidence = platformState.evidence,
                            stateConfirmed = platformState.confirmed,
                            stateDetail = platformState.detail,
                            sessionId = platformState.sessionId ?: current.sessionId,
                            error = platformState.error,
                            technicalError = if (platformState.error == null) {
                                null
                            } else {
                                current.technicalError
                            },
                        )
                    }
                }
            }
        }
    }

    fun updateProfileName(value: String) = updateDraft(ProfileField.PROFILE_NAME) {
        copy(profileName = value)
    }

    fun updateServerAddress(value: String) = updateDraft(ProfileField.SERVER_ADDRESS) {
        copy(serverAddress = value)
    }

    fun updateUsername(value: String) = updateDraft(ProfileField.USERNAME) {
        copy(username = value)
    }

    fun importCertificate(uri: Uri) {
        val importGeneration = ++setupImportGeneration
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            try {
                val loaded = certificateImporter.import(uri)
                if (importGeneration != setupImportGeneration) return@launch
                selectedCertificate = loaded
                mutableUiState.update { current ->
                    current.copy(
                        certificateInfo = loaded.info,
                        importedProfileInfo = null,
                        fieldErrors = current.fieldErrors - ProfileField.CERTIFICATE,
                        isBusy = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: CertificateLoadException) {
                if (importGeneration != setupImportGeneration) return@launch
                showError(exception.message, technicalMessage(exception))
            } catch (exception: Exception) {
                if (importGeneration != setupImportGeneration) return@launch
                showError("The CA certificate could not be read.", technicalMessage(exception))
            }
        }
    }

    fun importIkevProfile(uri: Uri) {
        val state = mutableUiState.value
        if (state.connectionState in ACTIVE_CONNECTION_STATES) {
            showError(
                "Disconnect the VPN before importing another profile.",
                "Portable profile import was blocked while the current state was " +
                    "${state.connectionState}.",
            )
            return
        }

        val importGeneration = ++setupImportGeneration
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            try {
                val imported = ikevProfileImporter.import(uri)
                if (importGeneration != setupImportGeneration) return@launch
                selectedCertificate = imported.certificate
                mutableUiState.update { current ->
                    current.copy(
                        screen = AppScreen.SETUP,
                        profileName = imported.config.profileName,
                        serverAddress = imported.config.serverAddress,
                        username = imported.config.username,
                        certificateInfo = imported.certificate.info,
                        importedProfileInfo = ImportedProfileUiInfo(
                            remoteId = imported.remoteId,
                            serverProfile = imported.serverProfile,
                            proxySummary = proxySummary(imported.proxy),
                            importRevision = importGeneration,
                        ),
                        fieldErrors = current.fieldErrors - setOf(
                            ProfileField.PROFILE_NAME,
                            ProfileField.SERVER_ADDRESS,
                            ProfileField.USERNAME,
                            ProfileField.CERTIFICATE,
                            ProfileField.PASSWORD,
                        ),
                        isBusy = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IkevProfileImportException) {
                if (importGeneration != setupImportGeneration) return@launch
                showError(exception.message, technicalMessage(exception))
            } catch (exception: CertificateLoadException) {
                if (importGeneration != setupImportGeneration) return@launch
                showError(exception.message, technicalMessage(exception))
            } catch (exception: Exception) {
                if (importGeneration != setupImportGeneration) return@launch
                showError("The .ikev profile could not be read.", technicalMessage(exception))
            }
        }
    }

    /** Password is consumed synchronously and is never copied into VpnUiState or persisted state. */
    fun provisionProfile(password: String) {
        val state = mutableUiState.value
        if (state.configured && state.connectionState in ACTIVE_CONNECTION_STATES) {
            showError(
                "Disconnect the VPN before editing or reprovisioning it.",
                "Provisioning was blocked while the current state was ${state.connectionState}.",
            )
            return
        }
        val certificate = selectedCertificate
        val validation = ProfileValidator.validate(
            profileName = state.profileName,
            serverAddress = state.serverAddress,
            username = state.username,
            password = password,
            hasCertificate = certificate != null,
        )
        if (!validation.isValid) {
            mutableUiState.update { it.copy(fieldErrors = validation.errors) }
            return
        }
        if (!vpnController.isPlatformSupported()) {
            showError(
                "The device does not support Android platform IKEv2 VPN profiles.",
                "PackageManager.FEATURE_IPSEC_TUNNELS is absent.",
            )
            return
        }

        val config = VpnProfileConfig(
            profileName = state.profileName.trim(),
            serverAddress = state.serverAddress.trim(),
            username = state.username.trim(),
        )
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            var staged = false
            var platformProfileReplaced = false
            try {
                profileRepository.stageProfile(config, certificate!!)
                staged = true
                ensureActive()
                when (val result = vpnController.provision(config, password, certificate.certificate)) {
                    is VpnResult.Failure -> {
                        abortStagedProvision(
                            result.userMessage,
                            result.technicalMessage,
                            platformProfileReplaced = false,
                        )
                    }
                    is VpnResult.Success -> {
                        platformProfileReplaced = true
                        when (val action = result.value) {
                            ProvisioningAction.Complete -> commitStagedProvision()
                            is ProvisioningAction.ConsentRequired -> {
                                consentRequestActive = true
                                mutableUiState.update {
                                    it.copy(
                                        configured = false,
                                        provisioningStatus = ProvisioningStatus.PENDING_CONSENT,
                                        connectionState = ConnectionState.NOT_CONFIGURED,
                                        isBusy = false,
                                    )
                                }
                                effectChannel.send(VpnUiEffect.RequestVpnConsent(action.intent))
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                if (staged && !platformProfileReplaced) {
                    withContext(NonCancellable) {
                        runCatching { profileRepository.abortStagedProfile(platformProfileReplaced = false) }
                    }
                }
                throw exception
            } catch (exception: Exception) {
                abortStagedProvision(
                    userMessage = if (platformProfileReplaced) {
                        "The VPN profile could not be saved."
                    } else {
                        "The VPN profile could not be staged."
                    },
                    technical = technicalMessage(exception),
                    platformProfileReplaced = platformProfileReplaced,
                )
            }
        }
    }

    fun onVpnConsentResult(granted: Boolean) {
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            val snapshot = try {
                profileRepository.snapshots.first()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError("The VPN consent result could not be read.", technicalMessage(exception))
                return@launch
            }
            if (!consentRequestActive && snapshot.pendingProfile == null) {
                showError(
                    "The VPN consent request is no longer active. Provision the profile again.",
                    "Ignored an Activity Result without a staged PENDING_CONSENT profile.",
                )
                return@launch
            }
            consentRequestActive = false

            if (granted) {
                try {
                    commitStagedProvision()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    abortStagedProvision(
                        userMessage = "The VPN consent result could not be saved.",
                        technical = technicalMessage(exception),
                        platformProfileReplaced = true,
                    )
                }
            } else {
                abortStagedProvision(
                    userMessage = "VPN permission was denied.",
                    technical = "Android VPN consent activity returned a non-success result.",
                    platformProfileReplaced = true,
                )
            }
        }
    }

    fun connect() {
        if (!mutableUiState.value.configured) {
            showError("VPN configuration is incomplete.", "Connect requested without a provisioned profile.")
            return
        }
        when (val result = vpnController.connect()) {
            is VpnResult.Success -> mutableUiState.update {
                it.copy(sessionId = result.value ?: it.sessionId, error = null, technicalError = null)
            }
            is VpnResult.Failure -> {
                showError(result.userMessage, result.technicalMessage)
                recordError(result.userMessage, result.technicalMessage)
            }
        }
    }

    fun disconnect() {
        when (val result = vpnController.disconnect()) {
            is VpnResult.Success -> Unit
            is VpnResult.Failure -> {
                showError(result.userMessage, result.technicalMessage)
                recordError(result.userMessage, result.technicalMessage)
            }
        }
    }

    fun refreshState() {
        vpnController.refreshState()
    }

    fun showSetup() {
        val state = mutableUiState.value
        if (state.connectionState in ACTIVE_CONNECTION_STATES) {
            showError(
                "Disconnect the VPN before editing its profile.",
                "Profile editing was blocked while the current state was ${state.connectionState}.",
            )
            return
        }
        latestSnapshot.profile?.let { profile ->
            selectedCertificate = profile.certificate
            setupImportGeneration += 1
            mutableUiState.update {
                it.copy(
                    screen = AppScreen.SETUP,
                    profileName = profile.config.profileName,
                    serverAddress = profile.config.serverAddress,
                    username = profile.config.username,
                    certificateInfo = profile.certificate.info,
                    importedProfileInfo = null,
                    fieldErrors = emptyMap(),
                )
            }
        } ?: mutableUiState.update { it.copy(screen = AppScreen.SETUP, fieldErrors = emptyMap()) }
    }

    fun showMain() {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { current ->
            val committed = latestSnapshot.profile?.takeIf {
                it.status == ProvisioningStatus.PROVISIONED
            }
            if (current.screen == AppScreen.SETUP && current.configured && committed != null) {
                setupImportGeneration += 1
                selectedCertificate = committed.certificate
                current.copy(
                    screen = AppScreen.MAIN,
                    profileName = committed.config.profileName,
                    serverAddress = committed.config.serverAddress,
                    username = committed.config.username,
                    certificateInfo = committed.certificate.info,
                    importedProfileInfo = null,
                    fieldErrors = emptyMap(),
                    isBusy = false,
                    error = null,
                    technicalError = null,
                )
            } else {
                current.copy(screen = if (current.configured) AppScreen.MAIN else AppScreen.SETUP)
            }
        }
    }

    fun showDiagnostics() {
        vpnController.refreshState()
        mutableUiState.update { it.copy(screen = AppScreen.DIAGNOSTICS) }
    }

    fun dismissError() {
        mutableUiState.update { it.copy(error = null, technicalError = null) }
    }

    private fun applyRepositorySnapshot(snapshot: RepositorySnapshot) {
        latestSnapshot = snapshot
        val profile = snapshot.profile
        val displayedProfile = snapshot.pendingProfile ?: profile
        val configured = profile?.status == ProvisioningStatus.PROVISIONED && snapshot.pendingProfile == null
        mutableUiState.update { current ->
            val draft = if (!draftInitialized && displayedProfile != null) {
                draftInitialized = true
                selectedCertificate = displayedProfile.certificate
                current.copy(
                    profileName = displayedProfile.config.profileName,
                    serverAddress = displayedProfile.config.serverAddress,
                    username = displayedProfile.config.username,
                    certificateInfo = displayedProfile.certificate.info,
                )
            } else {
                current
            }
            val initialScreen = if (!initialNavigationComplete) {
                initialNavigationComplete = true
                if (configured) AppScreen.MAIN else AppScreen.SETUP
            } else {
                draft.screen
            }
            val platformState = vpnController.state.value
            draft.copy(
                initialized = true,
                screen = initialScreen,
                configured = configured,
                provisioningStatus = snapshot.pendingProfile?.status ?: profile?.status,
                connectionState = if (configured) {
                    platformState.connectionState
                } else {
                    ConnectionState.NOT_CONFIGURED
                },
                stateEvidence = if (configured) platformState.evidence else StateEvidence.NONE,
                stateConfirmed = configured && platformState.confirmed,
                stateDetail = if (configured) platformState.detail else "Provision the profile before connecting.",
                lastVpnError = snapshot.diagnostics.lastError,
                lastTechnicalError = snapshot.diagnostics.lastTechnicalError,
                lastErrorTimestamp = snapshot.diagnostics.lastErrorTimestamp,
                repositoryError = snapshot.diagnostics.repositoryError,
            )
        }
    }

    private suspend fun commitStagedProvision() {
        profileRepository.commitStagedProfile()
        runCatching { profileRepository.clearError() }
        val committed = profileRepository.snapshots.first().profile
            ?: throw IllegalStateException("The committed VPN profile is unavailable.")
        selectedCertificate = committed.certificate
        mutableUiState.update {
            it.copy(
                profileName = committed.config.profileName,
                serverAddress = committed.config.serverAddress,
                username = committed.config.username,
                certificateInfo = committed.certificate.info,
                configured = true,
                provisioningStatus = ProvisioningStatus.PROVISIONED,
                screen = AppScreen.MAIN,
                connectionState = ConnectionState.DISCONNECTED,
                isBusy = false,
                fieldErrors = emptyMap(),
                error = null,
                technicalError = null,
                importedProfileInfo = null,
            )
        }
        vpnController.refreshState()
    }

    private fun recordError(userMessage: String, technical: String) {
        viewModelScope.launch {
            runCatching { profileRepository.recordError(userMessage, technical) }
        }
    }

    private suspend fun abortStagedProvision(
        userMessage: String,
        technical: String,
        platformProfileReplaced: Boolean,
    ) {
        consentRequestActive = false
        val technicalDetails = mutableListOf(technical)
        if (platformProfileReplaced) {
            when (val deleteResult = vpnController.deleteProvisionedProfile()) {
                is VpnResult.Failure -> technicalDetails += deleteResult.technicalMessage
                is VpnResult.Success -> Unit
            }
        }
        try {
            profileRepository.abortStagedProfile(platformProfileReplaced)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            technicalDetails += "Could not discard the staged profile: ${technicalMessage(exception)}"
        }
        try {
            profileRepository.recordError(userMessage, technicalDetails.joinToString(" | "))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            technicalDetails += "Could not persist diagnostics: ${technicalMessage(exception)}"
        }
        val restored = runCatching { profileRepository.snapshots.first().profile }.getOrNull()
        if (restored != null) {
            selectedCertificate = restored.certificate
        }
        val restoredConfigured = restored?.status == ProvisioningStatus.PROVISIONED &&
            !platformProfileReplaced
        mutableUiState.update {
            it.copy(
                screen = AppScreen.SETUP,
                profileName = restored?.config?.profileName ?: it.profileName,
                serverAddress = restored?.config?.serverAddress ?: it.serverAddress,
                username = restored?.config?.username ?: it.username,
                certificateInfo = restored?.certificate?.info ?: it.certificateInfo,
                configured = restoredConfigured,
                provisioningStatus = restored?.status ?: ProvisioningStatus.DRAFT,
                connectionState = if (restoredConfigured) ConnectionState.DISCONNECTED else ConnectionState.NOT_CONFIGURED,
                stateEvidence = StateEvidence.NONE,
                stateConfirmed = false,
                stateDetail = if (restoredConfigured) {
                    "The previous VPN profile is still provisioned."
                } else {
                    "Provision the profile before connecting."
                },
                error = userMessage,
                technicalError = technicalDetails.joinToString(" | "),
                isBusy = false,
            )
        }
    }

    private fun showError(userMessage: String, technical: String) {
        mutableUiState.update {
            it.copy(error = userMessage, technicalError = technical, isBusy = false)
        }
    }

    private fun technicalMessage(exception: Exception): String {
        return DiagnosticSanitizer.exceptionType(exception)
    }

    private fun updateDraft(
        field: ProfileField,
        transform: VpnUiState.() -> VpnUiState,
    ) {
        mutableUiState.update { current ->
            current.transform().copy(
                importedProfileInfo = null,
                fieldErrors = current.fieldErrors - field,
            )
        }
    }

    private fun proxySummary(proxy: ImportedProxyMetadata): String {
        return if (proxy.enabled) {
            "Proxy Mode available: ${proxy.host}:${proxy.port}\n" +
                "Android v1.1 currently uses Full Tunnel only."
        } else {
            "Proxy Mode not advertised"
        }
    }

    class Factory(
        private val profileRepository: ProfileRepository,
        private val certificateImporter: CertificateImporter,
        private val ikevProfileImporter: IkevProfileImporter,
        private val vpnController: VpnPlatformController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VpnViewModel::class.java))
            return VpnViewModel(
                profileRepository,
                certificateImporter,
                ikevProfileImporter,
                vpnController,
            ) as T
        }
    }

    private companion object {
        val ACTIVE_CONNECTION_STATES = setOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.DISCONNECTING,
            ConnectionState.UNKNOWN,
        )
    }
}
