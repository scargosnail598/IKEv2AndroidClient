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
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import com.saeed.ikev2vpn.diagnostics.DiagnosticSanitizer
import com.saeed.ikev2vpn.profile.IkevProfileShareException
import com.saeed.ikev2vpn.profile.IkevShareEncoder
import com.saeed.ikev2vpn.profile.IkevProfileImportException
import com.saeed.ikev2vpn.profile.IkevProfileImporter
import com.saeed.ikev2vpn.profile.ImportedProxyMetadata
import com.saeed.ikev2vpn.profile.ImportedIkevShare
import com.saeed.ikev2vpn.validation.ProfileField
import com.saeed.ikev2vpn.validation.ProfileValidator
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.AndroidServerPingProbe
import com.saeed.ikev2vpn.vpn.ServerPingProbe
import com.saeed.ikev2vpn.vpn.ServerPingSession
import com.saeed.ikev2vpn.vpn.ServerPingTarget
import com.saeed.ikev2vpn.vpn.ProvisioningAction
import com.saeed.ikev2vpn.vpn.StateEvidence
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import com.saeed.ikev2vpn.vpn.VpnResult
import com.saeed.ikev2vpn.vpn.currentTraffic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    pingProbe: ServerPingProbe = AndroidServerPingProbe(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        VpnUiState(platformSupported = vpnController.isPlatformSupported()),
    )
    private val effectChannel = Channel<VpnUiEffect>(Channel.BUFFERED)
    private var latestSnapshot = RepositorySnapshot()
    private var acceptsSharedPasswords = true
    private var secretGeneration = 0
    private var importedPassword: String? = null
    private var selectedCertificate: LoadedCertificate? = null
    private var passwordRequestedProfile: com.saeed.ikev2vpn.data.StoredProfile? = null
    private var draftProfileId = "legacy"
    private var draftInitialized = false
    private var initialNavigationComplete = false
    private var setupImportGeneration = 0
    private var consentRequestActive = false
    private val persistedConnectionEventKeys = mutableSetOf<String>()

    val uiState: StateFlow<VpnUiState> = mutableUiState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()
    private val pingSession = ServerPingSession(viewModelScope, pingProbe)
    val serverPing = pingSession.reading

    fun refreshServerPing() = pingSession.refresh()

    init {
        viewModelScope.launch {
            uiState.map { state ->
                if (state.configured && state.connectionState == ConnectionState.CONNECTED && state.stateConfirmed) {
                    ServerPingTarget(state.selectedProfileId, state.serverAddress, state.sessionId, state.appRouting)
                } else null
            }.distinctUntilChanged().collect(pingSession::setTarget)
        }
        viewModelScope.launch {
            profileRepository.snapshots.collect(::applyRepositorySnapshot)
        }
        viewModelScope.launch {
            vpnController.state.collect { platformState ->
                persistNewConnectionEvents(platformState.events)
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
                            sessionId = null,
                            connectedSinceMillis = null,
                            traffic = null,
                            connectionEvents = mergeConnectionEvents(
                                latestSnapshot.diagnostics.connectionEvents,
                                platformState.events,
                            ),
                        )
                    } else {
                        current.copy(
                            configured = true,
                            connectionState = platformState.connectionState,
                            stateEvidence = platformState.evidence,
                            stateConfirmed = platformState.confirmed,
                            stateDetail = platformState.detail,
                            connectionEvents = mergeConnectionEvents(
                                latestSnapshot.diagnostics.connectionEvents,
                                platformState.events,
                            ),
                            sessionId = platformState.sessionId.takeIf { platformState.connectionState in ACTIVE_CONNECTION_STATES },
                            traffic = platformState.currentTraffic(),
                            connectedSinceMillis = platformState.connectedSinceMillis.takeIf { platformState.connectionState == ConnectionState.CONNECTED },
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

    fun updateProfileName(value: String) = updateDraft(ProfileField.PROFILE_NAME, preserveImportedInfo = true) {
        copy(profileName = value)
    }

    fun updateServerAddress(value: String) = updateDraft(ProfileField.SERVER_ADDRESS) {
        copy(serverAddress = value)
    }

    fun updateUsername(value: String) = updateDraft(ProfileField.USERNAME) {
        copy(username = value)
    }

    fun updateAppRoutingMode(mode: AppRoutingMode) = updateDraft(ProfileField.APPLICATIONS, preserveImportedInfo = true) {
        copy(appRouting = appRouting.copy(mode = mode))
    }

    fun toggleApplication(packageName: String) = updateDraft(ProfileField.APPLICATIONS, preserveImportedInfo = true) {
        val packages = appRouting.packages
        copy(appRouting = appRouting.copy(packages = if (packageName in packages) {
            packages - packageName
        } else {
            packages + packageName
        }))
    }

    fun importCertificate(uri: Uri) {
        clearImportedPassword()
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
                        usesPublicCertificateTrust = false,
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

    fun importIkevProfile(uri: Uri) = importProfile {
        ImportedIkevShare(ikevProfileImporter.import(uri), null)
    }

    fun importQrImage(uri: Uri) = importProfile { ikevProfileImporter.importQrImage(uri) }

    fun importConfigurationLink(text: String) {
        viewModelScope.launch {
            // External links can arrive before the first repository snapshot.
            uiState.first { it.initialized }
            importProfile { ikevProfileImporter.importLink(text) }
        }
    }

    private fun importProfile(load: suspend () -> ImportedIkevShare) {
        if (!canChangeProfile()) {
            mutableUiState.update { it.copy(
                error = "Disconnect the VPN and finish the current operation before importing a profile.",
                technicalError = "Profile import is unavailable in the current VPN state.",
            ) }
            return
        }
        val importGeneration = ++setupImportGeneration
        clearImportedPassword()
        val importSecretGeneration = secretGeneration
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            try {
                val shared = load()
                if (importGeneration != setupImportGeneration) return@launch
                val imported = shared.profile
                draftInitialized = true
                draftProfileId = java.util.UUID.randomUUID().toString()
                selectedCertificate = imported.certificate
                importedPassword = shared.password.takeIf { acceptsSharedPasswords && secretGeneration == importSecretGeneration }
                mutableUiState.update { current ->
                    current.copy(
                        screen = AppScreen.SETUP,
                        isEditingProfile = false, editingProfileId = null, canReuseSavedPassword = false,
                        draftRevision = current.draftRevision + 1,
                        profileName = imported.config.profileName,
                        serverAddress = imported.config.serverAddress,
                        username = imported.config.username,
                        appRouting = AppRoutingConfig(),
                        certificateInfo = imported.certificate?.info,
                        usesPublicCertificateTrust = imported.usesPublicCertificateTrust,
                        hasImportedPassword = importedPassword != null,
                        importReplacementProfileId = null,
                        importedProfileInfo = ImportedProfileUiInfo(
                            remoteId = imported.remoteId,
                            serverProfile = imported.serverProfile,
                            proxySummary = proxySummary(imported.proxy),
                            importRevision = importGeneration,
                        ),
                        fieldErrors = emptyMap(),
                        isBusy = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IkevProfileImportException) {
                if (importGeneration == setupImportGeneration) showError(exception.message, technicalMessage(exception))
            } catch (exception: CertificateLoadException) {
                if (importGeneration == setupImportGeneration) showError(exception.message, technicalMessage(exception))
            } catch (exception: Exception) {
                if (importGeneration == setupImportGeneration) {
                    showError("The configuration could not be imported.", technicalMessage(exception))
                }
            }
        }
    }

    fun setImportForeground(foreground: Boolean) {
        acceptsSharedPasswords = foreground
        if (!foreground) clearImportedPassword()
    }

    fun clearImportedPassword() {
        secretGeneration += 1
        importedPassword = null
        mutableUiState.update { it.copy(hasImportedPassword = false) }
    }

    fun replaceImportedProfile(profileId: String?) {
        val state = uiState.value
        if (!canChangeProfile() || state.importedProfileInfo == null) return
        val existing = state.profiles.firstOrNull { it.config.id == profileId }
        if (profileId != null && (existing == null ||
                existing.config.serverAddress != state.serverAddress || existing.config.username != state.username)) return
        draftProfileId = existing?.config?.id ?: java.util.UUID.randomUUID().toString()
        mutableUiState.update { it.copy(importReplacementProfileId = existing?.config?.id) }
    }

    /** Password is consumed synchronously and is never copied into VpnUiState or persisted state. */
    fun provisionProfile(password: String) {
        val state = mutableUiState.value
        val reusePassword = state.isEditingProfile && state.canReuseSavedPassword && password.isEmpty()
        val effectivePassword = password.ifEmpty { importedPassword.orEmpty() }
        if (state.isBusy || consentRequestActive) return
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
            password = if (reusePassword) "saved-credential" else effectivePassword,
            hasCertificate = certificate != null || state.usesPublicCertificateTrust,
            appRouting = state.appRouting,
        )
        if (!validation.isValid) {
            mutableUiState.update { it.copy(fieldErrors = validation.errors) }
            return
        }
        if (!vpnController.isPlatformSupported()) {
            showError(
                "The device does not support strongSwan VPN tunnels.",
                "The bundled strongSwan VPN engine is unavailable on this device.",
            )
            return
        }

        val config = VpnProfileConfig(
            profileName = state.profileName.trim(),
            serverAddress = state.serverAddress.trim(),
            username = state.username.trim(),
            id = draftProfileId,
            appRouting = state.appRouting,
        )
        clearImportedPassword()
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            var staged = false
            var platformProfileReplaced = false
            try {
                retainActiveCredential()
                profileRepository.stageProfile(config, certificate)
                staged = true
                ensureActive()
                val provisioning = if (reusePassword) vpnController.restoreProfile(config, certificate?.certificate)
                    else vpnController.provision(config, effectivePassword, certificate?.certificate)
                when (val result = provisioning) {
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
                vpnController.clearTransientSecrets()
                abortStagedProvision(
                    userMessage = "VPN permission was denied.",
                    technical = "strongSwan VPN consent activity returned a non-success result.",
                    platformProfileReplaced = true,
                )
            }
        }
    }

    fun connect() {
        if (mutableUiState.value.isBusy || consentRequestActive) return
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
        vpnController.clearTransientSecrets()
        when (val result = vpnController.disconnect()) {
            is VpnResult.Success -> Unit
            is VpnResult.Failure -> {
                showError(result.userMessage, result.technicalMessage)
                recordError(result.userMessage, result.technicalMessage)
            }
        }
    }

    /** The result contains credentials only on explicit request and never enters VpnUiState. */
    suspend fun createProfileShareLink(id: String, includePassword: Boolean): String {
        val profile = latestSnapshot.profiles.firstOrNull { it.config.id == id }
            ?: latestSnapshot.profile?.takeIf { it.config.id == id }
            ?: throw IkevProfileShareException("This profile is no longer available.")
        val password = if (includePassword) {
            withContext(Dispatchers.IO) {
                try {
                    vpnController.loadProfileCredential(id)
                } catch (_: Exception) {
                    throw IkevProfileShareException(
                        "The saved password could not be read. Share without a password or save the profile again.",
                    )
                }
            } ?: throw IkevProfileShareException(
                "This profile has no saved password. Share without a password or save the profile again.",
            )
        } else null
        return withContext(Dispatchers.Default) {
            IkevShareEncoder.encode(profile, password)
        }
    }

    fun refreshState() {
        vpnController.refreshState()
    }

    fun addProfile() {
        clearImportedPassword()
        if (!canChangeProfile()) return
        draftProfileId = java.util.UUID.randomUUID().toString()
        selectedCertificate = null
        setupImportGeneration += 1
        mutableUiState.update {
            it.copy(screen = AppScreen.SETUP, isEditingProfile = false, editingProfileId = null, canReuseSavedPassword = false, draftRevision = it.draftRevision + 1, profileName = "", serverAddress = "", username = "",
                appRouting = AppRoutingConfig(),
                certificateInfo = null, usesPublicCertificateTrust = false, importedProfileInfo = null,
                fieldErrors = emptyMap(), error = null, technicalError = null)
        }
    }

    fun selectProfile(id: String) {
        clearImportedPassword()
        if (!canChangeProfile()) return
        val target = latestSnapshot.profiles.firstOrNull { it.config.id == id }
            ?: latestSnapshot.profile?.takeIf { it.config.id == id }
            ?: return
        if (id == latestSnapshot.profile?.config?.id && mutableUiState.value.configured) {
            showMain()
            return
        }
        try {
            retainActiveCredential()
            if (!vpnController.hasSavedCredential(id)) {
                passwordRequestedProfile = target
                mutableUiState.update { it.copy(credentialPromptProfileName = target.config.profileName) }
                return
            }
        } catch (exception: Exception) {
            showError("The saved password could not be read.", technicalMessage(exception))
            return
        }
        activateProfile(target, null)
    }

    fun cancelProfilePassword() {
        if (mutableUiState.value.isBusy) return
        passwordRequestedProfile = null
        mutableUiState.update { it.copy(credentialPromptProfileName = null) }
    }

    fun submitProfilePassword(password: String) {
        val target = passwordRequestedProfile ?: return
        if (mutableUiState.value.isBusy || consentRequestActive ||
            mutableUiState.value.connectionState in ACTIVE_CONNECTION_STATES || password.isEmpty()) return
        passwordRequestedProfile = null
        mutableUiState.update { it.copy(credentialPromptProfileName = null) }
        activateProfile(target, password)
    }

    private fun retainActiveCredential() {
        latestSnapshot.profile?.takeIf { it.status == ProvisioningStatus.PROVISIONED }?.let {
            vpnController.retainCurrentCredential(it.config.id)
        }
    }

    private fun activateProfile(target: com.saeed.ikev2vpn.data.StoredProfile, password: String?) {
        setupImportGeneration += 1
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            var staged = false
            var platformReplaced = false
            try {
                profileRepository.stageProfile(target.config, target.certificate)
                staged = true
                val result = if (password == null) {
                    vpnController.restoreProfile(target.config, target.certificate?.certificate)
                } else {
                    vpnController.provision(target.config, password, target.certificate?.certificate)
                }
                when (result) {
                    is VpnResult.Failure -> {
                        profileRepository.abortStagedProfile(false)
                        showError(result.userMessage, result.technicalMessage)
                    }
                    is VpnResult.Success -> {
                        platformReplaced = true
                        when (val action = result.value) {
                            ProvisioningAction.Complete -> commitStagedProvision()
                            is ProvisioningAction.ConsentRequired -> {
                                consentRequestActive = true
                                effectChannel.send(VpnUiEffect.RequestVpnConsent(action.intent))
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                if (staged && !platformReplaced) withContext(NonCancellable) {
                    runCatching { profileRepository.abortStagedProfile(false) }
                }
                throw exception
            } catch (exception: Exception) {
                if (staged) runCatching { profileRepository.abortStagedProfile(platformReplaced) }
                showError("The profile could not be selected.", technicalMessage(exception))
            }
        }
    }

    private fun canChangeProfile(): Boolean = mutableUiState.value.credentialPromptProfileName == null && !mutableUiState.value.isBusy &&
        !consentRequestActive && latestSnapshot.pendingProfile == null &&
        mutableUiState.value.connectionState !in ACTIVE_CONNECTION_STATES

    fun showSetup() {
        if (mutableUiState.value.connectionState in ACTIVE_CONNECTION_STATES) {
            mutableUiState.update { it.copy(error = "Disconnect the VPN before editing its profile.") }
            return
        }
        latestSnapshot.profile?.let { editProfile(it.config.id) } ?: addProfile()
    }

    fun editProfile(id: String) {
        if (!canChangeProfile()) return
        val profile = latestSnapshot.profiles.firstOrNull { it.config.id == id }
            ?: latestSnapshot.profile?.takeIf { it.config.id == id } ?: return
        clearImportedPassword()
        val reusable = runCatching { vpnController.hasSavedCredential(id) }.getOrDefault(false)
        draftInitialized = true
        draftProfileId = profile.config.id
        selectedCertificate = profile.certificate
        setupImportGeneration += 1
        mutableUiState.update {
            it.copy(
                screen = AppScreen.SETUP, isEditingProfile = true, editingProfileId = id, canReuseSavedPassword = reusable,
                draftRevision = it.draftRevision + 1,
                profileName = profile.config.profileName, serverAddress = profile.config.serverAddress,
                username = profile.config.username, appRouting = profile.config.appRouting,
                certificateInfo = profile.certificate?.info, usesPublicCertificateTrust = profile.certificate == null,
                importedProfileInfo = null, importReplacementProfileId = null,
                fieldErrors = emptyMap(), error = null, technicalError = null,
            )
        }
    }

    fun showMain() = leaveEditor(AppScreen.MAIN)
    fun showProfiles() = leaveEditor(AppScreen.PROFILES)

    private fun leaveEditor(screen: AppScreen) {
        if (mutableUiState.value.isBusy || consentRequestActive) return
        clearImportedPassword()
        setupImportGeneration += 1
        val committed = latestSnapshot.profile
        selectedCertificate = committed?.certificate
        draftProfileId = committed?.config?.id ?: "legacy"
        mutableUiState.update {
            it.copy(
                screen = screen, isEditingProfile = false, editingProfileId = null, canReuseSavedPassword = false,
                profileName = committed?.config?.profileName.orEmpty(),
                serverAddress = committed?.config?.serverAddress.orEmpty(), username = committed?.config?.username.orEmpty(),
                appRouting = committed?.config?.appRouting ?: AppRoutingConfig(),
                certificateInfo = committed?.certificate?.info,
                usesPublicCertificateTrust = committed != null && committed.certificate == null,
                importedProfileInfo = null, importReplacementProfileId = null,
                fieldErrors = emptyMap(), error = null, technicalError = null,
            )
        }
    }

    fun renameProfile(id: String, name: String) {
        if (!canChangeProfile()) return
        val value = name.trim()
        if (!ProfileValidator.isValidProfileName(value)) {
            showError("Enter a profile name with 128 characters or fewer.", "Invalid profile name.")
            return
        }
        val profile = mutableUiState.value.profiles.firstOrNull { it.config.id == id } ?: return
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            val active = latestSnapshot.profile?.config?.id == id
            var engineRenamed = false
            try {
                if (active && latestSnapshot.profile?.status == ProvisioningStatus.PROVISIONED) {
                    when (val result = vpnController.renameProvisionedProfile(value)) {
                        is VpnResult.Failure -> { showError(result.userMessage, result.technicalMessage); return@launch }
                        is VpnResult.Success -> engineRenamed = true
                    }
                }
                profileRepository.renameProfile(id, value)
                mutableUiState.update { it.copy(isBusy = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (engineRenamed) vpnController.renameProvisionedProfile(profile.config.profileName)
                showError("The profile could not be renamed.", technicalMessage(exception))
            }
        }
    }

    fun deleteProfile(id: String) {
        if (!canChangeProfile() || mutableUiState.value.profiles.none { it.config.id == id }) return
        clearImportedPassword()
        mutableUiState.update { it.copy(isBusy = true, error = null, technicalError = null) }
        viewModelScope.launch {
            var engineRemoved = false
            try {
                if (latestSnapshot.profile?.config?.id == id) {
                    when (val result = vpnController.deleteProvisionedProfile()) {
                        is VpnResult.Failure -> { showError(result.userMessage, result.technicalMessage); return@launch }
                        is VpnResult.Success -> engineRemoved = true
                    }
                }
                profileRepository.deleteProfile(id)
                applyRepositorySnapshot(profileRepository.snapshots.first())
                vpnController.forgetProfileCredential(id)
                mutableUiState.update { it.copy(isBusy = false) }
                showProfiles()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (engineRemoved) runCatching { profileRepository.setProvisioningStatus(ProvisioningStatus.NEEDS_REPROVISION) }
                showError("The profile could not be completely removed. Try again.", technicalMessage(exception))
            }
        }
    }

    fun showTrafficBypass() = leaveEditor(AppScreen.TRAFFIC_BYPASS)

    fun showAbout() {
        if (mutableUiState.value.isBusy) return
        clearImportedPassword()
        mutableUiState.update { it.copy(screen = AppScreen.ABOUT) }
    }

    fun showDiagnostics() {
        vpnController.refreshState()
        mutableUiState.update { it.copy(screen = AppScreen.DIAGNOSTICS) }
    }

    fun dismissError() {
        mutableUiState.update { it.copy(error = null, technicalError = null) }
    }

    fun clearConnectionLog() {
        vpnController.clearEventLog()
        persistedConnectionEventKeys.clear()
        mutableUiState.update { it.copy(connectionEvents = emptyList()) }
        viewModelScope.launch { runCatching { profileRepository.clearConnectionEvents() } }
    }

    private fun applyRepositorySnapshot(snapshot: RepositorySnapshot) {
        latestSnapshot = snapshot
        val profile = snapshot.profile
        val displayedProfile = snapshot.pendingProfile ?: profile
        val configured = profile?.status == ProvisioningStatus.PROVISIONED && snapshot.pendingProfile == null
        mutableUiState.update { current ->
            val draft = if ((!draftInitialized || current.screen != AppScreen.SETUP) && displayedProfile != null) {
                draftInitialized = true
                draftProfileId = displayedProfile.config.id
                selectedCertificate = displayedProfile.certificate
                current.copy(
                    profileName = displayedProfile.config.profileName,
                    serverAddress = displayedProfile.config.serverAddress,
                    username = displayedProfile.config.username,
                    appRouting = displayedProfile.config.appRouting,
                    certificateInfo = displayedProfile.certificate?.info,
                    usesPublicCertificateTrust = displayedProfile.certificate == null,
                )
            } else if (current.screen != AppScreen.SETUP && displayedProfile == null) {
                current.copy(profileName = "", serverAddress = "", username = "", appRouting = AppRoutingConfig(),
                    certificateInfo = null, usesPublicCertificateTrust = false)
            } else {
                current
            }
            val initialScreen = if (!initialNavigationComplete) {
                initialNavigationComplete = true
                AppScreen.MAIN
            } else {
                draft.screen
            }
            val platformState = vpnController.state.value
            draft.copy(
                profiles = snapshot.profiles.ifEmpty { listOfNotNull(snapshot.profile) },
                selectedProfileId = profile?.config?.id ?: "legacy",
                initialized = true,
                screen = initialScreen,
                configured = configured,
                provisioningStatus = snapshot.pendingProfile?.status ?: profile?.status,
                connectionState = if (configured) {
                    platformState.connectionState
                } else {
                    ConnectionState.NOT_CONFIGURED
                },
                traffic = platformState.currentTraffic().takeIf { configured },
                sessionId = platformState.sessionId.takeIf { configured && platformState.connectionState in ACTIVE_CONNECTION_STATES },
                connectedSinceMillis = platformState.connectedSinceMillis.takeIf { configured && platformState.connectionState == ConnectionState.CONNECTED },
                stateEvidence = if (configured) platformState.evidence else StateEvidence.NONE,
                stateConfirmed = configured && platformState.confirmed,
                stateDetail = if (configured) platformState.detail else "Provision the profile before connecting.",
                lastVpnError = snapshot.diagnostics.lastError,
                lastTechnicalError = snapshot.diagnostics.lastTechnicalError,
                lastErrorTimestamp = snapshot.diagnostics.lastErrorTimestamp,
                repositoryError = snapshot.diagnostics.repositoryError,
                connectionEvents = mergeConnectionEvents(
                    snapshot.diagnostics.connectionEvents,
                    platformState.events,
                ),
            )
        }
        persistedConnectionEventKeys += snapshot.diagnostics.connectionEvents.map(::connectionEventKey)
        persistNewConnectionEvents(vpnController.state.value.events)
    }

    private fun persistNewConnectionEvents(events: List<com.saeed.ikev2vpn.vpn.VpnLogEntry>) {
        val newEvents = events.filter { persistedConnectionEventKeys.add(connectionEventKey(it)) }
        if (newEvents.isNotEmpty()) {
            viewModelScope.launch { runCatching { profileRepository.recordConnectionEvents(newEvents) } }
        }
    }

    private fun connectionEventKey(event: com.saeed.ikev2vpn.vpn.VpnLogEntry): String =
        "${event.timestamp}|${event.message}|${event.detail.orEmpty()}"

    private fun mergeConnectionEvents(
        stored: List<com.saeed.ikev2vpn.vpn.VpnLogEntry>,
        live: List<com.saeed.ikev2vpn.vpn.VpnLogEntry>,
    ): List<com.saeed.ikev2vpn.vpn.VpnLogEntry> = (stored + live)
        .associateBy { "${it.timestamp}|${it.message}" }
        .values
        .sortedBy { it.timestamp }
        .takeLast(200)

    private suspend fun commitStagedProvision() {
        profileRepository.commitStagedProfile()
        runCatching { profileRepository.clearError() }
        val committed = profileRepository.snapshots.first().profile
            ?: throw IllegalStateException("The committed VPN profile is unavailable.")
        draftProfileId = committed.config.id
        selectedCertificate = committed.certificate
        mutableUiState.update {
            it.copy(
                profileName = committed.config.profileName,
                serverAddress = committed.config.serverAddress,
                username = committed.config.username,
                appRouting = committed.config.appRouting,
                certificateInfo = committed.certificate?.info,
                usesPublicCertificateTrust = committed.certificate == null,
                configured = true,
                provisioningStatus = ProvisioningStatus.PROVISIONED,
                screen = AppScreen.MAIN,
                connectionState = ConnectionState.DISCONNECTED,
                isBusy = false,
                fieldErrors = emptyMap(),
                error = null,
                technicalError = null,
                importedProfileInfo = null,
                isEditingProfile = false,
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
                screen = if (mutableUiState.value.screen == AppScreen.MAIN) AppScreen.MAIN else AppScreen.SETUP,
                profileName = restored?.config?.profileName ?: it.profileName,
                serverAddress = restored?.config?.serverAddress ?: it.serverAddress,
                username = restored?.config?.username ?: it.username,
                appRouting = restored?.config?.appRouting ?: it.appRouting,
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
        preserveImportedInfo: Boolean = false,
        transform: VpnUiState.() -> VpnUiState,
    ) {
        if (!preserveImportedInfo) {
            clearImportedPassword()
            draftProfileId = if (uiState.value.importedProfileInfo != null) java.util.UUID.randomUUID().toString() else draftProfileId
        }
        mutableUiState.update { current ->
            current.transform().copy(
                importReplacementProfileId = if (preserveImportedInfo) current.importReplacementProfileId else null,
                importedProfileInfo = if (preserveImportedInfo) current.importedProfileInfo else null,
                fieldErrors = current.fieldErrors - field,
            )
        }
    }

    private fun proxySummary(proxy: ImportedProxyMetadata): String {
        return if (proxy.enabled) {
            "Proxy Mode available: ${proxy.host}:${proxy.port}\n" +
                "Android v1.3 currently uses Full Tunnel only."
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
