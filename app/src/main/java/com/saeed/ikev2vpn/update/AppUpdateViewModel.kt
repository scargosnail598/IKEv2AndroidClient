package com.saeed.ikev2vpn.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase { IDLE, CHECKING, NO_RELEASE, CURRENT, AVAILABLE, DOWNLOADING, READY, PREPARING_INSTALL }
data class AppUpdateState(val installed: InstalledApp, val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppRelease? = null, val progress: Int = 0, val error: String? = null) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.PREPARING_INSTALL)
}
sealed interface UpdateEffect {
    data class AllowInstallation(val intent: Intent) : UpdateEffect
    data class OpenInstaller(val intent: Intent) : UpdateEffect
}

class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUpdateState(repository.installed))
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<UpdateEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var generation = 0
    private var operation: Job? = null
    private var prepared: VerifiedUpdate? = null
    private var permissionRequested = false

    fun check() {
        if (state.value.busy) return
        val request = ++generation
        permissionRequested = false
        val old = prepared
        prepared = null
        mutableState.update { it.copy(phase = UpdatePhase.CHECKING, error = null, release = null, progress = 0) }
        operation = viewModelScope.launch {
            try {
                old?.let { repository.discard(it) }
                val latest = repository.latestRelease()
                val installedVersion = ReleaseVersion.parse(repository.installed.versionName)
                    ?: throw UpdateException("This installed build has an unsupported version number.")
                if (request != generation) return@launch
                mutableState.update { it.copy(
                    phase = when { latest == null -> UpdatePhase.NO_RELEASE; latest.version > installedVersion -> UpdatePhase.AVAILABLE; else -> UpdatePhase.CURRENT },
                    release = latest,
                ) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { if (request == generation) failure(exception, UpdatePhase.IDLE) }
        }
    }

    fun download() {
        if (state.value.busy) return
        val release = state.value.release ?: return
        if (ReleaseVersion.parse(repository.installed.versionName)?.let { release.version <= it } != false) return
        val request = ++generation
        mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, error = null, progress = 0) }
        operation = viewModelScope.launch {
            try {
                prepared?.let { repository.discard(it) }
                prepared = null
                val file = repository.download(release) { value ->
                    if (request == generation) mutableState.update { it.copy(progress = value.coerceIn(0, 100)) }
                }
                if (request != generation) { repository.discard(file); return@launch }
                prepared = file
                mutableState.update { it.copy(phase = UpdatePhase.READY, progress = 100) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { if (request == generation) failure(exception, UpdatePhase.AVAILABLE) }
        }
    }

    fun cancelDownload() {
        if (state.value.phase != UpdatePhase.DOWNLOADING) return
        generation++
        operation?.cancel()
        mutableState.update { it.copy(phase = UpdatePhase.AVAILABLE, error = null, progress = 0) }
    }

    fun install() {
        if (state.value.busy) return
        val file = prepared ?: return
        mutableState.update { it.copy(phase = UpdatePhase.PREPARING_INSTALL, error = null) }
        operation = viewModelScope.launch {
            try {
                val intent = repository.installerIntent(file)
                mutableState.update { it.copy(phase = UpdatePhase.READY) }
                if (!repository.canInstall()) {
                    permissionRequested = true
                    effectChannel.send(UpdateEffect.AllowInstallation(repository.permissionIntent()))
                } else effectChannel.send(UpdateEffect.OpenInstaller(intent))
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { failure(exception, UpdatePhase.READY) }
        }
    }

    fun onInstallPermissionResult() {
        if (!permissionRequested) return
        permissionRequested = false
        if (repository.canInstall()) install()
        else mutableState.update { it.copy(error = "Allow this app to install updates in Android settings, then tap Install update.") }
    }
    fun launchFailed() { mutableState.update { it.copy(phase = UpdatePhase.READY, error = "Android could not open the installer or installation settings on this device.") } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    private fun failure(exception: Exception, phase: UpdatePhase) {
        mutableState.update { it.copy(phase = phase, error = if (exception is UpdateException) exception.message else "The update could not be completed. Try again.") }
    }
    class Factory(private val repository: AppUpdateRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppUpdateViewModel::class.java))
            return AppUpdateViewModel(repository) as T
        }
    }
}
