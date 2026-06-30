package ru.buswidget.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import ru.buswidget.BuildConfig
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
        var lat: Double = 0.0,
        var lon: Double = 0.0,
        var resolved: Boolean = false,
        var timeLeft: Int = Config.SESSION_SEC,
        var nextPoll: Int = 0,
    )

    private data class Snapshot(val fetchedAt: Long, val arrivals: List<WidgetArrival>)

    private val handler    = Handler(Looper.getMainLooper())
    private val sessions   = mutableMapOf<Int, Session>()
    private val snapshots  = mutableMapOf<Int, Snapshot>()
    private val fetching   = mutableSetOf<Int>()
    private val mapBitmaps = mutableMapOf<Int, Bitmap>()   // static map per map-widget
    private val mapLoading = mutableSetOf<Int>()
    private val mapNeedsFull = mutableSetOf<Int>()         // map widgets needing a full (vs tick) update
    private val mapStatus = mutableMapOf<Int, String>()    // placeholder text when no bitmap

    /** A widget belongs to the 4×2 map experiment (vs the plain auto widget). */
    private fun isMapWidget(widgetId: Int): Boolean =
        AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className
            ?.endsWith("MapWidgetProvider") == true

    private fun showLocatingFor(widgetId: Int) {
        val awm = AppWidgetManager.getInstance(this)
        if (isMapWidget(widgetId)) MapWidgetProvider.showLocating(this, awm, widgetId)
        else BusWidgetProviderAuto.showLocating(this, awm, widgetId)
    }

    private fun showIdleFor(widgetId: Int) {
        val awm = AppWidgetManager.getInstance(this)
        if (isMapWidget(widgetId)) MapWidgetProvider.showIdle(this, awm, widgetId)
        else BusWidgetProviderAuto.showIdle(this, awm, widgetId)
    }

    private fun showMessageFor(widgetId: Int, title: String, sub: String) {
        val awm = AppWidgetManager.getInstance(this)
        if (isMapWidget(widgetId)) MapWidgetProvider.showMessage(this, awm, widgetId, title, sub)
        else BusWidgetProviderAuto.showMessage(this, awm, widgetId, title, sub)
    }

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
        showLocatingFor(widgetId)
        if (sessions.size == 1) handler.post(tick)
        resolveNearestStop(widgetId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resolveNearestStop(widgetId: Int) {
        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            showMessageFor(widgetId, "Геолокация", "нет разрешения")
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
        s.lat = nearby.stop.lat
        s.lon = nearby.stop.lon
        s.distanceText = if (nearby.distanceMeters < 1000) "${nearby.distanceMeters}м"
                         else "%.1fкм".format(nearby.distanceMeters / 1000.0)
        s.resolved = true
        s.nextPoll = 0  // fetch immediately on next tick
        if (isMapWidget(widgetId)) {
            mapNeedsFull += widgetId   // first active render must be a full update
            if (s.lat != 0.0 && s.lon != 0.0) fetchStaticMapAsync(widgetId, s.lat, s.lon, null)
        }
        pushUpdate(widgetId, s)
    }

    private fun fail(widgetId: Int, msg: String) {
        showMessageFor(widgetId, "Остановка", msg)
        endSession(widgetId)
    }

    private fun endSession(widgetId: Int) {
        sessions.remove(widgetId)
        snapshots.remove(widgetId)
        mapBitmaps.remove(widgetId)
        mapNeedsFull.remove(widgetId)
        mapLoading.remove(widgetId)
        mapStatus.remove(widgetId)
        showIdleFor(widgetId)
        if (sessions.isEmpty()) { handler.removeCallbacks(tick); stopSelf() }
    }

    private fun pushUpdate(widgetId: Int, s: Session) {
        val awm = AppWidgetManager.getInstance(this)
        val live = liveArrivals(widgetId)
        if (isMapWidget(widgetId)) {
            // Full update (with the heavy map bitmap) only when the map or the row
            // data changed; otherwise a light per-second tick (timer + ETAs).
            if (mapNeedsFull.remove(widgetId)) {
                MapWidgetProvider.updateActive(
                    this, awm, widgetId, s.stopId, s.stopName, s.routes,
                    s.timeLeft, live, mapBitmaps[widgetId],
                    mapStatus[widgetId] ?: "загрузка…",
                )
            } else {
                MapWidgetProvider.updateTick(this, awm, widgetId, s.timeLeft, live)
            }
        } else {
            BusWidgetProviderAuto.updateActive(
                this, awm, widgetId, s.stopId, s.stopName, s.routes,
                s.distanceText, s.timeLeft, live,
            )
        }
    }

    /** Fetch a static map PNG of the stop once per session and re-render. */
    /**
     * Fetch a static map of the stop with a distance circle (a polygon, since the
     * static API has no native circle) sized by the nearest bus's ETA. Refreshed
     * each poll, so the circle steps down every ~15s as the bus approaches.
     */
    private fun fetchStaticMapAsync(widgetId: Int, lat: Double, lon: Double, etaSeconds: Int?) {
        if (widgetId in mapLoading) return
        mapLoading += widgetId
        val url = buildStaticMapUrl(lat, lon, etaSeconds)
        Thread {
            var code = -1
            val bmp = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                code = conn.responseCode
                val b = if (code in 200..299) BitmapFactory.decodeStream(conn.inputStream) else null
                conn.disconnect()
                b
            } catch (_: Exception) { null }
            handler.post {
                mapLoading -= widgetId
                if (bmp != null) {
                    mapBitmaps[widgetId] = bmp
                    mapStatus.remove(widgetId)
                } else if (!mapBitmaps.containsKey(widgetId)) {
                    // Surface the HTTP code so we can tell auth (403) from a bad
                    // request (400) etc. while this is experimental.
                    mapStatus[widgetId] = if (code > 0) "нет карты ($code)" else "нет сети"
                }
                mapNeedsFull += widgetId
                sessions[widgetId]?.let { pushUpdate(widgetId, it) }
            }
        }.start()
    }

    private fun buildStaticMapUrl(lat: Double, lon: Double, etaSeconds: Int?): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%.5f", v)
        val key = BuildConfig.JS_YANDEX_API
        val sb = StringBuilder("https://static-maps.yandex.ru/v1?size=200,200&lang=ru_RU&apikey=$key")
        sb.append("&pt=${f(lon)},${f(lat)},pm2rdm")

        val within = etaSeconds != null && etaSeconds <= 180
        val radius = if (within) maxOf(etaSeconds!! * 8.3, 90.0) else 500.0
        val rLat = radius / 111320.0
        val rLon = radius / (111320.0 * Math.cos(Math.toRadians(lat)))

        // Fit the view to the circle (or a default area when the bus is far).
        val fit = if (within) 1.25 else 1.0
        sb.append("&bbox=${f(lon - rLon * fit)},${f(lat - rLat * fit)}" +
                  "~${f(lon + rLon * fit)},${f(lat + rLat * fit)}")

        if (within) {
            val (stroke, fill) = when {
                etaSeconds!! <= 60  -> "E53040FF" to "E5304055"
                etaSeconds <= 120   -> "FF8C00FF" to "FF8C0055"
                else                -> "2ED87AFF" to "2ED87A4D"
            }
            val pts = StringBuilder()
            val n = 24
            for (i in 0..n) {
                val ang = 2 * Math.PI * i / n
                pts.append("${f(lon + rLon * Math.cos(ang))},${f(lat + rLat * Math.sin(ang))},")
            }
            pts.deleteCharAt(pts.length - 1)  // trailing comma
            sb.append("&pl=c:$stroke,f:$fill,w:2,$pts")
        }
        return sb.toString()
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
                        mapNeedsFull += widgetId   // row data changed → full update
                    }
                    // Refresh the map circle each poll with the nearest bus's ETA.
                    val s2 = sessions[widgetId]
                    if (s2 != null && isMapWidget(widgetId) && s2.lat != 0.0 && s2.lon != 0.0) {
                        val nearEta = liveArrivals(widgetId).firstOrNull()?.etaSeconds
                        fetchStaticMapAsync(widgetId, s2.lat, s2.lon, nearEta)
                    }
                    s2?.let { pushUpdate(widgetId, it) }
                }
            } catch (_: Exception) { handler.post { fetching -= widgetId } }
        }.start()
    }

    private fun parseArrivals(arr: JSONArray?): List<WidgetArrival> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val a    = arr.getJSONObject(i)
            fun str(key: String) = if (a.isNull(key)) "" else a.optString(key, "")
            val secs = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds")
            WidgetArrival(
                route      = str("route").ifBlank { "?" },
                direction  = str("direction"),
                eta        = str("eta_local").ifBlank { str("eta_text") }.ifBlank { "—" },
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
