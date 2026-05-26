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
    val eta:        String,
    val color:      Int,
    val etaSeconds: Int?,
    val direction:  String = "",
)

open class BusWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_START = "ru.buswidget.action.START"
        const val ACTION_STOP  = "ru.buswidget.action.STOP"

        private data class RowIds(val row: Int, val route: Int, val eta: Int, val dir: Int, val unit: Int)

        private val ROW_IDS = listOf(
            RowIds(R.id.row1, R.id.r1_route, R.id.r1_eta, R.id.r1_dir, R.id.r1_unit),
            RowIds(R.id.row2, R.id.r2_route, R.id.r2_eta, R.id.r2_dir, R.id.r2_unit),
            RowIds(R.id.row3, R.id.r3_route, R.id.r3_eta, R.id.r3_dir, R.id.r3_unit),
            RowIds(R.id.row4, R.id.r4_route, R.id.r4_eta, R.id.r4_dir, R.id.r4_unit),
        )

        private fun layoutFor(type: String) = when (type) {
            "light" -> R.layout.widget_bus_light
            "wide"  -> R.layout.widget_bus_wide
            else    -> R.layout.widget_bus_dark
        }

        private fun typeFor(ctx: Context, widgetId: Int) =
            widgetPrefs(ctx).getString("${widgetId}_type", "dark") ?: "dark"

        private fun formatEtaNum(secs: Int?): String = when {
            secs == null  -> "—"
            secs <= 0     -> "→"
            secs < 60     -> "<1"
            else          -> "${secs / 60}"
        }

        fun showIdle(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
            val type = typeFor(ctx, widgetId)
            val stopName = widgetPrefs(ctx).getString("${widgetId}_name", "Остановка") ?: "Остановка"
            val rv = RemoteViews(ctx.packageName, layoutFor(type))
            rv.setTextViewText(R.id.tw_stop, stopName)
            rv.setTextColor(R.id.tw_live, 0xFF9090B8.toInt())
            rv.setViewVisibility(R.id.tw_timer, View.GONE)
            rv.setViewVisibility(R.id.btn_start, View.VISIBLE)
            rv.setViewVisibility(R.id.btn_stop, View.GONE)
            if (type == "wide") {
                rv.setViewVisibility(R.id.rows_active, View.GONE)
            } else {
                ROW_IDS.forEach { rv.setViewVisibility(it.row, View.GONE) }
            }
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
            val type = typeFor(ctx, widgetId)
            val rv = RemoteViews(ctx.packageName, layoutFor(type))
            val m = timeLeft / 60; val s = timeLeft % 60
            rv.setTextViewText(R.id.tw_stop, stopName)
            rv.setTextViewText(R.id.tw_timer, "$m:${s.toString().padStart(2, '0')}")
            rv.setTextColor(R.id.tw_live, 0xFF2ED87A.toInt())
            rv.setViewVisibility(R.id.tw_timer, View.VISIBLE)
            rv.setViewVisibility(R.id.btn_start, View.GONE)
            rv.setViewVisibility(R.id.btn_stop, View.VISIBLE)
            if (type == "wide") {
                rv.setViewVisibility(R.id.rows_active, View.VISIBLE)
                ROW_IDS.forEachIndexed { i, ids ->
                    val a = arrivals.getOrNull(i)
                    val color = a?.color ?: 0xFF9090B8.toInt()
                    rv.setTextViewText(ids.route, a?.route ?: "—")
                    rv.setTextColor(ids.route, if (a != null) 0xFFF4F4F6.toInt() else 0xFF9090B8.toInt())
                    rv.setTextViewText(ids.eta, if (a != null) formatEtaNum(a.etaSeconds) else "—")
                    rv.setTextColor(ids.eta, color)
                    rv.setTextViewText(ids.unit, if (a != null) "МИН" else "")
                    rv.setTextColor(ids.unit, color)
                }
            } else {
                ROW_IDS.forEachIndexed { i, ids ->
                    val a = arrivals.getOrNull(i)
                    rv.setViewVisibility(ids.row, if (a != null) View.VISIBLE else View.GONE)
                    if (a != null) {
                        rv.setTextViewText(ids.route, a.route)
                        rv.setTextViewText(ids.eta, a.eta)
                        rv.setTextColor(ids.eta, a.color)
                        rv.setTextViewText(ids.dir, a.direction)
                    }
                }
            }
            rv.setOnClickPendingIntent(R.id.btn_stop, stopPendingIntent(ctx, widgetId))
            rv.setOnClickPendingIntent(R.id.tw_stop, openAppPendingIntent(ctx, widgetId))
            // for wide widget also set on content area:
            if (type == "wide") rv.setOnClickPendingIntent(R.id.rows_active, openAppPendingIntent(ctx, widgetId))
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

        private fun openAppPendingIntent(ctx: Context, widgetId: Int): PendingIntent {
            val p = widgetPrefs(ctx)
            val stopId = p.getString("${widgetId}_stopId", "") ?: ""
            val name   = p.getString("${widgetId}_name",   "") ?: ""
            val routes = p.getString("${widgetId}_routes", "") ?: ""
            val intent = Intent(ctx, ru.buswidget.ArrivalsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ru.buswidget.ArrivalsActivity.EXTRA_STOP_ID,   stopId)
                putExtra(ru.buswidget.ArrivalsActivity.EXTRA_STOP_NAME, name)
                putExtra(ru.buswidget.ArrivalsActivity.EXTRA_ROUTES,    routes)
            }
            return PendingIntent.getActivity(
                ctx, widgetId + 30_000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = widgetPrefs(ctx).edit()
        appWidgetIds.forEach { prefs.putString("${it}_type", "dark") }
        prefs.apply()
        appWidgetIds.forEach { showIdle(ctx, awm, it) }
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        val edit = widgetPrefs(ctx).edit()
        appWidgetIds.forEach { id ->
            edit.remove("${id}_stopId").remove("${id}_name").remove("${id}_routes").remove("${id}_type")
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
