package com.zhuo.c1cam.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureInFlightLimiterTest {
    @Test
    fun permitsAtMostConfiguredCaptures() {
        val limiter = CaptureInFlightLimiter(2)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(2, limiter.currentCount())

        limiter.release()

        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.currentCount())
    }

    @Test
    fun rejectsReleaseUnderflow() {
        val limiter = CaptureInFlightLimiter(2)

        assertThrows(IllegalStateException::class.java) {
            limiter.release()
        }
    }
}
