package com.zhuo.c1cam.capture

enum class ImageOutputFormat(
    val storageValue: String,
    val extension: String,
    val mimeType: String
) {
    JPEG("jpeg", "jpg", "image/jpeg"),
    PNG("png", "png", "image/png");

    companion object {
        fun fromStorageValue(value: String?): ImageOutputFormat {
            return entries.firstOrNull { it.storageValue == value } ?: JPEG
        }
    }
}
