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
    fun jpegUsesGpuAndPreservesColorsAndOrientationAcrossInputSwitches() {
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
        }
    }

    private fun render(image: ImageProxy, rotation: Int, mode: ChromaDenoiseMode): Bitmap {
        val geometry = StillCaptureGeometry.create(image.width, image.height, rotation,
            emptyList(), image.width, image.height, 1f, true, 24, 0f, 0)
        return checkNotNull(StillCaptureGlPipeline.process(1, image, geometry, null, mode, 100)) {
            "GPU path unexpectedly fell back"
        }
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), 8.0)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), 8.0)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 8.0)
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
