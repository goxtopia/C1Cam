package com.zhuo.c1cam

enum class PreviewDisplayMode(val storageValue: String) {
    CAMERA("camera"),
    RECTIFIED("rectified");

    fun toggled(): PreviewDisplayMode = if (this == CAMERA) RECTIFIED else CAMERA

    companion object {
        fun fromStorageValue(value: String?): PreviewDisplayMode {
            return entries.firstOrNull { it.storageValue == value } ?: CAMERA
        }
    }
}

object PreviewDisplayUiModel {
    fun toggleButtonLabel(mode: PreviewDisplayMode): String {
        return if (mode == PreviewDisplayMode.CAMERA) "LUT" else "CAM"
    }
}
