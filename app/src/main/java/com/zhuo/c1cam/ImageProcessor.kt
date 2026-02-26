package com.zhuo.c1cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageProxy
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

class ImageProcessor(private val context: Context) {

    // Reusable bitmaps for preview to reduce GC pressure
    private var reusedUprightBitmap: Bitmap? = null
    private var reusedRectifiedBitmap: Bitmap? = null
    private var reusedScaledBitmap: Bitmap? = null

    // Ping-pong buffer for output to UI thread to prevent tearing/crashes
    private val outputBitmaps = arrayOfNulls<Bitmap>(2)
    private var outputIndex = 0

    // Cached objects
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private val identityLut by lazy {
        val size = 2
        val data = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    data[idx++] = r.toFloat() / (size - 1)
                    data[idx++] = g.toFloat() / (size - 1)
                    data[idx++] = b.toFloat() / (size - 1)
                }
            }
        }
        Lut3D(size, data)
    }

    fun processAndSaveImage(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?,
        isChromaDenoiseOn: Boolean,
        isCropModeOff: Boolean,
        isWdrMode: Boolean,
        focalLength: Int
    ) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Convert to Bitmap, applying Chroma Noise Reduction if enabled
        val bitmap = try {
            if (isChromaDenoiseOn) {
                ChromaNoiseReduction.process(imageProxy)
            } else {
                imageProxy.toBitmap()
            }
        } catch (e: Exception) {
            Log.e("ImageProcessor", "Error during image processing", e)
            imageProxy.toBitmap()
        } finally {
            imageProxy.close()
        }

        // Rotate to upright
        val uprightBitmap = if (rotationDegrees != 0) {
            val m = Matrix()
            m.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else {
            bitmap
        }

        val finalBitmap = if (isCropModeOff) {
            val croppedBitmap = cropForFocalLength(uprightBitmap, focalLength)
            currentLut?.let {
                LutUtils.applyLut(croppedBitmap, it, isWdrMode)
            } ?: (if (isWdrMode) LutUtils.applyLut(croppedBitmap, identityLut, isWdrMode) else croppedBitmap)
        } else {
            // Map points
            val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)

            // Rectify (Full resolution for capture - creating new bitmaps is acceptable here for quality/simplicity)
            val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio, maxDimension = 0)

            // Apply LUT if active
            currentLut?.let {
                LutUtils.applyLut(rectifiedBitmap, it, isWdrMode)
            } ?: (if (isWdrMode) LutUtils.applyLut(rectifiedBitmap, identityLut, isWdrMode) else rectifiedBitmap)
        }

        // Save
        saveBitmapToGallery(finalBitmap)
    }

    fun processForPreview(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?,
        isCropModeOff: Boolean,
        isWdrMode: Boolean,
        focalLength: Int
    ): Bitmap {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        // This allocation is hard to avoid without custom YUV converter
        val bitmap = imageProxy.toBitmap()

        // 1. Rotate to upright
        val w = bitmap.width
        val h = bitmap.height
        val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) h else w
        val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) w else h

        if (reusedUprightBitmap == null || reusedUprightBitmap!!.width != uprightW || reusedUprightBitmap!!.height != uprightH) {
            reusedUprightBitmap = Bitmap.createBitmap(uprightW, uprightH, Bitmap.Config.ARGB_8888)
        }
        val uprightBmp = reusedUprightBitmap!!

        matrix.reset()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
            if (rotationDegrees == 90) matrix.postTranslate(h.toFloat(), 0f)
            else if (rotationDegrees == 180) matrix.postTranslate(w.toFloat(), h.toFloat())
            else if (rotationDegrees == 270) matrix.postTranslate(0f, w.toFloat())
        }

        val canvasUpright = Canvas(uprightBmp)
        canvasUpright.drawBitmap(bitmap, matrix, paint)

        // Intermediate bitmap that holds the image before final output (LUT or copy)
        val intermediateBitmap: Bitmap

        if (isCropModeOff) {
            // Digital Zoom Crop & Scale
            // 2. Crop logic (focal length)
            val cropRect = getCropRectForFocalLength(uprightBmp.width, uprightBmp.height, focalLength)

            // 3. Scale down logic
            val maxDim = 512
            val scale = if (max(cropRect.width(), cropRect.height()) > maxDim) {
                maxDim.toFloat() / max(cropRect.width(), cropRect.height())
            } else 1f

            val scaledW = (cropRect.width() * scale).toInt().coerceAtLeast(1)
            val scaledH = (cropRect.height() * scale).toInt().coerceAtLeast(1)

            if (reusedScaledBitmap == null || reusedScaledBitmap!!.width != scaledW || reusedScaledBitmap!!.height != scaledH) {
                reusedScaledBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
            }
            val scaledBmp = reusedScaledBitmap!!

            // Draw Crop+Scale
            val canvasScaled = Canvas(scaledBmp)
            val srcRect = android.graphics.Rect(cropRect.left.toInt(), cropRect.top.toInt(), cropRect.right.toInt(), cropRect.bottom.toInt())
            val dstRect = android.graphics.Rect(0, 0, scaledW, scaledH)
            canvasScaled.drawBitmap(uprightBmp, srcRect, dstRect, paint)

            intermediateBitmap = scaledBmp

        } else {
            // Map points and rectify
            val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBmp.width, uprightBmp.height, viewW, viewH)

            // Calculate target dimensions
            val dims = RectificationUtils.getRectifiedDimensions(uprightBmp, mappedPoints, targetAspectRatio, maxDimension = 512)
            val dstW = dims[0]
            val dstH = dims[1]

            if (reusedRectifiedBitmap == null || reusedRectifiedBitmap!!.width != dstW || reusedRectifiedBitmap!!.height != dstH) {
                reusedRectifiedBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            }
            val rectBmp = reusedRectifiedBitmap!!

            // Rectify
            RectificationUtils.rectifyToBitmap(uprightBmp, rectBmp, mappedPoints)

            intermediateBitmap = rectBmp
        }

        // Prepare Output Bitmap (Ping-Pong)
        val outW = intermediateBitmap.width
        val outH = intermediateBitmap.height

        if (outputBitmaps[outputIndex] == null || outputBitmaps[outputIndex]!!.width != outW || outputBitmaps[outputIndex]!!.height != outH) {
            outputBitmaps[outputIndex] = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        }
        val outputBmp = outputBitmaps[outputIndex]!!

        // Apply LUT or Copy to Output
        if (currentLut != null) {
            LutUtils.applyLut(intermediateBitmap, currentLut, isWdrMode, outputBmp)
        } else if (isWdrMode) {
            // Apply WDR using Identity LUT
            LutUtils.applyLut(intermediateBitmap, identityLut, isWdrMode, outputBmp)
        } else {
            // Just copy
            val c = Canvas(outputBmp)
            c.drawBitmap(intermediateBitmap, 0f, 0f, paint)
        }

        val result = outputBmp

        // Advance index for next frame
        outputIndex = (outputIndex + 1) % 2

        return result
    }

    private fun mapPointsToImage(
        normalizedViewPoints: List<PointF>,
        imageW: Int,
        imageH: Int,
        viewW: Int,
        viewH: Int
    ): List<PointF> {
        if (normalizedViewPoints.size != 4) return normalizedViewPoints

        val fViewW = viewW.toFloat()
        val fViewH = viewH.toFloat()

        // FIT_CENTER logic (scale to fit)
        val scale = kotlin.math.min(fViewW / imageW, fViewH / imageH)

        val scaledW = imageW * scale
        val scaledH = imageH * scale

        val dx = (fViewW - scaledW) / 2
        val dy = (fViewH - scaledH) / 2

        return normalizedViewPoints.map { pNorm ->
            // pNorm is normalized to View (0..1)
            val pViewX = pNorm.x * fViewW
            val pViewY = pNorm.y * fViewH

            // Map to Image Pixels
            val pImageX = (pViewX - dx) / scale
            val pImageY = (pViewY - dy) / scale

            // Normalize to Image (0..1)
            PointF(pImageX / imageW, pImageY / imageH)
        }
    }

    private fun cropForFocalLength(bitmap: Bitmap, focalLength: Int): Bitmap {
        if (focalLength <= 24) return bitmap
        val cropRect = getCropRectForFocalLength(bitmap.width, bitmap.height, focalLength)
        return Bitmap.createBitmap(bitmap, cropRect.left.toInt(), cropRect.top.toInt(), cropRect.width().toInt(), cropRect.height().toInt())
    }

    private fun getCropRectForFocalLength(w: Int, h: Int, focalLength: Int): android.graphics.RectF {
        if (focalLength <= 24) return android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
        val scale = focalLength / 24.0f
        val newW = w / scale
        val newH = h / scale
        val x = (w - newW) / 2
        val y = (h - newH) / 2
        return android.graphics.RectF(x, y, x + newW, y + newH)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/C1Cam")
            }
        }

        val contentResolver = context.contentResolver
        val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (outputUri != null) {
            try {
                val outputStream = contentResolver.openOutputStream(outputUri)
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e("ImageProcessor", "Error saving image", e)
            }
        }
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
