package ru.buswidget

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    companion object {
        private const val PREF_THEME = "theme_mode"

        /** 0 = follow system, 1 = light, 2 = dark */
        fun themeMode(ctx: Context): Int =
            ctx.getSharedPreferences("bw", Context.MODE_PRIVATE).getInt(PREF_THEME, 0)

        fun setThemeMode(ctx: Context, mode: Int) {
            ctx.getSharedPreferences("bw", Context.MODE_PRIVATE)
                .edit().putInt(PREF_THEME, mode).apply()
            applyTheme(mode)
        }

        fun applyTheme(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        applyTheme(themeMode(this))
    }
}
