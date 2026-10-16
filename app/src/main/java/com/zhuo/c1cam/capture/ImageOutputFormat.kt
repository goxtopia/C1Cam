package com.zhuo.c1cam.capture

enum class ImageOutputFormat(
    val storageValue: String,
    val extension: String,
    val mimeType: String,
    val displayName: String
) {
    JPEG("jpeg", "jpg", "image/jpeg", "JPG"),
    PNG("png", "png", "image/png", "PNG"),
    JPEG_DNG("jpeg_dng", "jpg", "image/jpeg", "JPG + DNG");

    val includesRaw: Boolean get() = this == JPEG_DNG
    val bitmapFormat: ImageOutputFormat get() = if (includesRaw) JPEG else this

    companion object {
        fun fromStorageValue(value: String?): ImageOutputFormat {
            return entries.firstOrNull { it.storageValue == value } ?: JPEG
        }
    }
}
