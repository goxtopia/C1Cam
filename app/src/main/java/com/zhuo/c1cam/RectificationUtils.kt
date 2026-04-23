package com.zhuo.c1cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max

object RectificationUtils {

    fun getRectifiedDimensions(sourceBitmap: Bitmap, normalizedPoints: List<PointF>, targetAspectRatio: Float, maxDimension: Int = 0): IntArray {
        if (normalizedPoints.size != 4) return intArrayOf(sourceBitmap.width, sourceBitmap.height)

        val w = sourceBitmap.width.toFloat()
        val h = sourceBitmap.height.toFloat()

        val p0 = PointF(normalizedPoints[0].x * w, normalizedPoints[0].y * h)
        val p1 = PointF(normalizedPoints[1].x * w, normalizedPoints[1].y * h)
        val p2 = PointF(normalizedPoints[2].x * w, normalizedPoints[2].y * h)
        val p3 = PointF(normalizedPoints[3].x * w, normalizedPoints[3].y * h)

        val w1 = hypot((p1.x - p0.x).toDouble(), (p1.y - p0.y).toDouble())
        val w2 = hypot((p2.x - p3.x).toDouble(), (p2.y - p3.y).toDouble())
        val h1 = hypot((p3.x - p0.x).toDouble(), (p3.y - p0.y).toDouble())
        val h2 = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())

        val maxWidth = max(w1, w2).toFloat()
        val maxHeight = max(h1, h2).toFloat()

        val sourceIsLandscape = maxWidth > maxHeight
        val targetIsLandscape = targetAspectRatio > 1.0f

        val finalAspectRatio = if (targetAspectRatio == 0f) {
             maxWidth / maxHeight
        } else if ((sourceIsLandscape && targetIsLandscape) || (!sourceIsLandscape && !targetIsLandscape)) {
            targetAspectRatio
        } else {
            1.0f / targetAspectRatio
        }

        val dstWidth: Float
        val dstHeight: Float

        if (finalAspectRatio >= 1.0f) {
            dstWidth = maxWidth
            dstHeight = dstWidth / finalAspectRatio
        } else {
            dstHeight = maxHeight
            dstWidth = dstHeight * finalAspectRatio
        }

        var dstW = dstWidth.toInt().coerceAtLeast(1)
        var dstH = dstHeight.toInt().coerceAtLeast(1)

        if (maxDimension > 0 && (dstW > maxDimension || dstH > maxDimension)) {
            val scale = maxDimension.toFloat() / max(dstW, dstH)
            dstW = (dstW * scale).toInt().coerceAtLeast(1)
            dstH = (dstH * scale).toInt().coerceAtLeast(1)
        }

        return intArrayOf(dstW, dstH)
    }

    fun rectifyToBitmap(sourceBitmap: Bitmap, destBitmap: Bitmap, normalizedPoints: List<PointF>) {
        if (normalizedPoints.size != 4) {
            // Fallback: just scale source to dest
            val canvas = Canvas(destBitmap)
            val matrix = Matrix()
            matrix.postScale(destBitmap.width.toFloat() / sourceBitmap.width, destBitmap.height.toFloat() / sourceBitmap.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.isFilterBitmap = true
            canvas.drawBitmap(sourceBitmap, matrix, paint)
            return
        }

        val w = sourceBitmap.width.toFloat()
        val h = sourceBitmap.height.toFloat()

        val p0 = PointF(normalizedPoints[0].x * w, normalizedPoints[0].y * h)
        val p1 = PointF(normalizedPoints[1].x * w, normalizedPoints[1].y * h)
        val p2 = PointF(normalizedPoints[2].x * w, normalizedPoints[2].y * h)
        val p3 = PointF(normalizedPoints[3].x * w, normalizedPoints[3].y * h)

        val srcPoints = floatArrayOf(
            p0.x, p0.y,
            p1.x, p1.y,
            p2.x, p2.y,
            p3.x, p3.y
        )

        val dstW = destBitmap.width.toFloat()
        val dstH = destBitmap.height.toFloat()

        val dstPoints = floatArrayOf(
            0f, 0f,
            dstW, 0f,
            dstW, dstH,
            0f, dstH
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val canvas = Canvas(destBitmap)
        // Clear bitmap? Usually not needed if we draw over it, but perspective warp might leave edges?
        // setPolyToPoly usually maps the quad to fill the rect.
        // But for safety/correctness if reusing:
        // destBitmap.eraseColor(0) // Optional, might be slow.

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.isFilterBitmap = true

        canvas.drawBitmap(sourceBitmap, matrix, paint)
    }

    fun rectifyToBitmapRealtime(sourceBitmap: Bitmap, destBitmap: Bitmap, normalizedPoints: List<PointF>) {
        if (normalizedPoints.size != 4 || !GlRectificationUtils.rectifyToBitmap(sourceBitmap, destBitmap, normalizedPoints)) {
            rectifyToBitmap(sourceBitmap, destBitmap, normalizedPoints)
        }
    }

    fun rectifyBitmap(sourceBitmap: Bitmap, normalizedPoints: List<PointF>, targetAspectRatio: Float, maxDimension: Int = 0): Bitmap {
        val dims = getRectifiedDimensions(sourceBitmap, normalizedPoints, targetAspectRatio, maxDimension)
        val dstW = dims[0]
        val dstH = dims[1]

        val resultBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        rectifyToBitmap(sourceBitmap, resultBitmap, normalizedPoints)
        return resultBitmap
    }
}
