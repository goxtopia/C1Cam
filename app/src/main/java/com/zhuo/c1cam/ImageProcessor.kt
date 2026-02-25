package com.zhuo.c1cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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

    fun processAndSaveImage(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?
    ) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = imageProxy.toBitmap()
        imageProxy.close() // Close immediately after conversion

        // Rotate to upright
        val uprightBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // Map points
        val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)

        // Rectify (Full resolution for capture)
        val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio, maxDimension = 0)

        // Apply LUT if active
        val finalBitmap = currentLut?.let {
            LutUtils.applyLut(rectifiedBitmap, it)
        } ?: rectifiedBitmap

        // Save
        saveBitmapToGallery(finalBitmap)
    }

    fun processForPreview(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?
    ): Bitmap {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = imageProxy.toBitmap()

        // Rotate to upright
        val uprightBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // Map points and rectify
        val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)
        val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio, maxDimension = 512)

        // Apply LUT if active
        return currentLut?.let {
            LutUtils.applyLut(rectifiedBitmap, it)
        } ?: rectifiedBitmap
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

        // Assuming FILL_CENTER logic (scale to fill)
        val scale = max(fViewW / imageW, fViewH / imageH)

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
                    // If you have a ViewModel or callback to show toasts properly, you can use that instead.
                    // For now, doing Toast in processor runs on context's main thread handling wrapper (or requires UI thread)
                    // We can rely on a callback later if needed, but Context.mainLooper usually handles Toast fine.
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
