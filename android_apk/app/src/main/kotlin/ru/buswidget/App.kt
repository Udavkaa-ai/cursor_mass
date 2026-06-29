package ru.buswidget

import android.app.Application
import com.yandex.mapkit.MapKit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val apiKey = BuildConfig.MAPKIT_API_KEY
            if (apiKey.isNotEmpty() && apiKey != "MAPKIT_API_KEY_PLACEHOLDER") {
                MapKit.setApiKey(apiKey)
            }
        } catch (e: Exception) {
            // Fallback if BuildConfig is not available
            android.util.Log.w("MapKit", "Failed to initialize MapKit: ${e.message}")
        }
    }
}
