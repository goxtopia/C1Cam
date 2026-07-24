package com.zhuo.c1cam

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

class InstrumentScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val divisions: Int
    private val centerAccent: Boolean
    private val focalDetents: Boolean
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val framePath = Path()

    init {
        val values = context.obtainStyledAttributes(attrs, R.styleable.InstrumentScaleView)
        divisions = values.getInt(R.styleable.InstrumentScaleView_scaleDivisions, 10)
            .coerceAtLeast(2)
        centerAccent = values.getBoolean(
            R.styleable.InstrumentScaleView_scaleCenterAccent,
            false
        )
        focalDetents = values.getBoolean(
            R.styleable.InstrumentScaleView_scaleFocalDetents,
            false
        )
        values.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val rect = RectF(dp(0.5f), cy - dp(10f), width - dp(0.5f), cy + dp(10f))
        if (rect.width() <= 0f) return

        val cut = dp(4f)
        framePath.reset()
        framePath.moveTo(rect.left + cut, rect.top)
        framePath.lineTo(rect.right - cut, rect.top)
        framePath.lineTo(rect.right, rect.top + cut)
        framePath.lineTo(rect.right, rect.bottom - cut)
        framePath.lineTo(rect.right - cut, rect.bottom)
        framePath.lineTo(rect.left + cut, rect.bottom)
        framePath.lineTo(rect.left, rect.bottom - cut)
        framePath.lineTo(rect.left, rect.top + cut)
        framePath.close()

        fillPaint.shader = LinearGradient(
            0f,
            rect.top,
            0f,
            rect.bottom,
            intArrayOf(
                Color.rgb(37, 43, 48),
                Color.rgb(12, 15, 17),
                Color.rgb(27, 32, 36)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(framePath, fillPaint)
        fillPaint.shader = null

        linePaint.color = Color.argb(112, 246, 247, 248)
        linePaint.strokeWidth = dp(0.8f)
        canvas.drawPath(framePath, linePaint)

        linePaint.color = Color.argb(48, 246, 247, 248)
        linePaint.strokeWidth = dp(0.6f)
        canvas.drawLine(rect.left + dp(7f), cy, rect.right - dp(7f), cy, linePaint)

        val usableLeft = rect.left + dp(9f)
        val usableRight = rect.right - dp(9f)
        for (index in 0..divisions) {
            val fraction = index.toFloat() / divisions
            val x = usableLeft + (usableRight - usableLeft) * fraction
            val isCenter = divisions % 2 == 0 && index == divisions / 2
            val isMajor = index == 0 || index == divisions || isCenter || index % 5 == 0
            val tickHeight = if (isMajor) dp(8f) else dp(4f)
            linePaint.color = when {
                centerAccent && isCenter -> Color.argb(210, 214, 255, 66)
                isMajor -> Color.argb(135, 246, 247, 248)
                else -> Color.argb(68, 246, 247, 248)
            }
            linePaint.strokeWidth = if (isMajor) dp(0.9f) else dp(0.65f)
            canvas.drawLine(x, cy - tickHeight / 2f, x, cy + tickHeight / 2f, linePaint)
        }

        if (focalDetents) {
            FocalLengthDetents.normalizedPositions().forEach { fraction ->
                val x = usableLeft + (usableRight - usableLeft) * fraction
                linePaint.color = Color.argb(225, 224, 226, 214)
                linePaint.strokeWidth = dp(1.25f)
                canvas.drawLine(x, cy - dp(6f), x, cy + dp(6f), linePaint)
                fillPaint.color = Color.argb(225, 224, 226, 214)
                canvas.drawCircle(x, cy - dp(7.2f), dp(1.25f), fillPaint)
            }
        }

        linePaint.color = Color.argb(74, 255, 255, 255)
        linePaint.strokeWidth = dp(0.7f)
        canvas.drawLine(
            rect.left + cut + dp(2f),
            rect.top + dp(2f),
            rect.right - cut - dp(2f),
            rect.top + dp(2f),
            linePaint
        )

        linePaint.color = Color.argb(120, 214, 255, 66)
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawLine(rect.left + dp(3f), cy - dp(4f), rect.left + dp(3f), cy + dp(4f), linePaint)
        canvas.drawLine(rect.right - dp(3f), cy - dp(4f), rect.right - dp(3f), cy + dp(4f), linePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
