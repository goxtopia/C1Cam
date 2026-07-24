package com.zhuo.c1cam.capture

data class CaptureMetadata(
    val capturedAtMillis: Long,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float?,
    val physicalFocalLengthMm: Float?,
    val equivalentFocalLengthMm: Int,
    val exposureCompensationEv: Float,
    val isAutoWhiteBalance: Boolean?,
    val isFlashFired: Boolean?
)
