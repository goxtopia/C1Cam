package com.zhuo.c1cam

enum class XpanMeteringMode(
    val storageValue: String,
    val shortLabel: String,
    val displayName: String
) {
    CENTER_WEIGHTED("center_weighted", "M·CTR", "Center-weighted metering"),
    AVERAGE("average", "M·AVG", "Average metering"),
    SPOT("spot", "M·SPT", "Spot metering");

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
