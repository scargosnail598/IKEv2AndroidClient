package com.saeed.ikev2vpn.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.saeed.ikev2vpn.MainActivity
import com.saeed.ikev2vpn.data.ProfileRepository
import com.saeed.ikev2vpn.vpn.VpnPlatformController
import com.saeed.ikev2vpn.widget.VpnWidgetUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.strongswan.android.R
import org.strongswan.android.logic.CharonVpnService

/** The engine owns the active foreground notification; this owns only the idle control. */
class VpnNotificationControls(context: Context, profiles: ProfileRepository, vpn: VpnPlatformController) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private var current = IdleNotificationState()
    init {
        val channel = NotificationChannel(CharonVpnService.NOTIFICATION_CHANNEL,
            context.getString(R.string.permanent_notification_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.permanent_notification_description)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(profiles.snapshots, vpn.state) { profile, state -> IdleNotificationState.from(profile, state) }
                .distinctUntilChanged().collect { current = it; refresh() }
        }
    }
    fun refresh() {
        if (!current.visible || !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CharonVpnService.NOTIFICATION_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            manager.cancel(IDLE_ID)
            return
        }
        val open = PendingIntent.getActivity(context, 14030,
            Intent(context, MainActivity::class.java).setAction(VpnWidgetUpdater.ACTION_OPEN_CONNECTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CharonVpnService.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.vpn_notification_disconnected))
            .setContentText(current.profileName)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(true).setShowWhen(false)
            .addAction(R.drawable.ic_notification_connecting, context.getString(R.string.connect),
                VpnNotificationActionReceiver.connectIntent(context)).build()
        // Permission can be revoked between the check and posting.
        try { manager.notify(IDLE_ID, notification) } catch (_: SecurityException) { }
    }
    private companion object { const val IDLE_ID = 1403 }
}
