package ru.buswidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Last [MAX_SAMPLES] observed arrivals per (stop, route), used to calibrate
 * Yandex's ETA for that route at that stop.
 *
 * The feed has no vehicle coordinates — only predicted ETAs — so the map
 * circle is "remaining time × average bus speed". What history CAN learn is
 * how the prediction relates to reality: each sample stores the ETA promised
 * when we first saw the bus (horizon) and how long it really took. The median
 * actual/predicted ratio then scales the remaining time behind the circle.
 *
 * Detection, polled every ~15 s: the nearest bus of a route is tracked by its
 * expected wall-clock arrival; it counts as arrived when its ETA hits 0, or
 * when it vanishes / the nearest prediction jumps to a later bus while the
 * tracked one was ≤ 2 min out.
 */
object ArrivalHistory {

    private const val PREFS = "bw_hist"
    private const val MAX_SAMPLES = 10
    private const val MIN_SAMPLES = 3
    private const val MIN_HORIZON = 60       // shorter predictions teach nothing
    private const val MAX_HORIZON = 15 * 60
    private const val NEAR_SEC = 120         // "about to arrive" for vanish detection
    private const val STALE_MS = 30 * 60_000L

    private data class Tracker(
        val firstSeenAt: Long,
        val horizonSec: Int,
        var expectedAt: Long,
        var lastSeenAt: Long,
        var lastEta: Int,
    )

    private val trackers = mutableMapOf<String, Tracker>()

    private fun key(stopId: String, route: String) =
        stopId.removePrefix("stop__") + "|" + route

    /** Feed one poll's arrivals for a stop: (route, etaSeconds or null). */
    @Synchronized
    fun observe(ctx: Context, stopId: String, arrivals: List<Pair<String, Int?>>) {
        val now = System.currentTimeMillis()
        val nearest = arrivals
            .filter { it.second != null && it.first.isNotBlank() }
            .groupBy({ it.first }, { it.second!! })
            .mapValues { (_, etas) -> etas.minOrNull() ?: 0 }

        val stopPrefix = stopId.removePrefix("stop__") + "|"
        // Tracked routes that vanished from the feed this poll
        trackers.keys.filter { it.startsWith(stopPrefix) }.forEach { k ->
            val route = k.removePrefix(stopPrefix)
            val t = trackers[k] ?: return@forEach
            if (route !in nearest) {
                if (t.lastEta <= NEAR_SEC) finish(ctx, k, t, now)
                else if (now - t.lastSeenAt > STALE_MS) trackers.remove(k)
            }
        }

        for ((route, eta) in nearest) {
            val k = key(stopId, route)
            val t = trackers[k]
            val predictedAt = now + eta * 1000L
            if (t == null) {
                start(k, now, eta)
                continue
            }
            val remaining = ((t.expectedAt - now) / 1000).coerceAtLeast(0)
            val tolerance = maxOf(120_000L, remaining * 500L)   // ±2 min or ±50 %
            when {
                abs(predictedAt - t.expectedAt) <= tolerance -> {
                    t.expectedAt = predictedAt
                    t.lastSeenAt = now
                    t.lastEta = eta
                    if (eta <= 0) finish(ctx, k, t, now)
                }
                predictedAt > t.expectedAt && t.lastEta <= NEAR_SEC -> {
                    // our bus left, the feed now shows the next one
                    finish(ctx, k, t, now)
                    start(k, now, eta)
                }
                else -> start(k, now, eta)   // lost track — begin again
            }
        }
    }

    private fun start(k: String, now: Long, eta: Int) {
        if (eta in MIN_HORIZON..MAX_HORIZON) {
            trackers[k] = Tracker(now, eta, now + eta * 1000L, now, eta)
        } else {
            trackers.remove(k)
        }
    }

    private fun finish(ctx: Context, k: String, t: Tracker, now: Long) {
        trackers.remove(k)
        // Arrival happened between the last sighting and now; best guess is
        // the last prediction, clamped into that window.
        val arrivedAt = (t.lastSeenAt + t.lastEta.coerceAtLeast(0) * 1000L)
            .coerceIn(t.lastSeenAt, now)
        val actualSec = ((arrivedAt - t.firstSeenAt) / 1000).toInt()
        val ratio = actualSec.toDouble() / t.horizonSec
        if (ratio !in 0.3..3.0) return   // implausible — likely a different bus

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(k, "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("ts", now)
            put("horizon", t.horizonSec)
            put("actual", actualSec)
        })
        while (arr.length() > MAX_SAMPLES) arr.remove(0)
        prefs.edit().putString(k, arr.toString()).apply()
    }

    private fun ratios(ctx: Context, stopId: String, route: String): List<Double> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(stopId, route), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getInt("actual").toDouble() / o.getInt("horizon")
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Number of stored arrivals for this route at this stop (0..10). */
    fun count(ctx: Context, stopId: String, route: String): Int = ratios(ctx, stopId, route).size

    /**
     * Median actual/predicted time ratio, or 1.0 until [MIN_SAMPLES] arrivals
     * are known. >1 means the route usually takes longer than Yandex says.
     */
    fun factor(ctx: Context, stopId: String, route: String): Double {
        val r = ratios(ctx, stopId, route).sorted()
        if (r.size < MIN_SAMPLES) return 1.0
        val median = if (r.size % 2 == 1) r[r.size / 2] else (r[r.size / 2 - 1] + r[r.size / 2]) / 2
        return median.coerceIn(0.6, 1.8)
    }

    /** ETA corrected by this route's history (used for the distance circle). */
    fun correctedEta(ctx: Context, stopId: String, route: String, etaSeconds: Int): Int =
        (etaSeconds * factor(ctx, stopId, route)).toInt()
}
