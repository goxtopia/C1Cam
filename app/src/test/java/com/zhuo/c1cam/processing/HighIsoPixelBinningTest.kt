package com.zhuo.c1cam.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HighIsoPixelBinningTest {
    @Test
    fun `binning activates only above threshold when enabled`() {
        assertEquals(
            1,
            HighIsoPixelBinning.resolveFactor(
                enabled = false,
                mode = PixelBinningMode.FOUR_BY_FOUR,
                isoThreshold = 1600,
                captureIso = 3200
            )
        )
        assertEquals(
            1,
            HighIsoPixelBinning.resolveFactor(
                enabled = true,
                mode = PixelBinningMode.TWO_BY_TWO,
                isoThreshold = 1600,
                captureIso = 1600
            )
        )
        assertEquals(
            2,
            HighIsoPixelBinning.resolveFactor(
                enabled = true,
                mode = PixelBinningMode.TWO_BY_TWO,
                isoThreshold = 1600,
                captureIso = 1601
            )
        )
        assertEquals(
            4,
            HighIsoPixelBinning.resolveFactor(
                enabled = true,
                mode = PixelBinningMode.FOUR_BY_FOUR,
                isoThreshold = 1600,
                captureIso = 3200
            )
        )
    }

    @Test
    fun `unknown capture ISO does not enable binning`() {
        assertEquals(
            1,
            HighIsoPixelBinning.resolveFactor(
                enabled = true,
                mode = PixelBinningMode.FOUR_BY_FOUR,
                isoThreshold = 400,
                captureIso = null
            )
        )
    }

    @Test
    fun `two by two binning averages every color channel`() {
        val pixels = intArrayOf(
            argb(255, 0, 10, 20), argb(255, 20, 30, 40),
            argb(255, 40, 50, 60), argb(255, 60, 70, 80)
        )

        assertArrayEquals(
            intArrayOf(argb(255, 30, 40, 50)),
            HighIsoPixelBinning.binPixels(pixels, 2, 2, 2)
        )
    }

    @Test
    fun `four by four binning creates one exact block average`() {
        val pixels = IntArray(16) { value ->
            argb(255, value * 2, value * 4, value * 6)
        }

        assertArrayEquals(
            intArrayOf(argb(255, 15, 30, 45)),
            HighIsoPixelBinning.binPixels(pixels, 4, 4, 4)
        )
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
