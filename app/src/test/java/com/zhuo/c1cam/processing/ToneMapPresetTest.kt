package com.zhuo.c1cam.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMapPresetTest {
    @Test
    fun invalid_storage_value_falls_back_to_none() {
        assertEquals(ToneMapPreset.NONE, ToneMapPreset.fromStorageValue("unknown"))
        assertEquals(ToneMapPreset.ACES, ToneMapPreset.fromStorageValue("aces"))
    }

    @Test
    fun none_preserves_rgb_values() {
        val mapped = ToneMapMath.apply(ToneMapPreset.NONE, 0.2f, 0.5f, 0.8f)

        assertEquals(0.2f, mapped[0], 0.0001f)
        assertEquals(0.5f, mapped[1], 0.0001f)
        assertEquals(0.8f, mapped[2], 0.0001f)
    }

    @Test
    fun aces_curve_is_monotonic_and_rolls_highlights() {
        val dark = ToneMapMath.apply(ToneMapPreset.ACES, 0.2f, 0.2f, 0.2f)[0]
        val middle = ToneMapMath.apply(ToneMapPreset.ACES, 0.5f, 0.5f, 0.5f)[0]
        val bright = ToneMapMath.apply(ToneMapPreset.ACES, 1f, 1f, 1f)[0]

        assertTrue(dark < middle)
        assertTrue(middle < bright)
        assertTrue(bright < 1f)
    }

    @Test
    fun none_reuses_existing_creative_lut() {
        val identity = Lut3D(
            2,
            floatArrayOf(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                1f, 1f, 0f,
                0f, 0f, 1f,
                1f, 0f, 1f,
                0f, 1f, 1f,
                1f, 1f, 1f
            )
        )

        assertSame(identity, ToneMapLutFactory.compose(ToneMapPreset.NONE, identity))
    }

    @Test
    fun filmic_preset_builds_gpu_compatible_lut() {
        val lut = ToneMapLutFactory.compose(ToneMapPreset.FILM_PRINT, null)

        assertNotNull(lut)
        assertEquals(33, lut!!.size)
        assertEquals(33 * 33 * 33 * 3, lut.data.size)
        assertTrue(lut.data.all { it in 0f..1f })
    }
}
