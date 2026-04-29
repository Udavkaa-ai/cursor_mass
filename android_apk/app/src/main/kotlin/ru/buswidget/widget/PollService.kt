package ru.buswidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.data.Config
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PollService : Service() {

    companion object {
        private const val ACTION_STOP  = "ru.buswidget.STOP_POLL"
        private const val EXTRA_ID     = "widget_id"
        private const val EXTRA_STOP   = "stop_id"
        private const val EXTRA_NAME   = "stop_name"
        private const val EXTRA_ROUTES = "routes"
        private const val NOTIF_ID     = 42
        private const val CHANNEL_ID   = "bus_poll"

        fun startFor(ctx: Context, widgetId: Int, stopId: String, stopName: String, routes: String) {
            val i = Intent(ctx, PollService::class.java).apply {
                putExtra(EXTRA_ID, widgetId); putExtra(EXTRA_STOP, stopId)
                putExtra(EXTRA_NAME, stopName); putExtra(EXTRA_ROUTES, routes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stopFor(ctx: Context, widgetId: Int) {
            ctx.startService(Intent(ctx, PollService::class.java).apply {
                action = ACTION_STOP; putExtra(EXTRA_ID, widgetId)
            })
        }

        fun formatEta(secs: Int): String = when {
            secs <= 0   -> "подъезж."
            secs < 60   -> "< 1 мин"
            secs < 3600 -> "${secs / 60} мин"
            else        -> "${secs / 3600}ч"
        }

        fun etaColor(secs: Int?): Int = when {
            secs == null -> 0xFF9090A0.toInt()  // grey   — unknown
            secs <= 0    -> 0xFFFF4D4D.toInt()  // red    — arriving
            secs <= 300  -> 0xFFFF4D4D.toInt()  // red    — < 5 min
            secs <= 420  -> 0xFFFFAA33.toInt()  // orange — 5-7 min
            secs <= 600  -> 0xFF42D883.toInt()  // green  — 7-10 min
            else         -> 0xFF6A6A7A.toInt()  // grey   — > 10 min
        }
    }

    private data class Session(
        val stopId: String, val stopName: String, val routes: String,
        var timeLeft: Int = Config.SESSION_SEC,
        var nextPoll: Int = 0,
    )

    // Fetched arrivals with timestamp for live countdown
    private data class Snapshot(val fetchedAt: Long, val arrivals: List<WidgetArrival>)

    private val handler   = Handler(Looper.getMainLooper())
    private val sessions  = mutableMapOf<Int, Session>()
    private val snapshots = mutableMapOf<Int, Snapshot>()

    private val tick = object : Runnable {
        override fun run() {
            if (sessions.isEmpty()) { stopSelf(); return }
            sessions.entries.toList().forEach { (widgetId, s) ->
                s.timeLeft--
                s.nextPoll--
                if (s.nextPoll <= 0) { s.nextPoll = Config.POLL_SEC; fetchAndUpdate(widgetId, s) }
                if (s.timeLeft <= 0) endSession(widgetId)
                else pushUpdate(widgetId, s)
            }
            if (sessions.isNotEmpty()) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() { super.onCreate(); postForegroundNotification() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            if (id != -1) endSession(id)
            return START_NOT_STICKY
        }
        val widgetId = intent?.getIntExtra(EXTRA_ID,    -1) ?: return START_NOT_STICKY
        val stopId   = intent.getStringExtra(EXTRA_STOP)    ?: return START_NOT_STICKY
        val stopName = intent.getStringExtra(EXTRA_NAME)    ?: stopId
        val routes   = intent.getStringExtra(EXTRA_ROUTES)  ?: ""
        if (widgetId == -1) return START_NOT_STICKY
        sessions[widgetId] = Session(stopId, stopName, routes)
        if (sessions.size == 1) handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun endSession(widgetId: Int) {
        sessions.remove(widgetId)
        snapshots.remove(widgetId)
        BusWidgetProvider.showIdle(this, AppWidgetManager.getInstance(this), widgetId)
        if (sessions.isEmpty()) { handler.removeCallbacks(tick); stopSelf() }
    }

    private fun pushUpdate(widgetId: Int, s: Session) {
        BusWidgetProvider.updateActive(
            this, AppWidgetManager.getInstance(this),
            widgetId, s.stopName, s.timeLeft,
            liveArrivals(widgetId),
        )
    }

    // Re-compute ETAs each second from stored etaSeconds + elapsed time
    private fun liveArrivals(widgetId: Int): List<WidgetArrival> {
        val snap = snapshots[widgetId] ?: return emptyList()
        val elapsed = ((System.currentTimeMillis() - snap.fetchedAt) / 1000).toInt()
        return snap.arrivals.mapNotNull { a ->
            val liveSecs = a.etaSeconds?.minus(elapsed)
            if (liveSecs != null && liveSecs < -30) return@mapNotNull null
            a.copy(
                eta   = liveSecs?.let { formatEta(it) } ?: a.eta,
                color = etaColor(liveSecs ?: a.etaSeconds),
            )
        }
    }

    private fun fetchAndUpdate(widgetId: Int, s: Session) {
        Thread {
            try {
                val base = Config.SERVER_URL.trimEnd('/')
                val qs = if (s.routes.isNotBlank()) "?routes=${URLEncoder.encode(s.routes, "UTF-8")}" else ""
                val conn = URL("$base/arrivals/${URLEncoder.encode(s.stopId, "UTF-8")}$qs")
                    .openConnection() as HttpURLConnection
                conn.apply { connectTimeout = 15_000; readTimeout = 15_000 }
                val json     = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val arrivals = parseArrivals(json.optJSONArray("arrivals"))
                handler.post {
                    val prev = snapshots[widgetId]
                    if (arrivals.isNotEmpty() && arrivals != prev?.arrivals) {
                        snapshots[widgetId] = Snapshot(System.currentTimeMillis(), arrivals)
                    }
                    sessions[widgetId]?.let { pushUpdate(widgetId, it) }
                }
            } catch (_: Exception) { /* next poll will retry */ }
        }.start()
    }

    private fun parseArrivals(arr: JSONArray?): List<WidgetArrival> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val a    = arr.getJSONObject(i)
            val secs = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds")
            WidgetArrival(
                route      = a.optString("route", "?"),
                eta        = a.optString("eta_local").ifBlank { a.optString("eta_text", "—") },
                etaSeconds = secs,
                color      = etaColor(secs),
            )
        }.sortedBy { it.etaSeconds ?: Int.MAX_VALUE }
    }

    private fun postForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Опрос остановок", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Где автобус?")
            .setContentText("Обновление данных остановок")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            startForeground(NOTIF_ID, notif)
    }
}
