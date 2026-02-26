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
        currentLut: Lut3D?,
        isChromaDenoiseOn: Boolean,
        isCropModeOff: Boolean,
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
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val finalBitmap = if (isCropModeOff) {
            val croppedBitmap = cropForFocalLength(uprightBitmap, focalLength)
            currentLut?.let {
                LutUtils.applyLut(croppedBitmap, it)
            } ?: croppedBitmap
        } else {
            // Map points
            val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)

            // Rectify (Full resolution for capture)
            val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio, maxDimension = 0)

            // Apply LUT if active
            currentLut?.let {
                LutUtils.applyLut(rectifiedBitmap, it)
            } ?: rectifiedBitmap
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
        focalLength: Int
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

        if (isCropModeOff) {
            // Digital Zoom Crop
            val croppedBitmap = cropForFocalLength(uprightBitmap, focalLength)

            // Scale down for preview performance
            val maxDim = 512
            val w = croppedBitmap.width
            val h = croppedBitmap.height
            val scale = if (max(w, h) > maxDim) maxDim.toFloat() / max(w, h).toFloat() else 1f

            val scaledBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(croppedBitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            } else {
                croppedBitmap
            }

            return currentLut?.let {
                LutUtils.applyLut(scaledBitmap, it)
            } ?: scaledBitmap
        } else {
            // Map points and rectify
            val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)
            val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio, maxDimension = 512)

            // Apply LUT if active
            return currentLut?.let {
                LutUtils.applyLut(rectifiedBitmap, it)
            } ?: rectifiedBitmap
        }
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
        val scale = focalLength / 24.0f
        val w = (bitmap.width / scale).toInt()
        val h = (bitmap.height / scale).toInt()
        val x = (bitmap.width - w) / 2
        val y = (bitmap.height - h) / 2
        return Bitmap.createBitmap(bitmap, x, y, w, h)
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
