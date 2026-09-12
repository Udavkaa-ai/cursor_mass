package ru.buswidget

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONObject
import ru.buswidget.data.Config
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AddStopActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_STOP_ID = "edit_stop_id"
        const val EXTRA_OPEN_PICKER  = "open_picker"
        const val EXTRA_PREFILL_ID   = "prefill_id"
        const val EXTRA_PREFILL_NAME = "prefill_name"
        const val EXTRA_PREFILL_LAT  = "prefill_lat"
        const val EXTRA_PREFILL_LON  = "prefill_lon"

        private val CHIP_STATES = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
    }

    private lateinit var etId:          EditText
    private lateinit var etName:        EditText
    private lateinit var etRoutes:      EditText
    private lateinit var tvRoutesLabel: TextView
    private lateinit var tvRoutesStatus: TextView
    private lateinit var chipsRoutes:   ChipGroup
    private var editingId: String? = null
    private var pickedLat = 0.0
    private var pickedLon = 0.0
    private var syncingChips = false   // guards the EditText ↔ chips feedback loop

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val stopId   = data.getStringExtra(MapPickerActivity.RESULT_STOP_ID)   ?: return@registerForActivityResult
            val stopName = data.getStringExtra(MapPickerActivity.RESULT_STOP_NAME) ?: stopId
            etId.setText(stopId)
            etName.setText(stopName)
            pickedLat = data.getDoubleExtra(MapPickerActivity.RESULT_LAT, 0.0)
            pickedLon = data.getDoubleExtra(MapPickerActivity.RESULT_LON, 0.0)
            toast("Остановка выбрана: $stopName")
            fetchAvailableRoutes(stopId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_stop)

        etId           = findViewById(R.id.etStopId)
        etName         = findViewById(R.id.etStopName)
        etRoutes       = findViewById(R.id.etRoutes)
        tvRoutesLabel  = findViewById(R.id.tvRoutesLabel)
        tvRoutesStatus = findViewById(R.id.tvRoutesStatus)
        chipsRoutes    = findViewById(R.id.chipsRoutes)

        editingId = intent.getStringExtra(EXTRA_EDIT_STOP_ID)
        if (editingId != null) {
            findViewById<TextView>(R.id.tvTitle).text = "Редактировать"
            StopStorage.load(this).find { it.id == editingId }?.let { stop ->
                etId.setText(stop.id)
                etName.setText(stop.name)
                etRoutes.setText(stop.routes)
                pickedLat = stop.lat
                pickedLon = stop.lon
            }
            findViewById<Button>(R.id.btnPickMap).visibility = View.GONE
            fetchAvailableRoutes(editingId!!)
        }

        // Manual edits to the text field re-check the matching chips
        etRoutes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!syncingChips) applySelectionToChips()
            }
        })

        findViewById<Button>(R.id.btnPickMap).setOnClickListener {
            mapPickerLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveStop() }

        // Prefilled from the "nearby stops" sheet: stop already chosen, just
        // load its routes so the user can pick which ones to track.
        val prefillId = intent.getStringExtra(EXTRA_PREFILL_ID)
        if (editingId == null && prefillId != null) {
            etId.setText(prefillId)
            etName.setText(intent.getStringExtra(EXTRA_PREFILL_NAME) ?: prefillId)
            pickedLat = intent.getDoubleExtra(EXTRA_PREFILL_LAT, 0.0)
            pickedLon = intent.getDoubleExtra(EXTRA_PREFILL_LON, 0.0)
            fetchAvailableRoutes(prefillId)
        }

        // "Find a stop near me": jump straight into the map picker (it centers
        // itself on the current GPS position when permission is granted).
        if (editingId == null && savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_PICKER, false)
        ) {
            mapPickerLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
    }

    /** Fetch every route serving the stop (unfiltered /arrivals) → toggle chips. */
    private fun fetchAvailableRoutes(stopId: String) {
        val base = Config.SERVER_URL.trimEnd('/')
        if (base.isBlank() || stopId.isBlank()) return
        tvRoutesLabel.visibility = View.VISIBLE
        tvRoutesStatus.visibility = View.VISIBLE
        tvRoutesStatus.text = "загружаю маршруты…"
        Thread {
            val routes: Map<String, String>? = try {
                val conn = URL("$base/arrivals/${URLEncoder.encode(stopId, "UTF-8")}")
                    .openConnection() as HttpURLConnection
                conn.apply { connectTimeout = 10_000; readTimeout = 10_000 }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val arr = json.optJSONArray("arrivals")
                val found = LinkedHashMap<String, String>()   // route → type
                if (arr != null) for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val route = if (a.isNull("route")) "" else a.optString("route")
                    if (route.isBlank() || route == "?") continue
                    val type = if (a.isNull("type")) "" else a.optString("type")
                    found.putIfAbsent(route, type)
                }
                found
            } catch (_: Exception) { null }
            runOnUiThread { renderRouteChips(routes) }
        }.start()
    }

    private fun renderRouteChips(routes: Map<String, String>?) {
        if (isFinishing || isDestroyed) return
        when {
            routes == null -> tvRoutesStatus.text = "не удалось загрузить маршруты"
            routes.isEmpty() -> tvRoutesStatus.text = "маршруты не найдены"
            else -> {
                tvRoutesStatus.visibility = View.GONE
                chipsRoutes.visibility = View.VISIBLE
                chipsRoutes.removeAllViews()
                val sorted = routes.entries.sortedWith(
                    compareBy({ it.key.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it.key })
                )
                sorted.forEach { (route, type) -> chipsRoutes.addView(makeChip(route, type)) }
                applySelectionToChips()
            }
        }
    }

    private fun makeChip(route: String, type: String): Chip {
        val typeLabel = when (type) {
            "bus"            -> "авт"
            "tram"           -> "трам"
            "trolleybus"     -> "трол"
            "electric_train" -> "мцд"
            "minibus"        -> "мрш"
            else             -> ""
        }
        return Chip(this).apply {
            tag = route
            text = if (typeLabel.isNotEmpty()) "$route · $typeLabel" else route
            isCheckable = true
            isCheckedIconVisible = false
            val c = { id: Int -> androidx.core.content.ContextCompat.getColor(context, id) }
            chipBackgroundColor = ColorStateList(CHIP_STATES,
                intArrayOf(0x595E8BFF, c(R.color.surfacePill)))
            chipStrokeColor = ColorStateList(CHIP_STATES,
                intArrayOf(0xB35E8BFF.toInt(), c(R.color.surfaceCardStroke)))
            chipStrokeWidth = 1f * resources.displayMetrics.density
            setTextColor(ColorStateList(CHIP_STATES,
                intArrayOf(c(R.color.textPrimary), c(R.color.textSecondary))))
            setOnCheckedChangeListener { _, _ -> if (!syncingChips) syncChipsToField() }
        }
    }

    /** Chips → text field (comma-separated route list). */
    private fun syncChipsToField() {
        val selected = (0 until chipsRoutes.childCount)
            .mapNotNull { chipsRoutes.getChildAt(it) as? Chip }
            .filter { it.isChecked }
            .map { it.tag as String }
        syncingChips = true
        etRoutes.setText(selected.joinToString(","))
        etRoutes.setSelection(etRoutes.text.length)
        syncingChips = false
    }

    /** Text field → chips (used on load and on manual typing). */
    private fun applySelectionToChips() {
        val selected = etRoutes.text.toString()
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        syncingChips = true
        for (i in 0 until chipsRoutes.childCount) {
            (chipsRoutes.getChildAt(i) as? Chip)?.let { it.isChecked = it.tag in selected }
        }
        syncingChips = false
    }

    private fun saveStop() {
        val id     = etId.text.toString().trim()
        val name   = etName.text.toString().trim()
        val routes = etRoutes.text.toString().trim()
        if (id.isBlank()) { toast("Сначала выберите остановку на карте"); return }
        val stop = Stop(id = id, name = name.ifBlank { id }, routes = routes, lat = pickedLat, lon = pickedLon)
        if (editingId != null) {
            StopStorage.update(this, stop)
        } else {
            StopStorage.add(this, stop)
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
