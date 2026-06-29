package ru.buswidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

class MapDistanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var etaSeconds: Int? = null
    private var etaStartSeconds: Int? = null  // for animation
    private var animationStartTime: Long = 0
    private var onCloseCallback: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    private val paintCircleStroke = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintCircleFill = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
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
    }

    private val paintScale = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#505060")
        alpha = 120
    }

    private val paintScaleText = Paint().apply {
        isAntiAlias = true
        textSize = 10f
        color = Color.parseColor("#8080A0")
        textAlign = Paint.Align.CENTER
    }

    private val paintLegendText = Paint().apply {
        isAntiAlias = true
        textSize = 14f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val paintLegendSubtext = Paint().apply {
        isAntiAlias = true
        textSize = 12f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#8080A0")
    }

    fun setEta(seconds: Int?) {
        if (etaSeconds != seconds) {
            etaSeconds = seconds
            if (seconds != null && (etaStartSeconds == null || etaStartSeconds != seconds)) {
                etaStartSeconds = seconds
                animationStartTime = System.currentTimeMillis()
                startAnimation()
            }
        }
        visibility = if (seconds != null) View.VISIBLE else View.GONE
        invalidate()
    }

    fun setOnCloseCallback(callback: (() -> Unit)?) {
        onCloseCallback = callback
    }

    private fun startAnimation() {
        handler.removeCallbacksAndMessages(null)
        val animRunnable = object : Runnable {
            override fun run() {
                if (etaSeconds != null) {
                    invalidate()
                    handler.postDelayed(this, 50)  // 50ms = 20fps animation
                }
            }
        }
        handler.post(animRunnable)
    }

    private fun getAnimatedEta(): Int? {
        val eta = etaSeconds ?: return null
        val startEta = etaStartSeconds ?: return eta
        val elapsed = (System.currentTimeMillis() - animationStartTime) / 1000.0
        return (startEta - elapsed.toInt()).coerceAtLeast(0)
    }

    private fun getCircleColor(seconds: Int?): Int = when {
        seconds == null -> Color.parseColor("#4A90E2")
        seconds <= 0 -> Color.parseColor("#E53040")      // red — arriving
        seconds < 300 -> Color.parseColor("#E53040")     // red — < 5 min
        seconds < 480 -> Color.parseColor("#FF8C00")     // orange — 5-8 min
        else -> Color.parseColor("#2ED87A")              // green — > 8 min
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (etaSeconds == null) return

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
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
                cx + radius + 20,
                cy - 8,
                paintScaleText
            )
        }
    }

    private fun drawDistanceCircle(canvas: Canvas, cx: Float, cy: Float, pixelsPerMeter: Float) {
        val eta = getAnimatedEta() ?: return
        val busSpeedMps = 8.3
        val busDistanceMeters = (eta * busSpeedMps).toInt().coerceAtLeast(0)
        val radius = busDistanceMeters * pixelsPerMeter
        val circleColor = getCircleColor(eta)

        paintCircleFill.color = circleColor
        paintCircleFill.alpha = 35
        paintCircleStroke.color = circleColor
        paintCircleStroke.alpha = 150

        canvas.drawCircle(cx, cy, radius, paintCircleFill)
        canvas.drawCircle(cx, cy, radius, paintCircleStroke)
    }

    private fun drawStop(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, 14f, paintStop)
        canvas.drawCircle(cx, cy, 14f, paintStopRing)
    }

    private fun drawLegend(canvas: Canvas, cx: Float, cy: Float) {
        val eta = getAnimatedEta() ?: return
        val busSpeedMps = 8.3
        val busDistanceMeters = (eta * busSpeedMps).toInt().coerceAtLeast(0)

        val distanceText = if (busDistanceMeters < 1000) {
            "$busDistanceMeters м"
        } else {
            "%.1f км".format(busDistanceMeters / 1000.0)
        }

        val timeText = when {
            eta <= 0 -> "⚡ подъезжает"
            eta < 60 -> "🔴 < 1 мин"
            eta < 300 -> "🔴 ${eta / 60} мин"
            eta < 480 -> "🟠 ${eta / 60} мин"
            else -> "🟢 ${eta / 60} мин"
        }

        paintLegendText.color = getCircleColor(eta)
        canvas.drawText(distanceText, cx, cy - 28, paintLegendText)
        canvas.drawText(timeText, cx, cy + 5, paintLegendSubtext)
    }
}
