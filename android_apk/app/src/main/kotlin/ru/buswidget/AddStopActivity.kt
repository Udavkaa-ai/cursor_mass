package ru.buswidget

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import ru.buswidget.data.Config
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AddStopActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var etUrl:    EditText
    private lateinit var etId:     EditText
    private lateinit var etName:   EditText
    private lateinit var etRoutes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_stop)

        etUrl    = findViewById(R.id.etUrl)
        etId     = findViewById(R.id.etStopId)
        etName   = findViewById(R.id.etStopName)
        etRoutes = findViewById(R.id.etRoutes)

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
            toast("ID остановки не найден в ссылке")
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
        StopStorage.add(this, Stop(id = id, name = name.ifBlank { id }, routes = routes))
        finish()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
