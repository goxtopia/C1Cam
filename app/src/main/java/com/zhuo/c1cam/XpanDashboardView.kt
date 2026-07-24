package com.zhuo.c1cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
    private val histogramTracePath = Path()
    private val levelPath = Path()
    private val backgroundPaint = Paint()

    private var isActive = false
    private var rawRollDegrees = 0f
    private var latestSensorRollDegrees = 0f
    private var pitchDegrees = 0f
    private var deviceRotationDegrees = 0f
    private var contentRotationDegrees = 0f
    private var displayRotation = Surface.ROTATION_0
    private var histogram = FloatArray(64)
    private var instrumentTheme = XpanInstrumentTheme.GREEN

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
        postInvalidateOnAnimation()
    }

    fun setInstrumentTheme(theme: XpanInstrumentTheme) {
        if (instrumentTheme == theme) return
        instrumentTheme = theme
        postInvalidateOnAnimation()
    }

    fun setOrientation(deviceRotation: Int, displayRotation: Int) {
        this.displayRotation = displayRotation
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
        drawHeader(canvas)
        drawLevel(canvas)
        drawHistogram(canvas)
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

    private fun drawHeader(canvas: Canvas) {
        val landscape = width > height
        val cx = if (landscape) width * 0.56f else width * 0.31f
        val cy = if (landscape) height * 0.16f else dp(24f)
        textPaint.color = Color.rgb(214, 255, 66)
        textPaint.textSize = sp(10f)
        textPaint.letterSpacing = 0.14f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("XPAN  ·  65:24", cx, cy, textPaint)

        textPaint.color = Color.argb(150, 246, 247, 248)
        textPaint.textSize = sp(8f)
        canvas.drawText("PANORAMIC FILM BACK", cx, cy + dp(17f), textPaint)
    }

    private fun drawLevel(canvas: Canvas) {
        val landscape = width > height
        val cx = if (landscape) width * 0.55f else width * 0.32f
        val cy = if (landscape) height * 0.53f else height * 0.39f
        val radius = if (landscape) height * 0.29f else minOf(width, height) * 0.18f
        val levelRollDegrees = normalizeSigned(rawRollDegrees - deviceRotationDegrees)
        val isLevel = kotlin.math.abs(levelRollDegrees) < 1.2f &&
            kotlin.math.abs(pitchDegrees) < 1.2f
        val accent = if (isLevel) {
            instrumentTheme.accent
        } else {
            Color.rgb(230, 157, 69)
        }
        val bezelRadius = radius + dp(15f)

        canvas.save()
        canvas.rotate(contentRotationDegrees, cx, cy)

        fillPaint.color = Color.argb(38, 0, 0, 0)
        canvas.drawCircle(cx + dp(4f), cy + dp(6f), bezelRadius + dp(4f), fillPaint)
        fillPaint.color = Color.argb(42, 0, 0, 0)
        canvas.drawCircle(cx + dp(2f), cy + dp(3f), bezelRadius + dp(2f), fillPaint)

        fillPaint.shader = RadialGradient(
            cx - bezelRadius * 0.28f,
            cy - bezelRadius * 0.34f,
            bezelRadius * 1.45f,
            intArrayOf(
                Color.rgb(119, 124, 119),
                Color.rgb(50, 54, 51),
                Color.rgb(17, 19, 18),
                Color.rgb(5, 7, 6)
            ),
            floatArrayOf(0f, 0.36f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, bezelRadius, fillPaint)
        fillPaint.shader = null
        linePaint.color = Color.argb(180, 203, 208, 201)
        linePaint.strokeWidth = dp(0.9f)
        canvas.drawCircle(cx, cy, bezelRadius - dp(1f), linePaint)
        linePaint.color = Color.argb(100, 0, 0, 0)
        linePaint.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, radius + dp(5f), linePaint)

        fillPaint.shader = RadialGradient(
            cx - radius * 0.22f,
            cy - radius * 0.28f,
            radius * 1.38f,
            intArrayOf(
                Color.rgb(40, 45, 41),
                Color.rgb(17, 21, 18),
                Color.rgb(7, 9, 8)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null

        val screwRadius = radius + dp(9.5f)
        for (angle in intArrayOf(45, 135, 225, 315)) {
            val radians = Math.toRadians(angle.toDouble())
            val screwX = cx + (kotlin.math.sin(radians) * screwRadius).toFloat()
            val screwY = cy - (kotlin.math.cos(radians) * screwRadius).toFloat()
            fillPaint.color = Color.rgb(12, 14, 13)
            canvas.drawCircle(screwX, screwY, dp(2.3f), fillPaint)
            linePaint.color = Color.argb(145, 205, 210, 203)
            linePaint.strokeWidth = dp(0.65f)
            canvas.drawLine(
                screwX - dp(1.15f),
                screwY + dp(0.5f),
                screwX + dp(1.15f),
                screwY - dp(0.5f),
                linePaint
            )
        }

        linePaint.strokeWidth = dp(0.8f)
        linePaint.color = Color.argb(92, 238, 242, 237)
        canvas.drawCircle(cx, cy, radius, linePaint)
        canvas.drawCircle(cx, cy, radius * 0.68f, linePaint)

        for (angle in -90..90 step 5) {
            val radians = Math.toRadians(angle.toDouble())
            val inner = when {
                angle % 30 == 0 -> radius * 0.78f
                angle % 15 == 0 -> radius * 0.84f
                else -> radius * 0.91f
            }
            val sx = cx + (kotlin.math.sin(radians) * inner).toFloat()
            val sy = cy - (kotlin.math.cos(radians) * inner).toFloat()
            val ex = cx + (kotlin.math.sin(radians) * radius).toFloat()
            val ey = cy - (kotlin.math.cos(radians) * radius).toFloat()
            linePaint.color = if (angle % 15 == 0) {
                Color.argb(165, 229, 234, 228)
            } else {
                Color.argb(75, 229, 234, 228)
            }
            linePaint.strokeWidth = if (angle % 30 == 0) dp(1.15f) else dp(0.7f)
            canvas.drawLine(sx, sy, ex, ey, linePaint)
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(6.2f)
        textPaint.letterSpacing = 0.04f
        textPaint.color = Color.argb(160, 236, 240, 235)
        for (angle in intArrayOf(-60, -30, 0, 30, 60)) {
            val radians = Math.toRadians(angle.toDouble())
            val labelRadius = radius * 0.69f
            val labelX = cx + (kotlin.math.sin(radians) * labelRadius).toFloat()
            val labelY = cy - (kotlin.math.cos(radians) * labelRadius).toFloat() +
                dp(2.1f)
            canvas.drawText(
                if (angle > 0) "+$angle" else angle.toString(),
                labelX,
                labelY,
                textPaint
            )
        }

        canvas.save()
        canvas.rotate(-levelRollDegrees.coerceIn(-35f, 35f), cx, cy)
        val pitchOffset = (pitchDegrees.coerceIn(-12f, 12f) / 12f) * radius * 0.28f
        linePaint.color = Color.argb(82, 229, 234, 228)
        linePaint.strokeWidth = dp(0.75f)
        for (pitchMark in -10..10 step 5) {
            val ladderY = cy + pitchOffset - (pitchMark / 10f) * radius * 0.34f
            val halfWidth = if (pitchMark == 0) radius * 0.52f else radius * 0.25f
            canvas.drawLine(
                cx - halfWidth,
                ladderY,
                cx - dp(7f),
                ladderY,
                linePaint
            )
            canvas.drawLine(
                cx + dp(7f),
                ladderY,
                cx + halfWidth,
                ladderY,
                linePaint
            )
        }
        linePaint.color = accent
        linePaint.strokeWidth = dp(2.1f)
        canvas.drawLine(
            cx - radius * 0.74f,
            cy + pitchOffset,
            cx - dp(12f),
            cy + pitchOffset,
            linePaint
        )
        canvas.drawLine(
            cx + dp(12f),
            cy + pitchOffset,
            cx + radius * 0.74f,
            cy + pitchOffset,
            linePaint
        )
        linePaint.strokeWidth = dp(1.3f)
        canvas.drawLine(
            cx,
            cy + pitchOffset - dp(8f),
            cx,
            cy + pitchOffset + dp(8f),
            linePaint
        )
        canvas.restore()

        val bubbleX = cx + (levelRollDegrees.coerceIn(-12f, 12f) / 12f) * radius * 0.42f
        val bubbleY = cy + (pitchDegrees.coerceIn(-12f, 12f) / 12f) * radius * 0.42f
        fillPaint.color = Color.argb(95, 0, 0, 0)
        canvas.drawCircle(bubbleX + dp(1.3f), bubbleY + dp(1.8f), dp(8.2f), fillPaint)
        linePaint.color = accent
        linePaint.strokeWidth = dp(1.3f)
        canvas.drawCircle(bubbleX, bubbleY, dp(8f), linePaint)
        fillPaint.shader = RadialGradient(
            bubbleX - dp(2.2f),
            bubbleY - dp(2.6f),
            dp(9f),
            intArrayOf(
                Color.argb(245, 246, 250, 235),
                accent,
                Color.argb(210, 39, 47, 34)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(bubbleX, bubbleY, dp(6.4f), fillPaint)
        fillPaint.shader = null
        fillPaint.color = Color.rgb(10, 12, 11)
        canvas.drawCircle(bubbleX, bubbleY, dp(1.7f), fillPaint)
        fillPaint.color = Color.argb(175, 255, 255, 255)
        canvas.drawCircle(bubbleX - dp(2f), bubbleY - dp(2.3f), dp(1.15f), fillPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.argb(205, 239, 243, 238)
        textPaint.textSize = sp(8f)
        textPaint.letterSpacing = 0.04f
        val readout = RectF(
            cx - radius * 0.56f,
            cy + radius * 0.67f,
            cx + radius * 0.56f,
            cy + radius * 0.88f
        )
        fillPaint.color = Color.argb(210, 4, 7, 5)
        canvas.drawRoundRect(readout, dp(2.5f), dp(2.5f), fillPaint)
        linePaint.color = Color.argb(125, 199, 205, 198)
        linePaint.strokeWidth = dp(0.75f)
        canvas.drawRoundRect(readout, dp(2.5f), dp(2.5f), linePaint)
        canvas.drawText(
            String.format(Locale.US, "%+.1f°  /  %+.1f°", levelRollDegrees, pitchDegrees),
            cx,
            readout.centerY() + dp(3f),
            textPaint
        )

        levelPath.reset()
        levelPath.moveTo(cx, cy - radius - dp(4f))
        levelPath.lineTo(cx - dp(5f), cy - radius + dp(5f))
        levelPath.lineTo(cx + dp(5f), cy - radius + dp(5f))
        levelPath.close()
        fillPaint.color = accent
        canvas.drawPath(levelPath, fillPaint)

        canvas.restore()
    }

    private fun drawHistogram(canvas: Canvas) {
        val layout = XpanInfoColumnLayoutModel.calculate(
            containerWidth = width,
            containerHeight = height,
            density = resources.displayMetrics.density,
            displayRotation = displayRotation
        )
        val column = layout.column
        val rect = RectF(
            column.left.toFloat(),
            column.histogramTop.toFloat(),
            column.right.toFloat(),
            column.histogramBottom.toFloat()
        )
        canvas.save()
        applyFixedLandscapeTransform(canvas, layout.rotationDegrees)
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.left,
            rect.bottom,
            intArrayOf(
                Color.rgb(69, 73, 69),
                Color.rgb(25, 28, 27),
                Color.rgb(8, 10, 9)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, dp(9f), dp(9f), fillPaint)
        fillPaint.shader = null
        linePaint.color = Color.argb(150, 190, 196, 188)
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(9f), dp(9f), linePaint)

        val screen = RectF(
            rect.left + dp(5f),
            rect.top + dp(5f),
            rect.right - dp(5f),
            rect.bottom - dp(5f)
        )
        fillPaint.shader = LinearGradient(
            screen.left,
            screen.top,
            screen.right,
            screen.bottom,
            intArrayOf(
                instrumentTheme.screenTop,
                instrumentTheme.screenMiddle,
                instrumentTheme.screenBottom
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(screen, dp(4f), dp(4f), fillPaint)
        fillPaint.shader = null
        linePaint.color = instrumentTheme.inkWithAlpha(210)
        linePaint.strokeWidth = dp(0.9f)
        canvas.drawRoundRect(screen, dp(4f), dp(4f), linePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = instrumentTheme.ink
        textPaint.textSize = sp(7.5f)
        textPaint.letterSpacing = 0.16f
        canvas.drawText("LUMA SCOPE", screen.left + dp(9f), screen.top + dp(15f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = sp(6.5f)
        textPaint.letterSpacing = 0.08f
        canvas.drawText("Y  0—255", screen.right - dp(9f), screen.top + dp(15f), textPaint)

        val graph = RectF(
            screen.left + dp(9f),
            screen.top + dp(23f),
            screen.right - dp(9f),
            screen.bottom - dp(10f)
        )
        linePaint.strokeCap = Paint.Cap.SQUARE
        linePaint.strokeWidth = dp(0.55f)
        linePaint.color = instrumentTheme.inkWithAlpha(48)
        for (division in 0..8) {
            val x = graph.left + graph.width() * division / 8f
            canvas.drawLine(x, graph.top, x, graph.bottom, linePaint)
        }
        for (division in 0..4) {
            val y = graph.top + graph.height() * division / 4f
            canvas.drawLine(graph.left, y, graph.right, y, linePaint)
        }
        linePaint.color = instrumentTheme.inkWithAlpha(105)
        linePaint.strokeWidth = dp(0.8f)
        canvas.drawLine(graph.centerX(), graph.top, graph.centerX(), graph.bottom, linePaint)
        canvas.drawLine(graph.left, graph.centerY(), graph.right, graph.centerY(), linePaint)

        linePaint.color = instrumentTheme.inkWithAlpha(155)
        linePaint.strokeWidth = dp(0.8f)
        for (division in 0..16) {
            val x = graph.left + graph.width() * division / 16f
            val tickHeight = if (division % 4 == 0) dp(4f) else dp(2.2f)
            canvas.drawLine(x, graph.bottom - tickHeight, x, graph.bottom, linePaint)
        }

        histogramPath.reset()
        histogramPath.moveTo(graph.left, graph.bottom)
        histogramTracePath.reset()
        val values = histogram
        for (index in values.indices) {
            val px = graph.left + graph.width() * index / (values.size - 1).coerceAtLeast(1)
            val py = graph.bottom - graph.height() * values[index].coerceIn(0f, 1f)
            histogramPath.lineTo(px, py)
            if (index == 0) {
                histogramTracePath.moveTo(px, py)
            } else {
                histogramTracePath.lineTo(px, py)
            }
        }
        histogramPath.lineTo(graph.right, graph.bottom)
        histogramPath.close()
        fillPaint.color = instrumentTheme.inkWithAlpha(72)
        canvas.drawPath(histogramPath, fillPaint)
        linePaint.color = instrumentTheme.ink
        linePaint.strokeWidth = dp(1.55f)
        canvas.drawPath(histogramTracePath, linePaint)

        val cornerLength = dp(7f)
        linePaint.color = instrumentTheme.inkWithAlpha(190)
        linePaint.strokeWidth = dp(1f)
        canvas.drawLine(graph.left, graph.top, graph.left + cornerLength, graph.top, linePaint)
        canvas.drawLine(graph.left, graph.top, graph.left, graph.top + cornerLength, linePaint)
        canvas.drawLine(graph.right, graph.top, graph.right - cornerLength, graph.top, linePaint)
        canvas.drawLine(graph.right, graph.top, graph.right, graph.top + cornerLength, linePaint)
        linePaint.strokeCap = Paint.Cap.ROUND
        canvas.restore()
    }

    private fun applyFixedLandscapeTransform(canvas: Canvas, rotationDegrees: Int) {
        when (rotationDegrees) {
            90 -> {
                canvas.translate(width.toFloat(), 0f)
                canvas.rotate(90f)
            }
            -90 -> {
                canvas.translate(0f, height.toFloat())
                canvas.rotate(-90f)
            }
            180, -180 -> {
                canvas.translate(width.toFloat(), height.toFloat())
                canvas.rotate(180f)
            }
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
