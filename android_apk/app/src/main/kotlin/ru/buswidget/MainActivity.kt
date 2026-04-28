package ru.buswidget

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.buswidget.data.Stop
import ru.buswidget.data.StopStorage

class MainActivity : AppCompatActivity() {

    private val adapter = StopAdapter(::openArrivals, ::deleteStop)
    private lateinit var rvStops: RecyclerView
    private lateinit var vEmpty:  View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvStops = findViewById(R.id.recyclerView)
        vEmpty  = findViewById(R.id.tvEmpty)
        rvStops.layoutManager = LinearLayoutManager(this)
        rvStops.adapter = adapter

        val openAdd = View.OnClickListener { startActivity(Intent(this, AddStopActivity::class.java)) }
        findViewById<Button>(R.id.btnAdd).setOnClickListener(openAdd)
        // CTA button in empty state
        findViewById<View>(R.id.btnAddEmpty).setOnClickListener(openAdd)
    }

    override fun onResume() {
        super.onResume()
        val stops = StopStorage.load(this)
        adapter.submit(stops)
        rvStops.visibility = if (stops.isEmpty()) View.GONE  else View.VISIBLE
        vEmpty.visibility  = if (stops.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openArrivals(stop: Stop) {
        startActivity(Intent(this, ArrivalsActivity::class.java).apply {
            putExtra(ArrivalsActivity.EXTRA_STOP_ID,   stop.id)
            putExtra(ArrivalsActivity.EXTRA_STOP_NAME, stop.name)
            putExtra(ArrivalsActivity.EXTRA_ROUTES,    stop.routes)
        })
    }

    private fun deleteStop(stop: Stop) {
        StopStorage.remove(this, stop.id)
        adapter.submit(StopStorage.load(this))
    }
}
