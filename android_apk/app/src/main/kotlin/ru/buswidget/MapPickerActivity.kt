package ru.buswidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.GeoObjectTapEvent
import com.yandex.mapkit.map.GeoObjectTapListener
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.uri.UriObjectMetadata

class MapPickerActivity : AppCompatActivity(), GeoObjectTapListener {

    companion object {
        const val RESULT_STOP_ID   = "stop_id"
        const val RESULT_STOP_NAME = "stop_name"
        private val MOSCOW = Point(55.751426, 37.618879)
    }

    private lateinit var mapView:   MapView
    private lateinit var tvHint:    TextView
    private lateinit var btnConfirm: Button
    private lateinit var mapObjects: MapObjectCollection

    private var selectedId:   String? = null
    private var selectedName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        mapView   = findViewById(R.id.mapView)
        tvHint    = findViewById(R.id.tvHint)
        btnConfirm = findViewById(R.id.btnConfirm)

        val map = mapView.mapWindow.map
        map.move(CameraPosition(MOSCOW, 13f, 0f, 0f))
        map.addTapListener(this)
        mapObjects = map.mapObjects

        btnConfirm.isEnabled = false
        btnConfirm.alpha = 0.4f
        btnConfirm.setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(RESULT_STOP_ID,   id)
                putExtra(RESULT_STOP_NAME, selectedName ?: id)
            })
            finish()
        }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    override fun onObjectTap(event: GeoObjectTapEvent): Boolean {
        val geo = event.geoObject
        val uri = geo.metadataContainer
            .getItem(UriObjectMetadata::class.java)
            ?.uris?.firstOrNull()?.value ?: return false

        if (!uri.contains("transit/stop")) return false

        val match = Regex("""stop(?:__)?(\d+)""").find(uri) ?: return false
        selectedId   = match.groupValues[1]
        selectedName = geo.name?.takeIf { it.isNotBlank() }

        tvHint.text  = "Выбрано: ${selectedName ?: selectedId}"
        tvHint.setTextColor(0xFFF4F4F6.toInt())
        btnConfirm.isEnabled = true
        btnConfirm.alpha = 1f
        return true
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
    }

    override fun onStop() {
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }
}
