package com.zhuo.c1cam.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegQualityTest {
    @Test
    fun default_quality_is_ninety_five() {
        assertEquals(95, JpegQuality.DEFAULT)
        assertTrue(JpegQuality.choices.contains(JpegQuality.DEFAULT))
    }

    @Test
    fun persisted_quality_is_limited_to_bitmap_encoder_range() {
        assertEquals(1, JpegQuality.sanitize(-10))
        assertEquals(95, JpegQuality.sanitize(95))
        assertEquals(100, JpegQuality.sanitize(120))
    }
}
