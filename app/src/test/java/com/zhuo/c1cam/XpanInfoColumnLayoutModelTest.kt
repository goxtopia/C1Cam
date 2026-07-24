package com.zhuo.c1cam

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XpanInfoColumnLayoutModelTest {

    @Test
    fun portraitMapsBothInstrumentsIntoFixedLeftLandscapeOrientation() {
        val layout = XpanInfoColumnLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1695,
            density = 3f,
            displayRotation = Surface.ROTATION_0
        )

        assertEquals(90, layout.rotationDegrees)
        assertEquals(1695, layout.logicalWidth)
        assertEquals(1080, layout.logicalHeight)
        assertTrue(layout.column.histogramBottom < layout.column.lcdTop)
        val columnWidth = layout.column.right - layout.column.left
        val lcdHeight = layout.column.lcdBottom - layout.column.lcdTop
        val histogramHeight =
            layout.column.histogramBottom - layout.column.histogramTop
        assertEquals(218f / 112f, columnWidth.toFloat() / lcdHeight, 0.02f)
        assertTrue(columnWidth.toFloat() / histogramHeight >= 3f)
        assertTrue(
            columnWidth >
                lcdHeight
        )
    }

    @Test
    fun leftLandscapeDisplayNeedsNoAdditionalInstrumentRotation() {
        val layout = XpanInfoColumnLayoutModel.calculate(
            containerWidth = 2400,
            containerHeight = 720,
            density = 3f,
            displayRotation = Surface.ROTATION_90
        )

        assertEquals(0, layout.rotationDegrees)
        assertEquals(1506, layout.column.left)
        assertEquals(2346, layout.column.right)
        assertTrue(layout.column.histogramTop < layout.column.histogramBottom)
        assertTrue(layout.column.histogramBottom < layout.column.lcdTop)
        assertEquals(
            218f / 112f,
            (layout.column.right - layout.column.left).toFloat() /
                (layout.column.lcdBottom - layout.column.lcdTop),
            0.02f
        )
    }

    @Test
    fun rightLandscapeDisplayStillFacesTheFixedLeftLandscapeDirection() {
        val layout = XpanInfoColumnLayoutModel.calculate(
            containerWidth = 2400,
            containerHeight = 720,
            density = 3f,
            displayRotation = Surface.ROTATION_270
        )

        assertEquals(-180, layout.rotationDegrees)
    }
}
