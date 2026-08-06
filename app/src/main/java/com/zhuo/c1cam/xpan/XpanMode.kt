package com.zhuo.c1cam.xpan

import com.zhuo.c1cam.camera.FocusMode
import com.zhuo.c1cam.camera.OrientationMath
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

    fun effectiveNoCropAspectRatio(
        enabled: Boolean,
        configured: Float,
        xpanConfigured: Float = ASPECT_RATIO
    ): Float {
        return if (enabled) {
            if (xpanConfigured <= 0f) 0f else xpanConfigured.coerceIn(1f, ASPECT_RATIO)
        } else {
            configured
        }
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

data class XpanFramePosition(
    val left: Int,
    val top: Int
)

object XpanFrameLayoutModel {
    fun calculate(
        containerWidth: Int,
        containerHeight: Int,
        uiLayout: XpanUiLayout = XpanUiLayout.SCHEME_1,
        aspectRatio: Float = XpanMode.ASPECT_RATIO,
        sourceAspectRatio: Float = 4f / 3f
    ): XpanFrameSize {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return XpanFrameSize(1, 1)
        }

        val isFullViewfinder = uiLayout == XpanUiLayout.SCHEME_2
        val xpanFrame = if (containerWidth > containerHeight) {
            val desiredWidth = containerWidth * if (isFullViewfinder) 0.94f else 0.72f
            val heightLimitedWidth = containerHeight *
                (if (isFullViewfinder) 0.64f else 0.52f) *
                XpanMode.ASPECT_RATIO
            val width = minOf(desiredWidth, heightLimitedWidth).toInt().coerceAtLeast(1)
            XpanFrameSize(
                width = width,
                height = (width / XpanMode.ASPECT_RATIO).toInt().coerceAtLeast(1)
            )
        } else {
            val desiredHeight = containerHeight * if (isFullViewfinder) 0.94f else 0.64f
            val widthLimitedHeight = containerWidth *
                (if (isFullViewfinder) 0.64f else 0.31f) *
                XpanMode.ASPECT_RATIO
            val height = minOf(desiredHeight, widthLimitedHeight).toInt().coerceAtLeast(1)
            XpanFrameSize(
                width = (height / XpanMode.ASPECT_RATIO).toInt().coerceAtLeast(1),
                height = height
            )
        }

        val frameRatio = effectiveFrameAspectRatio(aspectRatio, sourceAspectRatio)
        return if (containerWidth > containerHeight) {
            XpanFrameSize(
                width = (xpanFrame.height * frameRatio).toInt().coerceAtLeast(1),
                height = xpanFrame.height
            )
        } else {
            XpanFrameSize(
                width = xpanFrame.width,
                height = (xpanFrame.width * frameRatio).toInt().coerceAtLeast(1)
            )
        }
    }

    fun effectiveFrameAspectRatio(
        aspectRatio: Float,
        sourceAspectRatio: Float = 4f / 3f
    ): Float {
        val sourceLongToShort = sourceAspectRatio
            .takeIf { it > 0f }
            ?.let { maxOf(it, 1f / it) }
            ?: 4f / 3f
        return (if (aspectRatio > 0f) aspectRatio else sourceLongToShort)
            .coerceIn(1f, XpanMode.ASPECT_RATIO)
    }
}

