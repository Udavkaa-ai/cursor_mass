package ru.buswidget

import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.data.Config
import ru.buswidget.data.StopStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import ru.buswidget.BuildConfig

class ArrivalsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STOP_ID   = "stop_id"
        const val EXTRA_STOP_NAME = "stop_name"
        const val EXTRA_ROUTES    = "routes"
        private const val SESSION = Config.SESSION_SEC
        private const val POLL    = Config.POLL_SEC
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = ArrivalAdapter()

    private lateinit var tvStopName: TextView
    private lateinit var btnStart:   TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus:   TextView
    private lateinit var tvNextPoll: TextView
    private lateinit var mapView: WebView

    private lateinit var stopId:   String
    private lateinit var stopName: String
    private lateinit var routes:   String

    private var running        = false
    private var fetching       = false
    private var timeLeft       = 0
    private var nextPoll       = 0
    private var lastFetchAt    = 0L
    private var lastFetched:   List<Arrival> = emptyList()
    private var lastNotifiedRoute = ""
    private var lastMapUpdateTime = 0L
    private var stopLat = 0.0
    private var stopLon = 0.0
    private var mapInitialized = false
    // TODO: MapKit distance circle disabled
    // private var distanceCircle: com.yandex.mapkit.map.CircleMapObject? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            timeLeft = maxOf(0, timeLeft - 1)
            nextPoll = maxOf(0, nextPoll - 1)
            updateTimerUI()
            if (timeLeft == 0) { stopSession(); return }
            if (nextPoll == 0) { nextPoll = POLL; fetchArrivals() }
            // live countdown: re-compute ETAs from stored etaSeconds every tick
            if (lastFetchAt > 0) refreshLiveEtas()
            handler.postDelayed(this, 1000)
        }
    }

    private fun refreshLiveEtas() {
        val elapsed = ((System.currentTimeMillis() - lastFetchAt) / 1000).toInt()
        val live = lastFetched.mapNotNull { a ->
            val liveSecs = a.etaSeconds?.minus(elapsed)
            if (liveSecs != null && liveSecs < -30) return@mapNotNull null
            a.copy(etaLocal = liveSecs?.let { formatEta(it) } ?: a.etaLocal,
                   etaSeconds = liveSecs)
        }
        adapter.submit(live)
        val firstEta = live.firstOrNull()
        val now = System.currentTimeMillis()
        if (now - lastMapUpdateTime > 100) {
            updateMapDistance(firstEta?.etaSeconds ?: 0, firstEta?.etaLocal ?: "—")
            lastMapUpdateTime = now
        }
        checkAndNotifyIfBusNear(firstEta)
    }

    private fun checkAndNotifyIfBusNear(arrival: Arrival?) {
        if (arrival == null) return
        val eta = arrival.etaSeconds ?: return
        val routeKey = arrival.route
        if (eta in 1..60 && lastNotifiedRoute != routeKey) {
            lastNotifiedRoute = routeKey
            notifyBusNear(arrival)
        }
    }

    private fun notifyBusNear(arrival: Arrival) {
        Toast.makeText(this, "🚌 ${arrival.route} подъезжает! (${arrival.etaLocal})", Toast.LENGTH_LONG).show()
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 200, 100, 200)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
        } catch (_: Exception) {}
    }

    private fun formatEta(secs: Int): String = when {
        secs <= 0   -> "подъезжает"
        secs < 60   -> "< 1 мин"
        secs < 3600 -> "через ${secs / 60} мин"
        else        -> "через ${secs / 3600}ч"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_arrivals)

        stopId   = intent.getStringExtra(EXTRA_STOP_ID)   ?: ""
        stopName = intent.getStringExtra(EXTRA_STOP_NAME) ?: stopId
        routes   = intent.getStringExtra(EXTRA_ROUTES)    ?: ""

        tvStopName  = findViewById(R.id.tvStopName)
        btnStart    = findViewById<TextView>(R.id.btnStart)
        progressBar = findViewById(R.id.progressBar)
        tvStatus    = findViewById(R.id.tvStatus)
        tvNextPoll  = findViewById(R.id.tvNextPoll)

        tvStopName.text = stopName
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        mapView = findViewById(R.id.mapView)
        mapView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // file:// base loading https Yandex resources counts as mixed content
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        mapView.webViewClient = WebViewClient()

        val apiKey = BuildConfig.JS_YANDEX_API
        val mapHtml = loadAssetWithApiKey(apiKey)
        // Base URL must point at the assets dir so the relative <script src="map.js">
        // inside map.html resolves. Plain loadData() has no base URL → map.js never
        // loads → blank white map.
        mapView.loadDataWithBaseURL(
            "file:///android_asset/", mapHtml, "text/html", "utf-8", null
        )

        loadStopCoordinates()

        btnStart.setOnClickListener { if (running) stopSession() else startSession() }

        startSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startSession() {
        running  = true
        timeLeft = SESSION
        nextPoll = 0
        lastNotifiedRoute = ""
        @Suppress("DEPRECATION")
        btnStart.background = getDrawable(R.drawable.bg_btn_pill_stop)
        btnStart.setTextColor(0xFFE53040.toInt())
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "загрузка..."
        handler.post(tickRunnable)
    }

    private fun stopSession() {
        running = false
        handler.removeCallbacks(tickRunnable)
        @Suppress("DEPRECATION")
        btnStart.background = getDrawable(R.drawable.bg_btn_pill_run)
        btnStart.setTextColor(0xFF2ED87A.toInt())
        btnStart.text = "▶  ЗАПУСТИТЬ"
        progressBar.progress = 0
        progressBar.visibility = View.INVISIBLE
        tvNextPoll.text = ""
        tvStatus.text = if (timeLeft == 0) "сессия завершена" else "остановлено"
    }

    private fun updateTimerUI() {
        val m = timeLeft / 60; val s = timeLeft % 60
        btnStart.text = "⏹  СТОП  $m:${s.toString().padStart(2, '0')}"
        progressBar.progress = timeLeft
        progressBar.visibility = View.VISIBLE
        tvNextPoll.text = "обн ${nextPoll}с"
    }

    private fun fetchArrivals() {
        if (fetching) return  // skip if previous request still in flight
        val base = Config.SERVER_URL.trimEnd('/')
        if (base.isBlank() || stopId.isBlank()) {
            tvStatus.text = "SERVER_URL не задан в Config.kt"; return
        }
        fetching = true
        Thread {
            try {
                val qs = if (routes.isNotBlank()) "?routes=${URLEncoder.encode(routes, "UTF-8")}" else ""
                val conn = URL("$base/arrivals/${URLEncoder.encode(stopId, "UTF-8")}$qs")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json     = JSONObject(body)
                val name     = json.optString("name")
                val arrivals = parseArrivals(json.optJSONArray("arrivals"))
                val fetchedAt = System.currentTimeMillis()
                handler.post {
                    fetching    = false
                    lastFetchAt  = fetchedAt
                    lastFetched  = arrivals
                    lastMapUpdateTime = System.currentTimeMillis()
                    if (name.isNotBlank()) {
                        tvStopName.text = name
                        StopStorage.load(this).find { it.id == stopId }?.let { stop ->
                            StopStorage.update(this, stop.copy(name = name))
                        }
                    }
                    adapter.submit(arrivals)
                    val firstEta = arrivals.firstOrNull()
                    if (firstEta != null) {
                        updateMapDistance(firstEta.etaSeconds ?: 0, firstEta.etaLocal)
                    }
                    val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    tvStatus.text = "обновлено $t"
                }
            } catch (e: Exception) {
                handler.post { fetching = false; tvStatus.text = "ошибка: ${e.message?.take(60)}" }
            }
        }.start()
    }

    private fun loadStopCoordinates() {
        val stop = StopStorage.load(this).find { it.id == stopId } ?: return
        if (stop.lat == 0.0 || stop.lon == 0.0) return
        stopLat = stop.lat
        stopLon = stop.lon
        handler.postDelayed({
            if (!mapInitialized) {
                mapInitialized = true
                val escapedName = stopName.replace("'", "\\'")
                mapView.evaluateJavascript("initMap($stopLat, $stopLon, '$escapedName')") { }
            }
        }, 500)
    }

    private fun updateMapDistance(etaSeconds: Int, etaText: String) {
        if (!mapInitialized) return
        val escapedText = etaText.replace("'", "\\'")
        mapView.evaluateJavascript("updateDistance($etaSeconds, '$escapedText')") { }
    }

    private fun loadAssetWithApiKey(apiKey: String): String {
        val html = assets.open("map.html").bufferedReader().readText()
        return html.replace("YOUR_YANDEX_API_KEY", apiKey)
    }

    private fun parseArrivals(arr: JSONArray?): List<Arrival> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            // optString returns the literal "null" when the value is JSON null
            // (not missing), so guard every string field with isNull().
            fun str(key: String) = if (a.isNull(key)) "" else a.optString(key, "")
            Arrival(
                route      = str("route").ifBlank { "?" },
                direction  = str("direction"),
                etaLocal   = str("eta_local").ifBlank { str("eta_text") }.ifBlank { "—" },
                etaSeconds = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds"),
                type       = str("type"),
            )
        }.sortedBy { it.etaSeconds ?: Int.MAX_VALUE }
    }
}
