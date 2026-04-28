package ru.buswidget

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SESSION = 300
        private const val POLL    = 30
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = ArrivalAdapter()

    private lateinit var tvStopName: TextView
    private lateinit var btnStart:   Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus:   TextView
    private lateinit var tvNextPoll: TextView

    private var running   = false
    private var timeLeft  = 0
    private var nextPoll  = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            timeLeft  = maxOf(0, timeLeft - 1)
            nextPoll  = maxOf(0, nextPoll - 1)
            updateTimerUI()
            if (timeLeft == 0) { stopSession(); return }
            if (nextPoll == 0) { nextPoll = POLL; fetchArrivals() }
            handler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvStopName  = findViewById(R.id.tvStopName)
        btnStart    = findViewById(R.id.btnStart)
        progressBar = findViewById(R.id.progressBar)
        tvStatus    = findViewById(R.id.tvStatus)
        tvNextPoll  = findViewById(R.id.tvNextPoll)

        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnStart.setOnClickListener { if (running) stopSession() else startSession() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        applyStopName()
        if (!hasSavedConfig()) showSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Сессия ───────────────────────────────────────────────────────────────

    private fun startSession() {
        running  = true
        timeLeft = SESSION
        nextPoll = 0  // первый запрос сразу
        btnStart.background = getDrawable(R.drawable.bg_btn_stop)
        tvStatus.text = "загрузка..."
        handler.post(tickRunnable)
    }

    private fun stopSession() {
        running = false
        handler.removeCallbacks(tickRunnable)
        btnStart.background = getDrawable(R.drawable.bg_btn)
        btnStart.text = "ЗАПУСТИТЬ"
        progressBar.progress = 0
        tvNextPoll.text = ""
        if (timeLeft == 0) tvStatus.text = "сессия завершена"
        else tvStatus.text = "остановлено"
    }

    private fun updateTimerUI() {
        val m = timeLeft / 60; val s = timeLeft % 60
        btnStart.text = "СТОП   $m:${s.toString().padStart(2, '0')}"
        progressBar.progress = timeLeft
        tvNextPoll.text = "→ ${nextPoll}с"
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun fetchArrivals() {
        val p      = prefs()
        val base   = p.getString("url",    "").orEmpty().trimEnd('/')
        val stopId = p.getString("stop",   "").orEmpty().trim()
        val routes = p.getString("routes", "").orEmpty().trim()
        val apiKey = p.getString("key",    "").orEmpty().trim()
        if (base.isBlank() || stopId.isBlank()) {
            tvStatus.text = "укажи сервер и остановку"; return
        }

        Thread {
            try {
                val qs = buildString {
                    if (routes.isNotBlank()) append("?routes=${URLEncoder.encode(routes, "UTF-8")}")
                }
                val conn = URL("$base/arrivals/${URLEncoder.encode(stopId, "UTF-8")}$qs")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey)
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val name = json.optString("name")
                val arrivals = parseArrivals(json.optJSONArray("arrivals"))
                handler.post {
                    if (name.isNotBlank()) {
                        tvStopName.text = name
                        prefs().edit().putString("stop_name", name).apply()
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
        val result = mutableListOf<Arrival>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            result.add(Arrival(
                route     = a.optString("route", "?"),
                direction = a.optString("direction", ""),
                etaLocal  = a.optString("eta_local").ifBlank { a.optString("eta_text", "—") },
                etaSeconds = if (a.isNull("eta_seconds")) null else a.getInt("eta_seconds"),
            ))
        }
        // сортируем по eta_seconds, нулевые в конец
        return result.sortedBy { it.etaSeconds ?: Int.MAX_VALUE }
    }

    // ── Настройки ────────────────────────────────────────────────────────────

    private fun hasSavedConfig(): Boolean {
        val p = prefs()
        return !p.getString("url",   "").isNullOrBlank() &&
               !p.getString("stop",  "").isNullOrBlank()
    }

    private fun applyStopName() {
        val saved = prefs().getString("stop_name", "").orEmpty()
        if (saved.isNotBlank()) tvStopName.text = saved
        else {
            val stop = prefs().getString("stop", "").orEmpty()
            if (stop.isNotBlank()) tvStopName.text = stop
        }
    }

    private fun showSettings() {
        val p   = prefs()
        val dp  = resources.displayMetrics.density
        fun px(d: Int) = (d * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(8))
            setBackgroundColor(0xFF0F0F10.toInt())
        }

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(px(4), px(14), 0, px(3))
        }
        fun field(hint: String, value: String, pwd: Boolean = false) =
            EditText(this).apply {
                this.hint = hint; setText(value); textSize = 16f
                setTextColor(0xFFF4F4F6.toInt())
                setHintTextColor(0xFF555570.toInt())
                setBackgroundColor(0xFF1A1A1D.toInt())
                setPadding(px(12), px(10), px(12), px(10))
                if (pwd) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

        val fUrl    = field("https://…containers.yandexcloud.net", p.getString("url",    "").orEmpty())
        val fStop   = field("5854295457",                           p.getString("stop",   "").orEmpty())
        val fRoutes = field("925,907  (пусто = все)",               p.getString("routes", "").orEmpty())
        val fKey    = field("не обязательно",                       p.getString("key",    "").orEmpty(), pwd = true)

        root.addView(label("URL сервера"));   root.addView(fUrl)
        root.addView(label("ID остановки")); root.addView(fStop)
        root.addView(label("Маршруты"));     root.addView(fRoutes)
        root.addView(label("API-ключ"));     root.addView(fKey)

        AlertDialog.Builder(this, R.style.SettingsDialog)
            .setTitle("Настройки")
            .setView(root)
            .setCancelable(hasSavedConfig())
            .setPositiveButton("Сохранить") { _, _ ->
                p.edit()
                    .putString("url",    fUrl.text.toString().trim())
                    .putString("stop",   fStop.text.toString().trim())
                    .putString("routes", fRoutes.text.toString().trim())
                    .putString("key",    fKey.text.toString().trim())
                    .putString("stop_name", "")
                    .apply()
                applyStopName()
            }
            .show()
    }

    private fun prefs() = getSharedPreferences("bw", Context.MODE_PRIVATE)
}
