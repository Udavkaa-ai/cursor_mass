package ru.buswidget

import android.app.Application
import com.yandex.mapkit.MapKit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MapKit.setApiKey("YOUR_MAPKIT_API_KEY")
    }
}
