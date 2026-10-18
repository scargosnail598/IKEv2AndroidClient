package com.saeed.ikev2vpn.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.saeed.ikev2vpn.Ikev2VpnApplication

class VpnWidgetProvider : AppWidgetProvider() {
    private fun updater(context: Context) = (context.applicationContext as Ikev2VpnApplication).widgetUpdater

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updater(context).refresh(appWidgetIds)
    }
    override fun onEnabled(context: Context) { updater(context).start() }
    override fun onDisabled(context: Context) { updater(context).stop() }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updater(context).refresh(intArrayOf(appWidgetId))
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) updater(context).refresh()
    }
}
