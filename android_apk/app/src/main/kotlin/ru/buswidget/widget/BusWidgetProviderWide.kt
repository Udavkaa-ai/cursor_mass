package ru.buswidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context

class BusWidgetProviderWide : BusWidgetProvider() {
    override fun onUpdate(ctx: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = widgetPrefs(ctx).edit()
        appWidgetIds.forEach { prefs.putString("${it}_type", "wide") }
        prefs.apply()
        appWidgetIds.forEach { showIdle(ctx, awm, it) }
    }
}
