package com.zhuo.c1cam

import android.view.Surface
import kotlin.math.abs
import kotlin.math.roundToInt

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

data class XpanInfoColumnLayout(
    val left: Int,
    val right: Int,
    val histogramTop: Int,
    val histogramBottom: Int,
    val lcdTop: Int,
    val lcdBottom: Int
)

data class XpanFixedLandscapeInfoLayout(
    val rotationDegrees: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val column: XpanInfoColumnLayout,
    val lcdViewLeft: Int,
    val lcdViewTop: Int
)

object XpanInfoColumnLayoutModel {
    private const val LCD_ASPECT_RATIO = 218f / 112f
    private const val HISTOGRAM_ASPECT_RATIO = 2.8f

    fun calculate(
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        displayRotation: Int
    ): XpanFixedLandscapeInfoLayout {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return XpanFixedLandscapeInfoLayout(
                rotationDegrees = 0,
                logicalWidth = 1,
                logicalHeight = 1,
                column = XpanInfoColumnLayout(0, 1, 0, 1, 0, 1),
                lcdViewLeft = 0,
                lcdViewTop = 0
            )
        }

        val rotationDegrees = OrientationMath.controlRotationDegrees(
            deviceRotation = Surface.ROTATION_90,
            displayRotation = displayRotation
        )
        val swapsAxes = abs(rotationDegrees) == 90
        val logicalWidth = if (swapsAxes) containerHeight else containerWidth
        val logicalHeight = if (swapsAxes) containerWidth else containerHeight
        val column = calculateLandscapeColumn(logicalWidth, logicalHeight, density)
        val lcdCenterX = (column.left + column.right) / 2f
        val lcdCenterY = (column.lcdTop + column.lcdBottom) / 2f
        val mappedCenter = mapPointToDisplay(
            x = lcdCenterX,
            y = lcdCenterY,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            rotationDegrees = rotationDegrees
        )
        val lcdWidth = column.right - column.left
        val lcdHeight = column.lcdBottom - column.lcdTop

        return XpanFixedLandscapeInfoLayout(
            rotationDegrees = rotationDegrees,
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            column = column,
            lcdViewLeft = (mappedCenter.first - lcdWidth / 2f).roundToInt(),
            lcdViewTop = (mappedCenter.second - lcdHeight / 2f).roundToInt()
        )
    }

    private fun calculateLandscapeColumn(
        containerWidth: Int,
        containerHeight: Int,
        density: Float
    ): XpanInfoColumnLayout {
        val safeDensity = density.coerceAtLeast(0.1f)
        val margin = (18f * safeDensity).toInt()
        val gap = (12f * safeDensity).toInt()
        val right = (containerWidth - margin).coerceAtLeast(1)
        val desiredLeft = (containerWidth * 0.62f).toInt()
        val minimumWidth = (120f * safeDensity).toInt().coerceAtMost(right)
        val minimumHistogramHeight = (48f * safeDensity).toInt()
        val maximumLcdHeight = (
            containerHeight - margin * 2 - gap - minimumHistogramHeight
            ).coerceAtLeast(1)
        val maximumProportionalWidth = (maximumLcdHeight * LCD_ASPECT_RATIO).toInt()
        val desiredWidth = right - desiredLeft
        val columnWidth = desiredWidth
            .coerceAtMost(maximumProportionalWidth)
            .coerceAtLeast(minimumWidth.coerceAtMost(right))
        val left = (right - columnWidth).coerceAtLeast(0)

        val lcdHeight = (columnWidth / LCD_ASPECT_RATIO)
            .toInt()
            .coerceAtLeast(1)
        val lcdBottom = (containerHeight - margin).coerceAtLeast(1)
        val lcdTop = (lcdBottom - lcdHeight).coerceAtLeast(0)
        val histogramBottom = (lcdTop - gap).coerceAtLeast(1)
        val availableHistogramHeight = (histogramBottom - margin).coerceAtLeast(1)
        val desiredHistogramHeight = (columnWidth / HISTOGRAM_ASPECT_RATIO)
            .toInt()
            .coerceAtLeast(1)
        val histogramHeight = desiredHistogramHeight.coerceAtMost(availableHistogramHeight)
        val histogramTop = (histogramBottom - histogramHeight).coerceAtLeast(0)

        return XpanInfoColumnLayout(
            left = left,
            right = right,
            histogramTop = histogramTop,
            histogramBottom = histogramBottom,
            lcdTop = lcdTop,
            lcdBottom = lcdBottom
        )
    }

    private fun mapPointToDisplay(
        x: Float,
        y: Float,
        containerWidth: Int,
        containerHeight: Int,
        rotationDegrees: Int
    ): Pair<Float, Float> {
        return when (rotationDegrees) {
            90 -> containerWidth - y to x
            -90 -> y to containerHeight - x
            180, -180 -> containerWidth - x to containerHeight - y
            else -> x to y
        }
    }
}
