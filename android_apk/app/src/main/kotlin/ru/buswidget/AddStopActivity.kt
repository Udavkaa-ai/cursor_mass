package ru.buswidget

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage

class AddStopActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_STOP_ID = "edit_stop_id"
    }

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

        etId     = findViewById(R.id.etStopId)
        etName   = findViewById(R.id.etStopName)
        etRoutes = findViewById(R.id.etRoutes)

        editingId = intent.getStringExtra(EXTRA_EDIT_STOP_ID)
        if (editingId != null) {
            findViewById<TextView>(R.id.tvTitle).text = "Редактировать"
            StopStorage.load(this).find { it.id == editingId }?.let { stop ->
                etId.setText(stop.id)
                etName.setText(stop.name)
                etRoutes.setText(stop.routes)
            }
            findViewById<Button>(R.id.btnPickMap).visibility = android.view.View.GONE
        }

        findViewById<Button>(R.id.btnPickMap).setOnClickListener {
            mapPickerLauncher.launch(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveStop() }
    }

    private fun saveStop() {
        val id     = etId.text.toString().trim()
        val name   = etName.text.toString().trim()
        val routes = etRoutes.text.toString().trim()
        if (id.isBlank()) { toast("Сначала выберите остановку на карте"); return }
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
