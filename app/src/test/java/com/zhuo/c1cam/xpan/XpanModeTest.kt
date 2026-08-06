package com.zhuo.c1cam.xpan

import com.zhuo.c1cam.camera.FocusMode
import com.zhuo.c1cam.camera.PreviewAnalysisPolicy
import com.zhuo.c1cam.camera.StillCaptureGeometry
import com.zhuo.c1cam.settings.PreviewDisplayMode
import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun xpan_mode_uses_its_configured_ratio() {
        assertEquals(
            16f / 9f,
            XpanMode.effectiveNoCropAspectRatio(
                enabled = true,
                configured = 1.5f,
                xpanConfigured = 16f / 9f
            ),
            0.0001f
        )
    }

    @Test
    fun xpan_ratio_cannot_expand_beyond_the_native_frame() {
        assertEquals(
            XpanMode.ASPECT_RATIO,
            XpanMode.effectiveNoCropAspectRatio(
                enabled = true,
                configured = 1.5f,
                xpanConfigured = 4f
            ),
            0.0001f
        )
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

    @Test
    fun scheme_two_expands_the_viewfinder_without_changing_its_ratio() {
        val compact = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            uiLayout = XpanUiLayout.SCHEME_1
        )
        val full = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            uiLayout = XpanUiLayout.SCHEME_2
        )

        assertTrue(full.height > compact.height)
        assertTrue(full.width > compact.width)
        assertEquals(XpanMode.ASPECT_RATIO, full.height.toFloat() / full.width, 0.03f)
    }

    @Test
    fun changing_ratio_only_shortens_the_long_edge_in_portrait() {
        val xpan = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            uiLayout = XpanUiLayout.SCHEME_1,
            aspectRatio = XpanMode.ASPECT_RATIO
        )
        val sixteenNine = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            uiLayout = XpanUiLayout.SCHEME_1,
            aspectRatio = 16f / 9f
        )

        assertEquals(xpan.width, sixteenNine.width)
        assertTrue(sixteenNine.height < xpan.height)
        assertEquals(16f / 9f, sixteenNine.height.toFloat() / sixteenNine.width, 0.03f)
    }

    @Test
    fun changing_ratio_only_shortens_the_long_edge_in_landscape() {
        val xpan = XpanFrameLayoutModel.calculate(
            containerWidth = 1800,
            containerHeight = 820,
            uiLayout = XpanUiLayout.SCHEME_2,
            aspectRatio = XpanMode.ASPECT_RATIO
        )
        val square = XpanFrameLayoutModel.calculate(
            containerWidth = 1800,
            containerHeight = 820,
            uiLayout = XpanUiLayout.SCHEME_2,
            aspectRatio = 1f
        )

        assertEquals(xpan.height, square.height)
        assertTrue(square.width < xpan.width)
        assertEquals(1f, square.width.toFloat() / square.height, 0.03f)
    }

    @Test
    fun original_ratio_uses_the_source_orientation() {
        assertEquals(
            4f / 3f,
            XpanFrameLayoutModel.effectiveFrameAspectRatio(
                aspectRatio = 0f,
                sourceAspectRatio = 3f / 4f
            ),
            0.0001f
        )
    }

    @Test
    fun scheme_one_keeps_the_original_left_edge_in_landscape() {
        val nativeFrame = XpanFrameLayoutModel.calculate(
            containerWidth = 1800,
            containerHeight = 820,
            uiLayout = XpanUiLayout.SCHEME_1
        )
        val shortFrame = XpanFrameLayoutModel.calculate(
            containerWidth = 1800,
            containerHeight = 820,
            uiLayout = XpanUiLayout.SCHEME_1,
            aspectRatio = 16f / 9f
        )
        val position = XpanFramePositionModel.calculate(
            containerWidth = 1800,
            containerHeight = 820,
            frame = shortFrame,
            nativeFrame = nativeFrame,
            uiLayout = XpanUiLayout.SCHEME_1,
            landscapeStartMargin = 16,
            portraitCompactEndMargin = 28,
            portraitFullEndMargin = 16,
            topMargin = 18
        )

        assertEquals(16, position.left)
        assertEquals(18, position.top)
    }

    @Test
    fun scheme_one_preserves_the_original_right_side_in_portrait() {
        val nativeFrame = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            uiLayout = XpanUiLayout.SCHEME_1
        )
        val shortFrame = XpanFrameLayoutModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            uiLayout = XpanUiLayout.SCHEME_1,
            aspectRatio = 16f / 9f
        )
        val position = XpanFramePositionModel.calculate(
            containerWidth = 1080,
            containerHeight = 1800,
            frame = shortFrame,
            nativeFrame = nativeFrame,
            uiLayout = XpanUiLayout.SCHEME_1,
            landscapeStartMargin = 16,
            portraitCompactEndMargin = 28,
            portraitFullEndMargin = 16,
            topMargin = 18
        )

        assertEquals(1080 - 28, position.left + shortFrame.width)
        assertEquals(18, position.top)
    }

    @Test
    fun scheme_two_shrinks_around_the_original_center() {
        listOf(1800 to 820, 1080 to 1800).forEach { (width, height) ->
            val nativeFrame = XpanFrameLayoutModel.calculate(
                containerWidth = width,
                containerHeight = height,
                uiLayout = XpanUiLayout.SCHEME_2
            )
            val shortFrame = XpanFrameLayoutModel.calculate(
                containerWidth = width,
                containerHeight = height,
                uiLayout = XpanUiLayout.SCHEME_2,
                aspectRatio = 1.5f
            )
            val nativePosition = XpanFramePositionModel.calculate(
                containerWidth = width,
                containerHeight = height,
                frame = nativeFrame,
                nativeFrame = nativeFrame,
                uiLayout = XpanUiLayout.SCHEME_2,
                landscapeStartMargin = 16,
                portraitCompactEndMargin = 28,
                portraitFullEndMargin = 16,
                topMargin = 18
            )
            val shortPosition = XpanFramePositionModel.calculate(
                containerWidth = width,
                containerHeight = height,
                frame = shortFrame,
                nativeFrame = nativeFrame,
                uiLayout = XpanUiLayout.SCHEME_2,
                landscapeStartMargin = 16,
                portraitCompactEndMargin = 28,
                portraitFullEndMargin = 16,
                topMargin = 18
            )

            assertTrue(
                abs(
                    nativePosition.left * 2 + nativeFrame.width -
                        (shortPosition.left * 2 + shortFrame.width)
                ) <= 1
            )
            assertTrue(
                abs(
                    nativePosition.top * 2 + nativeFrame.height -
                        (shortPosition.top * 2 + shortFrame.height)
                ) <= 1
            )
        }
    }
}
