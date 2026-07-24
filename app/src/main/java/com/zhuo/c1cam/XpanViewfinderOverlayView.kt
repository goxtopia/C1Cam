package com.zhuo.c1cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class XpanViewfinderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(7f)
        val frame = RectF(inset, inset, width - inset, height - inset)

        drawEdgeVignette(canvas)

        paint.color = Color.argb(75, 246, 247, 248)
        paint.strokeWidth = dp(0.7f)
        canvas.drawRect(frame, paint)

        paint.color = Color.argb(42, 246, 247, 248)
        paint.strokeWidth = dp(0.6f)
        val thirdX1 = frame.left + frame.width() / 3f
        val thirdX2 = frame.left + frame.width() * 2f / 3f
        val thirdY1 = frame.top + frame.height() / 3f
        val thirdY2 = frame.top + frame.height() * 2f / 3f
        canvas.drawLine(thirdX1, frame.top, thirdX1, frame.bottom, paint)
        canvas.drawLine(thirdX2, frame.top, thirdX2, frame.bottom, paint)
        canvas.drawLine(frame.left, thirdY1, frame.right, thirdY1, paint)
        canvas.drawLine(frame.left, thirdY2, frame.right, thirdY2, paint)

        paint.color = Color.argb(190, 214, 255, 66)
        paint.strokeWidth = dp(1.1f)
        val mark = dp(7f)
        drawCorner(canvas, frame.left, frame.top, mark, mark)
        drawCorner(canvas, frame.right, frame.top, -mark, mark)
        drawCorner(canvas, frame.right, frame.bottom, -mark, -mark)
        drawCorner(canvas, frame.left, frame.bottom, mark, -mark)

        paint.color = Color.argb(145, 214, 255, 66)
        val cx = frame.centerX()
        val cy = frame.centerY()
        canvas.drawLine(cx - dp(8f), cy, cx - dp(2.5f), cy, paint)
        canvas.drawLine(cx + dp(2.5f), cy, cx + dp(8f), cy, paint)
        canvas.drawLine(cx, cy - dp(8f), cx, cy - dp(2.5f), paint)
        canvas.drawLine(cx, cy + dp(2.5f), cx, cy + dp(8f), paint)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + dx, y, paint)
        canvas.drawLine(x, y, x, y + dy, paint)
    }

    private fun drawEdgeVignette(canvas: Canvas) {
        val edge = minOf(dp(13f), minOf(width, height) * 0.10f)
        if (edge <= 0f) return
        val dark = Color.argb(105, 0, 0, 0)
        val clear = Color.TRANSPARENT

        vignettePaint.shader = LinearGradient(
            0f,
            0f,
            edge,
            0f,
            dark,
            clear,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, edge, height.toFloat(), vignettePaint)

        vignettePaint.shader = LinearGradient(
            width - edge,
            0f,
            width.toFloat(),
            0f,
            clear,
            dark,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(width - edge, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        vignettePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            edge,
            dark,
            clear,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), edge, vignettePaint)

        vignettePaint.shader = LinearGradient(
            0f,
            height - edge,
            0f,
            height.toFloat(),
            clear,
            dark,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, height - edge, width.toFloat(), height.toFloat(), vignettePaint)
        vignettePaint.shader = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
