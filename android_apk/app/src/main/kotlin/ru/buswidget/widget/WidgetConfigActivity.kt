package ru.buswidget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.buswidget.R
import ru.buswidget.StopAdapter
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage

class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContentView(R.layout.activity_widget_config)

        val stops = StopStorage.load(this)
        if (stops.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте остановки в приложении", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val adapter = StopAdapter(::pickStop) {}
        adapter.submit(stops)
        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    private fun pickStop(stop: Stop) {
        BusWidgetProvider.widgetPrefs(this).edit()
            .putString("${widgetId}_stopId", stop.id)
            .putString("${widgetId}_name",   stop.name)
            .putString("${widgetId}_routes", stop.routes)
            .apply()

        val awm = AppWidgetManager.getInstance(this)
        BusWidgetProvider.showIdle(this, awm, widgetId)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
