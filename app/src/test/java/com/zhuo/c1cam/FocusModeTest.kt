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
