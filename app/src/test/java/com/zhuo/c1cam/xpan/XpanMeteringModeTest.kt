package com.zhuo.c1cam.xpan

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

    @Test
    fun softwareMeteringUsesOnlyTheXpanFrame() {
        val frame = XpanSoftwareMeteringModel.frameFor(640, 360)
        assertEquals(0, frame.left)
        assertEquals(640, frame.right)
        assertTrue(frame.top > 0)
        assertTrue(frame.bottom < 360)
        assertEquals(XpanMode.ASPECT_RATIO, frame.width.toFloat() / frame.height, 0.02f)
    }

    @Test
    fun softwareMeteringTracksTheConfiguredFrameRatio() {
        val frame = XpanSoftwareMeteringModel.frameFor(
            imageWidth = 640,
            imageHeight = 360,
            aspectRatio = 1f
        )

        assertEquals(frame.width, frame.height)
        assertTrue(frame.left > 0)
        assertTrue(frame.right < 640)
    }

    @Test
    fun averageWeightIsUniformAcrossTheFrame() {
        assertEquals(1f, XpanSoftwareMeteringModel.sampleWeight(
            XpanMeteringMode.AVERAGE, 0.05f, 0.05f
        ))
        assertEquals(1f, XpanSoftwareMeteringModel.sampleWeight(
            XpanMeteringMode.AVERAGE, 0.5f, 0.5f
        ))
    }

    @Test
    fun centerWeightedModeFavorsTheCenter() {
        val edgeWeight = XpanSoftwareMeteringModel.sampleWeight(
            XpanMeteringMode.CENTER_WEIGHTED, 0.05f, 0.05f
        )
        val centerWeight = XpanSoftwareMeteringModel.sampleWeight(
            XpanMeteringMode.CENTER_WEIGHTED, 0.5f, 0.5f
        )
        assertTrue(centerWeight > edgeWeight)
    }

    @Test
    fun spotModeRejectsSamplesOutsideTheCenter() {
        assertEquals(1f, XpanSoftwareMeteringModel.sampleWeight(
            XpanMeteringMode.SPOT, 0.5f, 0.5f
        ))
        assertEquals(0f, XpanSoftwareMeteringModel.sampleWeight(
            XpanMeteringMode.SPOT, 0.2f, 0.5f
        ))
    }
}
