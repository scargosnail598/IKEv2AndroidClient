package com.saeed.ikev2vpn.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saeed.ikev2vpn.data.AppTheme

@Composable
fun VpnApp(
    viewModel: VpnViewModel,
    updateViewModel: com.saeed.ikev2vpn.update.AppUpdateViewModel,
    darkMode: Boolean,
    onDarkModeChanged: (Boolean) -> Unit,
    onConfigured: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    theme: AppTheme = AppTheme.BLUE,
    onThemeChanged: (AppTheme) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val serverPing by viewModel.serverPing.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.initialized, state.configured) {
        if (state.initialized && state.configured) onConfigured()
    }
    val updatePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateViewModel.onInstallPermissionResult()
    }
    val updateInstaller = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(updateViewModel) {
        updateViewModel.effects.collect { effect ->
            try {
                when (effect) {
                    is com.saeed.ikev2vpn.update.UpdateEffect.AllowInstallation -> updatePermission.launch(effect.intent)
                    is com.saeed.ikev2vpn.update.UpdateEffect.OpenInstaller -> updateInstaller.launch(effect.intent)
                }
            } catch (_: Exception) { updateViewModel.launchFailed() }
        }
    }
    val context = LocalContext.current
    var linkDialogOpen by remember { mutableStateOf(false) }
    var shareProfile by remember { mutableStateOf<com.saeed.ikev2vpn.data.StoredProfile?>(null) }
    shareProfile?.let { profile ->
        ProfileShareDialog(profile, viewModel::createProfileShareLink, onDismiss = { shareProfile = null })
    }
    val qrImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importQrImage)
    }
    val qrScanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::importConfigurationLink)
            result.data?.removeExtra(Intent.EXTRA_TEXT)
        }
    }
    if (linkDialogOpen) {
        ConfigurationLinkDialog(
            onDismiss = { linkDialogOpen = false },
            onImport = { text -> linkDialogOpen = false; viewModel.importConfigurationLink(text) },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importCertificate)
    }
    val ikevProfilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importIkevProfile)
    }
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is VpnUiEffect.RequestVpnConsent -> consentLauncher.launch(effect.intent)
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshState()
            if (event == Lifecycle.Event.ON_STOP) {
                linkDialogOpen = false
                shareProfile = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(
        enabled = state.screen != AppScreen.MAIN,
    ) {
        if (!state.isBusy) {
            if (state.screen == AppScreen.SETUP) viewModel.showProfiles() else viewModel.showMain()
        }
    }

    if (!state.initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    state.credentialPromptProfileName?.let { name ->
        ProfilePasswordDialog(name, viewModel::cancelProfilePassword, viewModel::submitProfilePassword)
    }

    val rootScreen = state.screen == AppScreen.MAIN || state.screen == AppScreen.PROFILES
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (rootScreen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = state.screen == AppScreen.MAIN,
                    onClick = viewModel::showMain, icon = { Icon(VpnIcons.Shield, null) }, label = { Text("Connection") })
                NavigationBarItem(selected = state.screen == AppScreen.PROFILES,
                    onClick = viewModel::showProfiles, icon = { Icon(VpnIcons.Profiles, null) }, label = { Text("Profiles") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (state.screen) {
                AppScreen.SETUP -> SetupScreen(
                    state = state,
                    onProfileNameChanged = viewModel::updateProfileName,
                    onServerChanged = viewModel::updateServerAddress,
                    onUsernameChanged = viewModel::updateUsername,
                    onAppRoutingModeChanged = viewModel::updateAppRoutingMode,
                    onToggleApplication = viewModel::toggleApplication,
                    onImportLink = { linkDialogOpen = true },
                    onScanQr = { qrScanner.launch(Intent(context, QrScannerActivity::class.java)) },
                    onImportQrImage = { qrImagePicker.launch(arrayOf("image/*")) },
                    onReplaceImportedProfile = viewModel::replaceImportedProfile,
                    onImportIkevProfile = { ikevProfilePicker.launch(arrayOf("*/*")) },
                    onImportCertificate = { certificatePicker.launch(arrayOf("*/*")) },
                    onProvision = viewModel::provisionProfile,
                    onBack = viewModel::showProfiles,
                    onDismissError = viewModel::dismissError,
                )
                AppScreen.MAIN -> MainScreen(
                    state = state, onProfiles = viewModel::showProfiles, onAddProfile = viewModel::addProfile,
                    onConnect = viewModel::connect, onDisconnect = viewModel::disconnect,
                    onDiagnostics = viewModel::showDiagnostics, darkMode = darkMode,
                    onDarkModeChanged = onDarkModeChanged, onDismissError = viewModel::dismissError,
                    theme = theme, onThemeChanged = onThemeChanged,
                    onAbout = viewModel::showAbout,
                    onNotificationSettings = onNotificationSettings,
                    onTrafficBypass = viewModel::showTrafficBypass,
                    serverPing = serverPing, onRefreshPing = viewModel::refreshServerPing,
                    onCheckUpdates = { viewModel.showAbout(); updateViewModel.check() },
                )
                AppScreen.PROFILES -> ProfilesScreen(
                    state, viewModel::selectProfile, viewModel::addProfile, viewModel::editProfile,
                    viewModel::renameProfile, viewModel::deleteProfile, viewModel::dismissError,
                    onShare = { shareProfile = it },
                )
                AppScreen.TRAFFIC_BYPASS -> TrafficBypassRoute(viewModel::showMain)
                AppScreen.ABOUT -> AboutScreen(updateState, viewModel::showMain,
                    updateViewModel::check, updateViewModel::download, updateViewModel::cancelDownload,
                    updateViewModel::install, updateViewModel::dismissError)
                AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
                    state = state, onBack = viewModel::showMain, onClearLog = viewModel::clearConnectionLog,
                )
            }
        }
    }
}
