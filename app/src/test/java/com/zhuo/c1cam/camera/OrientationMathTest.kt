package com.zhuo.c1cam.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationMathTest {
    @Test
    fun saved_image_needs_no_extra_rotation_when_device_matches_display() {
        assertEquals(
            0,
            OrientationMath.savedImageRotationDegrees(
                Surface.ROTATION_90,
                Surface.ROTATION_90
            )
        )
    }

    @Test
    fun saved_image_recovers_landscape_orientation_from_portrait_display() {
        assertEquals(
            270,
            OrientationMath.savedImageRotationDegrees(
                Surface.ROTATION_90,
                Surface.ROTATION_0
            )
        )
        assertEquals(
            90,
            OrientationMath.savedImageRotationDegrees(
                Surface.ROTATION_270,
                Surface.ROTATION_0
            )
        )
    }

    @Test
    fun controls_and_saved_image_use_opposite_relative_rotations() {
        assertEquals(
            90,
            OrientationMath.controlRotationDegrees(
                Surface.ROTATION_90,
                Surface.ROTATION_0
            )
        )
        assertEquals(
            270,
            OrientationMath.savedImageRotationDegrees(
                Surface.ROTATION_90,
                Surface.ROTATION_0
            )
        )
    }
}
