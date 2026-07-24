package com.zhuo.c1cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

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

    fun processAndSaveImage(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?,
        chromaDenoiseMode: ChromaDenoiseMode,
        isCropModeOff: Boolean,
        focalLength: Int,
        noCropAspectRatio: Float,
        outputFormat: ImageOutputFormat,
        jpegQuality: Int,
        captureMetadata: CaptureMetadata,
        savedImageRotationDegrees: Int
    ): Boolean {
        val processingStartedNanos = SystemClock.elapsedRealtimeNanos()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        var processingPath = "gpu"
        val bitmapToSave = try {
            val geometry = StillCaptureGeometry.create(
                rawWidth = imageProxy.width,
                rawHeight = imageProxy.height,
                imageRotationDegrees = rotationDegrees,
                normalizedViewPoints = normalizedViewPoints,
                viewWidth = viewW,
                viewHeight = viewH,
                targetAspectRatio = targetAspectRatio,
                isCropModeOff = isCropModeOff,
                focalLength = focalLength,
                noCropAspectRatio = noCropAspectRatio,
                savedImageRotationDegrees = savedImageRotationDegrees
            )
            StillCaptureGlPipeline.process(
                image = imageProxy,
                geometry = geometry,
                lut = currentLut,
                configuredDenoiseMode = chromaDenoiseMode,
                iso = captureMetadata.iso
            ) ?: run {
                processingPath = "cpu_fallback"
                processStillCaptureLegacy(
                    imageProxy = imageProxy,
                    normalizedViewPoints = normalizedViewPoints,
                    viewW = viewW,
                    viewH = viewH,
                    targetAspectRatio = targetAspectRatio,
                    currentLut = currentLut,
                    chromaDenoiseMode = chromaDenoiseMode,
                    isCropModeOff = isCropModeOff,
                    focalLength = focalLength,
                    noCropAspectRatio = noCropAspectRatio,
                    captureMetadata = captureMetadata,
                    rotationDegrees = rotationDegrees,
                    savedImageRotationDegrees = savedImageRotationDegrees
                )
            }
        } catch (error: Exception) {
            processingPath = "cpu_fallback"
            Log.e("ImageProcessor", "Merged capture pipeline failed", error)
            processStillCaptureLegacy(
                imageProxy = imageProxy,
                normalizedViewPoints = normalizedViewPoints,
                viewW = viewW,
                viewH = viewH,
                targetAspectRatio = targetAspectRatio,
                currentLut = currentLut,
                chromaDenoiseMode = chromaDenoiseMode,
                isCropModeOff = isCropModeOff,
                focalLength = focalLength,
                noCropAspectRatio = noCropAspectRatio,
                captureMetadata = captureMetadata,
                rotationDegrees = rotationDegrees,
                savedImageRotationDegrees = savedImageRotationDegrees
            )
        } finally {
            imageProxy.close()
        }

        val pixelsReadyNanos = SystemClock.elapsedRealtimeNanos()
        val saved = saveBitmapToGallery(
            bitmapToSave,
            outputFormat,
            JpegQuality.sanitize(jpegQuality),
            captureMetadata
        )
        Log.i(
            CAPTURE_PERF_TAG,
            "stage=complete path=$processingPath success=$saved " +
                "pixelsMs=${elapsedMillis(processingStartedNanos, pixelsReadyNanos)} " +
                "saveMs=${elapsedMillis(pixelsReadyNanos)} " +
                "totalMs=${elapsedMillis(processingStartedNanos)} " +
                "output=${bitmapToSave.width}x${bitmapToSave.height} " +
                "format=${outputFormat.name} jpegQuality=$jpegQuality"
        )
        return saved
    }

    private fun processStillCaptureLegacy(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?,
        chromaDenoiseMode: ChromaDenoiseMode,
        isCropModeOff: Boolean,
        focalLength: Int,
        noCropAspectRatio: Float,
        captureMetadata: CaptureMetadata,
        rotationDegrees: Int,
        savedImageRotationDegrees: Int
    ): Bitmap {
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        val bitmap = try {
            if (chromaDenoiseMode != ChromaDenoiseMode.OFF) {
                ChromaNoiseReduction.process(
                    image = imageProxy,
                    configuredMode = chromaDenoiseMode,
                    iso = captureMetadata.iso
                )
            } else {
                imageProxy.toBitmap()
            }
        } catch (error: Exception) {
            Log.e("ImageProcessor", "Legacy image conversion failed", error)
            imageProxy.toBitmap()
        }

        val uprightBitmap = if (rotationDegrees != 0) {
            val m = Matrix()
            m.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else {
            bitmap
        }

        val finalBitmap = if (isCropModeOff) {
            val croppedBitmap = cropForNoCropMode(uprightBitmap, focalLength, noCropAspectRatio)
            currentLut?.let {
                LutUtils.applyLut(croppedBitmap, it)
            } ?: croppedBitmap
        } else {
            // Map points
            val mappedPoints = mapPointsToImage(normalizedViewPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)

            // Rectify (Full resolution for capture - creating new bitmaps is acceptable here for quality/simplicity)
            val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio, maxDimension = 0)

            // Apply LUT if active
            currentLut?.let {
                LutUtils.applyLut(rectifiedBitmap, it)
            } ?: rectifiedBitmap
        }

        return rotateBitmap(finalBitmap, savedImageRotationDegrees).also {
            Log.i(
                CAPTURE_PERF_TAG,
                "stage=cpu_fallback_complete durationMs=${elapsedMillis(startedNanos)} " +
                    "output=${it.width}x${it.height}"
            )
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return bitmap

        val rotationMatrix = Matrix().apply {
            postRotate(normalizedRotation.toFloat())
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            rotationMatrix,
            true
        )
    }

    fun shutdown() {
        StillCaptureGlPipeline.release()
    }

    fun processForPreview(
        imageProxy: ImageProxy,
        normalizedViewPoints: List<PointF>,
        viewW: Int,
        viewH: Int,
        targetAspectRatio: Float,
        currentLut: Lut3D?,
        isCropModeOff: Boolean,
        focalLength: Int,
        noCropAspectRatio: Float
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
            val cropRect = getCropRectForNoCropMode(uprightBmp.width, uprightBmp.height, focalLength, noCropAspectRatio)

            // 3. Scale down logic
            val maxDim = PREVIEW_MAX_DIMENSION
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
            val dims = RectificationUtils.getRectifiedDimensions(
                uprightBmp,
                mappedPoints,
                targetAspectRatio,
                maxDimension = PREVIEW_MAX_DIMENSION
            )
            val dstW = dims[0]
            val dstH = dims[1]

            if (reusedRectifiedBitmap == null || reusedRectifiedBitmap!!.width != dstW || reusedRectifiedBitmap!!.height != dstH) {
                reusedRectifiedBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            }
            val rectBmp = reusedRectifiedBitmap!!

            // Rectify with GL acceleration and CPU fallback
            RectificationUtils.rectifyToBitmapRealtime(uprightBmp, rectBmp, mappedPoints)

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
            LutUtils.applyLut(intermediateBitmap, currentLut, outputBmp)
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

    private fun cropForNoCropMode(bitmap: Bitmap, focalLength: Int, aspectRatio: Float): Bitmap {
        val cropRect = getCropRectForNoCropMode(bitmap.width, bitmap.height, focalLength, aspectRatio)
        return Bitmap.createBitmap(bitmap, cropRect.left.toInt(), cropRect.top.toInt(), cropRect.width().toInt(), cropRect.height().toInt())
    }

    private fun getCropRectForNoCropMode(w: Int, h: Int, focalLength: Int, aspectRatio: Float): android.graphics.RectF {
        val focalRect = getCropRectForFocalLength(w, h, focalLength)
        if (aspectRatio <= 0f) return focalRect

        val focalWidth = focalRect.width()
        val focalHeight = focalRect.height()
        val sourceIsLandscape = focalWidth >= focalHeight
        val targetIsLandscape = aspectRatio >= 1f
        val effectiveAspectRatio = if (sourceIsLandscape == targetIsLandscape) {
            aspectRatio
        } else {
            1f / aspectRatio
        }

        val currentRatio = focalWidth / focalHeight
        var cropWidth = focalWidth
        var cropHeight = focalHeight
        if (currentRatio > effectiveAspectRatio) {
            cropWidth = focalHeight * effectiveAspectRatio
        } else {
            cropHeight = focalWidth / effectiveAspectRatio
        }

        val left = focalRect.left + (focalWidth - cropWidth) / 2f
        val top = focalRect.top + (focalHeight - cropHeight) / 2f
        return android.graphics.RectF(left, top, left + cropWidth, top + cropHeight)
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

    private fun saveBitmapToGallery(
        bitmap: Bitmap,
        outputFormat: ImageOutputFormat,
        jpegQuality: Int,
        metadata: CaptureMetadata
    ): Boolean {
        val saveStartedNanos = SystemClock.elapsedRealtimeNanos()
        val timestamp = Date(metadata.capturedAtMillis)
        val baseName = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(timestamp)
        val name = "$baseName.${outputFormat.extension}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, outputFormat.mimeType)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/C1Cam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val contentResolver = context.contentResolver
        val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (outputUri == null) return false

        var savedSuccessfully = false
        try {
            val outputStream = contentResolver.openOutputStream(outputUri, "w")
                ?: error("Unable to open gallery output stream")
            val compressionStartedNanos = SystemClock.elapsedRealtimeNanos()
            outputStream.use {
                val compressFormat = when (outputFormat) {
                    ImageOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageOutputFormat.PNG -> Bitmap.CompressFormat.PNG
                }
                if (!bitmap.compress(
                        compressFormat,
                        if (outputFormat == ImageOutputFormat.JPEG) jpegQuality else 100,
                        it
                    )
                ) {
                    error("Bitmap compression failed")
                }
            }
            Log.i(
                CAPTURE_PERF_TAG,
                "stage=compress durationMs=${elapsedMillis(compressionStartedNanos)} " +
                    "format=${outputFormat.name} quality=" +
                    if (outputFormat == ImageOutputFormat.JPEG) jpegQuality else 100
            )

            if (outputFormat == ImageOutputFormat.JPEG) {
                val exifStartedNanos = SystemClock.elapsedRealtimeNanos()
                writeJpegExif(outputUri, bitmap, metadata)
                Log.i(
                    CAPTURE_PERF_TAG,
                    "stage=exif durationMs=${elapsedMillis(exifStartedNanos)}"
                )
            }
            savedSuccessfully = true
        } catch (e: Exception) {
            Log.e("ImageProcessor", "Error saving image", e)
        } finally {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P && savedSuccessfully) {
                contentResolver.update(
                    outputUri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            } else if (!savedSuccessfully) {
                contentResolver.delete(outputUri, null, null)
            }
        }
        Log.i(
            CAPTURE_PERF_TAG,
            "stage=gallery_write success=$savedSuccessfully " +
                "durationMs=${elapsedMillis(saveStartedNanos)}"
        )
        return savedSuccessfully
    }

    private fun writeJpegExif(
        imageUri: Uri,
        bitmap: Bitmap,
        metadata: CaptureMetadata
    ) {
        val fileDescriptor = context.contentResolver.openFileDescriptor(imageUri, "rw")
            ?: error("Unable to open JPEG for metadata writing")
        fileDescriptor.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            val capturedAt = Date(metadata.capturedAtMillis)
            val exifDate = SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US).format(capturedAt)
            val subsecond = SimpleDateFormat(EXIF_SUBSECOND_FORMAT, Locale.US).format(capturedAt)

            exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, subsecond)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsecond)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subsecond)
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "C1Cam")
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                metadata.equivalentFocalLengthMm.toString()
            )
            exif.setAttribute(
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                decimalToRational(metadata.exposureCompensationEv)
            )

            metadata.iso?.let {
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString())
            }
            metadata.exposureTimeNs?.takeIf { it > 0L }?.let {
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, nanosecondsToRational(it))
            }
            metadata.aperture?.takeIf { it > 0f }?.let {
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, decimalToRational(it))
            }
            metadata.physicalFocalLengthMm?.takeIf { it > 0f }?.let {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, decimalToRational(it))
            }
            metadata.isAutoWhiteBalance?.let {
                exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, if (it) "0" else "1")
            }
            metadata.isFlashFired?.let {
                exif.setAttribute(ExifInterface.TAG_FLASH, if (it) "1" else "0")
            }
            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "Processed by C1Cam; equivalent focal length ${metadata.equivalentFocalLengthMm}mm"
            )
            exif.saveAttributes()
        }
    }

    private fun decimalToRational(value: Float, denominator: Int = 1000): String {
        val numerator = (value * denominator).roundToInt()
        val divisor = greatestCommonDivisor(kotlin.math.abs(numerator), denominator)
        return "${numerator / divisor}/${denominator / divisor}"
    }

    private fun nanosecondsToRational(value: Long): String {
        val denominator = 1_000_000_000L
        val divisor = greatestCommonDivisor(value, denominator)
        return "${value / divisor}/${denominator / divisor}"
    }

    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        var x = a.coerceAtLeast(1)
        var y = b.coerceAtLeast(1)
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
    }

    private fun greatestCommonDivisor(a: Long, b: Long): Long {
        var x = a.coerceAtLeast(1L)
        var y = b.coerceAtLeast(1L)
        while (y != 0L) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
    }

    private fun elapsedMillis(
        startNanos: Long,
        endNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): String {
        return String.format(
            Locale.US,
            "%.2f",
            (endNanos - startNanos) / 1_000_000.0
        )
    }

    companion object {
        private const val CAPTURE_PERF_TAG = "C1CapturePerf"
        private const val PREVIEW_MAX_DIMENSION = 1920
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss"
        private const val EXIF_SUBSECOND_FORMAT = "SSS"
    }
}
