package com.zhuo.c1cam.camera

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhuo.c1cam.R
import com.zhuo.c1cam.capture.ImageOutputFormat
import com.zhuo.c1cam.settings.AppSettings
import com.zhuo.c1cam.settings.PreviewDisplayMode
import com.zhuo.c1cam.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device benchmark; no photos are taken. Works against the previous Bitmap implementation too. */
@RunWith(AndroidJUnit4::class)
class PreviewPerformanceTest {
    @Test fun rawPreviewFrameDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Keep the real app's preferences separate by running with the validation application ID.
        val settings = AppSettings(context)
        val oldFormat = settings.imageOutputFormat
        val oldMode = settings.previewDisplayMode
        val oldXpan = settings.isXpanMode
        val oldLut = settings.lutName
        try {
            for ((xpan, lut) in listOf(true to null, false to null, false to "asset:Folger50.CUBE")) {
                settings.imageOutputFormat = ImageOutputFormat.JPEG_DNG
                settings.isXpanMode = xpan
                settings.previewDisplayMode = if (xpan) PreviewDisplayMode.CAMERA else PreviewDisplayMode.RECTIFIED
                settings.lutName = lut
                settings.save(emptyList())
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                try {
                    lateinit var display: Any
                    SystemClock.sleep(1800)
                    instrumentation.runOnMainSync {
                        val manager = field(activity, "cameraManager")!!
                        display = if (xpan) field(manager, "rawAnalysisPreview")!! else {
                            runCatching { field(manager, "rectifiedGlPreview") }.getOrNull()
                                ?: activity.findViewById<ImageView>(R.id.preview_rectified)
                        }
                    }
                    fun sample(): Long = if (display is ImageView) {
                        System.identityHashCode(((display as ImageView).drawable as? BitmapDrawable)?.bitmap).toLong()
                    } else display.javaClass.getMethod("getRenderedFrames").invoke(display) as Long
                    var first = 0L
                    var previous = 0L
                    var changes = 0
                    instrumentation.runOnMainSync { first = sample(); previous = first }
                    val started = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - started < 3000) {
                        instrumentation.runOnMainSync {
                            val current = sample()
                            if (current != previous) changes++
                            previous = current
                        }
                        SystemClock.sleep(5)
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val frames = if (display is ImageView) changes.toLong() else previous - first
                    val fps = frames * 1000.0 / elapsed
                    Log.i("PreviewPerf", "xpan=$xpan lut=${lut != null} frames=$frames fps=$fps renderer=${display.javaClass.simpleName}")
                    assertTrue("Preview stopped delivering frames", frames > 3)
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                    val destroyedDeadline = SystemClock.elapsedRealtime() + 5000
                    while (!activity.isDestroyed && SystemClock.elapsedRealtime() < destroyedDeadline) {
                        SystemClock.sleep(20)
                    }
                    assertTrue(activity.isDestroyed)
                }
            }
        } finally {
            settings.imageOutputFormat = oldFormat
            settings.previewDisplayMode = oldMode
            settings.isXpanMode = oldXpan
            settings.lutName = oldLut
            settings.save(emptyList())
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
}
