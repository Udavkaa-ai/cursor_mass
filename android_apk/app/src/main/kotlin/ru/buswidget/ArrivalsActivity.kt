package ru.buswidget

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var btnStart:   Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus:   TextView
    private lateinit var tvNextPoll: TextView

    private lateinit var stopId:   String
    private lateinit var stopName: String
    private lateinit var routes:   String

    private var running  = false
    private var timeLeft = 0
    private var nextPoll = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            timeLeft = maxOf(0, timeLeft - 1)
            nextPoll = maxOf(0, nextPoll - 1)
            updateTimerUI()
            if (timeLeft == 0) { stopSession(); return }
            if (nextPoll == 0) { nextPoll = POLL; fetchArrivals() }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_arrivals)

        stopId   = intent.getStringExtra(EXTRA_STOP_ID)   ?: ""
        stopName = intent.getStringExtra(EXTRA_STOP_NAME) ?: stopId
        routes   = intent.getStringExtra(EXTRA_ROUTES)    ?: ""

        tvStopName  = findViewById(R.id.tvStopName)
        btnStart    = findViewById(R.id.btnStart)
        progressBar = findViewById(R.id.progressBar)
        tvStatus    = findViewById(R.id.tvStatus)
        tvNextPoll  = findViewById(R.id.tvNextPoll)

        tvStopName.text = stopName

        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnStart.setOnClickListener { if (running) stopSession() else startSession() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startSession() {
        running  = true
        timeLeft = SESSION
        nextPoll = 0
        @Suppress("DEPRECATION")
        btnStart.background = getDrawable(R.drawable.bg_btn_stop)
        tvStatus.text = "загрузка..."
        handler.post(tickRunnable)
    }

    private fun stopSession() {
        running = false
        handler.removeCallbacks(tickRunnable)
        @Suppress("DEPRECATION")
        btnStart.background = getDrawable(R.drawable.bg_btn)
        btnStart.text = "ЗАПУСТИТЬ"
        progressBar.progress = 0
        tvNextPoll.text = ""
        tvStatus.text = if (timeLeft == 0) "сессия завершена" else "остановлено"
    }

    private fun updateTimerUI() {
        val m = timeLeft / 60; val s = timeLeft % 60
        btnStart.text = "СТОП   $m:${s.toString().padStart(2, '0')}"
        progressBar.progress = timeLeft
        tvNextPoll.text = "→ ${nextPoll}с"
    }

    private fun fetchArrivals() {
        val base = Config.SERVER_URL.trimEnd('/')
        if (base.isBlank() || stopId.isBlank()) {
            tvStatus.text = "SERVER_URL не задан в Config.kt"; return
        }
        Thread {
            try {
                val qs = if (routes.isNotBlank()) "?routes=${URLEncoder.encode(routes, "UTF-8")}" else ""
                val conn = URL("$base/arrivals/${URLEncoder.encode(stopId, "UTF-8")}$qs")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json     = JSONObject(body)
                val name     = json.optString("name")
                val arrivals = parseArrivals(json.optJSONArray("arrivals"))
                handler.post {
                    if (name.isNotBlank()) {
                        tvStopName.text = name
                        StopStorage.load(this).find { it.id == stopId }?.let { stop ->
                            StopStorage.update(this, stop.copy(name = name))
                        }
                    }
                    adapter.submit(arrivals)
                    val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    tvStatus.text = "обновлено $t"
                }
            } catch (e: Exception) {
                handler.post { tvStatus.text = "ошибка: ${e.message?.take(60)}" }
            }
        }.start()
    }

    private fun parseArrivals(arr: JSONArray?): List<Arrival> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            Arrival(
                route      = a.optString("route", "?"),
                direction  = a.optString("direction", ""),
                etaLocal   = a.optString("eta_local").ifBlank { a.optString("eta_text", "—") },
                etaSeconds = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds"),
            )
        }.sortedBy { it.etaSeconds ?: Int.MAX_VALUE }
    }
}
