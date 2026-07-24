package com.zhuo.c1cam.processing

enum class ChromaDenoiseMode(
    val storageValue: String,
    val displayName: String
) {
    OFF("off", "Off"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    AUTO("auto", "Auto");

    fun resolveForIso(iso: Int?): ChromaDenoiseMode {
        if (this != AUTO) return this
        return when {
            iso == null -> MEDIUM
            iso <= 100 -> OFF
            iso <= 400 -> LOW
            iso <= 1600 -> MEDIUM
            else -> HIGH
        }
    }

    companion object {
        fun fromStorageValue(value: String?): ChromaDenoiseMode? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}
