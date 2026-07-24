package com.zhuo.c1cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalLengthDetentsTest {
    @Test
    fun classicFocalLengthsAreDetents() {
        listOf(24, 28, 35, 40, 50).forEach {
            assertTrue(FocalLengthDetents.isClassicDetent(it))
        }
        assertFalse(FocalLengthDetents.isClassicDetent(32))
    }

    @Test
    fun positionsMatchTheTwentyFourToFiftyRange() {
        assertEquals(
            listOf(0f, 4f / 26f, 11f / 26f, 16f / 26f, 1f),
            FocalLengthDetents.normalizedPositions()
        )
    }
}
