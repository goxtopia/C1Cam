package com.zhuo.c1cam.xpan

import android.graphics.Color

enum class XpanInstrumentTheme(
    val storageValue: String,
    val displayName: String,
    val screenTop: Int,
    val screenMiddle: Int,
    val screenBottom: Int,
    val ink: Int,
    val accent: Int
) {
    GREEN(
        storageValue = "green",
        displayName = "Phosphor green",
        screenTop = Color.rgb(187, 194, 142),
        screenMiddle = Color.rgb(157, 166, 114),
        screenBottom = Color.rgb(134, 144, 95),
        ink = Color.rgb(35, 48, 31),
        accent = Color.rgb(214, 255, 66)
    ),
    AMBER(
        storageValue = "amber",
        displayName = "Amber orange",
        screenTop = Color.rgb(222, 177, 103),
        screenMiddle = Color.rgb(190, 137, 69),
        screenBottom = Color.rgb(154, 101, 42),
        ink = Color.rgb(64, 38, 20),
        accent = Color.rgb(255, 181, 77)
    );

    fun inkWithAlpha(alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(ink),
            Color.green(ink),
            Color.blue(ink)
        )
    }

    companion object {
        fun fromStorageValue(value: String?): XpanInstrumentTheme {
            return entries.firstOrNull { it.storageValue == value } ?: GREEN
        }
    }
}
