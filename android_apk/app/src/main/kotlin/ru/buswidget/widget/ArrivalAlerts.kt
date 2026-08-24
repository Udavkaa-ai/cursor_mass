package ru.buswidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.buswidget.ArrivalsActivity
import ru.buswidget.R

/**
 * "Bus arriving" alert notifications (inspired by OneBusAway's arrival
 * reminders): a heads-up notification with sound/vibration when a tracked bus
 * is within the user-set threshold. Fired from widget sessions and the app
 * screen alike; deduplicated per route per session by the callers' sets.
 */
object ArrivalAlerts {

    private const val CHANNEL_ID = "bus_alerts"
    private const val PREFS = "bw"
    private const val KEY_MIN = "alert_min"   // minutes; 0 = off

    /** Alert threshold in seconds; 0 = alerts disabled. Default: 1 minute. */
    fun thresholdSec(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MIN, 1) * 60

    fun thresholdMin(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MIN, 1)

    fun setThresholdMin(ctx: Context, minutes: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MIN, minutes).apply()

    /** Check live arrivals and alert once per route (tracked via [notified]). */
    fun check(
        ctx: Context,
        notified: MutableSet<String>,
        stopId: String,
        stopName: String,
        routes: String,
        live: List<WidgetArrival>,
    ) {
        val th = thresholdSec(ctx)
        if (th <= 0) return
        for (a in live) {
            val eta = a.etaSeconds ?: continue
            if (eta in 1..th && a.route !in notified) {
                notified += a.route
                post(ctx, stopId, stopName, routes, a.route, a.eta)
            }
        }
    }

    /** Post the alert notification itself. */
    fun post(
        ctx: Context,
        stopId: String,
        stopName: String,
        routes: String,
        route: String,
        etaText: String,
    ) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Автобус подъезжает", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Напоминание, когда отслеживаемый автобус почти на остановке"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 100, 200)
                }
            )
        }
        val intent = Intent(ctx, ArrivalsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ArrivalsActivity.EXTRA_STOP_ID, stopId)
            putExtra(ArrivalsActivity.EXTRA_STOP_NAME, stopName)
            putExtra(ArrivalsActivity.EXTRA_ROUTES, routes)
        }
        val pi = PendingIntent.getActivity(
            ctx, (stopId + route).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_bus)
            .setContentTitle("🚌 $route подъезжает!")
            .setContentText("$stopName · $etaText")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify((stopId + route).hashCode(), notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on 13+ — silently skip
        }
    }
}
