package com.zhuo.c1cam

enum class XpanUiLayout(
    val storageValue: String,
    val displayName: String,
    val description: String
) {
    SCHEME_1(
        storageValue = "scheme_1",
        displayName = "Scheme 1",
        description = "Compact viewfinder with a full instrument grid"
    ),
    SCHEME_2(
        storageValue = "scheme_2",
        displayName = "Scheme 2",
        description = "Full-width viewfinder with a lower instrument rail"
    );

    companion object {
        fun fromStorageValue(value: String?): XpanUiLayout {
            return entries.firstOrNull { it.storageValue == value } ?: SCHEME_1
        }
    }
}
