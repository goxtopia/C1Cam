package com.zhuo.c1cam.xpan

import kotlin.math.abs
import kotlin.math.max

enum class XpanMeteringMode(
    val storageValue: String,
    val displayName: String
) {
    CENTER_WEIGHTED("center_weighted", "Center-weighted metering"),
    AVERAGE("average", "Average metering"),
    SPOT("spot", "Spot metering");

    fun next(): XpanMeteringMode {
        val modes = entries
        return modes[(ordinal + 1) % modes.size]
    }

    companion object {
        fun fromStorageValue(value: String?): XpanMeteringMode {
            return entries.firstOrNull { it.storageValue == value } ?: CENTER_WEIGHTED
        }
    }
}

data class NormalizedMeteringRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val weight: Int
)

/**
 * Describes AE regions independently of Camera2 so the behavior remains testable.
 * Coordinates are normalized against the effective XPAN frame on the sensor.
 */
object XpanMeteringRegionModel {
    fun regionsFor(
        mode: XpanMeteringMode,
        maxRegionCount: Int
    ): List<NormalizedMeteringRegion> {
        if (maxRegionCount <= 0) return emptyList()

        return when (mode) {
            XpanMeteringMode.AVERAGE -> listOf(
                NormalizedMeteringRegion(0f, 0f, 1f, 1f, 1000)
            )

            XpanMeteringMode.CENTER_WEIGHTED -> {
                if (maxRegionCount >= 2) {
                    listOf(
                        NormalizedMeteringRegion(0f, 0f, 1f, 1f, 200),
                        NormalizedMeteringRegion(0.2f, 0.2f, 0.8f, 0.8f, 1000)
                    )
                } else {
                    listOf(
                        NormalizedMeteringRegion(0.15f, 0.15f, 0.85f, 0.85f, 1000)
                    )
                }
            }

            XpanMeteringMode.SPOT -> listOf(
                NormalizedMeteringRegion(0.425f, 0.425f, 0.575f, 0.575f, 1000)
            )
        }.take(maxRegionCount)
    }
}

data class XpanMeteringFrame(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object XpanSoftwareMeteringModel {
    fun frameFor(imageWidth: Int, imageHeight: Int): XpanMeteringFrame {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return XpanMeteringFrame(0, 0, 1, 1)
        }
        val sourceRatio = imageWidth.toFloat() / imageHeight.toFloat()
        return if (sourceRatio > XpanMode.ASPECT_RATIO) {
            val width = (imageHeight * XpanMode.ASPECT_RATIO)
                .toInt()
                .coerceIn(1, imageWidth)
            val left = (imageWidth - width) / 2
            XpanMeteringFrame(left, 0, left + width, imageHeight)
        } else {
            val height = (imageWidth / XpanMode.ASPECT_RATIO)
                .toInt()
                .coerceIn(1, imageHeight)
            val top = (imageHeight - height) / 2
            XpanMeteringFrame(0, top, imageWidth, top + height)
        }
    }

    fun sampleWeight(mode: XpanMeteringMode, normalizedX: Float, normalizedY: Float): Float {
        if (normalizedX !in 0f..1f || normalizedY !in 0f..1f) return 0f
        return when (mode) {
            XpanMeteringMode.AVERAGE -> 1f
            XpanMeteringMode.CENTER_WEIGHTED -> {
                val xDistance = abs(normalizedX - 0.5f) * 2f
                val yDistance = abs(normalizedY - 0.5f) * 2f
                val distance = max(xDistance, yDistance).coerceIn(0f, 1f)
                1f + 4f * (1f - distance) * (1f - distance)
            }
            XpanMeteringMode.SPOT -> {
                if (abs(normalizedX - 0.5f) <= 0.075f &&
                    abs(normalizedY - 0.5f) <= 0.075f
                ) {
                    1f
                } else {
                    0f
                }
            }
        }
    }

    fun normalizeVideoLuma(value: Int): Float {
        return ((value.coerceIn(0, 255) - 16f) / 219f).coerceIn(0f, 1f)
    }
}
