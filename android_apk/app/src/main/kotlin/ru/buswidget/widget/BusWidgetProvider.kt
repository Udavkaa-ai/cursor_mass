package ru.buswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import ru.buswidget.R

data class WidgetArrival(
    val route:      String,
    val direction:  String,
    val eta:        String,
    val color:      Int,
    val etaSeconds: Int?,
)

class BusWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_START = "ru.buswidget.action.START"
        const val ACTION_STOP  = "ru.buswidget.action.STOP"

        private data class RowIds(val row: Int, val route: Int, val dir: Int, val eta: Int)

        private val ROW_IDS = listOf(
            RowIds(R.id.row1, R.id.r1_route, R.id.r1_dir, R.id.r1_eta),
            RowIds(R.id.row2, R.id.r2_route, R.id.r2_dir, R.id.r2_eta),
            RowIds(R.id.row3, R.id.r3_route, R.id.r3_dir, R.id.r3_eta),
        )

        fun showIdle(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
            val stopName = widgetPrefs(ctx).getString("${widgetId}_name", "Остановка") ?: "Остановка"
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus)
            rv.setTextViewText(R.id.tw_stop, stopName)
            rv.setViewVisibility(R.id.tw_timer,  View.GONE)
            rv.setViewVisibility(R.id.btn_start, View.VISIBLE)
            rv.setViewVisibility(R.id.btn_stop,  View.GONE)
            ROW_IDS.forEach { ids -> rv.setViewVisibility(ids.row, View.GONE) }
            rv.setOnClickPendingIntent(R.id.btn_start, startPendingIntent(ctx, widgetId))
            awm.updateAppWidget(widgetId, rv)
        }

        fun updateActive(
            ctx:      Context,
            awm:      AppWidgetManager,
            widgetId: Int,
            stopName: String,
            timeLeft: Int,
            arrivals: List<WidgetArrival>,
        ) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_bus)
            val m = timeLeft / 60; val s = timeLeft % 60
            rv.setTextViewText(R.id.tw_stop,  stopName)
            rv.setTextViewText(R.id.tw_timer, "$m:${s.toString().padStart(2, '0')}")
            rv.setViewVisibility(R.id.tw_timer,  View.VISIBLE)
            rv.setViewVisibility(R.id.btn_start, View.GONE)
            rv.setViewVisibility(R.id.btn_stop,  View.VISIBLE)
            ROW_IDS.forEachIndexed { i, ids ->
                val a = arrivals.getOrNull(i)
                rv.setViewVisibility(ids.row, if (a != null) View.VISIBLE else View.GONE)
                if (a != null) {
                    rv.setTextViewText(ids.route, a.route)
                    rv.setTextViewText(ids.dir,   a.direction)
                    rv.setTextViewText(ids.eta,   a.eta)
                    rv.setTextColor(ids.eta, a.color)
                }
            }
            rv.setOnClickPendingIntent(R.id.btn_stop, stopPendingIntent(ctx, widgetId))
            awm.updateAppWidget(widgetId, rv)
        }

        fun widgetPrefs(ctx: Context) =
            ctx.getSharedPreferences("bw_widget", Context.MODE_PRIVATE)

        private fun startPendingIntent(ctx: Context, widgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, widgetId,
                Intent(ctx, BusWidgetProvider::class.java).apply {
                    action = ACTION_START
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun stopPendingIntent(ctx: Context, widgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, widgetId + 10_000,
                Intent(ctx, BusWidgetProvider::class.java).apply {
                    action = ACTION_STOP
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { try { showIdle(ctx, awm, it) } catch (_: Exception) {} }
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        val edit = widgetPrefs(ctx).edit()
        appWidgetIds.forEach { id ->
            edit.remove("${id}_stopId").remove("${id}_name").remove("${id}_routes")
            PollService.stopFor(ctx, id)
        }
        edit.apply()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        when (intent.action) {
            ACTION_START -> {
                val p      = widgetPrefs(ctx)
                val stopId = p.getString("${widgetId}_stopId", "") ?: ""
                val name   = p.getString("${widgetId}_name",   "") ?: ""
                val routes = p.getString("${widgetId}_routes", "") ?: ""
                if (stopId.isNotBlank()) PollService.startFor(ctx, widgetId, stopId, name, routes)
            }
            ACTION_STOP  -> PollService.stopFor(ctx, widgetId)
        }
    }
}
