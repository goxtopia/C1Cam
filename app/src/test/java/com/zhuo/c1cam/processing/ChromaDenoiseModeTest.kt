package com.zhuo.c1cam.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromaDenoiseModeTest {
    @Test
    fun `auto high selects a stronger profile than auto`() {
        assertEquals(ChromaDenoiseMode.OFF, ChromaDenoiseMode.AUTO.resolveForIso(100))
        assertEquals(ChromaDenoiseMode.LOW, ChromaDenoiseMode.AUTO_HIGH.resolveForIso(100))

        assertEquals(ChromaDenoiseMode.MEDIUM, ChromaDenoiseMode.AUTO.resolveForIso(1600))
        assertEquals(ChromaDenoiseMode.HIGH, ChromaDenoiseMode.AUTO_HIGH.resolveForIso(1600))

        assertEquals(ChromaDenoiseMode.HIGH, ChromaDenoiseMode.AUTO.resolveForIso(3200))
        assertEquals(
            ChromaDenoiseMode.VERY_HIGH,
            ChromaDenoiseMode.AUTO_HIGH.resolveForIso(3200)
        )
    }

    @Test
    fun `manual profiles are not changed by ISO`() {
        assertEquals(
            ChromaDenoiseMode.MEDIUM,
            ChromaDenoiseMode.MEDIUM.resolveForIso(6400)
        )
    }

    @Test
    fun `auto high luma selects its luma only high ISO profile`() {
        assertEquals(
            ChromaDenoiseMode.HIGH,
            ChromaDenoiseMode.AUTO_HIGH_LUMA.resolveForIso(null)
        )
        assertEquals(
            ChromaDenoiseMode.MEDIUM,
            ChromaDenoiseMode.AUTO_HIGH_LUMA.resolveForIso(400)
        )
        assertEquals(
            ChromaDenoiseMode.HIGH,
            ChromaDenoiseMode.AUTO_HIGH_LUMA.resolveForIso(1600)
        )
        assertEquals(
            ChromaDenoiseMode.VERY_HIGH_LUMA,
            ChromaDenoiseMode.AUTO_HIGH_LUMA.resolveForIso(1601)
        )
    }

    @Test
    fun `auto extra high luma increases strength with ISO`() {
        assertEquals(
            ChromaDenoiseMode.MEDIUM,
            ChromaDenoiseMode.AUTO_XHIGH_LUMA.resolveForIso(100)
        )
        assertEquals(
            ChromaDenoiseMode.HIGH,
            ChromaDenoiseMode.AUTO_XHIGH_LUMA.resolveForIso(400)
        )
        assertEquals(
            ChromaDenoiseMode.VERY_HIGH_LUMA,
            ChromaDenoiseMode.AUTO_XHIGH_LUMA.resolveForIso(1600)
        )
        assertEquals(
            ChromaDenoiseMode.XHIGH_LUMA,
            ChromaDenoiseMode.AUTO_XHIGH_LUMA.resolveForIso(1601)
        )
    }

    @Test
    fun `every automatic mode changes profile between low and high ISO`() {
        val automaticModes = listOf(
            ChromaDenoiseMode.AUTO,
            ChromaDenoiseMode.AUTO_HIGH,
            ChromaDenoiseMode.AUTO_HIGH_LUMA,
            ChromaDenoiseMode.AUTO_XHIGH_LUMA
        )

        automaticModes.forEach { mode ->
            val lowIsoProfile = mode.resolveForIso(100)
            val highIsoProfile = mode.resolveForIso(3200)
            check(lowIsoProfile != highIsoProfile) {
                "$mode must adapt its profile to capture ISO"
            }
        }
    }
}
