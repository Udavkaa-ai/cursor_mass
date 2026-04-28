package ru.buswidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Stop(
    val id: String,
    val name: String,
    val routes: String = "",
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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Stop.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("routes", routes)
    }

    private fun JSONObject.toStop() = Stop(
        id     = optString("id"),
        name   = optString("name"),
        routes = optString("routes"),
    )
}
