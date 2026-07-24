package com.zhuo.c1cam

object XpanMode {
    const val FRAME_WIDTH = 65f
    const val FRAME_HEIGHT = 24f
    const val ASPECT_RATIO = FRAME_WIDTH / FRAME_HEIGHT

    const val PREVIEW_WIDTH = 960
    const val PREVIEW_HEIGHT = 540
    const val ANALYSIS_WIDTH = 640
    const val ANALYSIS_HEIGHT = 360

    fun effectiveFocusMode(enabled: Boolean, configured: FocusMode): FocusMode {
        return if (enabled) FocusMode.AUTO else configured
    }

    fun effectiveCropModeOff(enabled: Boolean, configured: Boolean): Boolean {
        return enabled || configured
    }

    fun effectiveNoCropAspectRatio(enabled: Boolean, configured: Float): Float {
        return if (enabled) ASPECT_RATIO else configured
    }

    /**
     * CameraX zoom supplies the XPAN focal-length crop before the still buffer reaches
     * the processing pipeline, so the software crop must stay at the 24 mm baseline.
     */
    fun effectiveProcessingFocalLength(enabled: Boolean, configured: Int): Int {
        return if (enabled) 24 else configured
    }
}

data class XpanFrameSize(
    val width: Int,
    val height: Int
)

object XpanFrameLayoutModel {
    fun calculate(containerWidth: Int, containerHeight: Int): XpanFrameSize {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return XpanFrameSize(1, 1)
        }

        return if (containerWidth > containerHeight) {
            val desiredWidth = containerWidth * 0.72f
            val heightLimitedWidth = containerHeight * 0.52f * XpanMode.ASPECT_RATIO
            val width = minOf(desiredWidth, heightLimitedWidth).toInt().coerceAtLeast(1)
            XpanFrameSize(
                width = width,
                height = (width / XpanMode.ASPECT_RATIO).toInt().coerceAtLeast(1)
            )
        } else {
            val desiredHeight = containerHeight * 0.64f
            val widthLimitedHeight = containerWidth * 0.31f * XpanMode.ASPECT_RATIO
            val height = minOf(desiredHeight, widthLimitedHeight).toInt().coerceAtLeast(1)
            XpanFrameSize(
                width = (height / XpanMode.ASPECT_RATIO).toInt().coerceAtLeast(1),
                height = height
            )
        }
    }
}

data class XpanTelemetry(
    val histogram: FloatArray = FloatArray(64),
    val iso: Int? = null,
    val exposureTimeNs: Long? = null
)
