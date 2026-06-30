package ru.buswidget.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.data.Config
import ru.buswidget.data.StopStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Drives a 5-minute session for an auto-widget: resolves the nearest saved stop
 * by geolocation (works because this is a foreground service), then polls live
 * arrivals every [Config.POLL_SEC] and renders them via [BusWidgetProviderAuto].
 */
class AutoPollService : Service() {

    companion object {
        private const val ACTION_STOP = "ru.buswidget.auto.STOP_POLL"
        private const val EXTRA_ID    = "widget_id"
        private const val NOTIF_ID    = 43
        private const val CHANNEL_ID  = "bus_auto_poll"

        fun startFor(ctx: Context, widgetId: Int) {
            val i = Intent(ctx, AutoPollService::class.java).putExtra(EXTRA_ID, widgetId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                // Android 12+ may reject a background FGS start in rare cases — show a
                // hint instead of crashing.
                BusWidgetProviderAuto.showMessage(
                    ctx, AppWidgetManager.getInstance(ctx), widgetId, "Остановка", "откройте приложение")
            }
        }

        fun stopFor(ctx: Context, widgetId: Int) {
            ctx.startService(Intent(ctx, AutoPollService::class.java).apply {
                action = ACTION_STOP; putExtra(EXTRA_ID, widgetId)
            })
        }
    }

    private data class Session(
        var stopId: String = "",
        var stopName: String = "",
        var routes: String = "",
        var distanceText: String = "",
        var resolved: Boolean = false,
        var timeLeft: Int = Config.SESSION_SEC,
        var nextPoll: Int = 0,
    )

    private data class Snapshot(val fetchedAt: Long, val arrivals: List<WidgetArrival>)

    private val handler   = Handler(Looper.getMainLooper())
    private val sessions  = mutableMapOf<Int, Session>()
    private val snapshots = mutableMapOf<Int, Snapshot>()
    private val fetching  = mutableSetOf<Int>()

    private val tick = object : Runnable {
        override fun run() {
            if (sessions.isEmpty()) { stopSelf(); return }
            sessions.entries.toList().forEach { (widgetId, s) ->
                if (!s.resolved) return@forEach   // still waiting on the GPS fix
                s.timeLeft--
                s.nextPoll--
                if (s.nextPoll <= 0) { s.nextPoll = Config.POLL_SEC; fetchAndUpdate(widgetId, s) }
                if (s.timeLeft <= 0) endSession(widgetId) else pushUpdate(widgetId, s)
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
        val widgetId = intent?.getIntExtra(EXTRA_ID, -1) ?: return START_NOT_STICKY
        if (widgetId == -1) return START_NOT_STICKY

        sessions[widgetId] = Session()
        BusWidgetProviderAuto.showLocating(this, AppWidgetManager.getInstance(this), widgetId)
        if (sessions.size == 1) handler.post(tick)
        resolveNearestStop(widgetId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveNearestStop(widgetId: Int) {
        val awm = AppWidgetManager.getInstance(this)
        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            BusWidgetProviderAuto.showMessage(this, awm, widgetId, "Геолокация", "нет разрешения")
            endSession(widgetId)
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        try {
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { loc: Location? ->
                if (loc != null) onLocation(widgetId, loc)
                else client.lastLocation.addOnSuccessListener { last ->
                    if (last != null) onLocation(widgetId, last)
                    else fail(widgetId, "включите GPS")
                }.addOnFailureListener { fail(widgetId, "включите GPS") }
            }.addOnFailureListener { fail(widgetId, "включите GPS") }
        } catch (e: SecurityException) {
            fail(widgetId, "нет разрешения")
        }
    }

    private fun onLocation(widgetId: Int, loc: Location) {
        val s = sessions[widgetId] ?: return
        val nearby = StopStorage.findNearby(this, loc.latitude, loc.longitude, 1).firstOrNull()
        if (nearby == null) {
            fail(widgetId, "нет остановок рядом")
            return
        }
        s.stopId = nearby.stop.id
        s.stopName = nearby.stop.name
        s.routes = nearby.stop.routes
        s.distanceText = if (nearby.distanceMeters < 1000) "${nearby.distanceMeters}м"
                         else "%.1fкм".format(nearby.distanceMeters / 1000.0)
        s.resolved = true
        s.nextPoll = 0  // fetch immediately on next tick
        pushUpdate(widgetId, s)
    }

    private fun fail(widgetId: Int, msg: String) {
        BusWidgetProviderAuto.showMessage(
            this, AppWidgetManager.getInstance(this), widgetId, "Остановка", msg)
        endSession(widgetId)
    }

    private fun endSession(widgetId: Int) {
        sessions.remove(widgetId)
        snapshots.remove(widgetId)
        BusWidgetProviderAuto.showIdle(this, AppWidgetManager.getInstance(this), widgetId)
        if (sessions.isEmpty()) { handler.removeCallbacks(tick); stopSelf() }
    }

    private fun pushUpdate(widgetId: Int, s: Session) {
        BusWidgetProviderAuto.updateActive(
            this, AppWidgetManager.getInstance(this), widgetId,
            s.stopId, s.stopName, s.routes, s.distanceText, s.timeLeft,
            liveArrivals(widgetId),
        )
    }

    private fun liveArrivals(widgetId: Int): List<WidgetArrival> {
        val snap = snapshots[widgetId] ?: return emptyList()
        val elapsed = ((System.currentTimeMillis() - snap.fetchedAt) / 1000).toInt()
        return snap.arrivals.mapNotNull { a ->
            val liveSecs = a.etaSeconds?.minus(elapsed)
            if (liveSecs != null && liveSecs < -30) return@mapNotNull null
            a.copy(
                eta   = liveSecs?.let { PollService.formatEta(it) } ?: a.eta,
                color = PollService.etaColor(liveSecs ?: a.etaSeconds),
            )
        }
    }

    private fun fetchAndUpdate(widgetId: Int, s: Session) {
        if (widgetId in fetching) return
        fetching += widgetId
        Thread {
            try {
                val base = Config.SERVER_URL.trimEnd('/')
                val qs = if (s.routes.isNotBlank()) "?routes=${URLEncoder.encode(s.routes, "UTF-8")}" else ""
                val conn = URL("$base/arrivals/${URLEncoder.encode(s.stopId, "UTF-8")}$qs")
                    .openConnection() as HttpURLConnection
                conn.apply { connectTimeout = 10_000; readTimeout = 10_000 }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val arrivals = parseArrivals(json.optJSONArray("arrivals"))
                handler.post {
                    fetching -= widgetId
                    val prev = snapshots[widgetId]
                    if (arrivals.isNotEmpty() && arrivals != prev?.arrivals) {
                        snapshots[widgetId] = Snapshot(System.currentTimeMillis(), arrivals)
                    }
                    sessions[widgetId]?.let { pushUpdate(widgetId, it) }
                }
            } catch (_: Exception) { handler.post { fetching -= widgetId } }
        }.start()
    }

    private fun parseArrivals(arr: JSONArray?): List<WidgetArrival> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val a    = arr.getJSONObject(i)
            val secs = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds")
            WidgetArrival(
                route      = a.optString("route", "?"),
                direction  = a.optString("direction", ""),
                eta        = a.optString("eta_local").ifBlank { a.optString("eta_text", "—") },
                etaSeconds = secs,
                color      = PollService.etaColor(secs),
            )
        }.sortedBy { it.etaSeconds ?: Int.MAX_VALUE }
    }

    private fun postForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Ближайшая остановка", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Где автобус?")
            .setContentText("Ищу ближайшую остановку")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else
            startForeground(NOTIF_ID, notif)
    }
}
