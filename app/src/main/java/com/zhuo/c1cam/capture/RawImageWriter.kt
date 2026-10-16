package com.zhuo.c1cam.capture

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class RawImageWriter(private val context: Context) {
    /** Caller owns the ImageProxy and closes it after this synchronous worker operation. */
    @OptIn(ExperimentalGetImage::class)
    fun save(
        captureId: Long,
        image: ImageProxy,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        capturedAtMillis: Long,
        savedImageRotationDegrees: Int
    ): Boolean {
        val started = SystemClock.elapsedRealtimeNanos()
        val resolver = context.contentResolver
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(Date(capturedAtMillis)) + ".dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/C1Cam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            DngCreator(characteristics, result).use { creator ->
                val rotation = (image.imageInfo.rotationDegrees + savedImageRotationDegrees) % 360
                creator.setOrientation(when (rotation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                })
                resolver.openOutputStream(uri, "w").use { stream ->
                    checkNotNull(stream) { "Unable to open DNG output" }
                    creator.writeImage(stream, checkNotNull(image.image))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            Log.i("C1CapturePerf", "capture=$captureId stage=dng_saved durationMs=" +
                (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
            return true
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
