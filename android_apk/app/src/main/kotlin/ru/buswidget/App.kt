package ru.buswidget

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO: MapKit initialization disabled - requires authenticated Yandex repository access
        // try {
        //     val apiKey = BuildConfig.MAPKIT_API_KEY
        //     if (apiKey.isNotEmpty() && apiKey != "MAPKIT_API_KEY_PLACEHOLDER") {
        //         MapKit.setApiKey(apiKey)
        //     }
        // } catch (e: Exception) {
        //     android.util.Log.w("MapKit", "Failed to initialize MapKit: ${e.message}")
        // }
    }
}
