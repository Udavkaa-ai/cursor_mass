package ru.buswidget.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Единая точка получения геопозиции.
 *
 * Раньше мы верили lastLocation / BALANCED-фиксу как есть, а сетевые
 * координаты (по вышкам/Wi-Fi) на некоторых устройствах промахиваются на
 * десятки километров — виджет выбирал остановку «в 55 км», а поиск
 * «остановки рядом» смотрел не туда. Теперь: HIGH_ACCURACY (реальный GPS),
 * фиксы с плохой точностью или старше 3 минут отбраковываются.
 */
object Locator {

    private const val MAX_AGE_MS = 3 * 60_000L
    private const val MAX_ACCURACY_M = 300f

    fun ageMs(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000

    private fun accurate(loc: Location): Boolean =
        !loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_M

    private fun fresh(loc: Location): Boolean = ageMs(loc) <= MAX_AGE_MS

    /**
     * Запрашивает свежий точный фикс; onResult(null) — не удалось.
     * Порядок: свежий точный lastLocation (мгновенно) → актуальный
     * HIGH_ACCURACY фикс → любой полученный как последний шанс.
     */
    @SuppressLint("MissingPermission")  // все вызывающие проверяют разрешение
    fun request(ctx: Context, onResult: (Location?) -> Unit) {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        try {
            client.lastLocation.addOnSuccessListener { last: Location? ->
                if (last != null && fresh(last) && accurate(last)) {
                    onResult(last)
                    return@addOnSuccessListener
                }
                client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token,
                ).addOnSuccessListener { cur: Location? ->
                    onResult(
                        when {
                            cur != null && accurate(cur) -> cur
                            cur != null                  -> cur   // неточный, но лучше чем ничего
                            else                         -> last  // может быть null
                        }
                    )
                }.addOnFailureListener { onResult(last) }
            }.addOnFailureListener { onResult(null) }
        } catch (_: SecurityException) {
            onResult(null)
        }
    }
}
