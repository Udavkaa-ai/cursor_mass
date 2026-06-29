package ru.buswidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Stop(
    val id: String,
    val name: String,
    val routes: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

data class NearbyStop(
    val stop: Stop,
    val distanceMeters: Int,
)

object StopStorage {
    private const val PREFS     = "bw"
    private const val KEY_STOPS = "stops_v1"

    fun load(ctx: Context): MutableList<Stop> {
        val json = prefs(ctx).getString(KEY_STOPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapTo(mutableListOf()) { arr.getJSONObject(it).toStop() }
        } catch (_: Exception) { mutableListOf() }
    }

    fun save(ctx: Context, stops: List<Stop>) {
        val arr = JSONArray()
        stops.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_STOPS, arr.toString()).apply()
    }

    fun add(ctx: Context, stop: Stop) = save(ctx, load(ctx).apply { add(stop) })

    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filter { it.id != id })

    fun update(ctx: Context, stop: Stop) =
        save(ctx, load(ctx).map { if (it.id == stop.id) stop else it })

    fun findNearby(ctx: Context, userLat: Double, userLon: Double, maxCount: Int = 5): List<NearbyStop> {
        return load(ctx)
            .filter { it.lat != 0.0 && it.lon != 0.0 }
            .map { stop ->
                val distanceMeters = distanceBetween(userLat, userLon, stop.lat, stop.lon)
                NearbyStop(stop, distanceMeters)
            }
            .sortedBy { it.distanceMeters }
            .take(maxCount)
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val R = 6371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toInt()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Stop.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("routes", routes)
        put("lat", lat); put("lon", lon)
    }

    private fun JSONObject.toStop() = Stop(
        id     = optString("id"),
        name   = optString("name"),
        routes = optString("routes"),
        lat    = optDouble("lat", 0.0),
        lon    = optDouble("lon", 0.0),
    )
}
