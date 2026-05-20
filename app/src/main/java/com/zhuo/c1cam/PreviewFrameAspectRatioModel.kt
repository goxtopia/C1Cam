package com.zhuo.c1cam

object PreviewFrameAspectRatioModel {
    private const val DEFAULT_ASPECT_RATIO = 3f / 4f

    fun fromFrame(width: Int, height: Int, rotationDegrees: Int): Float {
        if (width <= 0 || height <= 0) return DEFAULT_ASPECT_RATIO
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val displayWidth = if (rotated) height else width
        val displayHeight = if (rotated) width else height
        if (displayWidth <= 0 || displayHeight <= 0) return DEFAULT_ASPECT_RATIO
        return displayWidth.toFloat() / displayHeight.toFloat()
    }
}
