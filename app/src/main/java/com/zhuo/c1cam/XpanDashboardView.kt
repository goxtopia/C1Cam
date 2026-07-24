package com.zhuo.c1cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.Surface
import android.view.View
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

class XpanDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val levelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val histogramPath = Path()
    private val backgroundPaint = Paint()

    private var isActive = false
    private var rawRollDegrees = 0f
    private var latestSensorRollDegrees = 0f
    private var pitchDegrees = 0f
    private var deviceRotationDegrees = 0f
    private var contentRotationDegrees = 0f
    private var histogram = FloatArray(64)
    private var iso: Int? = null
    private var exposureTimeNs: Long? = null

    fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        if (active && isAttachedToWindow) {
            registerLevelSensor()
        } else {
            sensorManager.unregisterListener(this)
        }
        visibility = if (active) VISIBLE else GONE
    }

    fun updateTelemetry(telemetry: XpanTelemetry) {
        histogram = telemetry.histogram.copyOf()
        iso = telemetry.iso
        exposureTimeNs = telemetry.exposureTimeNs
        postInvalidateOnAnimation()
    }

    fun setOrientation(deviceRotation: Int, displayRotation: Int) {
        deviceRotationDegrees = when (deviceRotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }
        contentRotationDegrees = OrientationMath.controlRotationDegrees(
            deviceRotation = deviceRotation,
            displayRotation = displayRotation
        ).toFloat()
        rawRollDegrees = latestSensorRollDegrees
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isActive) registerLevelSensor()
    }

    override fun onDetachedFromWindow() {
        sensorManager.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    private fun registerLevelSensor() {
        levelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!isActive || event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val newRoll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        val newPitch = Math.toDegrees(atan2(-z.toDouble(), hypot(x.toDouble(), y.toDouble()))).toFloat()
        latestSensorRollDegrees = newRoll
        rawRollDegrees = smoothAngle(rawRollDegrees, newRoll, 0.16f)
        pitchDegrees += (newPitch - pitchDegrees) * 0.16f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawPanelTexture(canvas)
        drawHeader(canvas)
        drawLevel(canvas)
        drawHistogram(canvas)
        drawExposureReadout(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                Color.rgb(7, 9, 8),
                Color.rgb(13, 16, 13),
                Color.rgb(7, 9, 8)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun drawPanelTexture(canvas: Canvas) {
        linePaint.strokeWidth = dp(1f)
        linePaint.color = Color.argb(11, 246, 247, 248)
        val spacing = dp(28f)
        var y = dp(14f)
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            y += spacing
        }

        fillPaint.color = Color.argb(30, 214, 255, 66)
        val dotSpacing = dp(32f)
        var dotY = dp(16f)
        var row = 0
        while (dotY < height) {
            var dotX = if (row % 2 == 0) dp(14f) else dp(30f)
            while (dotX < width) {
                canvas.drawCircle(dotX, dotY, dp(0.65f), fillPaint)
                dotX += dotSpacing
            }
            dotY += dotSpacing
            row += 1
        }
    }

    private fun drawHeader(canvas: Canvas) {
        val landscape = width > height
        val cx = if (landscape) width * 0.56f else width * 0.31f
        val cy = if (landscape) height * 0.16f else height * 0.12f
        canvas.save()
        canvas.rotate(contentRotationDegrees, cx, cy)
        textPaint.color = Color.rgb(214, 255, 66)
        textPaint.textSize = sp(10f)
        textPaint.letterSpacing = 0.14f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("XPAN  ·  65:24", cx, cy, textPaint)

        textPaint.color = Color.argb(150, 246, 247, 248)
        textPaint.textSize = sp(8f)
        canvas.drawText("PANORAMIC FILM BACK", cx, cy + dp(17f), textPaint)
        canvas.restore()
    }

    private fun drawLevel(canvas: Canvas) {
        val landscape = width > height
        val cx = if (landscape) width * 0.55f else width * 0.32f
        val cy = if (landscape) height * 0.53f else height * 0.39f
        val radius = if (landscape) height * 0.29f else minOf(width, height) * 0.18f
        val levelRollDegrees = normalizeSigned(rawRollDegrees - deviceRotationDegrees)
        val isLevel = kotlin.math.abs(levelRollDegrees) < 1.2f &&
            kotlin.math.abs(pitchDegrees) < 1.2f
        val accent = if (isLevel) Color.rgb(214, 255, 66) else Color.rgb(239, 187, 80)

        canvas.save()
        canvas.rotate(contentRotationDegrees, cx, cy)
        fillPaint.color = Color.argb(180, 17, 20, 18)
        canvas.drawCircle(cx, cy, radius + dp(13f), fillPaint)
        linePaint.strokeWidth = dp(1f)
        linePaint.color = Color.argb(65, 246, 247, 248)
        canvas.drawCircle(cx, cy, radius, linePaint)
        canvas.drawCircle(cx, cy, radius * 0.62f, linePaint)

        for (angle in -90..90 step 15) {
            val radians = Math.toRadians(angle.toDouble())
            val inner = if (angle % 45 == 0) radius * 0.82f else radius * 0.9f
            val sx = cx + (kotlin.math.sin(radians) * inner).toFloat()
            val sy = cy - (kotlin.math.cos(radians) * inner).toFloat()
            val ex = cx + (kotlin.math.sin(radians) * radius).toFloat()
            val ey = cy - (kotlin.math.cos(radians) * radius).toFloat()
            canvas.drawLine(sx, sy, ex, ey, linePaint)
        }

        canvas.save()
        canvas.rotate(-levelRollDegrees.coerceIn(-35f, 35f), cx, cy)
        linePaint.color = accent
        linePaint.strokeWidth = dp(2f)
        canvas.drawLine(cx - radius * 0.82f, cy, cx - dp(12f), cy, linePaint)
        canvas.drawLine(cx + dp(12f), cy, cx + radius * 0.82f, cy, linePaint)
        canvas.restore()

        val bubbleX = cx + (levelRollDegrees.coerceIn(-12f, 12f) / 12f) * radius * 0.42f
        val bubbleY = cy + (pitchDegrees.coerceIn(-12f, 12f) / 12f) * radius * 0.42f
        fillPaint.color = accent
        canvas.drawCircle(bubbleX, bubbleY, dp(6.5f), fillPaint)
        fillPaint.color = Color.rgb(10, 12, 11)
        canvas.drawCircle(bubbleX, bubbleY, dp(2f), fillPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.argb(170, 246, 247, 248)
        textPaint.textSize = sp(9f)
        canvas.drawText(
            String.format(Locale.US, "%+.1f°  /  %+.1f°", levelRollDegrees, pitchDegrees),
            cx,
            cy + radius + dp(29f),
            textPaint
        )
        textPaint.color = Color.rgb(214, 255, 66)
        textPaint.textSize = sp(8f)
        textPaint.letterSpacing = 0.12f
        canvas.drawText("LEVEL", cx, cy - radius - dp(21f), textPaint)
        canvas.restore()
    }

    private fun drawHistogram(canvas: Canvas) {
        val landscape = width > height
        val rect = if (landscape) {
            RectF(width * 0.70f, height * 0.20f, width - dp(18f), height - dp(18f))
        } else {
            RectF(width * 0.35f, height * 0.66f, width - dp(18f), height * 0.91f)
        }
        canvas.save()
        canvas.rotate(contentRotationDegrees, rect.centerX(), rect.centerY())
        fillPaint.color = Color.argb(160, 16, 19, 17)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)
        linePaint.color = Color.argb(52, 246, 247, 248)
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), linePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.argb(155, 246, 247, 248)
        textPaint.textSize = sp(8f)
        textPaint.letterSpacing = 0.12f
        canvas.drawText("LUMA HISTOGRAM", rect.left + dp(12f), rect.top + dp(17f), textPaint)

        val graph = RectF(
            rect.left + dp(12f),
            rect.top + dp(26f),
            rect.right - dp(12f),
            rect.bottom - dp(10f)
        )
        histogramPath.reset()
        histogramPath.moveTo(graph.left, graph.bottom)
        val values = histogram
        for (index in values.indices) {
            val px = graph.left + graph.width() * index / (values.size - 1).coerceAtLeast(1)
            val py = graph.bottom - graph.height() * values[index].coerceIn(0f, 1f)
            histogramPath.lineTo(px, py)
        }
        histogramPath.lineTo(graph.right, graph.bottom)
        histogramPath.close()
        fillPaint.color = Color.argb(115, 214, 255, 66)
        canvas.drawPath(histogramPath, fillPaint)
        linePaint.color = Color.rgb(214, 255, 66)
        linePaint.strokeWidth = dp(1.4f)
        canvas.drawPath(histogramPath, linePaint)
        canvas.restore()
    }

    private fun drawExposureReadout(canvas: Canvas) {
        val landscape = width > height
        val rect = if (landscape) {
            RectF(width * 0.31f, height * 0.60f, width * 0.47f, height - dp(18f))
        } else {
            RectF(dp(18f), height * 0.72f, width * 0.31f, height * 0.94f)
        }
        canvas.save()
        canvas.rotate(contentRotationDegrees, rect.centerX(), rect.centerY())
        fillPaint.color = Color.argb(160, 16, 19, 17)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)
        linePaint.color = Color.argb(52, 246, 247, 248)
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), linePaint)

        val isoText = iso?.toString() ?: "—"
        val shutterText = formatShutter(exposureTimeNs)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.letterSpacing = 0.08f
        if (landscape) {
            val secondColumn = rect.centerX() + dp(5f)
            textPaint.color = Color.argb(150, 246, 247, 248)
            textPaint.textSize = sp(7f)
            canvas.drawText("ISO", rect.left + dp(10f), rect.top + dp(18f), textPaint)
            canvas.drawText("SS", secondColumn, rect.top + dp(18f), textPaint)
            textPaint.color = Color.rgb(246, 247, 248)
            textPaint.letterSpacing = 0f
            textPaint.textSize = sp(13f)
            canvas.drawText(isoText, rect.left + dp(10f), rect.top + dp(40f), textPaint)
            textPaint.textSize = sp(11f)
            canvas.drawText(shutterText, secondColumn, rect.top + dp(40f), textPaint)
        } else {
            textPaint.color = Color.argb(150, 246, 247, 248)
            textPaint.textSize = sp(8f)
            canvas.drawText("ISO", rect.left + dp(13f), rect.top + dp(22f), textPaint)
            canvas.drawText("SHUTTER", rect.left + dp(13f), rect.top + dp(63f), textPaint)
            textPaint.color = Color.rgb(246, 247, 248)
            textPaint.textSize = sp(20f)
            textPaint.letterSpacing = 0f
            canvas.drawText(isoText, rect.left + dp(13f), rect.top + dp(45f), textPaint)
            textPaint.textSize = sp(15f)
            canvas.drawText(shutterText, rect.left + dp(13f), rect.top + dp(84f), textPaint)
        }
        canvas.restore()
    }

    private fun formatShutter(valueNs: Long?): String {
        if (valueNs == null || valueNs <= 0L) return "—"
        val seconds = valueNs / 1_000_000_000.0
        return if (seconds >= 0.8) {
            String.format(Locale.US, "%.1f s", seconds)
        } else {
            "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}"
        }
    }

    private fun smoothAngle(current: Float, target: Float, factor: Float): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return current + delta * factor
    }

    private fun normalizeSigned(value: Float): Float {
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
