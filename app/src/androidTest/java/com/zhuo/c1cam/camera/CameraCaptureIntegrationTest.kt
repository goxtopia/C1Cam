package com.zhuo.c1cam.camera

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhuo.c1cam.R
import com.zhuo.c1cam.capture.ImageOutputFormat
import com.zhuo.c1cam.settings.AppSettings
import com.zhuo.c1cam.settings.PreviewDisplayMode
import com.zhuo.c1cam.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Run explicitly on a camera device; writes temporary photos and removes only its own rows. */
@RunWith(AndroidJUnit4::class)
class CameraCaptureIntegrationTest {
    @Test
    fun allFormatsAndRapidRawCaptureWorkWithLivePreviews() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).first()
        assumeTrue(ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG))
        val settings = AppSettings(context)
        val oldFormat = settings.imageOutputFormat
        val oldPreview = settings.previewDisplayMode
        val oldXpan = settings.isXpanMode
        val created = mutableSetOf<android.net.Uri>()
        fun ownPhotos(): Map<android.net.Uri, String> {
            val rows = linkedMapOf<android.net.Uri, String>()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf(context.packageName), null)?.use { cursor ->
                while (cursor.moveToNext()) rows[android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))] = cursor.getString(1)
            }
            return rows
        }
        try {
            val configurations = listOf(
                Triple(PreviewDisplayMode.CAMERA, false, ImageOutputFormat.JPEG_DNG),
                Triple(PreviewDisplayMode.RECTIFIED, false, ImageOutputFormat.JPEG_DNG),
                Triple(PreviewDisplayMode.CAMERA, true, ImageOutputFormat.JPEG_DNG),
                Triple(PreviewDisplayMode.CAMERA, false, ImageOutputFormat.JPEG),
                Triple(PreviewDisplayMode.CAMERA, false, ImageOutputFormat.PNG)
            )
            for ((mode, xpan, format) in configurations) {
                settings.imageOutputFormat = format
                settings.previewDisplayMode = mode
                settings.isXpanMode = xpan
                settings.save(emptyList())
                val raw = format.includesRaw
                val shots = if (raw && mode == PreviewDisplayMode.CAMERA && !xpan) 2 else 1
                val before = ownPhotos().keys
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                try {
                    var manager: CameraManager? = null
                    instrumentation.runOnMainSync {
                        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
                        assertEquals(Configuration.ORIENTATION_PORTRAIT, activity.resources.configuration.orientation)
                        manager = field(activity, "cameraManager") as CameraManager
                    }
                    waitUntil(15_000) {
                        var open = false
                        instrumentation.runOnMainSync {
                            open = (field(checkNotNull(manager), "camera") as? Camera)
                                ?.cameraInfo?.cameraState?.value?.type == CameraState.Type.OPEN
                        }
                        open
                    }
                    if (xpan || mode == PreviewDisplayMode.RECTIFIED) {
                        waitUntil(10_000) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                val image = if (xpan) field(checkNotNull(manager), "rawAnalysisPreview")
                                    as? android.widget.ImageView else
                                    activity.findViewById<android.widget.ImageView>(R.id.preview_rectified)
                                ready = image?.drawable != null
                            }
                            ready
                        }
                    }
                    instrumentation.runOnMainSync {
                        assertEquals("Incorrect output format in $mode preview",
                            if (raw) ImageCapture.OUTPUT_FORMAT_RAW_JPEG else ImageCapture.OUTPUT_FORMAT_JPEG,
                            (field(checkNotNull(manager), "imageCapture") as ImageCapture).outputFormat)
                        activity.findViewById<View>(if (xpan) R.id.xpan_capture_button else R.id.capture_button)
                            .performClick()
                    }
                    if (shots == 2) {
                        // A second shutter while the first capture is still exposing/processing.
                        SystemClock.sleep(40)
                        instrumentation.runOnMainSync {
                            activity.findViewById<View>(R.id.capture_button).performClick()
                        }
                    }
                    waitUntil(30_000) {
                        val photos = ownPhotos().filterKeys { it !in before }
                        created.addAll(photos.keys)
                        photos.values.count { it.endsWith(".${format.extension}") } == shots &&
                            (!raw || photos.values.count { it.endsWith(".dng") } == shots)
                    }
                    val photos = ownPhotos().filterKeys { it !in before }
                    assertEquals(shots * (if (raw) 2 else 1), photos.size)
                    val pairs = photos.values.groupBy { it.substringBeforeLast('.') }
                    assertEquals(shots, pairs.size)
                    pairs.values.forEach { pair ->
                        assertEquals(if (raw) setOf("jpg", "dng") else setOf(format.extension),
                            pair.map { it.substringAfterLast('.') }.toSet())
                    }
                    for (uri in photos.keys) {
                        context.contentResolver.openInputStream(uri).use { stream ->
                            if (format == ImageOutputFormat.PNG) {
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                android.graphics.BitmapFactory.decodeStream(checkNotNull(stream), null, options)
                                assertTrue(options.outWidth > 0 && options.outHeight > 0)
                            } else {
                                val exif = ExifInterface(checkNotNull(stream))
                                assertTrue(exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) > 0)
                                assertTrue(exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) > 0)
                            }
                        }
                    }
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            settings.imageOutputFormat = oldFormat
            settings.previewDisplayMode = oldPreview
            settings.isXpanMode = oldXpan
            settings.save(emptyList())
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Camera operation timed out", condition())
    }
}
