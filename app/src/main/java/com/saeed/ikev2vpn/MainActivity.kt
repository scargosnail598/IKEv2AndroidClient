package com.saeed.ikev2vpn

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saeed.ikev2vpn.data.AppTheme
import com.saeed.ikev2vpn.ui.VpnApp
import com.saeed.ikev2vpn.ui.VpnViewModel
import com.saeed.ikev2vpn.ui.theme.Ikev2VpnTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels {
        val application = application as Ikev2VpnApplication
        VpnViewModel.Factory(
            profileRepository = application.profileRepository,
            certificateImporter = application.certificateImporter,
            ikevProfileImporter = application.ikevProfileImporter,
            vpnController = application.vpnController,
        )
    }

    private val updateViewModel: com.saeed.ikev2vpn.update.AppUpdateViewModel by viewModels {
        com.saeed.ikev2vpn.update.AppUpdateViewModel.Factory((application as Ikev2VpnApplication).appUpdates)
    }

    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { (application as Ikev2VpnApplication).notificationControls.refresh() }

    private fun requestNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val preferences = getSharedPreferences("notification_controls", MODE_PRIVATE)
        if (preferences.getBoolean("permission_handled", false)) return
        preferences.edit().putBoolean("permission_handled", true).apply()
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    }

    override fun onResume() {
        super.onResume()
        (application as Ikev2VpnApplication).notificationControls.refresh()
        if (viewModel.uiState.value.configured) requestNotificationsOnce()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleConfigurationIntent(intent)
        enableEdgeToEdge()
        val application = application as Ikev2VpnApplication
        setContent {
            val darkModeOverride by application.themePreferences.darkModeOverride
                .collectAsStateWithLifecycle(initialValue = null)
            val darkTheme = darkModeOverride ?: isSystemInDarkTheme()
            val theme by application.themePreferences.theme
                .collectAsStateWithLifecycle(initialValue = AppTheme.BLUE)
            val coroutineScope = rememberCoroutineScope()

            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                    ) { darkTheme },
                )
            }

            Ikev2VpnTheme(darkTheme = darkTheme, theme = theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VpnApp(
                        viewModel = viewModel,
                        updateViewModel = updateViewModel,
                        darkMode = darkTheme,
                        theme = theme,
                        onThemeChanged = { selected ->
                            coroutineScope.launch { application.themePreferences.setTheme(selected) }
                        },
                        onConfigured = ::requestNotificationsOnce,
                        onNotificationSettings = ::openNotificationSettings,
                        onDarkModeChanged = { enabled ->
                            coroutineScope.launch {
                                application.themePreferences.setDarkMode(enabled)
                            }
                        },
                    )
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConfigurationIntent(intent)
    }

    private fun handleConfigurationIntent(incoming: Intent) {
        if (incoming.action == com.saeed.ikev2vpn.widget.VpnWidgetUpdater.ACTION_OPEN_CONNECTION) {
            viewModel.showMain()
            return
        }
        if (incoming.action != Intent.ACTION_VIEW) return
        val text = incoming.dataString ?: return
        // Remove credentials from the Activity's retained intent before any recreation.
        incoming.data = null
        setIntent(Intent(this, MainActivity::class.java))
        viewModel.importConfigurationLink(text)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setImportForeground(true)
    }

    override fun onStop() {
        viewModel.setImportForeground(false)
        super.onStop()
    }

}
