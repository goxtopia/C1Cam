package com.zhuo.c1cam.settings

import com.zhuo.c1cam.camera.FocusMode
import com.zhuo.c1cam.capture.ImageOutputFormat
import com.zhuo.c1cam.capture.JpegQuality
import com.zhuo.c1cam.processing.ChromaDenoiseMode
import com.zhuo.c1cam.processing.HighIsoPixelBinning
import com.zhuo.c1cam.processing.PixelBinningMode
import com.zhuo.c1cam.processing.ToneMapPreset
import com.zhuo.c1cam.xpan.XpanInstrumentTheme
import com.zhuo.c1cam.xpan.XpanMeteringMode
import com.zhuo.c1cam.xpan.XpanMode
import com.zhuo.c1cam.xpan.XpanUiLayout
import android.content.Context
import android.graphics.PointF
import android.util.Log

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var targetAspectRatio: Float = 1.414f
    var focusVal: Float = 0.0f
    var focusMode: FocusMode = FocusMode.MANUAL
    var isAeLocked: Boolean = false
    var isAfLocked: Boolean = false
    var isTapToFocusEnabled: Boolean = false
    var evVal: Float = 0.0f
    var lutName: String? = null
    var isSportsMode = false
    var isNoiseReductionOff = false
    var isEdgeModeOff = false
    var chromaDenoiseMode: ChromaDenoiseMode = ChromaDenoiseMode.OFF
    var isHighIsoPixelBinningEnabled = false
    var highIsoPixelBinningMode: PixelBinningMode = PixelBinningMode.TWO_BY_TWO
    var highIsoPixelBinningThreshold: Int = HighIsoPixelBinning.DEFAULT_ISO_THRESHOLD
    var isCropModeOff = false
    var isWdrMode = false
    var focalLength: Int = 24
    var noCropAspectRatio: Float = 0f
    var previewDisplayMode: PreviewDisplayMode = PreviewDisplayMode.CAMERA
    var isCropFrameGuideVisible: Boolean = false
    var imageOutputFormat: ImageOutputFormat = ImageOutputFormat.JPEG
    var jpegQuality: Int = JpegQuality.DEFAULT
    var isXpanMode: Boolean = false
    var xpanAspectRatio: Float = XpanMode.ASPECT_RATIO
    var xpanUiLayout: XpanUiLayout = XpanUiLayout.SCHEME_1
    var xpanMeteringMode: XpanMeteringMode = XpanMeteringMode.CENTER_WEIGHTED
    var xpanInstrumentTheme: XpanInstrumentTheme = XpanInstrumentTheme.GREEN
    var inactivityTimeoutMinutes: Int = InactivityTimeout.DEFAULT_MINUTES
    var toneMapPreset: ToneMapPreset = ToneMapPreset.NONE
    var savedPoints: List<PointF>? = null

    init {
        load()
    }

    fun load() {
        targetAspectRatio = prefs.getFloat(KEY_ASPECT_RATIO, 1.414f)
        focusVal = prefs.getFloat(KEY_FOCUS_VAL, 0.0f)
        focusMode = FocusMode.fromStorageValue(prefs.getString(KEY_FOCUS_MODE, FocusMode.MANUAL.storageValue))
        isAeLocked = prefs.getBoolean(KEY_AE_LOCK, false)
        isAfLocked = prefs.getBoolean(KEY_AF_LOCK, false)
        isTapToFocusEnabled = prefs.getBoolean(KEY_TAP_TO_FOCUS, false)
        evVal = prefs.getFloat(KEY_EV_VAL, 0.0f)
        lutName = prefs.getString(KEY_LUT_NAME, null)
        isSportsMode = prefs.getBoolean(KEY_SPORTS_MODE, false)
        isNoiseReductionOff = prefs.getBoolean(KEY_NR_OFF, false)
        isEdgeModeOff = prefs.getBoolean(KEY_EDGE_OFF, false)
        chromaDenoiseMode = ChromaDenoiseMode.fromStorageValue(
            prefs.getString(KEY_CHROMA_DENOISE_MODE, null)
        ) ?: if (prefs.getBoolean(KEY_CHROMA_DENOISE, false)) {
            ChromaDenoiseMode.HIGH
        } else {
            ChromaDenoiseMode.OFF
        }
        isHighIsoPixelBinningEnabled = prefs.getBoolean(KEY_HIGH_ISO_BINNING_ENABLED, false)
        highIsoPixelBinningMode = PixelBinningMode.fromStorageValue(
            prefs.getString(KEY_HIGH_ISO_BINNING_MODE, null)
        )
        highIsoPixelBinningThreshold = HighIsoPixelBinning.sanitizeThreshold(
            prefs.getInt(
                KEY_HIGH_ISO_BINNING_THRESHOLD,
                HighIsoPixelBinning.DEFAULT_ISO_THRESHOLD
            )
        )
        isCropModeOff = prefs.getBoolean(KEY_CROP_MODE_OFF, false)
        isWdrMode = prefs.getBoolean(KEY_WDR_MODE, false)
        focalLength = prefs.getInt(KEY_FOCAL_LENGTH, 24)
        noCropAspectRatio = prefs.getFloat(KEY_NO_CROP_ASPECT_RATIO, 0f)
        previewDisplayMode = PreviewDisplayMode.fromStorageValue(
            prefs.getString(KEY_PREVIEW_DISPLAY_MODE, PreviewDisplayMode.CAMERA.storageValue)
        )
        isCropFrameGuideVisible = prefs.getBoolean(KEY_CROP_FRAME_GUIDE_VISIBLE, false)
        imageOutputFormat = ImageOutputFormat.fromStorageValue(
            prefs.getString(KEY_IMAGE_OUTPUT_FORMAT, ImageOutputFormat.JPEG.storageValue)
        )
        jpegQuality = JpegQuality.sanitize(
            prefs.getInt(KEY_JPEG_QUALITY, JpegQuality.DEFAULT)
        )
        isXpanMode = prefs.getBoolean(KEY_XPAN_MODE, false)
        xpanAspectRatio = prefs.getFloat(KEY_XPAN_ASPECT_RATIO, XpanMode.ASPECT_RATIO)
        xpanUiLayout = XpanUiLayout.fromStorageValue(
            prefs.getString(KEY_XPAN_UI_LAYOUT, XpanUiLayout.SCHEME_1.storageValue)
        )
        xpanMeteringMode = XpanMeteringMode.fromStorageValue(
            prefs.getString(KEY_XPAN_METERING_MODE, XpanMeteringMode.CENTER_WEIGHTED.storageValue)
        )
        xpanInstrumentTheme = XpanInstrumentTheme.fromStorageValue(
            prefs.getString(KEY_XPAN_INSTRUMENT_THEME, XpanInstrumentTheme.GREEN.storageValue)
        )
        inactivityTimeoutMinutes = InactivityTimeout.sanitize(
            prefs.getInt(KEY_INACTIVITY_TIMEOUT_MINUTES, InactivityTimeout.DEFAULT_MINUTES)
        )
        toneMapPreset = ToneMapPreset.fromStorageValue(
            prefs.getString(KEY_TONE_MAP_PRESET, ToneMapPreset.NONE.storageValue)
        )

        val pointsStr = prefs.getString(KEY_POINTS, null)
        if (pointsStr != null) {
            val parts = pointsStr.split(",")
            if (parts.size == 8) {
                try {
                    val pts = mutableListOf<PointF>()
                    for (i in 0 until 4) {
                        pts.add(PointF(parts[i * 2].toFloat(), parts[i * 2 + 1].toFloat()))
                    }
                    savedPoints = pts
                } catch (e: Exception) {
                    Log.e("AppSettings", "Error parsing points", e)
                }
            }
        }
    }

    fun save(currentPoints: List<PointF>) {
        val editor = prefs.edit()
        editor.putFloat(KEY_ASPECT_RATIO, targetAspectRatio)
        editor.putFloat(KEY_FOCUS_VAL, focusVal)
        editor.putString(KEY_FOCUS_MODE, focusMode.storageValue)
        editor.putBoolean(KEY_AE_LOCK, isAeLocked)
        editor.putBoolean(KEY_AF_LOCK, isAfLocked)
        editor.putBoolean(KEY_TAP_TO_FOCUS, isTapToFocusEnabled)
        editor.putFloat(KEY_EV_VAL, evVal)
        editor.putString(KEY_LUT_NAME, lutName)
        editor.putBoolean(KEY_SPORTS_MODE, isSportsMode)
        editor.putBoolean(KEY_NR_OFF, isNoiseReductionOff)
        editor.putBoolean(KEY_EDGE_OFF, isEdgeModeOff)
        editor.putString(KEY_CHROMA_DENOISE_MODE, chromaDenoiseMode.storageValue)
        editor.putBoolean(KEY_HIGH_ISO_BINNING_ENABLED, isHighIsoPixelBinningEnabled)
        editor.putString(KEY_HIGH_ISO_BINNING_MODE, highIsoPixelBinningMode.storageValue)
        editor.putInt(KEY_HIGH_ISO_BINNING_THRESHOLD, highIsoPixelBinningThreshold)
        editor.putBoolean(KEY_CROP_MODE_OFF, isCropModeOff)
        editor.putBoolean(KEY_WDR_MODE, isWdrMode)
        editor.putInt(KEY_FOCAL_LENGTH, focalLength)
        editor.putFloat(KEY_NO_CROP_ASPECT_RATIO, noCropAspectRatio)
        editor.putString(KEY_PREVIEW_DISPLAY_MODE, previewDisplayMode.storageValue)
        editor.putBoolean(KEY_CROP_FRAME_GUIDE_VISIBLE, isCropFrameGuideVisible)
        editor.putString(KEY_IMAGE_OUTPUT_FORMAT, imageOutputFormat.storageValue)
        editor.putInt(KEY_JPEG_QUALITY, jpegQuality)
        editor.putBoolean(KEY_XPAN_MODE, isXpanMode)
        editor.putFloat(KEY_XPAN_ASPECT_RATIO, xpanAspectRatio)
        editor.putString(KEY_XPAN_UI_LAYOUT, xpanUiLayout.storageValue)
        editor.putString(KEY_XPAN_METERING_MODE, xpanMeteringMode.storageValue)
        editor.putString(KEY_XPAN_INSTRUMENT_THEME, xpanInstrumentTheme.storageValue)
        editor.putInt(KEY_INACTIVITY_TIMEOUT_MINUTES, inactivityTimeoutMinutes)
        editor.putString(KEY_TONE_MAP_PRESET, toneMapPreset.storageValue)

        if (currentPoints.size == 4) {
            val sb = StringBuilder()
            for (p in currentPoints) {
                sb.append("${p.x},${p.y},")
            }
            if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
            editor.putString(KEY_POINTS, sb.toString())
        }

        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "C1CamPrefs"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
        private const val KEY_FOCUS_VAL = "focus_val"
        private const val KEY_FOCUS_MODE = "focus_mode"
        private const val KEY_AE_LOCK = "ae_lock"
        private const val KEY_AF_LOCK = "af_lock"
        private const val KEY_TAP_TO_FOCUS = "tap_to_focus"
        private const val KEY_EV_VAL = "ev_val"
        private const val KEY_POINTS = "points"
        private const val KEY_LUT_NAME = "lut_name"
        private const val KEY_SPORTS_MODE = "sports_mode"
        private const val KEY_NR_OFF = "nr_off"
        private const val KEY_EDGE_OFF = "edge_off"
        private const val KEY_CHROMA_DENOISE = "chroma_denoise_on"
        private const val KEY_CHROMA_DENOISE_MODE = "chroma_denoise_mode"
        private const val KEY_HIGH_ISO_BINNING_ENABLED = "high_iso_binning_enabled"
        private const val KEY_HIGH_ISO_BINNING_MODE = "high_iso_binning_mode"
        private const val KEY_HIGH_ISO_BINNING_THRESHOLD = "high_iso_binning_threshold"
        private const val KEY_CROP_MODE_OFF = "crop_mode_off"
        private const val KEY_WDR_MODE = "wdr_mode"
        private const val KEY_FOCAL_LENGTH = "focal_length"
        private const val KEY_NO_CROP_ASPECT_RATIO = "no_crop_aspect_ratio"
        private const val KEY_PREVIEW_DISPLAY_MODE = "preview_display_mode"
        private const val KEY_CROP_FRAME_GUIDE_VISIBLE = "crop_frame_guide_visible"
        private const val KEY_IMAGE_OUTPUT_FORMAT = "image_output_format"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_XPAN_MODE = "xpan_mode"
        private const val KEY_XPAN_ASPECT_RATIO = "xpan_aspect_ratio"
        private const val KEY_XPAN_UI_LAYOUT = "xpan_ui_layout"
        private const val KEY_XPAN_METERING_MODE = "xpan_metering_mode"
        private const val KEY_XPAN_INSTRUMENT_THEME = "xpan_instrument_theme"
        private const val KEY_INACTIVITY_TIMEOUT_MINUTES = "inactivity_timeout_minutes"
        private const val KEY_TONE_MAP_PRESET = "tone_map_preset"
    }
}
