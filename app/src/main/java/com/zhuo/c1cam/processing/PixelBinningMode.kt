package com.zhuo.c1cam.processing

enum class PixelBinningMode(
    val storageValue: String,
    val factor: Int,
    val displayName: String
) {
    TWO_BY_TWO("2x2", 2, "2×2 → 1"),
    FOUR_BY_FOUR("4x4", 4, "4×4 → 1");

    companion object {
        fun fromStorageValue(value: String?): PixelBinningMode {
            return entries.firstOrNull { it.storageValue == value } ?: TWO_BY_TWO
        }
    }
}
