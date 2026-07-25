package com.zhuo.c1cam.processing

import android.graphics.Bitmap

object HighIsoPixelBinning {
    const val DEFAULT_ISO_THRESHOLD = 1600
    val isoThresholdChoices = listOf(400, 800, 1600, 3200, 6400)

    fun sanitizeThreshold(value: Int): Int {
        return value.takeIf { it in isoThresholdChoices } ?: DEFAULT_ISO_THRESHOLD
    }

    fun resolveFactor(
        enabled: Boolean,
        mode: PixelBinningMode,
        isoThreshold: Int,
        captureIso: Int?
    ): Int {
        return if (enabled && captureIso != null && captureIso > isoThreshold) {
            mode.factor
        } else {
            1
        }
    }

    fun process(bitmap: Bitmap, factor: Int): Bitmap {
        require(factor == 2 || factor == 4) { "Pixel binning factor must be 2 or 4" }
        val outputWidth = bitmap.width / factor
        val outputHeight = bitmap.height / factor
        if (outputWidth < 1 || outputHeight < 1) return bitmap

        val outputPixels = IntArray(outputWidth * outputHeight)
        val sourceRows = IntArray(bitmap.width * factor)
        for (outputY in 0 until outputHeight) {
            bitmap.getPixels(
                sourceRows,
                0,
                bitmap.width,
                0,
                outputY * factor,
                bitmap.width,
                factor
            )
            binRows(
                sourcePixels = sourceRows,
                sourceWidth = bitmap.width,
                factor = factor,
                outputPixels = outputPixels,
                outputOffset = outputY * outputWidth,
                outputWidth = outputWidth
            )
        }

        return Bitmap.createBitmap(
            outputPixels,
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )
    }

    internal fun binPixels(
        sourcePixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        factor: Int
    ): IntArray {
        require(factor == 2 || factor == 4) { "Pixel binning factor must be 2 or 4" }
        require(sourceWidth > 0 && sourceHeight > 0)
        require(sourcePixels.size >= sourceWidth * sourceHeight)

        val outputWidth = sourceWidth / factor
        val outputHeight = sourceHeight / factor
        val outputPixels = IntArray(outputWidth * outputHeight)
        for (outputY in 0 until outputHeight) {
            binRows(
                sourcePixels = sourcePixels,
                sourceWidth = sourceWidth,
                factor = factor,
                outputPixels = outputPixels,
                outputOffset = outputY * outputWidth,
                outputWidth = outputWidth,
                sourceOffset = outputY * factor * sourceWidth
            )
        }
        return outputPixels
    }

    private fun binRows(
        sourcePixels: IntArray,
        sourceWidth: Int,
        factor: Int,
        outputPixels: IntArray,
        outputOffset: Int,
        outputWidth: Int,
        sourceOffset: Int = 0
    ) {
        val sampleCount = factor * factor
        for (outputX in 0 until outputWidth) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            val blockLeft = outputX * factor
            for (row in 0 until factor) {
                var index = sourceOffset + row * sourceWidth + blockLeft
                repeat(factor) {
                    val color = sourcePixels[index++]
                    alpha += color ushr 24
                    red += color ushr 16 and 0xff
                    green += color ushr 8 and 0xff
                    blue += color and 0xff
                }
            }
            val rounding = sampleCount / 2
            outputPixels[outputOffset + outputX] =
                ((alpha + rounding) / sampleCount shl 24) or
                    ((red + rounding) / sampleCount shl 16) or
                    ((green + rounding) / sampleCount shl 8) or
                    ((blue + rounding) / sampleCount)
        }
    }
}
