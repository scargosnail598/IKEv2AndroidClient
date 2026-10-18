package com.saeed.ikev2vpn.ui

import android.net.Uri
import com.saeed.ikev2vpn.certificate.CertificateImporter
import com.saeed.ikev2vpn.certificate.CertificateLoader
import com.saeed.ikev2vpn.certificate.CertificateLoaderTest
import com.saeed.ikev2vpn.certificate.LoadedCertificate
import com.saeed.ikev2vpn.data.AppRoutingConfig
import com.saeed.ikev2vpn.data.AppRoutingMode
import com.saeed.ikev2vpn.validation.ProfileField
import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.data.ProvisioningStatus
import com.saeed.ikev2vpn.data.RepositorySnapshot
import com.saeed.ikev2vpn.data.StoredDiagnostics
import com.saeed.ikev2vpn.data.StoredProfile
import com.saeed.ikev2vpn.data.VpnProfileConfig
import com.saeed.ikev2vpn.profile.IkevProfileImporter
import com.saeed.ikev2vpn.profile.IkevProfileParser
import com.saeed.ikev2vpn.profile.IkevShareDecoder
import com.saeed.ikev2vpn.profile.IkevShareDecoderTest
import com.saeed.ikev2vpn.profile.ImportedIkevShare
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import com.saeed.ikev2vpn.profile.ImportedIkevProfile
import com.saeed.ikev2vpn.profile.ImportedProxyMetadata
import com.saeed.ikev2vpn.vpn.ConnectionState
import com.saeed.ikev2vpn.vpn.ProvisioningAction
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import com.saeed.ikev2vpn.vpn.VpnResult
import com.saeed.ikev2vpn.vpn.VpnState
import com.saeed.ikev2vpn.vpn.VpnTraffic
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
        assertFalse(state.hasImportedPassword)
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

    @Test
    fun `per-app selection saves with the profile and cancel restores saved routing`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val viewModel = createViewModel(repository, FakeVpnController())
        viewModel.showSetup()
        viewModel.updateAppRoutingMode(AppRoutingMode.ONLY_SELECTED)
        viewModel.toggleApplication("com.example.browser")
        viewModel.provisionProfile("secret")
        val expected = AppRoutingConfig(AppRoutingMode.ONLY_SELECTED, setOf("com.example.browser"))
        assertEquals(expected, repository.current.profile!!.config.appRouting)
        viewModel.showSetup()
        viewModel.updateAppRoutingMode(AppRoutingMode.ALL)
        viewModel.toggleApplication("com.example.bank")
        viewModel.showMain()
        assertEquals(expected, viewModel.uiState.value.appRouting)
    }

    @Test
    fun `empty app allowlist is rejected before persistence or engine provisioning`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.showSetup()
        viewModel.updateAppRoutingMode(AppRoutingMode.ONLY_SELECTED)
        viewModel.provisionProfile("secret")
        assertTrue(ProfileField.APPLICATIONS in viewModel.uiState.value.fieldErrors)
        assertEquals(0, controller.provisionCalls)
        assertEquals(null, repository.current.pendingProfile)
    }

    @Test
    fun `switching profiles restores routing and new profiles default to all apps`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val routing = AppRoutingConfig(AppRoutingMode.EXCLUDE_SELECTED, setOf("com.example.bank"))
        val second = first.copy(config = first.config.copy(id = "second", appRouting = routing))
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.selectProfile("second")
        assertEquals(routing, controller.restoredProfile!!.appRouting)
        assertEquals(routing, viewModel.uiState.value.appRouting)
        viewModel.addProfile()
        assertEquals(AppRoutingConfig(), viewModel.uiState.value.appRouting)
    }

    @Test
    fun `share with password creates a new draft and provisions only after confirmation`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
        assertEquals(AppScreen.SETUP, viewModel.uiState.value.screen)
        assertTrue(viewModel.uiState.value.hasImportedPassword)
        assertEquals(0, controller.provisionCalls)
        assertEquals(0, repository.saveCalls)
        assertFalse(viewModel.uiState.value.toString().contains(IkevShareDecoderTest.PASSWORD))
        viewModel.provisionProfile("")
        assertEquals(IkevShareDecoderTest.PASSWORD, controller.provisionedPassword)
        assertTrue(repository.current.profile!!.config.id != "legacy")
        assertTrue(repository.current.profiles.any { it.config.id == "legacy" })
        assertFalse(viewModel.uiState.value.hasImportedPassword)
    }

    @Test
    fun `password free share requires credentials and an explicit value overrides included credentials`() = runTest {
        val repository = FakeProfileRepository(RepositorySnapshot())
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public"))
        viewModel.provisionProfile("")
        assertTrue(ProfileField.PASSWORD in viewModel.uiState.value.fieldErrors)
        assertEquals(0, controller.provisionCalls)
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
        viewModel.provisionProfile("  explicit replacement  ")
        assertEquals("  explicit replacement  ", controller.provisionedPassword)
    }

    @Test
    fun `backgrounding and identity edits discard included password`() = runTest {
        for (discard in listOf<(VpnViewModel) -> Unit>(
            { it.clearImportedPassword() }, { it.updateUsername("other") }, { it.addProfile() }, { it.showMain() },
        )) {
            val viewModel = createViewModel(FakeProfileRepository(RepositorySnapshot()), FakeVpnController())
            viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
            assertTrue(viewModel.uiState.value.hasImportedPassword)
            discard(viewModel)
            assertFalse(viewModel.uiState.value.hasImportedPassword)
            viewModel.provisionProfile("")
            assertTrue(ProfileField.PASSWORD in viewModel.uiState.value.fieldErrors)
        }
    }

    @Test
    fun `links decoded while stopped never retain their password on resume`() = runTest {
        val viewModel = createViewModel(FakeProfileRepository(RepositorySnapshot()), FakeVpnController())
        viewModel.setImportForeground(false)
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
        assertFalse(viewModel.uiState.value.hasImportedPassword)
        viewModel.setImportForeground(true)
        assertFalse(viewModel.uiState.value.hasImportedPassword)
        viewModel.provisionProfile("")
        assertTrue(ProfileField.PASSWORD in viewModel.uiState.value.fieldErrors)
    }

    @Test
    fun `decoder finishing after background clear cannot restore the included password`() = runTest {
        val pending = CompletableDeferred<ImportedIkevShare>()
        val viewModel = createViewModel(FakeProfileRepository(RepositorySnapshot()), FakeVpnController(), shareLoader = { pending.await() })
        viewModel.importConfigurationLink("pending")
        assertTrue(viewModel.uiState.value.isBusy)
        viewModel.clearImportedPassword()
        pending.complete(shareDecoder().decode(IkevShareDecoderTest.fixture("public-password")))
        assertFalse(viewModel.uiState.value.hasImportedPassword)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals("alice", viewModel.uiState.value.username)
    }

    @Test
    fun `external link waits for repository initialization before showing its draft`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val snapshots = MutableSharedFlow<RepositorySnapshot>()
        val delayed = object : ProfileRepository by repository {
            override val snapshots: Flow<RepositorySnapshot> = snapshots
        }
        val viewModel = createViewModel(delayed, FakeVpnController())
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public"))
        assertFalse(viewModel.uiState.value.initialized)
        snapshots.emit(repository.current)
        assertEquals("alice", viewModel.uiState.value.username)
        assertEquals(AppScreen.SETUP, viewModel.uiState.value.screen)
    }

    @Test
    fun `share cannot change connected profile or interrupt ongoing provisioning`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController(VpnState(connectionState = ConnectionState.CONNECTED))
        var calls = 0
        val viewModel = createViewModel(repository, controller, shareLoader = { calls++; error("Must not decode") })
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
        assertEquals(0, calls)
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("Disconnect"))

        val pending = CompletableDeferred<ImportedIkevShare>()
        val busyViewModel = createViewModel(FakeProfileRepository(RepositorySnapshot()), FakeVpnController(), shareLoader = { pending.await() })
        busyViewModel.importConfigurationLink("first")
        busyViewModel.importConfigurationLink("second")
        assertTrue(busyViewModel.uiState.value.isBusy)
        pending.complete(shareDecoder().decode(IkevShareDecoderTest.fixture("public")))
        assertFalse(busyViewModel.uiState.value.isBusy)
    }

    @Test
    fun `replacement requires an explicitly selected matching saved identity`() = runTest {
        val original = StoredProfile(VpnProfileConfig("Alice", "vpn.example.com", "alice", "alice-id"), null, ProvisioningStatus.PROVISIONED)
        val repository = FakeProfileRepository(RepositorySnapshot(profile = original, profiles = listOf(original)))
        val viewModel = createViewModel(repository, FakeVpnController())
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
        assertEquals(null, viewModel.uiState.value.importReplacementProfileId)
        viewModel.replaceImportedProfile("unknown")
        assertEquals(null, viewModel.uiState.value.importReplacementProfileId)
        viewModel.replaceImportedProfile("alice-id")
        assertEquals("alice-id", viewModel.uiState.value.importReplacementProfileId)
        viewModel.provisionProfile("")
        assertEquals("alice-id", repository.current.profile!!.config.id)
        assertEquals(1, repository.current.profiles.size)
    }

    @Test
    fun `bad share preserves current configuration and exposes no URI or secret`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val viewModel = createViewModel(repository, FakeVpnController())
        viewModel.importConfigurationLink("ikev://import/v1/not-valid?password=super-secret")
        assertEquals("Saved VPN", viewModel.uiState.value.profileName)
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.toString().contains("super-secret"))
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun `profiles remain browsable while connected but cannot mutate the active tunnel`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController(VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true))
        val viewModel = createViewModel(repository, controller)
        viewModel.showProfiles()
        assertEquals(AppScreen.PROFILES, viewModel.uiState.value.screen)
        assertFalse(viewModel.uiState.value.canManageProfiles)
        viewModel.renameProfile("legacy", "New")
        viewModel.deleteProfile("legacy")
        viewModel.editProfile("legacy")
        assertEquals("Saved VPN", repository.current.profile!!.config.profileName)
        assertEquals(0, controller.deleteCalls)
        assertEquals(AppScreen.PROFILES, viewModel.uiState.value.screen)
    }

    @Test
    fun `rename updates selected metadata without reprovisioning and rolls back an engine rename on failure`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.showProfiles()
        viewModel.renameProfile("legacy", "  Work  ")
        assertEquals("Work", viewModel.uiState.value.profileName)
        assertEquals("Work", repository.current.profile!!.config.profileName)
        assertEquals(listOf("Work"), controller.renamedNames)
        assertEquals(0, controller.provisionCalls)
        repository.failRename = true
        viewModel.renameProfile("legacy", "Rejected")
        assertEquals(listOf("Work", "Rejected", "Work"), controller.renamedNames)
        assertEquals("Work", repository.current.profile!!.config.profileName)
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `delete inactive profile removes only its saved data and credential`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = first.copy(config = first.config.copy(id = "second", profileName = "Second"))
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.showProfiles()
        viewModel.deleteProfile("second")
        assertEquals(listOf("legacy"), repository.current.profiles.map { it.config.id })
        assertEquals("legacy", repository.current.profile!!.config.id)
        assertEquals(setOf("second"), controller.forgottenCredentials)
        assertEquals(0, controller.deleteCalls)
        assertTrue(viewModel.uiState.value.configured)
    }

    @Test
    fun `delete selected profile clears the engine and shows remaining profiles without activation`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = first.copy(config = first.config.copy(id = "second", profileName = "Second"))
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.deleteProfile("legacy")
        assertEquals(null, repository.current.profile)
        assertEquals(listOf("second"), viewModel.uiState.value.profiles.map { it.config.id })
        assertEquals(AppScreen.PROFILES, viewModel.uiState.value.screen)
        assertEquals("", viewModel.uiState.value.profileName)
        assertFalse(viewModel.uiState.value.configured)
        assertEquals(1, controller.deleteCalls)
        assertEquals(setOf("legacy"), controller.forgottenCredentials)
        assertEquals(0, controller.provisionCalls)
        assertEquals(null, controller.restoredProfile)
    }

    @Test
    fun `engine removal failure preserves selected profile and credential`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController().apply { deleteFails = true }
        val viewModel = createViewModel(repository, controller)
        viewModel.deleteProfile("legacy")
        assertEquals("legacy", repository.current.profile!!.config.id)
        assertTrue(viewModel.uiState.value.configured)
        assertTrue(controller.forgottenCredentials.isEmpty())
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `storage failure after engine removal retains a recoverable profile instead of showing a working VPN`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate())).apply { failDelete = true }
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.deleteProfile("legacy")
        assertEquals(ProvisioningStatus.NEEDS_REPROVISION, repository.current.profile!!.status)
        assertFalse(viewModel.uiState.value.configured)
        assertTrue(controller.forgottenCredentials.isEmpty())
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `editing an inactive profile reuses its password only when saved and preserves other profiles`() = runTest {
        val first = provisionedSnapshot(testCertificate()).profile!!
        val second = first.copy(config = first.config.copy(id = "second", profileName = "Second"))
        val repository = FakeProfileRepository(RepositorySnapshot(profile = first, profiles = listOf(first, second)))
        val controller = FakeVpnController()
        val viewModel = createViewModel(repository, controller)
        viewModel.editProfile("second")
        assertEquals("legacy", repository.current.profile!!.config.id)
        assertEquals(null, controller.restoredProfile)
        assertTrue(viewModel.uiState.value.canReuseSavedPassword)
        viewModel.updateProfileName("Work")
        viewModel.provisionProfile("")
        assertEquals("second", controller.restoredProfile!!.id)
        assertEquals("Work", repository.current.profile!!.config.profileName)
        assertTrue(repository.current.profiles.any { it.config.id == "legacy" })
        assertEquals(0, controller.connectCalls)
    }

    @Test
    fun `editing without a stored credential still requires a password`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController().apply { savedCredentialAvailable = false }
        val viewModel = createViewModel(repository, controller)
        viewModel.showSetup()
        assertFalse(viewModel.uiState.value.canReuseSavedPassword)
        viewModel.provisionProfile("")
        assertTrue(ProfileField.PASSWORD in viewModel.uiState.value.fieldErrors)
        assertEquals(0, controller.provisionCalls)
    }

    @Test
    fun `home screen is available without a profile and cancelling first setup returns to profiles`() = runTest {
        val viewModel = createViewModel(FakeProfileRepository(RepositorySnapshot()), FakeVpnController())
        assertEquals(AppScreen.MAIN, viewModel.uiState.value.screen)
        viewModel.addProfile()
        viewModel.updateProfileName("Unsaved")
        viewModel.showProfiles()
        assertEquals(AppScreen.PROFILES, viewModel.uiState.value.screen)
        assertTrue(viewModel.uiState.value.profiles.isEmpty())
        assertEquals("", viewModel.uiState.value.profileName)
    }

    @Test
    fun `session data follows current tunnel state and is cleared after disconnect`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController(VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true,
            sessionId = "session-one", connectedSinceMillis = 1234L))
        val viewModel = createViewModel(repository, controller)
        assertEquals("session-one", viewModel.uiState.value.sessionId)
        assertEquals(1234L, viewModel.uiState.value.connectedSinceMillis)
        viewModel.showProfiles()
        viewModel.showMain()
        assertEquals(1234L, viewModel.uiState.value.connectedSinceMillis)
        controller.state.value = controller.state.value.copy(connectionState = ConnectionState.DISCONNECTED)
        assertEquals(null, viewModel.uiState.value.sessionId)
        assertEquals(null, viewModel.uiState.value.connectedSinceMillis)
    }

    @Test
    fun `renaming an imported draft keeps included credentials ready for explicit save`() = runTest {
        val controller = FakeVpnController()
        val viewModel = createViewModel(FakeProfileRepository(RepositorySnapshot()), controller)
        viewModel.importConfigurationLink(IkevShareDecoderTest.fixture("public-password"))
        viewModel.updateProfileName("Work")
        assertTrue(viewModel.uiState.value.hasImportedPassword)
        assertTrue(viewModel.uiState.value.importedProfileInfo != null)
        assertEquals(0, controller.provisionCalls)
        viewModel.provisionProfile("")
        assertEquals(IkevShareDecoderTest.PASSWORD, controller.provisionedPassword)
    }

    @Test
    fun `traffic updates live without saving profiles and resets on reconnect`() = runTest {
        val repository = FakeProfileRepository(provisionedSnapshot(testCertificate()))
        val controller = FakeVpnController(VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true,
            sessionId = "first", traffic = VpnTraffic(500, 100)))
        val viewModel = createViewModel(repository, controller)
        assertEquals(VpnTraffic(500, 100), viewModel.uiState.value.traffic)
        controller.state.value = controller.state.value.copy(traffic = VpnTraffic(1500, 300))
        assertEquals(VpnTraffic(1500, 300), viewModel.uiState.value.traffic)
        viewModel.showProfiles()
        viewModel.showMain()
        assertEquals(VpnTraffic(1500, 300), viewModel.uiState.value.traffic)
        repository.recordError("Unrelated diagnostic update", "No secret")
        assertEquals(VpnTraffic(1500, 300), viewModel.uiState.value.traffic)
        assertEquals(0, repository.saveCalls)

        // Defensively hide stale totals even if the engine includes them in a stopped state.
        controller.state.value = controller.state.value.copy(connectionState = ConnectionState.DISCONNECTED)
        assertEquals(null, viewModel.uiState.value.traffic)
        controller.state.value = controller.state.value.copy(connectionState = ConnectionState.CONNECTING, sessionId = "second")
        assertEquals(null, viewModel.uiState.value.traffic)
        controller.state.value = controller.state.value.copy(connectionState = ConnectionState.CONNECTED, traffic = null)
        assertEquals(null, viewModel.uiState.value.traffic)
        controller.state.value = controller.state.value.copy(traffic = VpnTraffic(0, 0))
        assertEquals(VpnTraffic(0, 0), viewModel.uiState.value.traffic)
    }

    @Test
    fun `traffic is hidden without a configured profile or confirmed connection`() = runTest {
        val engineState = VpnState(connectionState = ConnectionState.CONNECTED, confirmed = true, traffic = VpnTraffic(100, 50))
        val unconfigured = createViewModel(FakeProfileRepository(RepositorySnapshot()), FakeVpnController(engineState))
        assertEquals(null, unconfigured.uiState.value.traffic)
        val unconfirmed = createViewModel(FakeProfileRepository(provisionedSnapshot(testCertificate())),
            FakeVpnController(engineState.copy(confirmed = false)))
        assertEquals(null, unconfirmed.uiState.value.traffic)
    }

    private fun shareDecoder() = IkevShareDecoder(IkevProfileParser(CertificateLoader {
        Date.from(Instant.parse("2030-01-01T00:00:00Z"))
    }))

    private fun createViewModel(
        repository: ProfileRepository,
        controller: VpnPlatformController,
        importedProfile: ImportedIkevProfile? = null,
        onIkevProfileImport: () -> Unit = {},
        shareLoader: (suspend (String) -> ImportedIkevShare)? = null,
    ): VpnViewModel = VpnViewModel(
        profileRepository = repository,
        certificateImporter = object : CertificateImporter {
            override suspend fun import(uri: Uri): LoadedCertificate = error("Not used in this test")
        },
        ikevProfileImporter = object : IkevProfileImporter {
            override suspend fun importLink(text: String): ImportedIkevShare =
                shareLoader?.invoke(text) ?: shareDecoder().decode(text)

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
        var failRename = false
        var failDelete = false
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

        override suspend fun renameProfile(id: String, name: String) {
            if (failRename) error("Simulated rename failure")
            val all = (current.profiles + listOfNotNull(current.profile)).associateBy { it.config.id }.values.map {
                if (it.config.id == id) it.copy(config = it.config.copy(profileName = name)) else it
            }
            mutableSnapshots.value = current.copy(profile = all.firstOrNull { it.config.id == current.profile?.config?.id }, profiles = all)
        }

        override suspend fun deleteProfile(id: String) {
            if (failDelete) error("Simulated removal failure")
            val all = (current.profiles + listOfNotNull(current.profile)).associateBy { it.config.id }.values.filter { it.config.id != id }
            mutableSnapshots.value = current.copy(profile = current.profile?.takeUnless { it.config.id == id }, profiles = all)
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
        var connectCalls = 0
        var deleteFails = false
        val forgottenCredentials = mutableSetOf<String>()
        val renamedNames = mutableListOf<String>()
        var deleteCalls = 0
        var provisionCalls = 0
        var provisionedPassword: String? = null
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
            provisionedPassword = password
            return VpnResult.Success(ProvisioningAction.Complete)
        }

        override fun forgetProfileCredential(profileId: String) { forgottenCredentials += profileId }
        override fun renameProvisionedProfile(name: String): VpnResult<Unit> { renamedNames += name; return VpnResult.Success(Unit) }
        override fun connect(): VpnResult<String?> { connectCalls++; return VpnResult.Success(null) }
        override fun disconnect(): VpnResult<Unit> = VpnResult.Success(Unit)

        override fun deleteProvisionedProfile(): VpnResult<Unit> {
            deleteCalls += 1
            return if (deleteFails) VpnResult.Failure("Could not remove profile", "Simulated removal failure") else VpnResult.Success(Unit)
        }

        override fun refreshState() = Unit
    }
}
