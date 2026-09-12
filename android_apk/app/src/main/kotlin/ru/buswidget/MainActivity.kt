package ru.buswidget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage

class MainActivity : AppCompatActivity() {

    private val adapter = StopAdapter(::openArrivals, ::editStop, ::deleteStop)
    private lateinit var rvStops: RecyclerView
    private lateinit var vEmpty:  View
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeBackup(it) } }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readAndImport(it) } }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) findNearby() else toast("Нужно разрешение на геолокацию") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvStops = findViewById(R.id.recyclerView)
        vEmpty  = findViewById(R.id.tvEmpty)
        rvStops.layoutManager = LinearLayoutManager(this)
        rvStops.adapter = adapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val openAdd = View.OnClickListener { startActivity(Intent(this, AddStopActivity::class.java)) }
        findViewById<View>(R.id.btnAdd).setOnClickListener(openAdd)
        findViewById<View>(R.id.btnAddEmpty).setOnClickListener(openAdd)
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<TextView>(R.id.btnNearby).setOnClickListener { requestLocationAndFind() }

        // Android 13+: notifications (arrival alerts + the live departure board)
        // need a runtime grant.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        val stops = StopStorage.load(this)
        adapter.submit(stops)
        rvStops.visibility = if (stops.isEmpty()) View.GONE  else View.VISIBLE
        vEmpty.visibility  = if (stops.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMenu() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.MenuSheet)
        val v = layoutInflater.inflate(R.layout.sheet_menu, null)
        sheet.setContentView(v)
        fun item(id: Int, action: () -> Unit) =
            v.findViewById<View>(id).setOnClickListener { sheet.dismiss(); action() }
        item(R.id.miBackup)  { createDocumentLauncher.launch("buswidget_backup.json") }
        item(R.id.miRestore) { openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        item(R.id.miAlerts)  { showAlertSettings() }
        item(R.id.miTheme)   { showThemeDialog() }
        item(R.id.miAbout)   { startActivity(Intent(this, AboutActivity::class.java)) }
        sheet.show()
        // The framework wraps the content view in its own frame — clear its
        // background so bg_sheet's rounded top corners are what the user sees.
        (v.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun showThemeDialog() {
        val options = arrayOf("Как в системе", "Светлая", "Тёмная")
        val current = App.themeMode(this).coerceIn(0, 2)
        AlertDialog.Builder(this, R.style.SettingsDialog)
            .setTitle("Тема оформления")
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                App.setThemeMode(this, which)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showAlertSettings() {
        val options = arrayOf("Выключено", "За 1 минуту", "За 2 минуты", "За 3 минуты")
        val current = ru.buswidget.widget.ArrivalAlerts.thresholdMin(this).coerceIn(0, 3)
        AlertDialog.Builder(this, R.style.SettingsDialog)
            .setTitle("Напоминать о прибытии автобуса")
            .setSingleChoiceItems(options, current) { dialog, which ->
                ru.buswidget.widget.ArrivalAlerts.setThresholdMin(this, which)
                toast(if (which == 0) "Напоминания выключены" else "Напомню ${options[which].lowercase()}")
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun writeBackup(uri: Uri) {
        val stops = StopStorage.load(this)
        val arr = JSONArray()
        stops.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("routes", s.routes)
                put("lat", s.lat); put("lon", s.lon)
            })
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(arr.toString(2).toByteArray()) }
            Toast.makeText(this, "Бэкап сохранён (${stops.size} ост.)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readAndImport(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val imported = try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Stop(
                    id     = id,
                    name   = o.optString("name", id),
                    routes = o.optString("routes", ""),
                    lat    = o.optDouble("lat", 0.0),
                    lon    = o.optDouble("lon", 0.0),
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Неверный формат файла", Toast.LENGTH_LONG).show()
            return
        }
        if (imported.isEmpty()) {
            Toast.makeText(this, "В файле нет остановок", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.SettingsDialog)
            .setTitle("Восстановить ${imported.size} ост.?")
            .setMessage("Заменить текущий список или добавить к нему?")
            .setPositiveButton("Заменить") { _, _ ->
                StopStorage.save(this, imported)
                onResume()
                Toast.makeText(this, "Восстановлено ${imported.size} ост.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Добавить") { _, _ ->
                val existingIds = StopStorage.load(this).map { it.id }.toSet()
                val toAdd = imported.filter { it.id !in existingIds }
                StopStorage.save(this, StopStorage.load(this) + toAdd)
                onResume()
                Toast.makeText(this, "Добавлено ${toAdd.size} ост.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openArrivals(stop: Stop) {
        startActivity(Intent(this, ArrivalsActivity::class.java).apply {
            putExtra(ArrivalsActivity.EXTRA_STOP_ID,   stop.id)
            putExtra(ArrivalsActivity.EXTRA_STOP_NAME, stop.name)
            putExtra(ArrivalsActivity.EXTRA_ROUTES,    stop.routes)
        })
    }

    private fun editStop(stop: Stop) {
        startActivity(Intent(this, AddStopActivity::class.java).apply {
            putExtra(AddStopActivity.EXTRA_EDIT_STOP_ID, stop.id)
        })
    }

    private fun deleteStop(stop: Stop) {
        StopStorage.remove(this, stop.id)
        adapter.submit(StopStorage.load(this))
    }

    private fun requestLocationAndFind() {
        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) findNearby() else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun findNearby() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    showNearbySheet(location.latitude, location.longitude)
                    return@addOnSuccessListener
                }
                // No cached fix — request a fresh one before giving up
                fusedLocationClient.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    null,
                ).addOnSuccessListener { cur: Location? ->
                    if (cur != null) showNearbySheet(cur.latitude, cur.longitude)
                    else toast("Не удалось определить геолокацию")
                }.addOnFailureListener {
                    toast("Не удалось определить геолокацию")
                }
            }
        } catch (e: SecurityException) {
            toast("Ошибка доступа: ${e.message}")
        }
    }

    /** A nearby stop parsed from the Yandex Maps search results (server /nearby). */
    private data class NearbyMapStop(
        val id: String, val name: String,
        val lat: Double, val lon: Double, val distanceM: Int,
    )

    /**
     * Bottom sheet with REAL stops around the current position (scraped from
     * the map by the server) — not just saved ones. Untracked stops can be
     * added right from here; tracked ones open their arrivals board.
     */
    private fun showNearbySheet(lat: Double, lon: Double) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.MenuSheet)
        val v = layoutInflater.inflate(R.layout.sheet_nearby, null)
        sheet.setContentView(v)
        val list   = v.findViewById<android.widget.LinearLayout>(R.id.nearbyList)
        val status = v.findViewById<TextView>(R.id.tvNearbyStatus)
        v.findViewById<View>(R.id.miMapSearch).setOnClickListener { sheet.dismiss(); openMapNearMe() }
        sheet.show()
        (v.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        Thread {
            // Первый вызов после простоя = холодный старт контейнера + скрейп
            // карты, может быть долгим; при неудаче пробуем ещё раз.
            var stops = fetchNearbyStops(lat, lon)
            if (stops == null) {
                Thread.sleep(1500)
                stops = fetchNearbyStops(lat, lon)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || !sheet.isShowing) return@runOnUiThread
                when {
                    stops == null   -> status.text =
                        "не получилось узнать остановки рядом — попробуйте карту" +
                        (nearbyDebug?.let { "\n\n⚠ $it" } ?: "")
                    stops.isEmpty() -> status.text = "рядом остановок не нашлось — попробуйте карту"
                    else -> {
                        status.visibility = View.GONE
                        val savedIds = StopStorage.load(this)
                            .map { it.id.removePrefix("stop__") }.toSet()
                        stops.forEach { ns ->
                            list.addView(makeNearbyRow(ns, ns.id in savedIds, sheet, list))
                        }
                    }
                }
            }
        }.start()
    }

    /** Диагностика последней неудачи /nearby — показывается в шите. */
    @Volatile private var nearbyDebug: String? = null

    private fun fetchNearbyStops(lat: Double, lon: Double): List<NearbyMapStop>? {
        return try {
            val base = ru.buswidget.data.Config.SERVER_URL.trimEnd('/')
            val url = java.net.URL(
                "$base/nearby?lat=%.6f&lon=%.6f&radius=1500&limit=8"
                    .format(java.util.Locale.US, lat, lon)
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            // Холодный старт serverless-контейнера + скрейп Яндекса дольше
            // 10 с — обычный таймаут /arrivals здесь мал.
            conn.readTimeout = 25_000
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(160) ?: ""
                conn.disconnect()
                nearbyDebug = "HTTP $code $err"
                null
            } else {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                nearbyDebug = null
                val arr = json.optJSONArray("stops") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val id = o.optString("stop_id").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    NearbyMapStop(
                        id        = id.removePrefix("stop__"),
                        name      = o.optString("name", id),
                        lat       = o.optDouble("lat", 0.0),
                        lon       = o.optDouble("lon", 0.0),
                        distanceM = o.optInt("distance_m", 0),
                    )
                }
            }
        } catch (e: Exception) {
            nearbyDebug = e.toString().take(200)
            null
        }
    }

    private fun makeNearbyRow(
        ns: NearbyMapStop, tracked: Boolean,
        sheet: com.google.android.material.bottomsheet.BottomSheetDialog,
        parent: android.view.ViewGroup,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_nearby, parent, false)
        row.findViewById<TextView>(R.id.tvNearbyName).text = ns.name
        row.findViewById<TextView>(R.id.tvNearbyDist).text = formatDistance(ns.distanceM)
        row.findViewById<TextView>(R.id.tvNearbyAction).text =
            if (tracked) "открыть" else "+ добавить"
        row.setOnClickListener {
            sheet.dismiss()
            if (tracked) {
                StopStorage.load(this)
                    .find { it.id.removePrefix("stop__") == ns.id }
                    ?.let { openArrivals(it) }
            } else {
                startActivity(Intent(this, AddStopActivity::class.java).apply {
                    putExtra(AddStopActivity.EXTRA_PREFILL_ID,   ns.id)
                    putExtra(AddStopActivity.EXTRA_PREFILL_NAME, ns.name)
                    putExtra(AddStopActivity.EXTRA_PREFILL_LAT,  ns.lat)
                    putExtra(AddStopActivity.EXTRA_PREFILL_LON,  ns.lon)
                })
            }
        }
        return row
    }

    /** Open AddStopActivity with the map picker auto-launched centered on GPS. */
    private fun openMapNearMe() {
        startActivity(Intent(this, AddStopActivity::class.java).apply {
            putExtra(AddStopActivity.EXTRA_OPEN_PICKER, true)
        })
    }

    private fun formatDistance(meters: Int): String = when {
        meters < 1000 -> "$meters м"
        else -> "%.1f км".format(meters / 1000.0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
