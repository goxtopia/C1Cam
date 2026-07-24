package com.zhuo.c1cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XpanMeteringModeTest {
    @Test
    fun invalidStorageValueFallsBackToCenterWeighted() {
        assertEquals(
            XpanMeteringMode.CENTER_WEIGHTED,
            XpanMeteringMode.fromStorageValue("unknown")
        )
    }

    @Test
    fun modeCyclesThroughAllThreePatterns() {
        assertEquals(XpanMeteringMode.AVERAGE, XpanMeteringMode.CENTER_WEIGHTED.next())
        assertEquals(XpanMeteringMode.SPOT, XpanMeteringMode.AVERAGE.next())
        assertEquals(XpanMeteringMode.CENTER_WEIGHTED, XpanMeteringMode.SPOT.next())
    }

    @Test
    fun unsupportedDeviceProducesNoRegions() {
        assertTrue(
            XpanMeteringRegionModel.regionsFor(
                XpanMeteringMode.SPOT,
                maxRegionCount = 0
            ).isEmpty()
        )
    }

    @Test
    fun averageUsesTheWholeSensor() {
        val region = XpanMeteringRegionModel.regionsFor(
            XpanMeteringMode.AVERAGE,
            maxRegionCount = 1
        ).single()
        assertEquals(0f, region.left)
        assertEquals(0f, region.top)
        assertEquals(1f, region.right)
        assertEquals(1f, region.bottom)
    }

    @Test
    fun centerWeightedUsesLayeredRegionsWhenAvailable() {
        val regions = XpanMeteringRegionModel.regionsFor(
            XpanMeteringMode.CENTER_WEIGHTED,
            maxRegionCount = 2
        )
        assertEquals(2, regions.size)
        assertTrue(regions[1].weight > regions[0].weight)
        assertTrue(regions[1].left > regions[0].left)
        assertTrue(regions[1].right < regions[0].right)
    }

    @Test
    fun spotRegionIsSmallerThanSingleRegionCenterWeighted() {
        val center = XpanMeteringRegionModel.regionsFor(
            XpanMeteringMode.CENTER_WEIGHTED,
            maxRegionCount = 1
        ).single()
        val spot = XpanMeteringRegionModel.regionsFor(
            XpanMeteringMode.SPOT,
            maxRegionCount = 1
        ).single()
        assertTrue(spot.right - spot.left < center.right - center.left)
        assertTrue(spot.bottom - spot.top < center.bottom - center.top)
    }
}
