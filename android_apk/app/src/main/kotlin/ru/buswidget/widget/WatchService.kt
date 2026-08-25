package ru.buswidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import ru.buswidget.R
import ru.buswidget.data.Config
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Watches ONE chosen bus and fires an alert when it is ~N minutes away
 * (OneBusAway-style arrival alarm for a specific departure).
 *
 * Buses carry no vehicle id in our data, so the watcher tracks the bus's
 * expected wall-clock arrival time: each poll it re-matches the arrival of the
 * same route/direction whose predicted arrival is closest to the expectation
 * and refines it. Even if the server data drops out, the alert still fires on
 * the projected time.
 */
class WatchService : Service() {

    companion object {
        private const val ACTION_CANCEL = "ru.buswidget.watch.CANCEL"
        private const val EXTRA_STOP_ID   = "stop_id"
        private const val EXTRA_STOP_NAME = "stop_name"
        private const val EXTRA_ROUTE     = "route"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ETA_SEC   = "eta_sec"
        private const val EXTRA_THRESHOLD = "threshold_min"
        private const val NOTIF_ID   = 44
        private const val CHANNEL_ID = "bus_watch"
        private const val MAX_WATCH_SEC = 45 * 60   // safety cap

        fun startWatch(
            ctx: Context, stopId: String, stopName: String,
            route: String, direction: String, etaSeconds: Int, thresholdMin: Int,
        ) {
            val i = Intent(ctx, WatchService::class.java).apply {
                putExtra(EXTRA_STOP_ID, stopId)
                putExtra(EXTRA_STOP_NAME, stopName)
                putExtra(EXTRA_ROUTE, route)
                putExtra(EXTRA_DIRECTION, direction)
                putExtra(EXTRA_ETA_SEC, etaSeconds)
                putExtra(EXTRA_THRESHOLD, thresholdMin)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private data class Watcher(
        val stopId: String,
        val stopName: String,
        val route: String,
        val direction: String,
        var expectedArrivalAt: Long,   // wall-clock estimate of the bus's arrival
        val thresholdSec: Int,
        val startedAt: Long = System.currentTimeMillis(),
    )

    private val handler  = Handler(Looper.getMainLooper())
    private val watchers = mutableListOf<Watcher>()
    private val fetchingStops = mutableSetOf<String>()
    private var nextPoll = 0
    private var lastNotifText: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (watchers.isEmpty()) { stopSelf(); return }
            val now = System.currentTimeMillis()

            val fired = watchers.filter { (it.expectedArrivalAt - now) / 1000 <= it.thresholdSec }
            fired.forEach { w ->
                ArrivalAlerts.post(
                    this@WatchService, w.stopId, w.stopName, "",
                    w.route, "будет через ~${w.thresholdSec / 60} мин",
                )
            }
            watchers.removeAll(fired.toSet())
            watchers.removeAll { now - it.startedAt > MAX_WATCH_SEC * 1000L }

            if (watchers.isEmpty()) { stopSelf(); return }

            if (--nextPoll <= 0) { nextPoll = Config.POLL_SEC; pollStops() }
            updateForegroundNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("запускаю…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            watchers.clear()
            handler.removeCallbacks(tick)
            stopSelf()
            return START_NOT_STICKY
        }
        val stopId = intent?.getStringExtra(EXTRA_STOP_ID) ?: return START_NOT_STICKY
        val w = Watcher(
            stopId    = stopId,
            stopName  = intent.getStringExtra(EXTRA_STOP_NAME) ?: stopId,
            route     = intent.getStringExtra(EXTRA_ROUTE) ?: return START_NOT_STICKY,
            direction = intent.getStringExtra(EXTRA_DIRECTION) ?: "",
            expectedArrivalAt = System.currentTimeMillis() +
                intent.getIntExtra(EXTRA_ETA_SEC, 0) * 1000L,
            thresholdSec = intent.getIntExtra(EXTRA_THRESHOLD, 3) * 60,
        )
        // Re-watching the same bus replaces the old watcher
        watchers.removeAll { it.stopId == w.stopId && it.route == w.route && it.direction == w.direction }
        watchers += w
        lastNotifText = null
        nextPoll = 0
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    /** Poll each watched stop and refine the watchers' expected arrival times. */
    private fun pollStops() {
        watchers.map { it.stopId }.distinct().forEach { stopId ->
            if (stopId in fetchingStops) return@forEach
            fetchingStops += stopId
            Thread {
                val arrivals = try {
                    val base = Config.SERVER_URL.trimEnd('/')
                    val conn = URL("$base/arrivals/${URLEncoder.encode(stopId, "UTF-8")}")
                        .openConnection() as HttpURLConnection
                    conn.apply { connectTimeout = 10_000; readTimeout = 10_000 }
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    parse(json.optJSONArray("arrivals"))
                } catch (_: Exception) { null }
                handler.post {
                    fetchingStops -= stopId
                    if (arrivals != null) refine(stopId, arrivals)
                }
            }.start()
        }
    }

    /** (route, direction, etaSeconds) triples with null-safe parsing. */
    private fun parse(arr: JSONArray?): List<Triple<String, String, Int>> {
        arr ?: return emptyList()
        val out = mutableListOf<Triple<String, String, Int>>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.isNull("eta_seconds")) continue
            val route = if (a.isNull("route")) "" else a.optString("route")
            if (route.isBlank()) continue
            val dir = if (a.isNull("direction")) "" else a.optString("direction")
            out += Triple(route, dir, a.getInt("eta_seconds"))
        }
        return out
    }

    private fun refine(stopId: String, arrivals: List<Triple<String, String, Int>>) {
        val now = System.currentTimeMillis()
        watchers.filter { it.stopId == stopId }.forEach { w ->
            val candidates = arrivals.filter { (route, dir, _) ->
                route == w.route && (w.direction.isBlank() || dir == w.direction)
            }
            // The same bus = the prediction closest to our current expectation
            val best = candidates.minByOrNull { (_, _, eta) ->
                kotlin.math.abs(now + eta * 1000L - w.expectedArrivalAt)
            }
            if (best != null) w.expectedArrivalAt = now + best.third * 1000L
        }
    }

    private fun updateForegroundNotification() {
        val now = System.currentTimeMillis()
        val lines = watchers.joinToString("\n") { w ->
            val min = ((w.expectedArrivalAt - now) / 60000).coerceAtLeast(0)
            "${w.route} → ~$min мин · напомню за ${w.thresholdSec / 60}"
        }
        if (lines == lastNotifText) return
        lastNotifText = lines
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(lines))
    }

    private fun buildNotification(text: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Слежение за автобусом", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val cancelIntent = Intent(this, WatchService::class.java).apply { action = ACTION_CANCEL }
        val cancelPi =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                PendingIntent.getForegroundService(
                    this, 1, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else
                PendingIntent.getService(
                    this, 1, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = watchers.firstOrNull()?.let { "👁 Слежу · ${it.stopName}" } ?: "👁 Слежу за автобусом"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_bus)
            .setContentTitle(title)
            .setContentText(text.replace("\n", "  ·  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Отменить", cancelPi)
            .build()
    }
}
