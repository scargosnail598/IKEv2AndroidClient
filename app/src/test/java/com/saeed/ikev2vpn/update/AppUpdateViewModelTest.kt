package com.saeed.ikev2vpn.update

import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun `check handles no release and same or older published versions without offering a downgrade`() = runTest {
        for (version in listOf(null, "1.4.0", "1.3.9")) {
            val repo = FakeRepository().apply { latest = version?.let { release(it) } }
            val vm = AppUpdateViewModel(repo)
            vm.check()
            assertEquals(if (version == null) UpdatePhase.NO_RELEASE else UpdatePhase.CURRENT, vm.state.value.phase)
            vm.download()
            assertEquals(0, repo.downloads)
        }
    }
    @Test fun `new release requires explicit download and explicit installation`() = runTest {
        val repo = FakeRepository()
        val vm = AppUpdateViewModel(repo)
        vm.check()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertEquals(0, repo.downloads)
        vm.download()
        assertEquals(UpdatePhase.READY, vm.state.value.phase)
        assertEquals(0, repo.installerPreparations)
        vm.install()
        assertTrue(vm.effects.first() is UpdateEffect.OpenInstaller)
        assertEquals(1, repo.installerPreparations)
    }
    @Test fun `unknown source permission resumes installation only after approval`() = runTest {
        val repo = FakeRepository().apply { allowed = false }
        val vm = AppUpdateViewModel(repo)
        vm.check(); vm.download(); vm.install()
        assertTrue(vm.effects.first() is UpdateEffect.AllowInstallation)
        vm.onInstallPermissionResult()
        assertTrue(vm.state.value.error!!.contains("Allow"))
        vm.install()
        assertTrue(vm.effects.first() is UpdateEffect.AllowInstallation)
        repo.allowed = true
        vm.onInstallPermissionResult()
        assertTrue(vm.effects.first() is UpdateEffect.OpenInstaller)
    }
    @Test fun `failed verification stays retriable and never opens an installer`() = runTest {
        val repo = FakeRepository().apply { failDownload = true }
        val vm = AppUpdateViewModel(repo)
        vm.check(); vm.download()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertTrue(vm.state.value.error!!.contains("checksum"))
        vm.install()
        assertEquals(0, repo.installerPreparations)
    }
    @Test fun `cancel permits a fresh check without keeping a pending download`() = runTest {
        val repo = FakeRepository().apply { pending = CompletableDeferred() }
        val vm = AppUpdateViewModel(repo)
        vm.check(); vm.download()
        assertEquals(UpdatePhase.DOWNLOADING, vm.state.value.phase)
        vm.cancelDownload()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        repo.latest = null
        vm.check()
        assertEquals(UpdatePhase.NO_RELEASE, vm.state.value.phase)
        vm.install()
        assertEquals(0, repo.installerPreparations)
    }
    private class FakeRepository : AppUpdateRepository {
        override val installed = InstalledApp("com.saeed.ikev2vpn", "1.4.0", 6, true)
        var latest: AppRelease? = release("1.5.0")
        var downloads = 0
        var installerPreparations = 0
        var allowed = true
        var failDownload = false
        var pending: CompletableDeferred<Unit>? = null
        override suspend fun latestRelease() = latest
        override suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): VerifiedUpdate {
            downloads++
            pending?.await()
            if (failDownload) throw UpdateException("checksum mismatch")
            onProgress(100)
            return VerifiedUpdate(release, File("not-a-real-apk"), "not-a-real-hash")
        }
        override suspend fun installerIntent(update: VerifiedUpdate): Intent { installerPreparations++; return mock(Intent::class.java) }
        override suspend fun discard(update: VerifiedUpdate) = Unit
        override fun canInstall() = allowed
        override fun permissionIntent(): Intent = mock(Intent::class.java)
    }
    companion object {
        private fun release(version: String): AppRelease = AppRelease(ReleaseVersion.parse(version)!!, "notes", ReleaseAsset("app.apk", "", 10), ReleaseAsset("app.apk.sha256", "", 80))
    }
}
