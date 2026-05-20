package com.zhuo.c1cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusModeTest {
    @Test
    fun toggle_switches_between_manual_and_auto() {
        assertEquals(FocusMode.AUTO, FocusMode.MANUAL.toggled())
        assertEquals(FocusMode.MANUAL, FocusMode.AUTO.toggled())
    }

    @Test
    fun manual_mode_enables_focus_slider_and_shows_mf_label() {
        assertTrue(FocusModeUiModel.isFocusSliderEnabled(FocusMode.MANUAL))
        assertEquals("MF", FocusModeUiModel.buttonLabel(FocusMode.MANUAL))
    }

    @Test
    fun auto_mode_disables_focus_slider_and_shows_af_label() {
        assertFalse(FocusModeUiModel.isFocusSliderEnabled(FocusMode.AUTO))
        assertEquals("AF", FocusModeUiModel.buttonLabel(FocusMode.AUTO))
    }

    @Test
    fun invalid_storage_value_falls_back_to_manual_mode() {
        assertEquals(FocusMode.MANUAL, FocusMode.fromStorageValue("unknown"))
    }

    @Test
    fun tap_to_focus_is_enabled_only_when_auto_mode_and_setting_enabled() {
        assertFalse(FocusModeUiModel.isTapToFocusEnabled(FocusMode.MANUAL, false))
        assertFalse(FocusModeUiModel.isTapToFocusEnabled(FocusMode.MANUAL, true))
        assertFalse(FocusModeUiModel.isTapToFocusEnabled(FocusMode.AUTO, false))
        assertTrue(FocusModeUiModel.isTapToFocusEnabled(FocusMode.AUTO, true))
    }

    @Test
    fun preview_display_toggle_switches_between_camera_and_rectified() {
        assertEquals(PreviewDisplayMode.RECTIFIED, PreviewDisplayMode.CAMERA.toggled())
        assertEquals(PreviewDisplayMode.CAMERA, PreviewDisplayMode.RECTIFIED.toggled())
    }

    @Test
    fun preview_display_invalid_storage_value_falls_back_to_camera() {
        assertEquals(PreviewDisplayMode.CAMERA, PreviewDisplayMode.fromStorageValue("unknown"))
    }

    @Test
    fun preview_display_toggle_button_shows_target_view_label() {
        assertEquals("LUT", PreviewDisplayUiModel.toggleButtonLabel(PreviewDisplayMode.CAMERA))
        assertEquals("CAM", PreviewDisplayUiModel.toggleButtonLabel(PreviewDisplayMode.RECTIFIED))
    }

    @Test
    fun crop_frame_guide_visibility_requires_setting_no_crop_mode_and_camera_view() {
        assertTrue(CropFrameGuideModel.shouldShowGuide(true, true, PreviewDisplayMode.CAMERA))
        assertFalse(CropFrameGuideModel.shouldShowGuide(false, true, PreviewDisplayMode.CAMERA))
        assertFalse(CropFrameGuideModel.shouldShowGuide(true, false, PreviewDisplayMode.CAMERA))
        assertFalse(CropFrameGuideModel.shouldShowGuide(true, true, PreviewDisplayMode.RECTIFIED))
    }

    @Test
    fun crop_frame_guide_full_frame_matches_original_focal_and_ratio() {
        val rect = CropFrameGuideModel.normalizedFrameRect(
            sourceAspectRatio = 3f / 4f,
            focalLength = 24,
            aspectRatio = 0f
        )

        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
    }

    @Test
    fun crop_frame_guide_reflects_focal_length_crop() {
        val rect = CropFrameGuideModel.normalizedFrameRect(
            sourceAspectRatio = 3f / 4f,
            focalLength = 50,
            aspectRatio = 0f
        )

        assertTrue(rect.width < 0.5f)
        assertTrue(rect.height < 0.5f)
        assertEquals(0.5f, rect.centerX, 0.0001f)
        assertEquals(0.5f, rect.centerY, 0.0001f)
    }

    @Test
    fun crop_frame_guide_applies_requested_ratio_inside_source_orientation() {
        val rect = CropFrameGuideModel.normalizedFrameRect(
            sourceAspectRatio = 3f / 4f,
            focalLength = 24,
            aspectRatio = 16f / 9f
        )

        assertEquals(0.125f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.875f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
    }

    @Test
    fun crop_frame_guide_matches_processor_math_for_three_to_two() {
        val rect = CropFrameGuideModel.normalizedFrameRect(
            sourceAspectRatio = 3f / 4f,
            focalLength = 24,
            aspectRatio = 3f / 2f
        )

        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
        assertEquals(0.16666667f, rect.left, 0.0001f)
        assertEquals(0.8333333f, rect.right, 0.0001f)
    }

    @Test
    fun crop_frame_guide_matches_processor_math_for_twentyone_to_nine_like_ratio() {
        val rect = CropFrameGuideModel.normalizedFrameRect(
            sourceAspectRatio = 3f / 4f,
            focalLength = 24,
            aspectRatio = 21f / 9f
        )

        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
        assertEquals(0.21428572f, rect.left, 0.0001f)
        assertEquals(0.78571427f, rect.right, 0.0001f)
    }

    @Test
    fun crop_frame_guide_projects_into_wider_fit_center_camera_view() {
        val rect = CropFrameGuideModel.projectedFrameRectInView(
            sourceAspectRatio = 3f / 4f,
            viewAspectRatio = 4f / 5f,
            focalLength = 24,
            aspectRatio = 16f / 9f
        )

        assertEquals(0.171875f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.828125f, rect.right, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
    }

    @Test
    fun preview_frame_aspect_ratio_uses_rotated_frame_orientation() {
        assertEquals(
            9f / 16f,
            PreviewFrameAspectRatioModel.fromFrame(1280, 720, 90),
            0.0001f
        )
        assertEquals(
            3f / 4f,
            PreviewFrameAspectRatioModel.fromFrame(1440, 1080, 90),
            0.0001f
        )
    }

    @Test
    fun camera_preview_policy_prefers_lower_cost_analysis() {
        val policy = PreviewAnalysisPolicy.forMode(PreviewDisplayMode.CAMERA)

        assertFalse(policy.useHighestAvailableResolution)
        assertEquals(1280, policy.analysisWidth)
        assertEquals(720, policy.analysisHeight)
        assertEquals(0, policy.processEveryNthFrame)
    }

    @Test
    fun rectified_preview_policy_prefers_highest_quality_analysis() {
        val policy = PreviewAnalysisPolicy.forMode(PreviewDisplayMode.RECTIFIED)

        assertTrue(policy.useHighestAvailableResolution)
        assertEquals(0, policy.analysisWidth)
        assertEquals(0, policy.analysisHeight)
        assertEquals(1, policy.processEveryNthFrame)
    }

    @Test
    fun frame_processing_policy_skips_all_hidden_rectified_updates_in_camera_mode() {
        val policy = PreviewAnalysisPolicy.forMode(PreviewDisplayMode.CAMERA)

        assertFalse(policy.shouldProcessFrame(1))
        assertFalse(policy.shouldProcessFrame(2))
        assertFalse(policy.shouldProcessFrame(30))
    }

    @Test
    fun frame_processing_policy_processes_every_frame_in_rectified_mode() {
        val policy = PreviewAnalysisPolicy.forMode(PreviewDisplayMode.RECTIFIED)

        assertTrue(policy.shouldProcessFrame(1))
        assertTrue(policy.shouldProcessFrame(2))
        assertTrue(policy.shouldProcessFrame(30))
    }

    @Test
    fun log_lut_should_be_brighter_than_scene_linear_after_wdr_restore() {
        val wdrMidTone = Math.pow(0.5, 0.35).toFloat()
        val expectedDisplayMidTone = Math.pow(0.5, 1.0 / 2.2).toFloat()
        val restoredDisplayMidTone = Math.pow(wdrMidTone.toDouble(), 1.0 / (0.35 * 2.2)).toFloat()

        assertTrue(restoredDisplayMidTone > 0.5f)
        assertEquals(expectedDisplayMidTone, restoredDisplayMidTone, 0.0001f)
    }

    @Test
    fun tap_to_focus_uses_auto_focus_only_mode_when_enabled_in_settings() {
        assertTrue(FocusModeUiModel.isTapToFocusEnabled(FocusMode.AUTO, true))
        assertFalse(FocusModeUiModel.isTapToFocusEnabled(FocusMode.AUTO, false))
        assertFalse(FocusModeUiModel.isTapToFocusEnabled(FocusMode.MANUAL, true))
    }
}
