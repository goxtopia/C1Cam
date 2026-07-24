package com.zhuo.c1cam

import org.junit.Assert.assertEquals
import org.junit.Test

class StillCaptureGeometryTest {
    @Test
    fun portrait_camera_rotation_produces_portrait_output_without_sensor_correction() {
        val geometry = StillCaptureGeometry.create(
            rawWidth = 4000,
            rawHeight = 3000,
            imageRotationDegrees = 90,
            normalizedViewPoints = emptyList(),
            viewWidth = 0,
            viewHeight = 0,
            targetAspectRatio = 0f,
            isCropModeOff = true,
            focalLength = 24,
            noCropAspectRatio = 0f,
            savedImageRotationDegrees = 0
        )

        assertEquals(3000, geometry.outputWidth)
        assertEquals(4000, geometry.outputHeight)
        assertProjectedPoint(geometry, 0f, 0f, 0f, 1f)
        assertProjectedPoint(geometry, 1f, 0f, 0f, 0f)
        assertProjectedPoint(geometry, 1f, 1f, 1f, 0f)
        assertProjectedPoint(geometry, 0f, 1f, 1f, 1f)
    }

    @Test
    fun sensor_landscape_correction_is_folded_into_same_transform() {
        val geometry = StillCaptureGeometry.create(
            rawWidth = 4000,
            rawHeight = 3000,
            imageRotationDegrees = 90,
            normalizedViewPoints = emptyList(),
            viewWidth = 0,
            viewHeight = 0,
            targetAspectRatio = 0f,
            isCropModeOff = true,
            focalLength = 24,
            noCropAspectRatio = 0f,
            savedImageRotationDegrees = 270
        )

        assertEquals(4000, geometry.outputWidth)
        assertEquals(3000, geometry.outputHeight)
        assertProjectedPoint(geometry, 0f, 0f, 0f, 0f)
        assertProjectedPoint(geometry, 1f, 0f, 1f, 0f)
        assertProjectedPoint(geometry, 1f, 1f, 1f, 1f)
        assertProjectedPoint(geometry, 0f, 1f, 0f, 1f)
    }

    private fun assertProjectedPoint(
        geometry: StillCaptureGeometry,
        outputX: Float,
        outputY: Float,
        expectedRawX: Float,
        expectedRawY: Float
    ) {
        val matrix = geometry.outputToRawHomography
        val divisor = matrix[2] * outputX + matrix[5] * outputY + matrix[8]
        val rawX = (matrix[0] * outputX + matrix[3] * outputY + matrix[6]) / divisor
        val rawY = (matrix[1] * outputX + matrix[4] * outputY + matrix[7]) / divisor
        assertEquals(expectedRawX, rawX, 0.0001f)
        assertEquals(expectedRawY, rawY, 0.0001f)
    }
}
