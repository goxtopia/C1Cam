package com.zhuo.c1cam.xpan

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
        assertEquals(745, layout.column.left)
        assertEquals(1641, layout.column.right)
        assertTrue(layout.column.levelBottom < layout.column.histogramTop)
        assertEquals(layout.column.histogramTop, layout.column.lcdTop)
        assertEquals(layout.column.histogramBottom, layout.column.lcdBottom)
        assertTrue(layout.column.histogramRight < layout.column.lcdLeft)
        assertEquals(18, layout.column.lcdLeft - layout.column.histogramRight)
        assertEquals(18, layout.column.histogramTop - layout.column.levelBottom)
        assertEquals(layout.column.lcdLeft, layout.column.levelLeft)
        assertEquals(layout.column.lcdRight, layout.column.levelRight)
        val histogramWidth =
            layout.column.histogramRight - layout.column.histogramLeft
        val histogramHeight =
            layout.column.histogramBottom - layout.column.histogramTop
        val lcdHeight =
            layout.column.lcdBottom - layout.column.lcdTop
        assertEquals(1f, histogramWidth.toFloat() / histogramHeight, 0.02f)
        assertEquals(histogramHeight, lcdHeight)
        assertTrue(layout.column.levelRight > layout.column.levelLeft)
        assertTrue(layout.column.right - layout.column.left >
            lcdHeight)
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
        assertEquals(1056, layout.column.left)
        assertEquals(2346, layout.column.right)
        assertTrue(layout.column.histogramTop < layout.column.histogramBottom)
        assertTrue(layout.column.levelBottom < layout.column.histogramTop)
        assertEquals(
            layout.column.histogramBottom - layout.column.histogramTop,
            layout.column.histogramRight - layout.column.histogramLeft
        )
        assertEquals(
            layout.column.histogramBottom - layout.column.histogramTop,
            layout.column.lcdBottom - layout.column.lcdTop
        )
        assertTrue(layout.column.levelRight - layout.column.levelLeft >
            layout.column.histogramRight - layout.column.histogramLeft)
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

    @Test
    fun schemeTwoPlacesAllInstrumentsInOneRightAlignedRail() {
        val layout = XpanInfoColumnLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1695,
            density = 3f,
            displayRotation = Surface.ROTATION_0,
            uiLayout = XpanUiLayout.SCHEME_2
        )
        val column = layout.column

        assertEquals(column.histogramTop, column.levelTop)
        assertEquals(column.histogramTop, column.lcdTop)
        assertEquals(column.histogramBottom, column.levelBottom)
        assertEquals(column.histogramBottom, column.lcdBottom)
        assertTrue(column.left >= (layout.logicalWidth * 0.28f).toInt())
        assertEquals(layout.logicalWidth - 54, column.right)
        assertEquals(column.right, column.lcdRight)
        assertTrue(column.histogramRight < column.levelLeft)
        assertTrue(column.levelRight < column.lcdLeft)
        assertEquals(
            column.histogramBottom - column.histogramTop,
            column.histogramRight - column.histogramLeft
        )
        assertTrue(column.lcdRight - column.lcdLeft >
            column.lcdBottom - column.lcdTop)
    }
}