object XpanFramePositionModel {
    fun calculate(
        containerWidth: Int,
        containerHeight: Int,
        frame: XpanFrameSize,
        nativeFrame: XpanFrameSize,
        uiLayout: XpanUiLayout,
        landscapeStartMargin: Int,
        portraitCompactEndMargin: Int,
        portraitFullEndMargin: Int,
        topMargin: Int
    ): XpanFramePosition {
        if (containerWidth > containerHeight) {
            val nativeLeft = if (uiLayout == XpanUiLayout.SCHEME_2) {
                (containerWidth - nativeFrame.width) / 2
            } else {
                landscapeStartMargin
            }
            val left = if (uiLayout == XpanUiLayout.SCHEME_2) {
                nativeLeft + (nativeFrame.width - frame.width) / 2
            } else {
                nativeLeft
            }
            return XpanFramePosition(
                left = left.coerceAtLeast(0),
                top = topMargin.coerceAtLeast(0)
            )
        }

        val endMargin = if (uiLayout == XpanUiLayout.SCHEME_2) {
            portraitFullEndMargin
        } else {
            portraitCompactEndMargin
        }
        val top = if (uiLayout == XpanUiLayout.SCHEME_2) {
            topMargin + (nativeFrame.height - frame.height) / 2
        } else {
            topMargin
        }
        return XpanFramePosition(
            left = (containerWidth - endMargin - frame.width).coerceAtLeast(0),
            top = top.coerceAtLeast(0)
        )
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
    val histogramLeft: Int,
    val histogramRight: Int,
    val histogramTop: Int,
    val histogramBottom: Int,
    val levelLeft: Int,
    val levelRight: Int,
    val levelTop: Int,
    val levelBottom: Int,
    val lcdLeft: Int,
    val lcdRight: Int,
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
    // The instrument group starts after the primary framing area and always reaches
    // the far content edge. Only its row height may compress on shorter displays.
    private const val DASHBOARD_POSITION_FRACTION = 0.44f
    private const val TOP_ROW_HEIGHT_FRACTION = 0.43f

    fun calculate(
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        displayRotation: Int,
        uiLayout: XpanUiLayout = XpanUiLayout.SCHEME_1
    ): XpanFixedLandscapeInfoLayout {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return XpanFixedLandscapeInfoLayout(
                rotationDegrees = 0,
                logicalWidth = 1,
                logicalHeight = 1,
                column = XpanInfoColumnLayout(
                    left = 0,
                    right = 1,
                    histogramLeft = 0,
                    histogramRight = 1,
                    histogramTop = 0,
                    histogramBottom = 1,
                    levelLeft = 0,
                    levelRight = 1,
                    levelTop = 0,
                    levelBottom = 1,
                    lcdLeft = 0,
                    lcdRight = 1,
                    lcdTop = 0,
                    lcdBottom = 1
                ),
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
        val column = when (uiLayout) {
            XpanUiLayout.SCHEME_1 ->
                calculateScheme1(logicalWidth, logicalHeight, density)
            XpanUiLayout.SCHEME_2 ->
                calculateScheme2(logicalWidth, logicalHeight, density)
        }
        val lcdCenterX = (column.lcdLeft + column.lcdRight) / 2f
        val lcdCenterY = (column.lcdTop + column.lcdBottom) / 2f
        val mappedCenter = mapPointToDisplay(
            x = lcdCenterX,
            y = lcdCenterY,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            rotationDegrees = rotationDegrees
        )
        val lcdWidth = column.lcdRight - column.lcdLeft
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

    private fun calculateScheme1(
        containerWidth: Int,
        containerHeight: Int,
        density: Float
    ): XpanInfoColumnLayout {
        val safeDensity = density.coerceAtLeast(0.1f)
        val margin = (18f * safeDensity).toInt()
        val gap = (6f * safeDensity).toInt()
        val maximumRight = (containerWidth - margin).coerceAtLeast(1)
        val left = (containerWidth * DASHBOARD_POSITION_FRACTION)
            .toInt()
            .coerceIn(0, (maximumRight - 1).coerceAtLeast(0))
        val right = maximumRight
        val columnWidth = (right - left).coerceAtLeast(1)

        // Both rows share one exact thickness. When the fixed-landscape grid is
        // mapped into a portrait display this makes the two visible columns equal
        // width and prevents the lower panel from protruding past the upper pair.
        val maximumRowHeight = (
            (containerHeight - margin * 2 - gap) / 2
            ).coerceAtLeast(1)
        val rowHeight = (columnWidth * TOP_ROW_HEIGHT_FRACTION)
            .toInt()
            .coerceAtMost(maximumRowHeight)
            .coerceAtLeast(1)
        val lcdHeight = rowHeight
        val lcdBottom = (containerHeight - margin).coerceAtLeast(1)
        val lcdTop = (lcdBottom - lcdHeight).coerceAtLeast(0)
        val topRowBottom = (lcdTop - gap).coerceAtLeast(1)
        val topRowHeight = rowHeight.coerceAtMost(topRowBottom)
        val topRowTop = (topRowBottom - topRowHeight).coerceAtLeast(0)

        // Scheme 1 maps into an L shape on the portrait display: the scope sits
        // above the ISO/SS panel on the left, while the level occupies the lower
        // right cell. Keeping the level and LCD on the same logical X span makes
        // their visible portrait edges align after the fixed-landscape rotation.
        val histogramWidth = lcdHeight
            .coerceAtMost((columnWidth - gap).coerceAtLeast(1))
        val histogramRight = (left + histogramWidth).coerceAtMost(right)
        val lcdLeft = (histogramRight + gap).coerceAtMost(right)

        return XpanInfoColumnLayout(
            left = left,
            right = right,
            histogramLeft = left,
            histogramRight = histogramRight,
            histogramTop = lcdTop,
            histogramBottom = lcdBottom,
            levelLeft = lcdLeft,
            levelRight = right,
            levelTop = topRowTop,
            levelBottom = topRowBottom,
            lcdLeft = lcdLeft,
            lcdRight = right,
            lcdTop = lcdTop,
            lcdBottom = lcdBottom
        )
    }

    private fun calculateScheme2(
        containerWidth: Int,
        containerHeight: Int,
        density: Float
    ): XpanInfoColumnLayout {
        val safeDensity = density.coerceAtLeast(0.1f)
        val margin = (18f * safeDensity).toInt()
        val gap = (6f * safeDensity).toInt()
        val right = (containerWidth - margin).coerceAtLeast(1)
        val bottom = (containerHeight - margin).coerceAtLeast(1)

        val viewfinderWidth = minOf(
            containerWidth * 0.94f,
            containerHeight * 0.64f * XpanMode.ASPECT_RATIO
        )
        val viewfinderBottom = margin + viewfinderWidth / XpanMode.ASPECT_RATIO
        val railTop = (viewfinderBottom + gap * 2f)
            .toInt()
            .coerceAtMost((bottom - 1).coerceAtLeast(0))
        val availableRailHeight = (bottom - railTop).coerceAtLeast(1)
        val inscriptionRight = (containerWidth * 0.28f).toInt()
        val availableInstrumentWidth = (right - inscriptionRight).coerceAtLeast(1)

        val histogramRatio = 1f
        val levelRatio = 1.22f
        val lcdRatio = 2.35f
        val totalRatio = histogramRatio + levelRatio + lcdRatio
        val instrumentHeight = minOf(
            availableRailHeight.toFloat(),
            (availableInstrumentWidth - gap * 2f).coerceAtLeast(1f) / totalRatio
        ).toInt().coerceAtLeast(1)
        val groupWidth = (
            instrumentHeight * totalRatio + gap * 2f
            ).toInt()
            .coerceAtMost(availableInstrumentWidth)
            .coerceAtLeast(1)
        val groupLeft = (right - groupWidth).coerceAtLeast(inscriptionRight)
        val instrumentTop = railTop +
            (availableRailHeight - instrumentHeight).coerceAtLeast(0) / 2
        val instrumentBottom = (instrumentTop + instrumentHeight).coerceAtMost(bottom)

        val histogramLeft = groupLeft
        val histogramRight = (
            histogramLeft + instrumentHeight * histogramRatio
            ).toInt().coerceAtMost(right)
        val levelLeft = (histogramRight + gap).coerceAtMost(right)
        val levelRight = (
            levelLeft + instrumentHeight * levelRatio
            ).toInt().coerceAtMost(right)
        val lcdLeft = (levelRight + gap).coerceAtMost(right)

        return XpanInfoColumnLayout(
            left = groupLeft,
            right = right,
            histogramLeft = histogramLeft,
            histogramRight = histogramRight,
            histogramTop = instrumentTop,
            histogramBottom = instrumentBottom,
            levelLeft = levelLeft,
            levelRight = levelRight,
            levelTop = instrumentTop,
            levelBottom = instrumentBottom,
            lcdLeft = lcdLeft,
            lcdRight = right,
            lcdTop = instrumentTop,
            lcdBottom = instrumentBottom
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
