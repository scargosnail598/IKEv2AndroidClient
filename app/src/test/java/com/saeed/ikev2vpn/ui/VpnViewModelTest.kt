package com.saeed.ikev2vpn.ui

import android.net.Uri
import com.saeed.ikev2vpn.certificate.CertificateImporter
import com.saeed.ikev2vpn.certificate.CertificateLoader
import com.saeed.ikev2vpn.certificate.CertificateLoaderTest
import com.saeed.ikev2vpn.certificate.LoadedCertificate
import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.RepositorySnapshot
import com.saeed.ikev2vpn.data.StoredDiagnostics
import com.saeed.ikev2vpn.data.StoredProfile
import com.saeed.ikev2vpn.data.VpnProfileConfig
import com.saeed.ikev2vpn.profile.IkevProfileImporter
import com.saeed.ikev2vpn.profile.ImportedIkevProfile
import com.saeed.ikev2vpn.profile.ImportedProxyMetadata
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.ProvisioningAction
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import com.saeed.ikev2vpn.vpn.VpnResult
import com.saeed.ikev2vpn.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class VpnViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `canceling edit restores the committed profile`() = runTest {
        val certificate = testCertificate()
        val repository = FakeProfileRepository(provisionedSnapshot(certificate))
        val viewModel = createViewModel(repository, FakeVpnController())

        viewModel.showSetup()
        viewModel.updateProfileName("Unsaved name")
        viewModel.updateServerAddress("draft.example.com")
        viewModel.updateUsername("draft-user")
        viewModel.showMain()

        val state = viewModel.uiState.value
        assertEquals(AppScreen.MAIN, state.screen)
        assertEquals("Saved VPN", state.profileName)
        assertEquals("vpn.example.com", state.serverAddress)
        assertEquals("saved-user", state.username)
        assertEquals(certificate.info.sha256Fingerprint, state.certificateInfo?.sha256Fingerprint)
    }

    @Test
    fun `editing is blocked while the VPN is connected`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController(
            VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true),
        )
        val viewModel = createViewModel(repository, controller)

        viewModel.showSetup()

        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("Disconnect"))
    }

    @Test
    fun `storage failure removes the platform profile and leaves an unconfigured draft`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate())).apply {
            failSave = true
        }
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.showSetup()

        viewModel.provisionProfile("temporary-password")

        val state = viewModel.uiState.value
        assertEquals(1, controller.deleteCalls)
        assertFalse(state.configured)
        assertEquals(ProvisioningStatus.DRAFT, state.provisioningStatus)
        assertEquals(ConnectionState.NOT_CONFIGURED, state.connectionState)
        assertFalse(state.toString().contains("temporary-password"))
    }

    @Test
    fun `portable import populates setup without provisioning or persistence`() = runTest {
        val certificate = testCertificate()
        val repository = FakeProfileRepository(RepositorySnapshot())
        val controller = FakeVpnController()
        val imported = ImportedIkevProfile(
            config = VpnProfileConfig("saeed@155.117.13.45", "155.117.13.45", "saeed"),
            certificate = certificate,
            remoteId = "155.117.13.45",
            serverProfile = "secure",
            proxy = ImportedProxyMetadata(true, "socks5", "10.254.254.1", 1080),
        )
        val viewModel = createViewModel(repository, controller, imported)
        val uri = mock(Uri::class.java)

        viewModel.importIkevProfile(uri)

        val state = viewModel.uiState.value
        assertEquals("saeed@155.117.13.45", state.profileName)
        assertEquals("155.117.13.45", state.serverAddress)
        assertEquals("saeed", state.username)
        assertEquals(certificate.info.sha256Fingerprint, state.certificateInfo?.sha256Fingerprint)
        assertTrue(state.importedProfileInfo?.proxySummary.orEmpty().contains("10.254.254.1:1080"))
        assertEquals(0, controller.provisionCalls)
        assertEquals(0, repository.saveCalls)
        assertFalse(state.toString().contains("password", ignoreCase = true))
    }

    @Test
    fun `portable import is blocked while connected`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController(
            VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true),
        )
        var importerCalls = 0
        val viewModel = createViewModel(
            repository = repository,
            controller = controller,
            onIkevProfileImport = { importerCalls += 1 },
        )
        val uri = mock(Uri::class.java)

        viewModel.importIkevProfile(uri)

        assertTrue(viewModel.uiState.value.error.orEmpty().contains("Disconnect"))
        assertEquals(0, controller.provisionCalls)
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
        assertEquals(0, importerCalls)
    }

    @Test
    fun `adding a profile preserves the saved profile and creates a distinct identity`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.addProfile()
        assertEquals("", viewModel.uiState.value.profileName)
        assertEquals("Saved VPN", repository.current.profile?.config?.profileName)
        viewModel.updateProfileName("Second")
        viewModel.updateServerAddress("second.example.com")
        viewModel.updateUsername("second-user")
        assertEquals(0, controller.deleteCalls)
        viewModel.showMain()
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
    }

    @Test
    fun `provisioning a new imported profile uses a new identity`() = runTest {
        val certificate = testCertificate()
        val repository = FakeProfileRepository(provisionedSnapshot(certificate))
        val imported = ImportedIkevProfile(VpnProfileConfig("Second", "second.example.com", "second-user"),
            certificate, "second.example.com", "secure", ImportedProxyMetadata(false))
        val viewModel = createViewModel(repository, FakeVpnController(), imported)
        viewModel.addProfile()
        viewModel.importIkevProfile(mock(Uri::class.java))
        viewModel.provisionProfile("temporary-password")
        assertEquals("Second", repository.current.profile?.config?.profileName)
        assertTrue(repository.current.profile?.config?.id != "legacy")
        assertFalse(viewModel.uiState.value.toString().contains("temporary-password"))
    }

    @Test
    fun `switching profiles activates directly without editing or deleting profiles`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = StoredProfile(VpnProfileConfig("Second", "second.example.com", "second-user", "second"),
            null, ProvisioningStatus.NEEDS_REPROVISION)
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.selectProfile("second")
        assertEquals(0, controller.deleteCalls)
        assertEquals("Second", viewModel.uiState.value.profileName)
        assertEquals("second", viewModel.uiState.value.selectedProfileId)
        assertTrue(viewModel.uiState.value.usesPublicCertificateTrust)
        assertTrue(viewModel.uiState.value.configured)
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertEquals("second", controller.restoredProfile?.id)
        assertEquals(null, controller.restoredCertificate)
        viewModel.selectProfile("legacy")
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
        assertEquals("legacy", controller.restoredProfile?.id)
        assertEquals(first.certificate?.certificate, controller.restoredCertificate)
    }

    @Test
    fun `adding and switching profiles are blocked while connected`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = first.copy(config = first.config.copy(profileName = "Second", id = "second"))
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController(VpnState(connectionState = ConnectionState.CONNECTED))
        val viewModel = createViewModel(repository, controller)
        viewModel.addProfile()
        viewModel.selectProfile("second")
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
        assertEquals(0, controller.deleteCalls)
        assertEquals(null, controller.restoredProfile)
    }

    @Test
    fun `selecting current profile leaves main screen and does not reprovision`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.selectProfile("legacy")
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertEquals(0, controller.provisionCalls)
        viewModel.showSetup()
        viewModel.updateProfileName("Unsaved")
        viewModel.selectProfile("legacy")
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
    }

    @Test
    fun `older profile prompts only for password and cancellation keeps active profile`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = StoredProfile(VpnProfileConfig("Second", "second.example.com", "second-user", "second"),
            null, ProvisioningStatus.NEEDS_REPROVISION)
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController().apply { savedCredentialAvailable = false }
        val viewModel = createViewModel(repository, controller)
        viewModel.selectProfile("second")
        assertEquals("Second", viewModel.uiState.value.credentialPromptProfileName)
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertEquals("legacy", repository.current.profile?.config?.id)
        viewModel.cancelProfilePassword()
        assertEquals(null, viewModel.uiState.value.credentialPromptProfileName)
        assertEquals("legacy", repository.current.profile?.config?.id)
        viewModel.selectProfile("second")
        viewModel.submitProfilePassword("secret-second")
        assertEquals("second", repository.current.profile?.config?.id)
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertFalse(viewModel.uiState.value.toString().contains("secret-second"))
        assertEquals(0, controller.deleteCalls)
    }

    @Test
    fun `failed activation preserves previous selection and stays on main`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = first.copy(config = first.config.copy(profileName = "Second", id = "second"))
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController().apply { restoreFails = true }
        val viewModel = createViewModel(repository, controller)
        viewModel.selectProfile("second")
        assertEquals("legacy", repository.current.profile?.config?.id)
        assertEquals(null, repository.current.pendingProfile)
        assertTrue(viewModel.uiState.value.configured)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        assertEquals(0, controller.deleteCalls)
    }

    private fun createViewModel(
        repository: ProfileRepository,
        controller: VpnPlatformController,
        importedProfile: ImportedIkevProfile? = null,
        onIkevProfileImport: () -> Unit = {},
    ): VpnViewModel = VpnViewModel(
        profileRepository = repository,
        certificateImporter = object : CertificateImporter {
            override suspend fun import(uri: Uri): LoadedCertificate = error("Not used in this test")
        },
        ikevProfileImporter = object : IkevProfileImporter {
            override suspend fun import(uri: Uri): ImportedIkevProfile {
                onIkevProfileImport()
                return importedProfile ?: error("Not used in this test")
            }
        },
        vpnController = controller,
    )

    private fun provisionedSnapshot(certificate: LoadedCertificate) = RepositorySnapshot(
        profile = StoredProfile(
            config = VpnProfileConfig("Saved VPN", "vpn.example.com", "saved-user"),
            certificate = certificate,
            status = ProvisioningStatus.PROVISIONED,
        ),
    )

    private fun testCertificate(): LoadedCertificate {
        val loader = CertificateLoader {
            Date.from(Instant.parse("2030-01-01T00:00:00Z"))
        }
        return loader.load(Base64.getDecoder().decode(CertificateLoaderTest.DER_BASE64))
    }

    private class FakeProfileRepository(initial: RepositorySnapshot) : ProfileRepository {
        private val mutableSnapshots = MutableStateFlow(initial)
        override val snapshots: Flow<RepositorySnapshot> = mutableSnapshots
        val current: RepositorySnapshot get() = mutableSnapshots.value
        var failSave = false
        var saveCalls = 0

        override suspend fun saveProfile(
            config: VpnProfileConfig,
            certificate: LoadedCertificate?,
            status: ProvisioningStatus,
        ) {
            saveCalls += 1
            if (failSave) error("Simulated storage failure")
            val profile = StoredProfile(config, certificate, status)
            val allProfiles = (current.profiles + listOfNotNull(current.profile)).associateBy { it.config.id }.toMutableMap()
            allProfiles[config.id] = profile
            mutableSnapshots.value = current.copy(profile = profile, pendingProfile = null, profiles = allProfiles.values.toList())
        }

        override suspend fun stageProfile(config: VpnProfileConfig, certificate: LoadedCertificate?) {
            mutableSnapshots.value = current.copy(pendingProfile = StoredProfile(config, certificate, ProvisioningStatus.PENDING_CONSENT))
        }

        override suspend fun commitStagedProfile() {
            val pending = current.pendingProfile ?: error("No pending profile")
            saveProfile(pending.config, pending.certificate, ProvisioningStatus.PROVISIONED)
        }

        override suspend fun abortStagedProfile(platformProfileReplaced: Boolean) {
            mutableSnapshots.value = current.copy(pendingProfile = null,
                profile = current.profile?.let { if (platformProfileReplaced) it.copy(status = ProvisioningStatus.DRAFT) else it })
        }

        override suspend fun setProvisioningStatus(status: ProvisioningStatus) {
            val profile = mutableSnapshots.value.profile ?: error("No profile")
            mutableSnapshots.value = mutableSnapshots.value.copy(profile = profile.copy(status = status))
        }

        override suspend fun recordError(userMessage: String, technicalMessage: String) {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                diagnostics = StoredDiagnostics(
                    lastError = userMessage,
                    lastTechnicalError = technicalMessage,
                ),
            )
        }

        override suspend fun clearError() {
            mutableSnapshots.value = mutableSnapshots.value.copy(diagnostics = StoredDiagnostics())
        }
    }

    private class FakeVpnController(
        initialState: VpnState = VpnState(connectionState = ConnectionState.DISCONNECTED),
    ) : VpnPlatformController {
        private val mutableState = MutableStateFlow(initialState)
        override val state = mutableState
        var deleteCalls = 0
        var provisionCalls = 0
        var savedCredentialAvailable = true
        var restoreFails = false
        var restoredProfile: VpnProfileConfig? = null
        var restoredCertificate: X509Certificate? = null

        override fun hasSavedCredential(profileId: String) = savedCredentialAvailable
        override fun restoreProfile(config: VpnProfileConfig, serverRootCa: X509Certificate?): VpnResult<ProvisioningAction> {
            restoredProfile = config
            restoredCertificate = serverRootCa
            return if (restoreFails) VpnResult.Failure("Could not activate profile", "Simulated restore failure")
            else VpnResult.Success(ProvisioningAction.Complete)
        }

        override fun isPlatformSupported(): Boolean = true

        override fun provision(
            config: VpnProfileConfig,
            password: String,
            serverRootCa: X509Certificate?,
        ): VpnResult<ProvisioningAction> {
            provisionCalls += 1
            return VpnResult.Success(ProvisioningAction.Complete)
        }

        override fun connect(): VpnResult<String?> = VpnResult.Success(null)
        override fun disconnect(): VpnResult<Unit> = VpnResult.Success(Unit)

        override fun deleteProvisionedProfile(): VpnResult<Unit> {
            deleteCalls += 1
            return VpnResult.Success(Unit)
        }

        override fun refreshState() = Unit
    }
}
