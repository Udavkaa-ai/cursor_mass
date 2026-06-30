package ru.buswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import ru.buswidget.ArrivalsActivity
import ru.buswidget.R

/**
 * 2×2 widget that, on tapping ▶, runs a 5-minute session: it resolves the
 * nearest saved stop by geolocation and shows live arrivals — driven by
 * [AutoPollService] (a foreground service, so location works without any
 * "background location" permission). ■ stops the session early.
 */
class BusWidgetProviderAuto : AppWidgetProvider() {

    companion object {
        const val ACTION_START = "ru.buswidget.auto.START"
        const val ACTION_STOP  = "ru.buswidget.auto.STOP"

        private data class ColIds(val col: Int, val route: Int, val eta: Int, val unit: Int)

        private val COL_IDS = listOf(
            ColIds(R.id.row1, R.id.r1_route, R.id.r1_eta, R.id.r1_unit),
            ColIds(R.id.row2, R.id.r2_route, R.id.r2_eta, R.id.r2_unit),
            ColIds(R.id.row3, R.id.r3_route, R.id.r3_eta, R.id.r3_unit),
        )

        private fun formatEtaNum(secs: Int?): String = when {
            secs == null -> "—"
            secs <= 0    -> "→"
            secs < 60    -> "<1"
            else         -> "${secs / 60}"
        }

        /** Idle state: prompt to start, no active rows. */
        fun showIdle(ctx: Context, awm: AppWidgetManager, widgetId: Int) =
            renderInactive(ctx, awm, widgetId, "Ближайшая остановка", "")

        /** Short message state (no stops found / location error), with a retry ▶. */
        fun showMessage(ctx: Context, awm: AppWidgetManager, widgetId: Int, title: String, sub: String) =
            renderInactive(ctx, awm, widgetId, title, sub)

        private fun renderInactive(
            ctx: Context, awm: AppWidgetManager, widgetId: Int, title: String, sub: String,
        ) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
            rv.setTextViewText(R.id.tw_stop, title)
            rv.setTextViewText(R.id.tw_distance, sub)
            rv.setViewVisibility(R.id.tw_timer, View.GONE)
            rv.setViewVisibility(R.id.btn_stop, View.GONE)
            rv.setViewVisibility(R.id.rows_active, View.GONE)
            rv.setViewVisibility(R.id.btn_start, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.btn_start, startIntent(ctx, widgetId))
            awm.updateAppWidget(widgetId, rv)
        }

        /** Transient state while the GPS fix / nearest-stop lookup is in flight. */
        fun showLocating(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
            rv.setTextViewText(R.id.tw_stop, "Определяю остановку…")
            rv.setTextViewText(R.id.tw_distance, "")
            rv.setViewVisibility(R.id.tw_timer, View.GONE)
            rv.setViewVisibility(R.id.btn_start, View.GONE)
            rv.setViewVisibility(R.id.rows_active, View.GONE)
            rv.setViewVisibility(R.id.btn_stop, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.btn_stop, stopIntent(ctx, widgetId))
            awm.updateAppWidget(widgetId, rv)
        }

        /** Active state: nearest stop, distance, countdown timer and live arrivals. */
        fun updateActive(
            ctx: Context,
            awm: AppWidgetManager,
            widgetId: Int,
            stopId: String,
            stopName: String,
            routes: String,
            distanceText: String,
            timeLeft: Int,
            arrivals: List<WidgetArrival>,
        ) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
            val m = timeLeft / 60; val s = timeLeft % 60
            rv.setTextViewText(R.id.tw_stop, stopName)
            rv.setTextViewText(R.id.tw_distance, distanceText)
            rv.setTextViewText(R.id.tw_timer, "$m:${s.toString().padStart(2, '0')}")
            rv.setViewVisibility(R.id.tw_timer, View.VISIBLE)
            rv.setViewVisibility(R.id.btn_start, View.GONE)
            rv.setViewVisibility(R.id.btn_stop, View.VISIBLE)
            rv.setViewVisibility(R.id.rows_active, View.VISIBLE)
            COL_IDS.forEachIndexed { i, ids ->
                val a = arrivals.getOrNull(i)
                val color = a?.color ?: 0xFF9090B8.toInt()
                rv.setViewVisibility(ids.col, if (a != null) View.VISIBLE else View.INVISIBLE)
                rv.setTextViewText(ids.route, a?.route ?: "")
                rv.setTextViewText(ids.eta, if (a != null) formatEtaNum(a.etaSeconds) else "")
                rv.setTextColor(ids.eta, color)
                rv.setTextViewText(ids.unit, if (a != null) "МИН" else "")
                rv.setTextColor(ids.unit, color)
            }
            rv.setOnClickPendingIntent(R.id.btn_stop, stopIntent(ctx, widgetId))
            // Tapping the stop name or the arrivals area opens the full screen.
            val openApp = openArrivalsIntent(ctx, widgetId, stopId, stopName, routes)
            rv.setOnClickPendingIntent(R.id.tw_stop, openApp)
            rv.setOnClickPendingIntent(R.id.rows_active, openApp)
            awm.updateAppWidget(widgetId, rv)
        }

        private fun startIntent(ctx: Context, widgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, widgetId,
                Intent(ctx, BusWidgetProviderAuto::class.java).apply {
                    action = ACTION_START
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun stopIntent(ctx: Context, widgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, widgetId + 10_000,
                Intent(ctx, BusWidgetProviderAuto::class.java).apply {
                    action = ACTION_STOP
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openArrivalsIntent(
            ctx: Context, widgetId: Int, stopId: String, name: String, routes: String,
        ): PendingIntent {
            val intent = Intent(ctx, ArrivalsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ArrivalsActivity.EXTRA_STOP_ID, stopId)
                putExtra(ArrivalsActivity.EXTRA_STOP_NAME, name)
                putExtra(ArrivalsActivity.EXTRA_ROUTES, routes)
            }
            return PendingIntent.getActivity(
                ctx, widgetId + 50_000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { showIdle(ctx, awm, it) }
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AutoPollService.stopFor(ctx, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (intent.action) {
            ACTION_START -> AutoPollService.startFor(ctx, widgetId)
            ACTION_STOP  -> AutoPollService.stopFor(ctx, widgetId)
        }
    }
}
