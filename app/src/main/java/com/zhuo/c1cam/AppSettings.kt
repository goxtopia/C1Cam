package com.zhuo.c1cam

import android.content.Context
import android.graphics.PointF
import android.util.Log

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var targetAspectRatio: Float = 1.414f
    var focusVal: Float = 0.0f
    var evVal: Float = 0.0f
    var lutName: String? = null
    var isSportsMode = false
    var isNoiseReductionOff = false
    var isEdgeModeOff = false
    var savedPoints: List<PointF>? = null

    init {
        load()
    }

    fun load() {
        targetAspectRatio = prefs.getFloat(KEY_ASPECT_RATIO, 1.414f)
        focusVal = prefs.getFloat(KEY_FOCUS_VAL, 0.0f)
        evVal = prefs.getFloat(KEY_EV_VAL, 0.0f)
        lutName = prefs.getString(KEY_LUT_NAME, null)
        isSportsMode = prefs.getBoolean(KEY_SPORTS_MODE, false)
        isNoiseReductionOff = prefs.getBoolean(KEY_NR_OFF, false)
        isEdgeModeOff = prefs.getBoolean(KEY_EDGE_OFF, false)

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
        editor.putFloat(KEY_EV_VAL, evVal)
        editor.putString(KEY_LUT_NAME, lutName)
        editor.putBoolean(KEY_SPORTS_MODE, isSportsMode)
        editor.putBoolean(KEY_NR_OFF, isNoiseReductionOff)
        editor.putBoolean(KEY_EDGE_OFF, isEdgeModeOff)

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
        private const val KEY_EV_VAL = "ev_val"
        private const val KEY_POINTS = "points"
        private const val KEY_LUT_NAME = "lut_name"
        private const val KEY_SPORTS_MODE = "sports_mode"
        private const val KEY_NR_OFF = "nr_off"
        private const val KEY_EDGE_OFF = "edge_off"
    }
}
