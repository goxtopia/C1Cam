package com.zhuo.c1cam.camera

import com.zhuo.c1cam.xpan.XpanMode

internal object PreviewFrameGeometry {
    /** Same centered sensor crop as the photo, upright for the fixed display orientation. */
    fun forXpan(width: Int, height: Int, rotationDegrees: Int, aspectRatio: Float): StillCaptureGeometry =
        StillCaptureGeometry.create(width, height, rotationDegrees, emptyList(), width, height,
            0f, true, XpanMode.effectiveProcessingFocalLength(true, 24),
            XpanMode.effectiveNoCropAspectRatio(true, 0f, aspectRatio), 0)
}
