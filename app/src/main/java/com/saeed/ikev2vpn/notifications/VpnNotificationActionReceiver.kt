package com.saeed.ikev2vpn.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.saeed.ikev2vpn.Ikev2VpnApplication
import kotlinx.coroutines.*
import org.strongswan.android.R

class VpnNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(VpnNotificationActions.CONNECT, VpnNotificationActions.DISCONNECT)) return
        val pending = goAsync()
        val app = context.applicationContext as Ikev2VpnApplication
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val error = withTimeout(5000) { VpnNotificationActions(app.profileRepository, app.vpnController).execute(action) }
                if (error != null) Toast.makeText(app, error, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(app, R.string.vpn_notification_action_failed, Toast.LENGTH_LONG).show()
            } finally {
                try { app.notificationControls.refresh() } finally { pending.finish() }
            }
        }
    }
    companion object {
        @JvmStatic fun connectIntent(context: Context): PendingIntent = command(context, VpnNotificationActions.CONNECT, 14031)
        @JvmStatic fun disconnectIntent(context: Context): PendingIntent = command(context, VpnNotificationActions.DISCONNECT, 14032)
        private fun command(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, code, Intent(context, VpnNotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
