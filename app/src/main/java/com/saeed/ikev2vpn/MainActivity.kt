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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleConfigurationIntent(intent)
        enableEdgeToEdge()
        val application = application as Ikev2VpnApplication
        setContent {
            val darkModeOverride by application.themePreferences.darkModeOverride
                .collectAsStateWithLifecycle(initialValue = null)
            val darkTheme = darkModeOverride ?: isSystemInDarkTheme()
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

            Ikev2VpnTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VpnApp(
                        viewModel = viewModel,
                        updateViewModel = updateViewModel,
                        darkMode = darkTheme,
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
