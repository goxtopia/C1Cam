package com.zhuo.c1cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val FOCUS_INDICATOR_DURATION_MS = 900L
    }

    var onDoubleTapListener: (() -> Unit)? = null
    var onSingleTapListener: ((x: Float, y: Float) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTapListener?.invoke()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!isEditMode) {
                onSingleTapListener?.invoke(e.x, e.y)
                return true
            }
            return false
        }
    })

    private val paintPoint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintLine = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val path = Path()
    private val focusIndicatorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // 4 points in view coordinates
    private val points = mutableListOf<PointF>()
    private var isInitialized = false
    private var selectedPointIndex = -1
    private val touchRadius = 50f
    private var focusIndicatorPoint: PointF? = null
    private var focusIndicatorStartMs: Long = 0L
    private var focusIndicatorColor: Int = Color.WHITE

    var isEditMode = false
        set(value) {
            field = value
            invalidate()
        }

    var isOverlayVisible = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (points.isEmpty()) {
                val margin = 100f
                points.add(PointF(margin, margin))
                points.add(PointF(w.toFloat() - margin, margin))
                points.add(PointF(w.toFloat() - margin, h.toFloat() - margin))
                points.add(PointF(margin, h.toFloat() - margin))
                isInitialized = true
                invalidate()
            } else if (oldw > 0 && oldh > 0) {
                val scaleX = w.toFloat() / oldw.toFloat()
                val scaleY = h.toFloat() / oldh.toFloat()
                points.forEach {
                    it.x *= scaleX
                    it.y *= scaleY
                }
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isOverlayVisible && isInitialized && points.size == 4) {
            path.rewind()
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until 4) {
                path.lineTo(points[i].x, points[i].y)
            }
            path.close()

            canvas.drawPath(path, paintLine)

            if (isEditMode) {
                paintPoint.color = Color.RED
            } else {
                paintPoint.color = Color.YELLOW
            }

            for (p in points) {
                canvas.drawCircle(p.x, p.y, 20f, paintPoint)
            }
        }

        drawFocusIndicator(canvas)
    }

    private fun drawFocusIndicator(canvas: Canvas) {
        val point = focusIndicatorPoint ?: return
        val elapsed = SystemClock.uptimeMillis() - focusIndicatorStartMs
        if (elapsed >= FOCUS_INDICATOR_DURATION_MS) {
            focusIndicatorPoint = null
            return
        }

        val progress = elapsed.toFloat() / FOCUS_INDICATOR_DURATION_MS
        val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
        val radius = 48f - progress * 12f
        val halfCross = 20f - progress * 4f
        val gap = 12f

        focusIndicatorPaint.alpha = alpha
        focusIndicatorPaint.color = focusIndicatorColor
        canvas.drawCircle(point.x, point.y, radius, focusIndicatorPaint)
        canvas.drawLine(point.x - gap - halfCross, point.y, point.x - gap, point.y, focusIndicatorPaint)
        canvas.drawLine(point.x + gap, point.y, point.x + gap + halfCross, point.y, focusIndicatorPaint)
        canvas.drawLine(point.x, point.y - gap - halfCross, point.x, point.y - gap, focusIndicatorPaint)
        canvas.drawLine(point.x, point.y + gap, point.x, point.y + gap + halfCross, focusIndicatorPaint)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }

        if (!isOverlayVisible) return false

        if (!isEditMode) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                var bestDist = Float.MAX_VALUE
                var bestIndex = -1
                for (i in points.indices) {
                    val dx = event.x - points[i].x
                    val dy = event.y - points[i].y
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    if (dist < touchRadius * 2 && dist < bestDist) {
                        bestDist = dist
                        bestIndex = i
                    }
                }
                if (bestIndex != -1) {
                    selectedPointIndex = bestIndex
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectedPointIndex != -1) {
                    val x = event.x.coerceIn(0f, width.toFloat())
                    val y = event.y.coerceIn(0f, height.toFloat())
                    points[selectedPointIndex].set(x, y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedPointIndex = -1
                return true
            }
        }
        return false
    }

    @Synchronized
    fun getNormalizedPoints(): List<PointF> {
        if (!isInitialized || width == 0 || height == 0) return emptyList()
        // Return a copy to avoid modification
        return points.map { PointF(it.x / width, it.y / height) }
    }

    fun showFocusIndicator(x: Float, y: Float, color: Int = Color.WHITE) {
        focusIndicatorPoint = PointF(
            x.coerceIn(0f, width.toFloat().coerceAtLeast(0f)),
            y.coerceIn(0f, height.toFloat().coerceAtLeast(0f))
        )
        focusIndicatorColor = color
        focusIndicatorStartMs = SystemClock.uptimeMillis()
        invalidate()
        postInvalidateOnAnimation()
    }

    fun setNormalizedPoints(normalizedPoints: List<PointF>) {
        if (normalizedPoints.size != 4) return

        // Store them to apply when size is ready
        post {
            if (width > 0 && height > 0) {
                points.clear()
                points.addAll(normalizedPoints.map { PointF(it.x * width, it.y * height) })
                isInitialized = true
                invalidate()
            }
        }
    }
}
