package ru.buswidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.location.Location
import android.view.View
import android.widget.RemoteViews
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.R
import ru.buswidget.data.Config
import ru.buswidget.data.StopStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class BusWidgetProviderAuto : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
        appWidgetIds.forEach { widgetId ->
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val nearby = StopStorage.findNearby(ctx, location.latitude, location.longitude, 1)
                        if (nearby.isNotEmpty()) {
                            updateWidget(ctx, awm, widgetId, nearby[0])
                        } else {
                            showNoStops(ctx, awm, widgetId)
                        }
                    } else {
                        showNoLocation(ctx, awm, widgetId)
                    }
                }
            } catch (e: SecurityException) {
                showNoLocation(ctx, awm, widgetId)
            }
        }
    }

    private fun updateWidget(ctx: Context, awm: AppWidgetManager, widgetId: Int, nearby: ru.buswidget.data.NearbyStop) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_bus_auto)
        val distKm = nearby.distanceMeters / 1000.0
        val distText = if (nearby.distanceMeters < 1000) "${nearby.distanceMeters}м" else "%.1fкм".format(distKm)
        rv.setTextViewText(R.id.tw_stop, nearby.stop.name)
        rv.setTextViewText(R.id.tw_distance, distText)

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
        rv.setTextViewText(R.id.tw_distance, "нет данных")
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
