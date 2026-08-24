package ru.buswidget.widget

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.buswidget.ArrivalsActivity
import ru.buswidget.R

/**
 * Live departure board rendered into the session's foreground notification —
 * next buses with countdown, visible from the lock screen and any app.
 * Callers re-post it (same id/channel) only when the rendered text changes,
 * so the shade updates at minute granularity without alert spam.
 */
object BoardNotification {

    /** Render the board lines; used by callers for change detection too. */
    fun renderLines(live: List<WidgetArrival>): String =
        if (live.isEmpty()) "ждём данные…"
        else live.take(4).joinToString("\n") { a ->
            val dir = if (a.direction.isNotBlank()) " · ${a.direction}" else ""
            "${a.route} — ${a.eta}$dir"
        }

    fun build(
        ctx: Context,
        channelId: String,
        stopId: String,
        stopName: String,
        routes: String,
        lines: String,
    ): Notification {
        val intent = Intent(ctx, ArrivalsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ArrivalsActivity.EXTRA_STOP_ID, stopId)
            putExtra(ArrivalsActivity.EXTRA_STOP_NAME, stopName)
            putExtra(ArrivalsActivity.EXTRA_ROUTES, routes)
        }
        val pi = PendingIntent.getActivity(
            ctx, stopId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_tile_bus)
            .setContentTitle("📍 $stopName")
            .setContentText(lines.replace("\n", "  ·  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
