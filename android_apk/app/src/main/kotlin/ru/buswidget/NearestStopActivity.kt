package ru.buswidget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import ru.buswidget.data.StopStorage

/**
 * Invisible router used by the Quick Settings tile: gets a location fix (we're
 * a foreground activity, so no background-location limits), finds the nearest
 * saved stop and opens its arrivals screen. Finishes itself in every path.
 */
class NearestStopActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            toast("Разрешите геолокацию в приложении")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        toast("Ищу ближайшую остановку…")
        ru.buswidget.data.Locator.request(this) { loc ->
            if (loc != null) openNearest(loc) else fail("включите GPS")
        }
    }

    private fun openNearest(loc: Location) {
        val nearest = StopStorage.findNearby(this, loc.latitude, loc.longitude, 1).firstOrNull()
        if (nearest == null) {
            fail("нет остановок с координатами")
            return
        }
        startActivity(Intent(this, ArrivalsActivity::class.java).apply {
            putExtra(ArrivalsActivity.EXTRA_STOP_ID, nearest.stop.id)
            putExtra(ArrivalsActivity.EXTRA_STOP_NAME, nearest.stop.name)
            putExtra(ArrivalsActivity.EXTRA_ROUTES, nearest.stop.routes)
        })
        finish()
    }

    private fun fail(msg: String) {
        toast("Не получилось: $msg")
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
