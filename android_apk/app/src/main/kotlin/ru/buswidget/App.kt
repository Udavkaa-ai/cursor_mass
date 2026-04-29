package ru.buswidget

import android.app.Application
import com.yandex.mapkit.MapKitFactory

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MapKitFactory.setApiKey("bf1da5ff-0a6b-4056-a5ea-6435b6bb85f8")
        MapKitFactory.initialize(this)
    }
}
