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
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
                showMessageStatic(ctx, widgetId, "Остановка", "откройте приложение")
            }
        }

        fun stopFor(ctx: Context, widgetId: Int) {
            val i = Intent(ctx, AutoPollService::class.java).apply {
                action = ACTION_STOP; putExtra(EXTRA_ID, widgetId)
            }
            // startForegroundService (not startService): tapping СТОП on a widget
            // whose service was killed while the phone was locked crashed with a
            // background-start IllegalStateException. The FGS start is always
            // allowed; the service resets the widget to idle and stops itself.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {
                resetToIdle(ctx, widgetId)
            }
        }

        private fun isMapWidgetStatic(ctx: Context, widgetId: Int): Boolean =
            AppWidgetManager.getInstance(ctx).getAppWidgetInfo(widgetId)?.provider?.className
                ?.endsWith("MapWidgetProvider") == true

        /** Reset a frozen widget to its idle state without needing the service. */
        fun resetToIdle(ctx: Context, widgetId: Int) {
            val awm = AppWidgetManager.getInstance(ctx)
            if (isMapWidgetStatic(ctx, widgetId)) MapWidgetProvider.showIdle(ctx, awm, widgetId)
            else BusWidgetProviderAuto.showIdle(ctx, awm, widgetId)
        }

        private fun showMessageStatic(ctx: Context, widgetId: Int, title: String, sub: String) {
            val awm = AppWidgetManager.getInstance(ctx)
            if (isMapWidgetStatic(ctx, widgetId)) MapWidgetProvider.showMessage(ctx, awm, widgetId, title, sub)
            else BusWidgetProviderAuto.showMessage(ctx, awm, widgetId, title, sub)
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
        var startedAt: Long = System.currentTimeMillis(),
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
    // 30-секундная «корзина» ETA последней загруженной карты: пока корзина не
    // сменилась, картинку не перезапрашиваем — бережём дневной лимит Static API
    private val mapLastKey = mutableMapOf<Int, Int>()
    private val alerted = mutableMapOf<Int, MutableSet<String>>()  // arrival-alerted routes per widget
    private var lastBoardText: String? = null

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
                // Wall-clock, not tick-count: Doze throttles the handler while the
                // phone is locked, and counted ticks stretched the 5-min session.
                s.timeLeft = (Config.SESSION_SEC - (System.currentTimeMillis() - s.startedAt) / 1000).toInt()
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
        ru.buswidget.data.Locator.request(this) { loc ->
            if (loc != null) onLocation(widgetId, loc)
            else fail(widgetId, "включите GPS")
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
        s.startedAt = System.currentTimeMillis()  // the 5-min window starts now
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
        mapLastKey.remove(widgetId)
        mapNeedsFull.remove(widgetId)
        mapLoading.remove(widgetId)
        mapStatus.remove(widgetId)
        alerted.remove(widgetId)
        showIdleFor(widgetId)
        lastBoardText = null
        if (sessions.isEmpty()) { handler.removeCallbacks(tick); stopSelf() }
    }

    private fun pushUpdate(widgetId: Int, s: Session) {
        val awm = AppWidgetManager.getInstance(this)
        val live = liveArrivals(widgetId)
        ArrivalAlerts.check(
            this, alerted.getOrPut(widgetId) { mutableSetOf() },
            s.stopId, s.stopName, s.routes, live,
        )
        // Live departure board in the shade (see PollService for the pattern).
        if (widgetId == sessions.keys.firstOrNull()) {
            val lines = BoardNotification.renderLines(live)
            if (lines != lastBoardText) {
                lastBoardText = lines
                getSystemService(NotificationManager::class.java)?.notify(
                    NOTIF_ID,
                    BoardNotification.build(this, CHANNEL_ID, s.stopId, s.stopName, s.routes, lines),
                )
            }
        }
        if (isMapWidget(widgetId)) {
            // Full update (with the heavy map bitmap) only when the map or the row
            // data changed; otherwise a light per-second tick (timer + ETAs).
            if (mapNeedsFull.remove(widgetId)) {
                MapWidgetProvider.updateActive(
                    this, awm, widgetId, s.stopId, s.stopName, s.routes,
                    s.distanceText, s.timeLeft, live, mapBitmaps[widgetId],
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

    /**
     * Compute the map slot's size (in request px, at 2× for sharpness) and the
     * matching corner radius from the widget's real dimensions, so the fetched
     * image has the slot's exact aspect ratio and uniform 12dp corners.
     * Layout constants: root h-padding 10+10, 8dp gap, half width for the map;
     * root v-padding 6+6, ~26dp header row.
     */
    private fun mapSlotSpec(widgetId: Int): Triple<Int, Int, Float> {
        val opts = AppWidgetManager.getInstance(this).getAppWidgetOptions(widgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
        val slotWdp = ((wDp - 28) / 2).coerceAtLeast(80)
        val slotHdp = (hDp - 38).coerceAtLeast(60)
        var w = slotWdp * 2; var h = slotHdp * 2
        // Static API caps: 650×450 — shrink uniformly to preserve the aspect.
        val f = minOf(650f / w, 450f / h, 1f)
        w = (w * f).toInt(); h = (h * f).toInt()
        return Triple(w, h, 12f * 2 * f)
    }

    /**
     * Fetch a static map of the stop with a distance circle (a polygon, since the
     * static API has no native circle) sized by the nearest bus's ETA. Refreshed
     * each poll, so the circle steps down every ~15s as the bus approaches.
     */
    /** Подложки карт неделю живут на диске: карта — константа, меняется только кружок. */
    private val mapCacheTtlMs = 7L * 24 * 3600 * 1000

    private fun fetchStaticMapAsync(widgetId: Int, lat: Double, lon: Double, etaSeconds: Int?) {
        if (widgetId in mapLoading) return
        mapLoading += widgetId
        val (mapW, mapH, radiusPx) = mapSlotSpec(widgetId)
        // Зум зависит только от 30-сек корзины ETA — так подложка кэшируется,
        // а кружок и его цвет рисуем локально поверх.
        val bucket = if (etaSeconds == null || etaSeconds > 180) -1
                     else etaSeconds.coerceAtLeast(0) / 30
        val url = buildStaticMapUrl(lat, lon, bucket, mapW, mapH)
        val stopKey = (sessions[widgetId]?.stopId ?: "x").replace(Regex("[^A-Za-z0-9_]"), "")
        val cacheFile = java.io.File(cacheDir, "basemap_${stopKey}_${mapW}x${mapH}_$bucket.png")
        Thread {
            var code = -1
            // 1) подложка: диск → сеть (с записью на диск)
            var base: Bitmap? = try {
                if (cacheFile.exists() &&
                    System.currentTimeMillis() - cacheFile.lastModified() < mapCacheTtlMs
                ) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            } catch (_: Exception) { null }
            if (base == null) {
                base = try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                    code = conn.responseCode
                    val raw = if (code in 200..299) BitmapFactory.decodeStream(conn.inputStream) else null
                    conn.disconnect()
                    raw?.also { b ->
                        try {
                            cacheFile.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) { null }
            }
            // 2) кружок расстояния поверх + скругление углов
            val bmp = base?.let { roundCorners(drawCircleOverlay(it, bucket, etaSeconds), radiusPx) }
            handler.post {
                mapLoading -= widgetId
                if (bmp != null) {
                    mapBitmaps[widgetId] = bmp
                    mapStatus.remove(widgetId)
                } else if (!mapBitmaps.containsKey(widgetId)) {
                    // Surface the HTTP code so we can tell auth (403) from a bad
                    // request (400) etc. while this is experimental.
                    mapStatus[widgetId] = when {
                        code == 403 -> "карта: исчерпан дневной лимит"
                        code > 0    -> "нет карты ($code)"
                        else        -> "нет сети"
                    }
                }
                mapNeedsFull += widgetId
                sessions[widgetId]?.let { pushUpdate(widgetId, it) }
            }
        }.start()
    }

    /** Return a copy of [src] with rounded corners (RemoteViews can't clip). */
    private fun roundCorners(src: Bitmap, radiusPx: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        return out
    }

    /**
     * ПОДЛОЖКА карты (без кружка): бесключевой 1.x-эндпоинт, как у мини-карт
     * списка остановок (ключевой v1 отдавал 403 по лимитам ключа). Зум задаёт
     * bucket (30-сек корзина ETA, -1 = автобус далеко): радиус берём по верху
     * корзины, чтобы URL был стабилен всю корзину и кэшировался.
     */
    private fun buildStaticMapUrl(lat: Double, lon: Double, bucket: Int, w: Int, h: Int): String {
        fun f(v: Double) = String.format(java.util.Locale.US, "%.5f", v)
        val sb = StringBuilder("https://static-maps.yandex.ru/1.x/?l=map&lang=ru_RU&size=$w,$h")
        sb.append("&pt=${f(lon)},${f(lat)},pm2rdm")

        val within = bucket >= 0
        val etaTop = (bucket + 1) * 30
        val radius = if (within) maxOf(etaTop * 8.3, 90.0) else 500.0
        val rLat = radius / 111320.0
        val rLon = radius / (111320.0 * Math.cos(Math.toRadians(lat)))

        // Fit the view to the circle (or a default area when the bus is far).
        val fit = if (within) 1.25 else 1.0
        sb.append("&bbox=${f(lon - rLon * fit)},${f(lat - rLat * fit)}" +
                  "~${f(lon + rLon * fit)},${f(lat + rLat * fit)}")
        return sb.toString()
    }

    /**
     * Кружок расстояния рисуем сами поверх подложки: bbox симметричен вокруг
     * остановки, так что центр = центр картинки, а радиус в пикселях — это
     * min(w,h)/2 без запаса fit=1.25. Цвет — по живому ETA, без сети.
     */
    private fun drawCircleOverlay(base: Bitmap, bucket: Int, etaSeconds: Int?): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        if (bucket < 0 || etaSeconds == null) return out
        val (strokeColor, fillColor) = when {
            etaSeconds <= 60  -> 0xFFE53040.toInt() to 0x55E53040
            etaSeconds <= 120 -> 0xFFFF8C00.toInt() to 0x55FF8C00
            else              -> 0xFF2ED87A.toInt() to 0x4D2ED87A
        }
        val canvas = Canvas(out)
        val cx = out.width / 2f
        val cy = out.height / 2f
        val r = minOf(out.width, out.height) / 2f / 1.25f
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor; style = Paint.Style.FILL
        })
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor; style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        })
        return out
    }

    private fun liveArrivals(widgetId: Int): List<WidgetArrival> {
        val snap = snapshots[widgetId] ?: return emptyList()
        val elapsed = ((System.currentTimeMillis() - snap.fetchedAt) / 1000).toInt()
        return snap.arrivals.mapNotNull { a ->
            val liveSecs = a.etaSeconds?.minus(elapsed)
            if (liveSecs != null && liveSecs < -30) return@mapNotNull null
            // Keep etaSeconds live as well — the 3×1 widget renders the number
            // from etaSeconds, which otherwise stays frozen between data changes.
            a.copy(
                eta        = liveSecs?.let { PollService.formatEta(it) } ?: a.eta,
                etaSeconds = liveSecs,
                color      = PollService.etaColor(liveSecs ?: a.etaSeconds),
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
                    // Refresh the map circle when the nearest ETA moves to the
                    // next 30s bucket (not every poll — Static API has a daily
                    // request quota and 403s once it's spent).
                    val s2 = sessions[widgetId]
                    if (s2 != null && isMapWidget(widgetId) && s2.lat != 0.0 && s2.lon != 0.0) {
                        val nearEta = liveArrivals(widgetId).firstOrNull()?.etaSeconds
                        val key = if (nearEta == null || nearEta > 180) -1
                                  else nearEta.coerceAtLeast(0) / 30
                        if (mapLastKey[widgetId] != key || !mapBitmaps.containsKey(widgetId)) {
                            mapLastKey[widgetId] = key
                            fetchStaticMapAsync(widgetId, s2.lat, s2.lon, nearEta)
                        }
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
            .setSmallIcon(ru.buswidget.R.drawable.ic_tile_bus)
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
