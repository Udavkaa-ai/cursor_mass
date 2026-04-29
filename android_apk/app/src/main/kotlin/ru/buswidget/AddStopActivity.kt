package ru.buswidget

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var etUrl:    EditText
    private lateinit var etId:     EditText
    private lateinit var etName:   EditText
    private lateinit var etRoutes: EditText
    private var editingId: String? = null

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val stopId   = data.getStringExtra(MapPickerActivity.RESULT_STOP_ID)   ?: return@registerForActivityResult
            val stopName = data.getStringExtra(MapPickerActivity.RESULT_STOP_NAME) ?: stopId
            etId.setText(stopId)
            etName.setText(stopName)
            toast("Остановка выбрана: $stopName")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_stop)

        etUrl    = findViewById(R.id.etUrl)
        etId     = findViewById(R.id.etStopId)
        etName   = findViewById(R.id.etStopName)
        etRoutes = findViewById(R.id.etRoutes)

        editingId = intent.getStringExtra(EXTRA_EDIT_STOP_ID)
        if (editingId != null) {
            findViewById<TextView>(R.id.tvTitle).text = "Редактировать"
            StopStorage.load(this).find { it.id == editingId }?.let { stop ->
                etId.setText(stop.id)
                etId.isEnabled = false
                etId.alpha = 0.5f
                etName.setText(stop.name)
                etRoutes.setText(stop.routes)
            }
            etUrl.visibility = android.view.View.GONE
            findViewById<Button>(R.id.btnParseUrl).visibility = android.view.View.GONE
            findViewById<Button>(R.id.btnPickMap).visibility  = android.view.View.GONE
        }

        findViewById<Button>(R.id.btnPickMap).setOnClickListener {
            mapPickerLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btnParseUrl).setOnClickListener  { parseUrl() }
        findViewById<Button>(R.id.btnFetchName).setOnClickListener { fetchName() }
        findViewById<Button>(R.id.btnSave).setOnClickListener      { saveStop() }
    }

    private fun parseUrl() {
        val url   = etUrl.text.toString()
        val match = Regex("""stops/(?:stop__)?(\d+)""").find(url)
        if (match != null) {
            etId.setText(match.groupValues[1])
            toast("ID найден: ${match.groupValues[1]}")
        } else {
            toast("ID не найден. Скопируйте адрес из адресной строки браузера")
        }
    }

    private fun fetchName() {
        val id = etId.text.toString().trim()
        if (id.isBlank()) { toast("Сначала введите ID"); return }
        val base = Config.SERVER_URL.trimEnd('/')
        if (base.isBlank()) { toast("SERVER_URL не задан в Config.kt"); return }
        Thread {
            try {
                val conn = URL("$base/arrivals/${URLEncoder.encode(id, "UTF-8")}")
                    .openConnection() as HttpURLConnection
                conn.apply { connectTimeout = 10_000; readTimeout = 10_000 }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val name = json.optString("name")
                handler.post {
                    if (name.isNotBlank()) etName.setText(name)
                    else toast("Имя не получено")
                }
            } catch (e: Exception) {
                handler.post { toast("Ошибка: ${e.message?.take(50)}") }
            }
        }.start()
    }

    private fun saveStop() {
        val id     = etId.text.toString().trim()
        val name   = etName.text.toString().trim()
        val routes = etRoutes.text.toString().trim()
        if (id.isBlank()) { toast("ID остановки обязателен"); return }
        val stop = Stop(id = id, name = name.ifBlank { id }, routes = routes)
        if (editingId != null) {
            StopStorage.update(this, stop)
        } else {
            StopStorage.add(this, stop)
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
