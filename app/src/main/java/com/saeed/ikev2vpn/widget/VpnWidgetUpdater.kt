package com.saeed.ikev2vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.graphics.toColorInt
import android.widget.RemoteViews
import com.saeed.ikev2vpn.MainActivity
import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.strongswan.android.R

/** Observe only while a launcher widget exists; traffic/log changes do not redraw it. */
class VpnWidgetUpdater(context: Context, private val profiles: ProfileRepository, private val vpn: VpnPlatformController) {
    private val context = context.applicationContext
    private val manager = AppWidgetManager.getInstance(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var current = VpnWidgetState()
    private fun ids(): IntArray = manager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))

    fun start() {
        if (observation?.isActive == true || ids().isEmpty()) return
        observation = scope.launch {
            combine(profiles.snapshots, vpn.state) { snapshot, state -> VpnWidgetState.from(snapshot, state) }
                .distinctUntilChanged()
                .collect { state ->
                    current = state
                    render(ids())
                }
        }
    }

    fun stop() {
        observation?.cancel()
        observation = null
        current = VpnWidgetState()
    }

    fun refresh(widgetIds: IntArray = ids()) {
        render(widgetIds)
        start()
    }

    private fun render(widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val status = context.getString(when (current.status) {
            WidgetStatus.CHECKING -> R.string.vpn_widget_checking
            WidgetStatus.SETUP -> R.string.vpn_widget_setup
            WidgetStatus.DISCONNECTED -> R.string.vpn_widget_disconnected
            WidgetStatus.CONNECTING -> R.string.vpn_widget_connecting
            WidgetStatus.CONNECTED -> R.string.vpn_widget_connected
            WidgetStatus.DISCONNECTING -> R.string.vpn_widget_disconnecting
            WidgetStatus.ERROR -> R.string.vpn_widget_error
            WidgetStatus.UNKNOWN -> R.string.vpn_widget_unknown
        })
        val profile = current.profileName ?: context.getString(R.string.vpn_widget_no_profile)
        val open = PendingIntent.getActivity(context, 1402,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_CONNECTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.vpn_widget).apply {
            setTextViewText(R.id.vpn_widget_status, status)
            setTextViewText(R.id.vpn_widget_profile, profile)
            setTextColor(R.id.vpn_widget_status, (when (current.status) {
                WidgetStatus.CONNECTED -> "#70E1B5"
                WidgetStatus.ERROR -> "#FFB4AB"
                else -> "#E1E7EF"
            }).toColorInt())
            setContentDescription(R.id.vpn_widget_root, "$status. $profile. ${context.getString(R.string.vpn_widget_open)}")
            setOnClickPendingIntent(R.id.vpn_widget_root, open)
        }
        manager.updateAppWidget(widgetIds, views)
    }

    companion object {
        const val ACTION_OPEN_CONNECTION = "com.saeed.ikev2vpn.OPEN_CONNECTION"
    }
}
