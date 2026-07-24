package com.zhuo.c1cam.camera

import com.zhuo.c1cam.processing.ImageProcessor
import android.view.Surface

object OrientationMath {
    fun controlRotationDegrees(deviceRotation: Int, displayRotation: Int): Int {
        return normalizeSignedDegrees(
            surfaceRotationDegrees(deviceRotation) - surfaceRotationDegrees(displayRotation)
        )
    }

    /**
     * ImageProxy is already rotated into the display orientation by ImageProcessor.
     * Rotate in the opposite direction to recover the physical capture orientation.
     */
    fun savedImageRotationDegrees(deviceRotation: Int, displayRotation: Int): Int {
        return normalizeClockwiseDegrees(
            surfaceRotationDegrees(displayRotation) - surfaceRotationDegrees(deviceRotation)
        )
    }

    private fun surfaceRotationDegrees(rotation: Int): Int {
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun normalizeSignedDegrees(degrees: Int): Int {
        return ((degrees + 540) % 360) - 180
    }

    private fun normalizeClockwiseDegrees(degrees: Int): Int {
        return ((degrees % 360) + 360) % 360
    }
}
