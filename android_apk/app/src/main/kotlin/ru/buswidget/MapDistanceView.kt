package ru.buswidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class MapDistanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var etaSeconds: Int? = null
    private val paintCircle = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4A90E2")
        alpha = 80
    }

    private val paintCircleFill = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#4A90E2")
        alpha = 30
    }

    private val paintStop = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#2ED87A")
    }

    private val paintStopRing = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2ED87A")
        alpha = 100
    }

    private val paintScale = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#9090B8")
        alpha = 100
    }

    private val paintText = Paint().apply {
        isAntiAlias = true
        textSize = 11f
        color = Color.parseColor("#9090B8")
        textAlign = Paint.Align.CENTER
    }

    fun setEta(seconds: Int?) {
        etaSeconds = seconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2

        val maxDistanceMeters = 3000
        val pixelsPerMeter = minOf(cx, cy) / maxDistanceMeters

        drawScaleCircles(canvas, cx, cy, pixelsPerMeter)
        drawDistanceCircle(canvas, cx, cy, pixelsPerMeter)
        drawStop(canvas, cx, cy)
        drawLegend(canvas, cx, cy - 10)
    }

    private fun drawScaleCircles(canvas: Canvas, cx: Float, cy: Float, pixelsPerMeter: Float) {
        val distances = listOf(1000, 2000, 3000)
        distances.forEach { meters ->
            val radius = meters * pixelsPerMeter
            canvas.drawCircle(cx, cy, radius, paintScale)
            canvas.drawText(
                "${meters / 1000}км",
                cx + radius + 15,
                cy - 5,
                paintText
            )
        }
    }

    private fun drawDistanceCircle(canvas: Canvas, cx: Float, cy: Float, pixelsPerMeter: Float) {
        val eta = etaSeconds ?: return
        val busSpeedMps = 8.3
        val busDistanceMeters = (eta * busSpeedMps).toInt().coerceAtLeast(0)
        val radius = busDistanceMeters * pixelsPerMeter
        canvas.drawCircle(cx, cy, radius, paintCircleFill)
        canvas.drawCircle(cx, cy, radius, paintCircle)
    }

    private fun drawStop(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, 12f, paintStop)
        canvas.drawCircle(cx, cy, 12f, paintStopRing)
    }

    private fun drawLegend(canvas: Canvas, cx: Float, cy: Float) {
        val eta = etaSeconds ?: return
        val busSpeedMps = 8.3
        val busDistanceMeters = (eta * busSpeedMps).toInt().coerceAtLeast(0)

        val distanceText = if (busDistanceMeters < 1000) {
            "$busDistanceMeters м"
        } else {
            "%.1f км".format(busDistanceMeters / 1000.0)
        }

        val timeText = when {
            eta <= 0 -> "подъезжает"
            eta < 60 -> "< 1 мин"
            else -> "${eta / 60} мин"
        }

        val legendPaint = Paint().apply {
            isAntiAlias = true
            textSize = 13f
            textAlign = Paint.Align.CENTER
            color = Color.parseColor("#F4F4F6")
        }

        canvas.drawText(distanceText, cx, cy - 25, legendPaint)
        canvas.drawText(timeText, cx, cy + 5, legendPaint)
    }
}
