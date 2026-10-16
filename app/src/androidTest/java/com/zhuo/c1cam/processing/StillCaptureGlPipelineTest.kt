package com.zhuo.c1cam.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhuo.c1cam.camera.StillCaptureGeometry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StillCaptureGlPipelineTest {
    @Test
    fun liveSurfaceMatchesXpanCropForAllRightAngleOrientations() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context,
            com.zhuo.c1cam.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val source = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) source.setPixel(x, y, when {
            y < 14 || y >= 50 -> Color.MAGENTA // Must be cropped out, not fitted inside the view.
            y < 32 && x < 48 -> Color.RED
            y < 32 -> Color.GREEN
            x < 48 -> Color.BLUE
            else -> Color.YELLOW
        })
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            .toByteArray()
        lateinit var display: LivePreviewGlView
        try {
            instrumentation.runOnMainSync {
                display = LivePreviewGlView(activity)
                activity.addContentView(display, android.widget.FrameLayout.LayoutParams(96, 35))
                display.setActive(true)
            }
            val expected = listOf(
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
            )
            listOf(false, true).forEach { wdr ->
            listOf(0, 90, 180, 270).forEachIndexed { index, rotation ->
                val geometry = com.zhuo.c1cam.camera.PreviewFrameGeometry.forXpan(96, 64, rotation,
                    com.zhuo.c1cam.xpan.XpanMode.ASPECT_RATIO)
                val before = display.renderedFrames
                instrumentation.runOnMainSync {
                    display.layoutParams = android.widget.FrameLayout.LayoutParams(
                        geometry.outputWidth, geometry.outputHeight)
                    display.submit(TestImage(ImageFormat.JPEG, 96, 64, arrayOf(plane(bytes, 96))), geometry, null, wdr)
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                while (display.renderedFrames <= before && android.os.SystemClock.elapsedRealtime() < deadline) {
                    android.os.SystemClock.sleep(20)
                }
                assertTrue(display.renderedFrames > before)
                instrumentation.runOnMainSync { display.requestRender() }
                android.os.SystemClock.sleep(150)
                val copy = Bitmap.createBitmap(geometry.outputWidth, geometry.outputHeight, Bitmap.Config.ARGB_8888)
                try {
                    val done = java.util.concurrent.CountDownLatch(1)
                    var result = -1
                    instrumentation.runOnMainSync {
                        android.view.PixelCopy.request(display, copy, { status -> result = status; done.countDown() },
                            android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(android.view.PixelCopy.SUCCESS, result)
                    assertColor(expected[index][0], copy.getPixel(copy.width / 4, copy.height / 4), if (wdr) 35.0 else 8.0)
                    assertColor(expected[index][1], copy.getPixel(copy.width * 3 / 4, copy.height / 4), if (wdr) 35.0 else 8.0)
                    assertColor(expected[index][2], copy.getPixel(copy.width / 4, copy.height * 3 / 4), if (wdr) 35.0 else 8.0)
                    assertColor(expected[index][3], copy.getPixel(copy.width * 3 / 4, copy.height * 3 / 4), if (wdr) 35.0 else 8.0)
                } finally {
                    copy.recycle()
                }
            }
            }
        } finally {
            source.recycle()
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            awaitDestroyed(activity)
        }
    }

    @Test
    fun jpegUsesGpuAndPreservesColorsAndOrientationAcrossInputSwitches() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context,
            com.zhuo.c1cam.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val source = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, when {
                y < 48 && x < 32 -> Color.RED
                y < 48 -> Color.GREEN
                x < 32 -> Color.BLUE
                else -> Color.YELLOW
            })
        }
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            .toByteArray()
        val jpeg = TestImage(ImageFormat.JPEG, 64, 96, arrayOf(plane(bytes, 64)))
        val yuv = TestImage(ImageFormat.YUV_420_888, 64, 96, arrayOf(
            plane(ByteArray(64 * 96) { 128.toByte() }, 64),
            plane(ByteArray(32 * 48) { 128.toByte() }, 32),
            plane(ByteArray(32 * 48) { 128.toByte() }, 32)
        ))
        try {
            repeat(2) {
                val result = render(jpeg, 0, ChromaDenoiseMode.OFF)
                assertColor(Color.RED, result.getPixel(16, 24))
                assertColor(Color.GREEN, result.getPixel(48, 24))
                assertColor(Color.BLUE, result.getPixel(16, 72))
                assertColor(Color.YELLOW, result.getPixel(48, 72))
                result.recycle()
                val neutral = render(yuv, 0, ChromaDenoiseMode.OFF)
                assertColor(Color.rgb(128, 128, 128), neutral.getPixel(32, 48))
                neutral.recycle()
            }
            val rotated = render(jpeg, 90, ChromaDenoiseMode.OFF)
            assertEquals(96, rotated.width)
            assertEquals(64, rotated.height)
            assertColor(Color.BLUE, rotated.getPixel(24, 16))
            assertColor(Color.RED, rotated.getPixel(72, 16))
            assertColor(Color.YELLOW, rotated.getPixel(24, 48))
            assertColor(Color.GREEN, rotated.getPixel(72, 48))
            rotated.recycle()
            val denoised = render(jpeg, 90, ChromaDenoiseMode.MEDIUM)
            // Chroma denoise deliberately reduces saturation in dark blue regions.
            val blue = denoised.getPixel(24, 16)
            assertTrue(Color.blue(blue) > Color.red(blue) + 100)
            assertTrue(Color.blue(blue) > Color.green(blue) + 100)
            denoised.recycle()
        } finally {
            source.recycle()
            StillCaptureGlPipeline.release()
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            awaitDestroyed(activity)
        }
    }

    @Test
    fun localWdrProtectsBlackMaterialRetainsTextureAndPrecedesLut() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context,
            com.zhuo.c1cam.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val w = 512; val h = 320
        val luma = ByteArray(w*h) { i ->
            val x=i%w; val y=i/w
            (when {
                x in 370..390 && y in 130..190 -> 24 // black material in a bright environment
                x < 256 -> if ((x/2)%2==0) 52 else 60 // texture in a broad dark region
                else -> 224
            }).toByte()
        }
        val image = TestImage(ImageFormat.YUV_420_888,w,h,arrayOf(plane(luma,w),
            plane(ByteArray(w*h/4){128.toByte()},w/2),plane(ByteArray(w*h/4){128.toByte()},w/2)))
        val geometry = StillCaptureGeometry.create(w,h,0,emptyList(),w,h,0f,true,24,0f,0)
        val output = mutableListOf<Bitmap>()
        fun mapped(lut: Lut3D? = null, denoise: ChromaDenoiseMode = ChromaDenoiseMode.OFF, enabled: Boolean = true): Bitmap =
            checkNotNull(StillCaptureGlPipeline.process(77,image,geometry,lut,denoise,800,enabled)) {
                "WDR unexpectedly fell back from GPU"
            }.also { output.add(it) }
        try {
            val original = mapped(enabled=false)
            val result = mapped()
            fun value(b: Bitmap,x: Int,y: Int=160)=Color.green(b.getPixel(x,y))
            assertTrue("Broad shadows were not lifted",value(result,100)>value(original,100)+20)
            assertTrue("Black material was washed out",value(result,380)<=value(original,380)+5)
            assertTrue("Dark halo at illumination boundary",value(result,252)>=value(result,100)-20)
            val beforeContrast = LocalToneMapping.logLuma(LocalToneMapping.linear(value(original,102)/255f))-
                LocalToneMapping.logLuma(LocalToneMapping.linear(value(original,100)/255f))
            val afterContrast = LocalToneMapping.logLuma(LocalToneMapping.linear(value(result,102)/255f))-
                LocalToneMapping.logLuma(LocalToneMapping.linear(value(result,100)/255f))
            assertEquals("Texture contrast was compressed",beforeContrast,afterContrast,.12f)
            val cpu = original.copy(Bitmap.Config.ARGB_8888,true).also { output.add(it) }
            LocalToneMapping.apply(cpu)
            for (x in listOf(100,102,260,380,450)) assertEquals("CPU/GPU mismatch at $x",
                value(cpu,x).toDouble(),value(result,x).toDouble(),4.0)
            val lut = Lut3D(2,FloatArray(24).apply {
                for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                    val i=(b*4+g*2+r)*3
                    this[i]=r*.5f; this[i+1]=g*.5f; this[i+2]=b*.5f
                }
            })
            val graded = mapped(lut)
            assertEquals("LUT must run after WDR",value(result,100)*.5,value(graded,100).toDouble(),2.0)
            val denoised = mapped(denoise=ChromaDenoiseMode.HIGH)
            assertEquals(value(result,100).toDouble(),value(denoised,100).toDouble(),3.0)
            // Changing input format, size and WDR state must not leave stale guide textures/uniforms.
            val jpegBytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.JPEG,100,it) }.toByteArray()
            val jpeg = TestImage(ImageFormat.JPEG,w,h,arrayOf(plane(jpegBytes,w)))
            val jpegResult = checkNotNull(StillCaptureGlPipeline.process(78,jpeg,geometry,null,
                ChromaDenoiseMode.OFF,800,true)).also { output.add(it) }
            assertEquals(value(result,100).toDouble(),value(jpegResult,100).toDouble(),4.0)
            val reset = mapped(enabled=false)
            assertEquals(value(original,100),value(reset,100))
            // Save synthetic comparison only in the isolated test app's private cache.
            val comparison = Bitmap.createBitmap(w*3,h,Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(comparison)
            canvas.drawBitmap(original,0f,0f,null); canvas.drawBitmap(result,w.toFloat(),0f,null)
            canvas.drawBitmap(cpu,(w*2).toFloat(),0f,null)
            java.io.File(context.cacheDir,"wdr-comparison.png").outputStream().use {
                comparison.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            comparison.recycle()
        } finally {
            output.forEach { it.recycle() }
            StillCaptureGlPipeline.release()
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync(); awaitDestroyed(activity)
        }
    }

    private fun awaitDestroyed(activity: android.app.Activity) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!activity.isDestroyed && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
        }
        assertTrue(activity.isDestroyed)
    }

    private fun render(image: ImageProxy, rotation: Int, mode: ChromaDenoiseMode): Bitmap {
        val geometry = StillCaptureGeometry.create(image.width, image.height, rotation,
            emptyList(), image.width, image.height, 1f, true, 24, 0f, 0)
        return checkNotNull(StillCaptureGlPipeline.process(1, image, geometry, null, mode, 100)) {
            "GPU path unexpectedly fell back"
        }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Double = 8.0) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), tolerance)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), tolerance)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), tolerance)
    }

    private fun plane(bytes: ByteArray, stride: Int) = object : ImageProxy.PlaneProxy {
        override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        override fun getRowStride() = stride
        override fun getPixelStride() = 1
    }

    private class TestImage(
        private val imageFormat: Int,
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val imagePlanes: Array<ImageProxy.PlaneProxy>
    ) : ImageProxy {
        override fun close() {}
        override fun getFormat() = imageFormat
        override fun getWidth() = imageWidth
        override fun getHeight() = imageHeight
        override fun getPlanes() = imagePlanes
        override fun getCropRect() = Rect(0, 0, width, height)
        override fun setCropRect(rect: Rect?) {}
        override fun getImageInfo(): ImageInfo = error("Unused by GPU renderer")
        override fun getImage(): Image? = null
    }
}
