package ru.buswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import ru.buswidget.ArrivalsActivity
import ru.buswidget.R

/**
 * Experimental 4×2 widget: a static map snippet of the nearest stop plus the
 * next 3 buses. Driven by [AutoPollService] (same 5-minute session as the
 * geolocation widget); the map is a static PNG from Yandex Static Maps, since a
 * widget can't host a live WebView.
 */
class MapWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_START = "ru.buswidget.map.START"
        const val ACTION_STOP  = "ru.buswidget.map.STOP"

        private data class RowIds(val row: Int, val route: Int, val dir: Int, val eta: Int)

        private val ROW_IDS = listOf(
            RowIds(R.id.row1, R.id.r1_route, R.id.r1_dir, R.id.r1_eta),
            RowIds(R.id.row2, R.id.r2_route, R.id.r2_dir, R.id.r2_eta),
            RowIds(R.id.row3, R.id.r3_route, R.id.r3_dir, R.id.r3_eta),
        )

        fun showIdle(ctx: Context, awm: AppWidgetManager, widgetId: Int) =
            renderInactive(ctx, awm, widgetId, "Карта + автобусы", "")

        fun showMessage(ctx: Context, awm: AppWidgetManager, widgetId: Int, title: String, sub: String) =
            renderInactive(ctx, awm, widgetId, title, sub)

        private fun renderInactive(
            ctx: Context, awm: AppWidgetManager, widgetId: Int, title: String, sub: String,
        ) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_map)
            rv.setTextViewText(R.id.tw_stop, title)
            rv.setTextViewText(R.id.tw_timer, sub)
            rv.setViewVisibility(R.id.tw_timer, if (sub.isBlank()) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.btn_stop, View.GONE)
            rv.setViewVisibility(R.id.content_active, View.GONE)
            rv.setViewVisibility(R.id.btn_start, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.btn_start, startIntent(ctx, widgetId))
            awm.updateAppWidget(widgetId, rv)
        }

        fun showLocating(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_map)
            rv.setTextViewText(R.id.tw_stop, "Определяю остановку…")
            rv.setViewVisibility(R.id.tw_timer, View.GONE)
            rv.setViewVisibility(R.id.btn_start, View.GONE)
            rv.setViewVisibility(R.id.content_active, View.GONE)
            rv.setViewVisibility(R.id.btn_stop, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.btn_stop, stopIntent(ctx, widgetId))
            awm.updateAppWidget(widgetId, rv)
        }

        fun updateActive(
            ctx: Context,
            awm: AppWidgetManager,
            widgetId: Int,
            stopId: String,
            stopName: String,
            routes: String,
            timeLeft: Int,
            arrivals: List<WidgetArrival>,
            mapBitmap: Bitmap?,
            mapStatus: String,
        ) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_map)
            val m = timeLeft / 60; val s = timeLeft % 60
            rv.setTextViewText(R.id.tw_stop, stopName)
            rv.setTextViewText(R.id.tw_timer, "$m:${s.toString().padStart(2, '0')}")
            rv.setViewVisibility(R.id.tw_timer, View.VISIBLE)
            rv.setViewVisibility(R.id.btn_start, View.GONE)
            rv.setViewVisibility(R.id.btn_stop, View.VISIBLE)
            rv.setViewVisibility(R.id.content_active, View.VISIBLE)

            if (mapBitmap != null) {
                rv.setViewVisibility(R.id.map_image, View.VISIBLE)
                rv.setViewVisibility(R.id.map_status, View.GONE)
                rv.setImageViewBitmap(R.id.map_image, mapBitmap)
            } else {
                rv.setViewVisibility(R.id.map_image, View.GONE)
                rv.setViewVisibility(R.id.map_status, View.VISIBLE)
                rv.setTextViewText(R.id.map_status, mapStatus)
            }

            ROW_IDS.forEachIndexed { i, ids ->
                val a = arrivals.getOrNull(i)
                rv.setViewVisibility(ids.row, if (a != null) View.VISIBLE else View.INVISIBLE)
                rv.setTextViewText(ids.route, a?.route ?: "")
                rv.setTextViewText(ids.dir, a?.direction ?: "")
                rv.setTextViewText(ids.eta, a?.eta ?: "")
                rv.setTextColor(ids.eta, a?.color ?: 0xFF9090B8.toInt())
            }

            rv.setOnClickPendingIntent(R.id.btn_stop, stopIntent(ctx, widgetId))
            val openApp = openArrivalsIntent(ctx, widgetId, stopId, stopName, routes)
            rv.setOnClickPendingIntent(R.id.tw_stop, openApp)
            rv.setOnClickPendingIntent(R.id.map_slot, openApp)
            awm.updateAppWidget(widgetId, rv)
        }

        /**
         * Per-second tick: update only the countdown timer and ETA texts via a
         * partial update, so the (relatively heavy) map bitmap isn't re-sent to
         * the widget every second — only on full updates when data/map changes.
         */
        fun updateTick(
            ctx: Context, awm: AppWidgetManager, widgetId: Int,
            timeLeft: Int, arrivals: List<WidgetArrival>,
        ) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_map)
            val m = timeLeft / 60; val s = timeLeft % 60
            rv.setTextViewText(R.id.tw_timer, "$m:${s.toString().padStart(2, '0')}")
            ROW_IDS.forEachIndexed { i, ids ->
                val a = arrivals.getOrNull(i)
                rv.setTextViewText(ids.eta, a?.eta ?: "")
                rv.setTextColor(ids.eta, a?.color ?: 0xFF9090B8.toInt())
            }
            awm.partiallyUpdateAppWidget(widgetId, rv)
        }

        private fun startIntent(ctx: Context, widgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, widgetId + 70_000,
                Intent(ctx, MapWidgetProvider::class.java).apply {
                    action = ACTION_START
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun stopIntent(ctx: Context, widgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, widgetId + 80_000,
                Intent(ctx, MapWidgetProvider::class.java).apply {
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
                ctx, widgetId + 90_000, intent,
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
