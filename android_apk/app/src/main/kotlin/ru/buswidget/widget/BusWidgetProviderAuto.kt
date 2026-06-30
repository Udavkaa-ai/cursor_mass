package ru.buswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.view.View
import android.widget.RemoteViews
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.ArrivalsActivity
import ru.buswidget.MainActivity
import ru.buswidget.R
import ru.buswidget.data.Config
import ru.buswidget.data.StopStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BusWidgetProviderAuto : AppWidgetProvider() {

    companion object {
        /** Re-trigger onUpdate for every auto-widget instance (e.g. after the app
         *  refreshes the cached location). */
        fun refreshAll(ctx: Context) {
            val awm = AppWidgetManager.getInstance(ctx)
            val ids = awm.getAppWidgetIds(ComponentName(ctx, BusWidgetProviderAuto::class.java))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(Intent(ctx, BusWidgetProviderAuto::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        appWidgetIds.forEach { widgetId ->
            try {
                // lastLocation returns null from a background widget update when no
                // foreground app has requested location recently. Try it first (fast),
                // and if it's null actively request a fresh fix via getCurrentLocation().
                client.lastLocation.addOnSuccessListener { cached: Location? ->
                    if (cached != null) {
                        cacheLocation(ctx, cached)
                        onLocationReceived(ctx, awm, widgetId, cached)
                    } else {
                        requestFreshLocation(ctx, awm, widgetId)
                    }
                }.addOnFailureListener {
                    requestFreshLocation(ctx, awm, widgetId)
                }
            } catch (e: SecurityException) {
                showNoLocation(ctx, awm, widgetId)
            }
        }
    }

    private fun requestFreshLocation(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        try {
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { fresh: Location? ->
                if (fresh != null) {
                    cacheLocation(ctx, fresh)
                    onLocationReceived(ctx, awm, widgetId, fresh)
                } else {
                    useCachedOrFail(ctx, awm, widgetId)
                }
            }.addOnFailureListener {
                useCachedOrFail(ctx, awm, widgetId)
            }
        } catch (e: SecurityException) {
            showNoLocation(ctx, awm, widgetId)
        }
    }

    private fun useCachedOrFail(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
        val cached = loadCachedLocation(ctx)
        if (cached != null) {
            onLocationReceived(ctx, awm, widgetId, cached)
        } else {
            showNoLocation(ctx, awm, widgetId)
        }
    }

    private fun onLocationReceived(ctx: Context, awm: AppWidgetManager, widgetId: Int, location: Location) {
        val nearby = StopStorage.findNearby(ctx, location.latitude, location.longitude, 1)
        if (nearby.isNotEmpty()) {
            updateWidget(ctx, awm, widgetId, nearby[0])
        } else {
            showNoStops(ctx, awm, widgetId)
        }
    }

    private fun cacheLocation(ctx: Context, location: Location) {
        ctx.getSharedPreferences("bw_widget", Context.MODE_PRIVATE).edit()
            .putString("last_lat", location.latitude.toString())
            .putString("last_lon", location.longitude.toString())
            .apply()
    }

    private fun loadCachedLocation(ctx: Context): Location? {
        val p = ctx.getSharedPreferences("bw_widget", Context.MODE_PRIVATE)
        val lat = p.getString("last_lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("last_lon", null)?.toDoubleOrNull() ?: return null
        return Location("cache").apply { latitude = lat; longitude = lon }
    }

    private fun updateWidget(ctx: Context, awm: AppWidgetManager, widgetId: Int, nearby: ru.buswidget.data.NearbyStop) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
        val distKm = nearby.distanceMeters / 1000.0
        val distText = if (nearby.distanceMeters < 1000) "${nearby.distanceMeters}м" else "%.1fкм".format(distKm)
        rv.setTextViewText(R.id.tw_stop, nearby.stop.name)
        rv.setTextViewText(R.id.tw_distance, distText)

        // Add click listener to open ArrivalsActivity
        val intent = Intent(ctx, ArrivalsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ArrivalsActivity.EXTRA_STOP_ID, nearby.stop.id)
            putExtra(ArrivalsActivity.EXTRA_STOP_NAME, nearby.stop.name)
            putExtra(ArrivalsActivity.EXTRA_ROUTES, nearby.stop.routes)
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, widgetId + 50_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        Thread {
            try {
                val base = Config.SERVER_URL.trimEnd('/')
                val qs = if (nearby.stop.routes.isNotBlank()) "?routes=${URLEncoder.encode(nearby.stop.routes, "UTF-8")}" else ""
                val conn = URL("$base/arrivals/${URLEncoder.encode(nearby.stop.id, "UTF-8")}$qs")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 8_000; readTimeout = 8_000
                    setRequestProperty("Accept", "application/json")
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val arrivals = parseArrivals(json.optJSONArray("arrivals"))

                val rows = listOf(
                    Triple(R.id.row1, R.id.r1_route, R.id.r1_dir to R.id.r1_eta),
                    Triple(R.id.row2, R.id.r2_route, R.id.r2_dir to R.id.r2_eta),
                    Triple(R.id.row3, R.id.r3_route, R.id.r3_dir to R.id.r3_eta),
                    Triple(R.id.row4, R.id.r4_route, R.id.r4_dir to R.id.r4_eta),
                )
                rows.forEachIndexed { i, (rowId, routeId, dirEta) ->
                    val a = arrivals.getOrNull(i)
                    rv.setViewVisibility(rowId, if (a != null) View.VISIBLE else View.GONE)
                    if (a != null) {
                        rv.setTextViewText(routeId, a.route)
                        rv.setTextViewText(dirEta.first, a.direction)
                        rv.setTextViewText(dirEta.second, a.eta)
                        rv.setTextColor(dirEta.second, a.color)
                    }
                }
                awm.updateAppWidget(widgetId, rv)
            } catch (e: Exception) {
                // Silently ignore network errors; show stale data
            }
        }.start()
    }

    private fun showNoLocation(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
        rv.setTextViewText(R.id.tw_stop, "Геолокация")
        rv.setTextViewText(R.id.tw_distance, "включите GPS")

        // Add click to open app for permission grant
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, widgetId + 60_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        awm.updateAppWidget(widgetId, rv)
    }

    private fun showNoStops(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
        rv.setTextViewText(R.id.tw_stop, "Остановки")
        rv.setTextViewText(R.id.tw_distance, "не сохранены")
        awm.updateAppWidget(widgetId, rv)
    }

    private fun parseArrivals(arr: JSONArray?): List<WidgetArrival> {
        arr ?: return emptyList()
        return (0 until minOf(arr.length(), 4)).mapNotNull { i ->
            val a = arr.getJSONObject(i)
            val route = a.optString("route", "?").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val etaSecs = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds")
            WidgetArrival(
                route = route,
                eta = a.optString("eta_local", a.optString("eta_text", "—")),
                color = PollService.etaColor(etaSecs),
                etaSeconds = etaSecs,
                direction = a.optString("direction", ""),
            )
        }
    }
}
