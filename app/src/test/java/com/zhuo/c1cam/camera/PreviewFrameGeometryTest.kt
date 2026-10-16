package com.zhuo.c1cam.camera

import com.zhuo.c1cam.xpan.XpanMode
import org.junit.Assert.*
import org.junit.Test

class PreviewFrameGeometryTest {
    @Test fun xpanCropsSameSensorBandInAllFourOrientations() {
        val top = (3000f - 4000f / XpanMode.ASPECT_RATIO) / 6000f
        val bottom = 1f - top
        val expected = listOf(
            listOf(0f to top, 1f to top, 1f to bottom, 0f to bottom),
            listOf(0f to bottom, 0f to top, 1f to top, 1f to bottom),
            listOf(1f to bottom, 0f to bottom, 0f to top, 1f to top),
            listOf(1f to top, 1f to bottom, 0f to bottom, 0f to top)
        )
        listOf(0, 90, 180, 270).forEachIndexed { index, rotation ->
            val geometry = PreviewFrameGeometry.forXpan(4000, 3000, rotation, XpanMode.ASPECT_RATIO)
            val ratio = geometry.outputWidth.toFloat() / geometry.outputHeight
            assertEquals(if (rotation % 180 == 0) XpanMode.ASPECT_RATIO else 1f / XpanMode.ASPECT_RATIO,
                ratio, 0.002f)
            val corners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
            corners.forEachIndexed { corner, (x, y) ->
                val matrix = geometry.outputToRawHomography
                val divisor = matrix[2] * x + matrix[5] * y + matrix[8]
                assertEquals(expected[index][corner].first,
                    (matrix[0] * x + matrix[3] * y + matrix[6]) / divisor, 0.0001f)
                assertEquals(expected[index][corner].second,
                    (matrix[1] * x + matrix[4] * y + matrix[7]) / divisor, 0.0001f)
            }
        }
    }

    @Test fun customAndOriginalAspectRatiosAreRespectedWithoutApplyingZoomTwice() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val original = PreviewFrameGeometry.forXpan(4000, 3000, rotation, 0f)
            val custom = PreviewFrameGeometry.forXpan(4000, 3000, rotation, 2f)
            assertEquals(if (rotation % 180 == 0) 4000 else 3000, original.outputWidth)
            assertEquals(if (rotation % 180 == 0) 3000 else 4000, original.outputHeight)
            assertEquals(if (rotation % 180 == 0) 2f else 0.5f,
                custom.outputWidth.toFloat() / custom.outputHeight, 0.002f)
        }
    }
}
