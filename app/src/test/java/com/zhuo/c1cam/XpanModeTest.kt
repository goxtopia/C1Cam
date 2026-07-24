package com.zhuo.c1cam

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XpanModeTest {
    @Test
    fun xpan_ratio_matches_65_by_24_frame() {
        assertEquals(65f / 24f, XpanMode.ASPECT_RATIO, 0.0001f)
    }

    @Test
    fun xpan_forces_auto_focus_and_direct_capture_path() {
        assertEquals(FocusMode.AUTO, XpanMode.effectiveFocusMode(true, FocusMode.MANUAL))
        assertTrue(XpanMode.effectiveCropModeOff(true, false))
        assertEquals(
            XpanMode.ASPECT_RATIO,
            XpanMode.effectiveNoCropAspectRatio(true, 1.5f),
            0.0001f
        )
        assertEquals(24, XpanMode.effectiveProcessingFocalLength(true, 50))
    }

    @Test
    fun normal_mode_preserves_existing_configuration() {
        assertEquals(FocusMode.MANUAL, XpanMode.effectiveFocusMode(false, FocusMode.MANUAL))
        assertFalse(XpanMode.effectiveCropModeOff(false, false))
        assertEquals(1.5f, XpanMode.effectiveNoCropAspectRatio(false, 1.5f), 0.0001f)
        assertEquals(50, XpanMode.effectiveProcessingFocalLength(false, 50))
    }

    @Test
    fun xpan_analysis_policy_is_low_resolution_and_frame_skipped() {
        val policy = PreviewAnalysisPolicy.forMode(
            PreviewDisplayMode.CAMERA,
            isXpanMode = true
        )

        assertFalse(policy.useHighestAvailableResolution)
        assertEquals(640, policy.analysisWidth)
        assertEquals(360, policy.analysisHeight)
        assertFalse(policy.shouldProcessFrame(1))
        assertTrue(policy.shouldProcessFrame(2))
    }

    @Test
    fun xpan_landscape_capture_geometry_is_locked_to_panorama_ratio() {
        val geometry = StillCaptureGeometry.create(
            rawWidth = 4000,
            rawHeight = 3000,
            imageRotationDegrees = 0,
            normalizedViewPoints = listOf(
                PointF(0f, 0f),
                PointF(1f, 0f),
                PointF(1f, 1f),
                PointF(0f, 1f)
            ),
            viewWidth = 1000,
            viewHeight = 750,
            targetAspectRatio = 1f,
            isCropModeOff = true,
            focalLength = XpanMode.effectiveProcessingFocalLength(true, 50),
            noCropAspectRatio = XpanMode.effectiveNoCropAspectRatio(true, 1f),
            savedImageRotationDegrees = 0
        )

        assertEquals(
            XpanMode.ASPECT_RATIO,
            geometry.outputWidth.toFloat() / geometry.outputHeight,
            0.002f
        )
    }

    @Test
    fun portrait_screen_uses_fixed_vertical_viewfinder() {
        val frame = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800
        )

        assertTrue(frame.height > frame.width)
        assertEquals(XpanMode.ASPECT_RATIO, frame.height.toFloat() / frame.width, 0.03f)
    }

    @Test
    fun landscape_screen_uses_fixed_horizontal_viewfinder() {
        val frame = XpanFrameLayoutModel.calculate(
            containerWidth = 1800,
            containerHeight = 820
        )

        assertTrue(frame.width > frame.height)
        assertEquals(XpanMode.ASPECT_RATIO, frame.width.toFloat() / frame.height, 0.03f)
    }
}
