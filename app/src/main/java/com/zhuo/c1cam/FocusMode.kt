package com.zhuo.c1cam

enum class FocusMode(val storageValue: String) {
    MANUAL("manual"),
    AUTO("auto");

    fun toggled(): FocusMode = if (this == MANUAL) AUTO else MANUAL

    companion object {
        fun fromStorageValue(value: String?): FocusMode {
            return entries.firstOrNull { it.storageValue == value } ?: MANUAL
        }
    }
}

object FocusModeUiModel {
    fun buttonLabel(mode: FocusMode): String {
        return if (mode == FocusMode.AUTO) "AF" else "MF"
    }

    fun isFocusSliderEnabled(mode: FocusMode): Boolean {
        return mode == FocusMode.MANUAL
    }

    fun isTapToFocusEnabled(mode: FocusMode, isTapToFocusSettingEnabled: Boolean): Boolean {
        return mode == FocusMode.AUTO && isTapToFocusSettingEnabled
    }
}
