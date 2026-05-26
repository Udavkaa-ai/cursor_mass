package ru.buswidget

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage

class MainActivity : AppCompatActivity() {

    private val adapter = StopAdapter(::openArrivals, ::editStop, ::deleteStop)
    private lateinit var rvStops: RecyclerView
    private lateinit var vEmpty:  View

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeBackup(it) } }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readAndImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvStops = findViewById(R.id.recyclerView)
        vEmpty  = findViewById(R.id.tvEmpty)
        rvStops.layoutManager = LinearLayoutManager(this)
        rvStops.adapter = adapter

        val openAdd = View.OnClickListener { startActivity(Intent(this, AddStopActivity::class.java)) }
        findViewById<Button>(R.id.btnAdd).setOnClickListener(openAdd)
        findViewById<View>(R.id.btnAddEmpty).setOnClickListener(openAdd)
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showMenu() }
    }

    override fun onResume() {
        super.onResume()
        val stops = StopStorage.load(this)
        adapter.submit(stops)
        rvStops.visibility = if (stops.isEmpty()) View.GONE  else View.VISIBLE
        vEmpty.visibility  = if (stops.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Сохранить бэкап", "Восстановить из файла")) { _, which ->
                when (which) {
                    0 -> createDocumentLauncher.launch("buswidget_backup.json")
                    1 -> openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
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
        AlertDialog.Builder(this)
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
}
