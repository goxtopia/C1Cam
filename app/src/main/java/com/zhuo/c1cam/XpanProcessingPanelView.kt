package com.zhuo.c1cam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.min

class XpanProcessingPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(
            "monospace",
            android.graphics.Typeface.BOLD
        )
    }
    private val segmentPath = Path()
    private var status = CaptureProcessingStatus()
    private var iso: Int? = null
    private var exposureTimeNs: Long? = null
    private var needlePosition = IDLE_NEEDLE_POSITION
    private var needleAnimator: ValueAnimator? = null

    fun updateStatus(newStatus: CaptureProcessingStatus) {
        status = newStatus
        updateAccessibilityDescription()
        val targetPosition = when (newStatus.foregroundStage) {
            CaptureProcessingStage.EXPOSING -> 0f
            CaptureProcessingStage.PROCESSING -> 0.5f
            CaptureProcessingStage.SAVING -> 1f
            null -> IDLE_NEEDLE_POSITION
        }
        needleAnimator?.cancel()
        needleAnimator = ValueAnimator.ofFloat(needlePosition, targetPosition).apply {
            duration = 190L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                needlePosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    fun updateTelemetry(telemetry: XpanTelemetry) {
        iso = telemetry.iso
        exposureTimeNs = telemetry.exposureTimeNs
        updateAccessibilityDescription()
        postInvalidateOnAnimation()
    }

    private fun updateAccessibilityDescription() {
        contentDescription = buildString {
            append("ISO ")
            append(iso ?: "unknown")
            append(", shutter ")
            append(formatShutter(exposureTimeNs))
            append(", ")
            append(status.pendingCount)
            append(" captures pending, ")
            append(
                when (status.foregroundStage) {
                    CaptureProcessingStage.EXPOSING -> "exposing"
                    CaptureProcessingStage.PROCESSING -> "processing"
                    CaptureProcessingStage.SAVING -> "saving"
                    null -> "standby"
                }
            )
        }
    }

    override fun onDetachedFromWindow() {
        needleAnimator?.cancel()
        needleAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val unit = min(width / BASE_WIDTH, height / BASE_HEIGHT)
        drawEnclosure(canvas, unit)

        val lcd = RectF(
            8f * unit,
            8f * unit,
            width - 8f * unit,
            height - 8f * unit
        )
        fillPaint.shader = LinearGradient(
            lcd.left,
            lcd.top,
            lcd.right,
            lcd.bottom,
            intArrayOf(
                Color.rgb(187, 194, 142),
                Color.rgb(157, 166, 114),
                Color.rgb(134, 144, 95)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(lcd, 2.5f * unit, 2.5f * unit, fillPaint)
        fillPaint.shader = null
        linePaint.color = Color.argb(220, 28, 34, 25)
        linePaint.strokeWidth = 1.2f * unit
        canvas.drawRoundRect(lcd, 2.5f * unit, 2.5f * unit, linePaint)

        drawExposureReadout(canvas, lcd, unit)
        drawQueueReadout(canvas, lcd, unit)
        drawStageGauge(canvas, lcd, unit)
    }

    private fun drawEnclosure(canvas: Canvas, unit: Float) {
        val outer = RectF(0f, 0f, width.toFloat(), height.toFloat())
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                Color.rgb(67, 71, 67),
                Color.rgb(27, 30, 29),
                Color.rgb(8, 10, 9)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(outer, 7f * unit, 7f * unit, fillPaint)
        fillPaint.shader = null

        linePaint.color = Color.argb(190, 192, 197, 190)
        linePaint.strokeWidth = 0.8f * unit
        canvas.drawRoundRect(
            RectF(0.7f * unit, 0.7f * unit, width - 0.7f * unit, height - 0.7f * unit),
            6.3f * unit,
            6.3f * unit,
            linePaint
        )

        val positions = arrayOf(
            4.5f * unit to 4.5f * unit,
            width - 4.5f * unit to 4.5f * unit,
            4.5f * unit to height - 4.5f * unit,
            width - 4.5f * unit to height - 4.5f * unit
        )
        positions.forEach { (x, y) ->
            fillPaint.color = Color.rgb(9, 11, 10)
            canvas.drawCircle(x, y, 1.7f * unit, fillPaint)
            linePaint.color = Color.argb(145, 214, 218, 212)
            linePaint.strokeWidth = 0.55f * unit
            canvas.drawLine(x - unit, y, x + unit, y, linePaint)
        }
    }

    private fun drawExposureReadout(canvas: Canvas, lcd: RectF, unit: Float) {
        val ink = LCD_INK
        val center = lcd.centerX()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = ink
        textPaint.letterSpacing = 0.12f
        textPaint.textSize = 6.8f * unit
        canvas.drawText("ISO", lcd.left + 6f * unit, lcd.top + 7f * unit, textPaint)
        canvas.drawText("SS", center + 4f * unit, lcd.top + 7f * unit, textPaint)

        textPaint.letterSpacing = 0f
        textPaint.textSize = 16f * unit
        canvas.drawText(
            iso?.toString() ?: "—",
            lcd.left + 6f * unit,
            lcd.top + 23f * unit,
            textPaint
        )
        textPaint.textSize = 13f * unit
        canvas.drawText(
            formatShutter(exposureTimeNs),
            center + 4f * unit,
            lcd.top + 23f * unit,
            textPaint
        )

        linePaint.color = Color.argb(100, 35, 48, 31)
        linePaint.strokeWidth = 0.65f * unit
        canvas.drawLine(
            lcd.left + 5f * unit,
            lcd.top + 30f * unit,
            lcd.right - 5f * unit,
            lcd.top + 30f * unit,
            linePaint
        )
    }

    private fun drawQueueReadout(canvas: Canvas, lcd: RectF, unit: Float) {
        val ghostInk = Color.argb(35, 35, 48, 31)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = LCD_INK
        textPaint.textSize = 6.1f * unit
        textPaint.letterSpacing = 0.14f
        canvas.drawText("BUFFER", lcd.left + 6f * unit, lcd.top + 36f * unit, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 5.6f * unit
        textPaint.letterSpacing = 0.06f
        val frameLabel = if (status.pendingCount == 0) {
            "READY"
        } else {
            "#${status.foregroundCaptureId?.rem(1000)?.toString()?.padStart(3, '0') ?: "---"}"
        }
        canvas.drawText(frameLabel, lcd.right - 6f * unit, lcd.top + 36f * unit, textPaint)

        val digitHeight = 20f * unit
        val digitWidth = 11f * unit
        val digitGap = 3f * unit
        val right = lcd.right - 7f * unit
        val top = lcd.top + 39f * unit
        drawSevenSegmentDigit(
            canvas,
            8,
            right - digitWidth * 2f - digitGap,
            top,
            digitWidth,
            digitHeight,
            ghostInk
        )
        drawSevenSegmentDigit(
            canvas,
            8,
            right - digitWidth,
            top,
            digitWidth,
            digitHeight,
            ghostInk
        )
        val count = status.pendingCount.coerceIn(0, 99)
        drawSevenSegmentDigit(
            canvas,
            count / 10,
            right - digitWidth * 2f - digitGap,
            top,
            digitWidth,
            digitHeight,
            LCD_INK
        )
        drawSevenSegmentDigit(
            canvas,
            count % 10,
            right - digitWidth,
            top,
            digitWidth,
            digitHeight,
            LCD_INK
        )

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = LCD_INK
        textPaint.textSize = 6f * unit
        textPaint.letterSpacing = 0.11f
        canvas.drawText("PENDING", lcd.left + 6f * unit, top + 13f * unit, textPaint)

        linePaint.color = Color.argb(100, 35, 48, 31)
        linePaint.strokeWidth = 0.65f * unit
        canvas.drawLine(
            lcd.left + 5f * unit,
            lcd.top + 61f * unit,
            lcd.right - 5f * unit,
            lcd.top + 61f * unit,
            linePaint
        )
    }

    private fun drawStageGauge(canvas: Canvas, lcd: RectF, unit: Float) {
        val baselineY = lcd.bottom - 13f * unit
        val left = lcd.left + 12f * unit
        val right = lcd.right - 12f * unit
        val tickXs = floatArrayOf(left, (left + right) / 2f, right)

        val stageText = when (status.foregroundStage) {
            CaptureProcessingStage.EXPOSING -> "EXPOSING"
            CaptureProcessingStage.PROCESSING -> "PROCESSING"
            CaptureProcessingStage.SAVING -> "SAVING"
            null -> "STANDBY"
        }
        textPaint.color = LCD_INK
        textPaint.textSize = 5.4f * unit
        textPaint.letterSpacing = 0.1f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(stageText, lcd.centerX(), lcd.top + 70f * unit, textPaint)

        linePaint.color = Color.argb(180, 35, 48, 31)
        linePaint.strokeWidth = 0.8f * unit
        canvas.drawLine(left, baselineY, right, baselineY, linePaint)
        tickXs.forEach { x ->
            canvas.drawLine(
                x,
                baselineY - 3f * unit,
                x,
                baselineY + 2f * unit,
                linePaint
            )
        }

        textPaint.textSize = 4.7f * unit
        textPaint.letterSpacing = 0.03f
        canvas.drawText("EXP", tickXs[0], lcd.bottom - 3.5f * unit, textPaint)
        canvas.drawText("PROC", tickXs[1], lcd.bottom - 3.5f * unit, textPaint)
        canvas.drawText("SAVE", tickXs[2], lcd.bottom - 3.5f * unit, textPaint)

        val pivotX = (left + right) / 2f
        val pivotY = baselineY - 1.5f * unit
        val targetX = left + (right - left) * needlePosition.coerceIn(0f, 1f)
        val targetY = baselineY - 10f * unit
        linePaint.color = LCD_INK
        linePaint.strokeWidth = 1.5f * unit
        linePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(pivotX, pivotY, targetX, targetY, linePaint)
        fillPaint.color = LCD_INK
        canvas.drawCircle(pivotX, pivotY, 2.1f * unit, fillPaint)
        fillPaint.color = Color.rgb(154, 163, 111)
        canvas.drawCircle(pivotX, pivotY, 0.8f * unit, fillPaint)
        linePaint.strokeCap = Paint.Cap.SQUARE
    }

    private fun drawSevenSegmentDigit(
        canvas: Canvas,
        digit: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        color: Int
    ) {
        val activeSegments = DIGIT_SEGMENTS[digit.coerceIn(0, 9)]
        val thickness = width * 0.16f
        val halfHeight = height / 2f
        val segments = arrayOf(
            RectF(left + thickness, top, left + width - thickness, top + thickness),
            RectF(left + width - thickness, top + thickness, left + width, top + halfHeight - thickness / 2f),
            RectF(left + width - thickness, top + halfHeight + thickness / 2f, left + width, top + height - thickness),
            RectF(left + thickness, top + height - thickness, left + width - thickness, top + height),
            RectF(left, top + halfHeight + thickness / 2f, left + thickness, top + height - thickness),
            RectF(left, top + thickness, left + thickness, top + halfHeight - thickness / 2f),
            RectF(left + thickness, top + halfHeight - thickness / 2f, left + width - thickness, top + halfHeight + thickness / 2f)
        )
        fillPaint.color = color
        for (index in segments.indices) {
            if (activeSegments[index]) {
                drawSegment(canvas, segments[index], thickness * 0.35f)
            }
        }
    }

    private fun drawSegment(canvas: Canvas, rect: RectF, cut: Float) {
        segmentPath.reset()
        if (rect.width() > rect.height()) {
            segmentPath.moveTo(rect.left + cut, rect.top)
            segmentPath.lineTo(rect.right - cut, rect.top)
            segmentPath.lineTo(rect.right, rect.centerY())
            segmentPath.lineTo(rect.right - cut, rect.bottom)
            segmentPath.lineTo(rect.left + cut, rect.bottom)
            segmentPath.lineTo(rect.left, rect.centerY())
        } else {
            segmentPath.moveTo(rect.left, rect.top + cut)
            segmentPath.lineTo(rect.centerX(), rect.top)
            segmentPath.lineTo(rect.right, rect.top + cut)
            segmentPath.lineTo(rect.right, rect.bottom - cut)
            segmentPath.lineTo(rect.centerX(), rect.bottom)
            segmentPath.lineTo(rect.left, rect.bottom - cut)
        }
        segmentPath.close()
        canvas.drawPath(segmentPath, fillPaint)
    }

    private fun formatShutter(valueNs: Long?): String {
        if (valueNs == null || valueNs <= 0L) return "—"
        val seconds = valueNs / 1_000_000_000.0
        return if (seconds >= 0.8) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}"
        }
    }

    companion object {
        private const val BASE_WIDTH = 218f
        private const val BASE_HEIGHT = 112f
        private const val IDLE_NEEDLE_POSITION = 0f
        private val LCD_INK = Color.rgb(35, 48, 31)
        private val DIGIT_SEGMENTS = arrayOf(
            booleanArrayOf(true, true, true, true, true, true, false),
            booleanArrayOf(false, true, true, false, false, false, false),
            booleanArrayOf(true, true, false, true, true, false, true),
            booleanArrayOf(true, true, true, true, false, false, true),
            booleanArrayOf(false, true, true, false, false, true, true),
            booleanArrayOf(true, false, true, true, false, true, true),
            booleanArrayOf(true, false, true, true, true, true, true),
            booleanArrayOf(true, true, true, false, false, false, false),
            booleanArrayOf(true, true, true, true, true, true, true),
            booleanArrayOf(true, true, true, true, false, true, true)
        )
    }
}
