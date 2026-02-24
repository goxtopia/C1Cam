package com.zhuo.c1cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max

object RectificationUtils {

    fun rectifyBitmap(sourceBitmap: Bitmap, normalizedPoints: List<PointF>, targetAspectRatio: Float): Bitmap {
        if (normalizedPoints.size != 4) return sourceBitmap

        val w = sourceBitmap.width.toFloat()
        val h = sourceBitmap.height.toFloat()

        // Convert normalized points to pixel coordinates
        // Points are ordered TL, TR, BR, BL
        val p0 = PointF(normalizedPoints[0].x * w, normalizedPoints[0].y * h)
        val p1 = PointF(normalizedPoints[1].x * w, normalizedPoints[1].y * h)
        val p2 = PointF(normalizedPoints[2].x * w, normalizedPoints[2].y * h)
        val p3 = PointF(normalizedPoints[3].x * w, normalizedPoints[3].y * h)

        // Calculate dimensions of the source quadrilateral
        val w1 = hypot((p1.x - p0.x).toDouble(), (p1.y - p0.y).toDouble())
        val w2 = hypot((p2.x - p3.x).toDouble(), (p2.y - p3.y).toDouble())
        val h1 = hypot((p3.x - p0.x).toDouble(), (p3.y - p0.y).toDouble())
        val h2 = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())

        val maxWidth = max(w1, w2).toFloat()
        val maxHeight = max(h1, h2).toFloat()

        // Determine orientation
        val sourceIsLandscape = maxWidth > maxHeight
        val targetIsLandscape = targetAspectRatio > 1.0f

        // Adjust target aspect ratio orientation to match source
        val finalAspectRatio = if (targetAspectRatio == 0f) {
             maxWidth / maxHeight // Keep original if 0
        } else if ((sourceIsLandscape && targetIsLandscape) || (!sourceIsLandscape && !targetIsLandscape)) {
            targetAspectRatio
        } else {
            1.0f / targetAspectRatio
        }

        // Calculate destination dimensions
        // We calculate based on the larger dimension to preserve detail
        // If finalAspectRatio > 1 (Landscape), width is dominant.
        // If finalAspectRatio < 1 (Portrait), height is dominant.

        // However, we want to fit the source quad into a rectangle of aspect ratio R.
        // We can choose the scale. Let's try to match the source area or max dimension.
        // Let's use maxWidth for width if landscape, or maxHeight for height if portrait.

        val dstWidth: Float
        val dstHeight: Float

        if (finalAspectRatio >= 1.0f) {
            dstWidth = maxWidth
            dstHeight = dstWidth / finalAspectRatio
        } else {
            dstHeight = maxHeight
            dstWidth = dstHeight * finalAspectRatio
        }

        val dstW = dstWidth.toInt().coerceAtLeast(1)
        val dstH = dstHeight.toInt().coerceAtLeast(1)

        // Destination points (Rectangle at 0,0)
        val dstPoints = floatArrayOf(
            0f, 0f,
            dstW.toFloat(), 0f,
            dstW.toFloat(), dstH.toFloat(),
            0f, dstH.toFloat()
        )

        // Source points mapping to destination points
        // p0 -> (0,0)
        // p1 -> (w,0)
        // p2 -> (w,h)
        // p3 -> (0,h)
        val srcPoints = floatArrayOf(
            p0.x, p0.y,
            p1.x, p1.y,
            p2.x, p2.y,
            p3.x, p3.y
        )

        val matrix = Matrix()
        // setPolyToPoly(src, srcIndex, dst, dstIndex, pointCount)
        // Map srcPoints to dstPoints
        matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)

        val resultBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.isFilterBitmap = true

        // Draw the source bitmap transformed by the matrix onto the new bitmap
        canvas.drawBitmap(sourceBitmap, matrix, paint)

        return resultBitmap
    }
}
