package com.zhuo.c1cam

data class CropFrameGuideRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f
}

object CropFrameGuideModel {
    private const val BASE_FOCAL_LENGTH = 24
    private const val DEFAULT_SOURCE_ASPECT_RATIO = 3f / 4f

    fun shouldShowGuide(
        isSettingEnabled: Boolean,
        isCropModeOff: Boolean,
        previewDisplayMode: PreviewDisplayMode
    ): Boolean {
        return isSettingEnabled && isCropModeOff && previewDisplayMode == PreviewDisplayMode.CAMERA
    }

    fun projectedFrameRectInView(
        sourceAspectRatio: Float,
        viewAspectRatio: Float,
        focalLength: Int,
        aspectRatio: Float
    ): CropFrameGuideRect {
        val imageRect = normalizedFrameRect(sourceAspectRatio, focalLength, aspectRatio)
        val safeSourceAspectRatio = sourceAspectRatio.takeIf { it > 0f } ?: DEFAULT_SOURCE_ASPECT_RATIO
        val safeViewAspectRatio = viewAspectRatio.takeIf { it > 0f } ?: safeSourceAspectRatio

        val displayedWidth: Float
        val displayedHeight: Float
        val insetX: Float
        val insetY: Float

        if (safeViewAspectRatio > safeSourceAspectRatio) {
            displayedWidth = safeSourceAspectRatio / safeViewAspectRatio
            displayedHeight = 1f
            insetX = (1f - displayedWidth) / 2f
            insetY = 0f
        } else {
            displayedWidth = 1f
            displayedHeight = safeViewAspectRatio / safeSourceAspectRatio
            insetX = 0f
            insetY = (1f - displayedHeight) / 2f
        }

        return CropFrameGuideRect(
            left = insetX + imageRect.left * displayedWidth,
            top = insetY + imageRect.top * displayedHeight,
            right = insetX + imageRect.right * displayedWidth,
            bottom = insetY + imageRect.bottom * displayedHeight
        )
    }

    fun normalizedFrameRect(
        sourceAspectRatio: Float,
        focalLength: Int,
        aspectRatio: Float
    ): CropFrameGuideRect {
        val focalRect = focalRect(focalLength)
        if (aspectRatio <= 0f) {
            return focalRect
        }

        val safeSourceAspectRatio = sourceAspectRatio.takeIf { it > 0f } ?: DEFAULT_SOURCE_ASPECT_RATIO
        val sourceIsLandscape = safeSourceAspectRatio >= 1f
        val targetIsLandscape = aspectRatio >= 1f
        val effectiveAspectRatio = if (sourceIsLandscape == targetIsLandscape) {
            aspectRatio
        } else {
            1f / aspectRatio
        }

        val currentRatio = (focalRect.width / focalRect.height) * safeSourceAspectRatio
        var cropWidth = focalRect.width
        var cropHeight = focalRect.height

        if (currentRatio > effectiveAspectRatio) {
            cropWidth = focalRect.height * effectiveAspectRatio / safeSourceAspectRatio
        } else {
            cropHeight = focalRect.width * safeSourceAspectRatio / effectiveAspectRatio
        }

        val left = focalRect.left + (focalRect.width - cropWidth) / 2f
        val top = focalRect.top + (focalRect.height - cropHeight) / 2f
        return CropFrameGuideRect(
            left = left,
            top = top,
            right = left + cropWidth,
            bottom = top + cropHeight
        )
    }

    private fun focalRect(focalLength: Int): CropFrameGuideRect {
        if (focalLength <= BASE_FOCAL_LENGTH) {
            return CropFrameGuideRect(0f, 0f, 1f, 1f)
        }

        val scale = focalLength / BASE_FOCAL_LENGTH.toFloat()
        val width = 1f / scale
        val height = 1f / scale
        val left = (1f - width) / 2f
        val top = (1f - height) / 2f
        return CropFrameGuideRect(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height
        )
    }
}
