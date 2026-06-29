package ru.buswidget

import android.app.Application
import com.yandex.mapkit.MapKit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MapKit.setApiKey(BuildConfig.MAPKIT_API_KEY)
    }
}
