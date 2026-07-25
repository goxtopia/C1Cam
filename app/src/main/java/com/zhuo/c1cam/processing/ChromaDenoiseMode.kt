package com.zhuo.c1cam.processing

enum class ChromaDenoiseMode(
    val storageValue: String,
    val displayName: String
) {
    OFF("off", "Off"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    VERY_HIGH("very_high", "Very high"),
    VERY_HIGH_LUMA("very_high_luma", "Very high · Luma"),
    XHIGH_LUMA("xhigh_luma", "Extra high · Luma"),
    AUTO("auto", "Auto"),
    AUTO_HIGH("auto_high", "Auto · High"),
    AUTO_HIGH_LUMA("auto_high_luma", "Auto · High · Luma"),
    AUTO_XHIGH_LUMA("auto_xhigh_luma", "Auto · Extra high · Luma");

    fun resolveForIso(iso: Int?): ChromaDenoiseMode {
        return when (this) {
            AUTO -> when {
                iso == null -> MEDIUM
                iso <= 100 -> OFF
                iso <= 400 -> LOW
                iso <= 1600 -> MEDIUM
                else -> HIGH
            }
            AUTO_HIGH -> when {
                iso == null -> HIGH
                iso <= 100 -> LOW
                iso <= 400 -> MEDIUM
                iso <= 1600 -> HIGH
                else -> VERY_HIGH
            }
            AUTO_HIGH_LUMA -> when {
                iso == null -> HIGH
                iso <= 100 -> LOW
                iso <= 400 -> MEDIUM
                iso <= 1600 -> HIGH
                else -> VERY_HIGH_LUMA
            }
            AUTO_XHIGH_LUMA -> when {
                iso == null -> VERY_HIGH_LUMA
                iso <= 100 -> MEDIUM
                iso <= 400 -> HIGH
                iso <= 1600 -> VERY_HIGH_LUMA
                else -> XHIGH_LUMA
            }
            else -> this
        }
    }

    companion object {
        fun fromStorageValue(value: String?): ChromaDenoiseMode? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}
