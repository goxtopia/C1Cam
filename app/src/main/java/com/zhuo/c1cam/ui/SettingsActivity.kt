package com.zhuo.c1cam.ui

import com.zhuo.c1cam.R
import com.zhuo.c1cam.capture.ImageOutputFormat
import com.zhuo.c1cam.capture.JpegQuality
import com.zhuo.c1cam.processing.ChromaDenoiseMode
import com.zhuo.c1cam.processing.LutUtils
import com.zhuo.c1cam.processing.ToneMapPreset
import com.zhuo.c1cam.settings.AppSettings
import com.zhuo.c1cam.settings.InactivityTimeout
import com.zhuo.c1cam.xpan.XpanInstrumentTheme
import com.zhuo.c1cam.xpan.XpanUiLayout
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    private lateinit var appSettings: AppSettings
    private lateinit var selectionPanel: View
    private lateinit var selectionTitle: TextView
    private lateinit var selectionHint: TextView
    private lateinit var selectionList: ListView
    private lateinit var customRatioForm: View
    private lateinit var customWidthInput: EditText
    private lateinit var customHeightInput: EditText
    private lateinit var targetRatioValue: TextView
    private lateinit var focalLengthValue: TextView
    private lateinit var noCropRatioValue: TextView
    private lateinit var lutValue: TextView
    private lateinit var toneMapValue: TextView
    private lateinit var outputFormatValue: TextView
    private lateinit var jpegQualityValue: TextView
    private lateinit var chromaDenoiseValue: TextView
    private lateinit var inactivityTimeoutValue: TextView
    private lateinit var xpanUiLayoutValue: TextView
    private lateinit var xpanInstrumentThemeValue: TextView

    private var selectionMode: SelectionMode? = null
    private var visibleChoices: List<Choice> = emptyList()

    private val importLutLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importLut(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        appSettings = AppSettings(this)
        selectionPanel = findViewById(R.id.settings_selection_panel)
        selectionTitle = findViewById(R.id.selection_title)
        selectionHint = findViewById(R.id.selection_hint)
        selectionList = findViewById(R.id.selection_list)
        customRatioForm = findViewById(R.id.custom_ratio_form)
        customWidthInput = findViewById(R.id.custom_width_input)
        customHeightInput = findViewById(R.id.custom_height_input)
        targetRatioValue = findViewById(R.id.target_ratio_value)
        focalLengthValue = findViewById(R.id.focal_length_value)
        noCropRatioValue = findViewById(R.id.no_crop_ratio_value)
        lutValue = findViewById(R.id.lut_value)
        toneMapValue = findViewById(R.id.tone_map_value)
        outputFormatValue = findViewById(R.id.output_format_value)
        jpegQualityValue = findViewById(R.id.jpeg_quality_value)
        chromaDenoiseValue = findViewById(R.id.chroma_denoise_value)
        inactivityTimeoutValue = findViewById(R.id.inactivity_timeout_value)
        xpanUiLayoutValue = findViewById(R.id.xpan_ui_layout_value)
        xpanInstrumentThemeValue = findViewById(R.id.xpan_instrument_theme_value)

        findViewById<View>(R.id.settings_back).setOnClickListener {
            performButtonHaptic(it)
            finish()
        }
        findViewById<View>(R.id.selection_back).setOnClickListener {
            performButtonHaptic(it)
            closeSelectionPage()
        }
        findViewById<View>(R.id.target_ratio_row).setOnClickListener {
            openSelectionPage(SelectionMode.TARGET_RATIO)
        }
        findViewById<View>(R.id.focal_length_row).setOnClickListener {
            openSelectionPage(SelectionMode.FOCAL_LENGTH)
        }
        findViewById<View>(R.id.no_crop_ratio_row).setOnClickListener {
            openSelectionPage(SelectionMode.NO_CROP_RATIO)
        }
        findViewById<View>(R.id.lut_row).setOnClickListener {
            openSelectionPage(SelectionMode.LUT)
        }
        findViewById<View>(R.id.tone_map_row).setOnClickListener {
            openSelectionPage(SelectionMode.TONE_MAP)
        }
        findViewById<View>(R.id.output_format_row).setOnClickListener {
            openSelectionPage(SelectionMode.OUTPUT_FORMAT)
        }
        findViewById<View>(R.id.jpeg_quality_row).setOnClickListener {
            openSelectionPage(SelectionMode.JPEG_QUALITY)
        }
        findViewById<View>(R.id.chroma_denoise_row).setOnClickListener {
            openSelectionPage(SelectionMode.CHROMA_DENOISE)
        }
        findViewById<View>(R.id.inactivity_timeout_row).setOnClickListener {
            openSelectionPage(SelectionMode.INACTIVITY_TIMEOUT)
        }
        findViewById<View>(R.id.xpan_instrument_theme_row).setOnClickListener {
            openSelectionPage(SelectionMode.XPAN_INSTRUMENT_THEME)
        }
        findViewById<View>(R.id.xpan_ui_layout_row).setOnClickListener {
            openSelectionPage(SelectionMode.XPAN_UI_LAYOUT)
        }
        findViewById<View>(R.id.import_lut_row).setOnClickListener {
            importLutLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
        }
        findViewById<View>(R.id.custom_ratio_apply).setOnClickListener {
            performButtonHaptic(it)
            applyCustomRatio()
        }

        selectionList.setOnItemClickListener { _, _, position, _ ->
            handleChoice(visibleChoices[position])
        }

        bindSwitches()
        updateSummaries()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionPanel.visibility == View.VISIBLE) {
                    closeSelectionPage()
                } else {
                    finish()
                }
            }
        })
    }

    private fun bindSwitches() {
        bindSwitch(R.id.xpan_mode_switch, appSettings.isXpanMode) {
            appSettings.isXpanMode = it
        }
        bindSwitch(R.id.sports_switch, appSettings.isSportsMode) {
            appSettings.isSportsMode = it
        }
        bindSwitch(R.id.noise_reduction_off_switch, appSettings.isNoiseReductionOff) {
            appSettings.isNoiseReductionOff = it
        }
        bindSwitch(R.id.edge_off_switch, appSettings.isEdgeModeOff) {
            appSettings.isEdgeModeOff = it
        }
        bindSwitch(R.id.crop_mode_off_switch, appSettings.isCropModeOff) {
            appSettings.isCropModeOff = it
        }
        bindSwitch(R.id.wdr_switch, appSettings.isWdrMode) {
            appSettings.isWdrMode = it
        }
        bindSwitch(R.id.tap_focus_switch, appSettings.isTapToFocusEnabled) {
            appSettings.isTapToFocusEnabled = it
        }
        bindSwitch(R.id.frame_guide_switch, appSettings.isCropFrameGuideVisible) {
            appSettings.isCropFrameGuideVisible = it
        }
    }

    private fun bindSwitch(viewId: Int, initialValue: Boolean, onChanged: (Boolean) -> Unit) {
        val switch = findViewById<MaterialSwitch>(viewId)
        switch.isChecked = initialValue
        switch.setOnCheckedChangeListener { _, isChecked ->
            switch.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onChanged(isChecked)
            saveSettings()
        }
    }

    private fun openSelectionPage(mode: SelectionMode) {
        selectionMode = mode
        customRatioForm.visibility = View.GONE
        selectionList.visibility = View.VISIBLE
        selectionPanel.visibility = View.VISIBLE

        visibleChoices = when (mode) {
            SelectionMode.TARGET_RATIO -> targetRatioChoices()
            SelectionMode.FOCAL_LENGTH -> focalLengthChoices()
            SelectionMode.NO_CROP_RATIO -> noCropRatioChoices()
            SelectionMode.LUT -> lutChoices()
            SelectionMode.TONE_MAP -> toneMapChoices()
            SelectionMode.OUTPUT_FORMAT -> outputFormatChoices()
            SelectionMode.JPEG_QUALITY -> jpegQualityChoices()
            SelectionMode.CHROMA_DENOISE -> chromaDenoiseChoices()
            SelectionMode.INACTIVITY_TIMEOUT -> inactivityTimeoutChoices()
            SelectionMode.XPAN_UI_LAYOUT -> xpanUiLayoutChoices()
            SelectionMode.XPAN_INSTRUMENT_THEME -> xpanInstrumentThemeChoices()
        }

        selectionTitle.text = when (mode) {
            SelectionMode.TARGET_RATIO -> "Target aspect ratio"
            SelectionMode.FOCAL_LENGTH -> "Equivalent focal length"
            SelectionMode.NO_CROP_RATIO -> "No-crop framing ratio"
            SelectionMode.LUT -> "Color profile"
            SelectionMode.TONE_MAP -> "Tone mapping"
            SelectionMode.OUTPUT_FORMAT -> "File format"
            SelectionMode.JPEG_QUALITY -> "JPEG quality"
            SelectionMode.CHROMA_DENOISE -> "Chroma noise reduction"
            SelectionMode.INACTIVITY_TIMEOUT -> "Auto-exit timeout"
            SelectionMode.XPAN_UI_LAYOUT -> "XPAN UI layout"
            SelectionMode.XPAN_INSTRUMENT_THEME -> "XPAN instrument screen"
        }
        selectionHint.text = when (mode) {
            SelectionMode.TARGET_RATIO -> "Choose the corrected output shape."
            SelectionMode.FOCAL_LENGTH -> "Choose the lens equivalent used by the framing guide."
            SelectionMode.NO_CROP_RATIO -> "Choose the guide shown when crop mode is disabled."
            SelectionMode.LUT -> "Choose a look. The live preview updates after returning to the camera."
            SelectionMode.TONE_MAP -> "Applied before the selected color profile in preview and capture."
            SelectionMode.OUTPUT_FORMAT -> "JPEG stores capture metadata. PNG preserves pixels losslessly."
            SelectionMode.JPEG_QUALITY -> "Higher quality produces larger JPEG files and takes longer to encode."
            SelectionMode.CHROMA_DENOISE -> "Auto selects strength from the ISO recorded for each capture."
            SelectionMode.INACTIVITY_TIMEOUT -> "The display stays awake until this period passes without interaction."
            SelectionMode.XPAN_UI_LAYOUT ->
                "Choose between the compact instrument grid and the full-width viewfinder."
            SelectionMode.XPAN_INSTRUMENT_THEME ->
                "Applies one shared screen color to the histogram and processing LCD."
        }

        selectionList.adapter = ArrayAdapter(
            this,
            R.layout.item_settings_option,
            android.R.id.text1,
            visibleChoices.map { it.label }
        )
        selectionList.choiceMode = ListView.CHOICE_MODE_SINGLE
        val selectedIndex = visibleChoices.indexOfFirst { it.selected }
        if (selectedIndex >= 0) {
            selectionList.setItemChecked(selectedIndex, true)
            selectionList.setSelection(selectedIndex)
        }
    }

    private fun handleChoice(choice: Choice) {
        when (selectionMode) {
            SelectionMode.TARGET_RATIO -> {
                val ratio = choice.floatValue
                if (ratio == null) {
                    showCustomRatioForm()
                    return
                }
                appSettings.targetAspectRatio = ratio
            }

            SelectionMode.FOCAL_LENGTH -> {
                appSettings.focalLength = choice.intValue ?: return
            }

            SelectionMode.NO_CROP_RATIO -> {
                appSettings.noCropAspectRatio = choice.floatValue ?: return
            }

            SelectionMode.LUT -> {
                appSettings.lutName = choice.storageKey
            }

            SelectionMode.TONE_MAP -> {
                appSettings.toneMapPreset = choice.toneMapPreset ?: return
            }

            SelectionMode.OUTPUT_FORMAT -> {
                appSettings.imageOutputFormat = choice.outputFormat ?: return
            }

            SelectionMode.JPEG_QUALITY -> {
                appSettings.jpegQuality = choice.intValue ?: return
            }

            SelectionMode.CHROMA_DENOISE -> {
                appSettings.chromaDenoiseMode = choice.chromaDenoiseMode ?: return
            }

            SelectionMode.INACTIVITY_TIMEOUT -> {
                appSettings.inactivityTimeoutMinutes = InactivityTimeout.sanitize(
                    choice.intValue ?: return
                )
                AppInactivityController.updateTimeout(appSettings.inactivityTimeoutMinutes)
            }

            SelectionMode.XPAN_INSTRUMENT_THEME -> {
                appSettings.xpanInstrumentTheme = choice.xpanInstrumentTheme ?: return
            }

            SelectionMode.XPAN_UI_LAYOUT -> {
                appSettings.xpanUiLayout = choice.xpanUiLayout ?: return
            }

            null -> return
        }
        saveSettings()
        updateSummaries()
        closeSelectionPage()
    }

    private fun showCustomRatioForm() {
        selectionTitle.text = "Custom aspect ratio"
        selectionHint.text = "Use any positive width and height."
        selectionList.visibility = View.GONE
        customRatioForm.visibility = View.VISIBLE
        customWidthInput.requestFocus()
    }

    private fun applyCustomRatio() {
        val width = customWidthInput.text.toString().toFloatOrNull()
        val height = customHeightInput.text.toString().toFloatOrNull()
        if (width == null || height == null || width <= 0f || height <= 0f) {
            Toast.makeText(this, "Enter a valid width and height", Toast.LENGTH_SHORT).show()
            return
        }
        appSettings.targetAspectRatio = width / height
        saveSettings()
        updateSummaries()
        customRatioForm.visibility = View.GONE
        closeSelectionPage()
    }

    private fun closeSelectionPage() {
        if (customRatioForm.visibility == View.VISIBLE &&
            selectionMode == SelectionMode.TARGET_RATIO
        ) {
            openSelectionPage(SelectionMode.TARGET_RATIO)
            return
        }
        selectionPanel.visibility = View.GONE
        selectionMode = null
    }

    private fun targetRatioChoices(): List<Choice> {
        val values = listOf(
            "Original" to 0f,
            "A4 · 1.414:1" to 1.414f,
            "Letter · 1.294:1" to 1.294f,
            "4:3 · 1.333:1" to 1.333f,
            "16:9 · 1.778:1" to 1.778f
        )
        return values.map { (label, value) ->
            Choice(label, floatValue = value, selected = nearlyEqual(appSettings.targetAspectRatio, value))
        } + Choice(
            label = "Custom ratio…",
            selected = values.none { nearlyEqual(appSettings.targetAspectRatio, it.second) }
        )
    }

    private fun focalLengthChoices(): List<Choice> {
        return listOf(24, 28, 35, 40, 50).map { focalLength ->
            val zoom = focalLength / 24f
            Choice(
                label = "$focalLength mm  ·  ${formatZoom(zoom)}×",
                intValue = focalLength,
                selected = appSettings.focalLength == focalLength
            )
        }
    }

    private fun noCropRatioChoices(): List<Choice> {
        val values = listOf(
            "Original" to 0f,
            "3:2" to 1.5f,
            "16:9" to 16f / 9f,
            "2.35:1" to 2.35f,
            "2.55:1" to 2.55f,
            "1:1" to 1f
        )
        return values.map { (label, value) ->
            Choice(label, floatValue = value, selected = nearlyEqual(appSettings.noCropAspectRatio, value))
        }
    }

    private fun lutChoices(): List<Choice> {
        val choices = mutableListOf(
            Choice("None · Natural color", storageKey = null, selected = appSettings.lutName == null)
        )
        assets.list("luts")
            ?.filter { it.endsWith(".cube", ignoreCase = true) }
            ?.sortedBy { it.lowercase(Locale.US) }
            ?.forEach { filename ->
                val key = "$LUT_KEY_PREFIX_ASSET:$filename"
                choices += Choice(
                    label = filename.substringBeforeLast('.'),
                    storageKey = key,
                    selected = normalizeStorageKey(appSettings.lutName) == key
                )
            }

        ensureImportedLutDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".cube", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.forEach { file ->
                val key = "$LUT_KEY_PREFIX_IMPORTED:${file.name}"
                choices += Choice(
                    label = "${file.name.substringBeforeLast('.')}  ·  Imported",
                    storageKey = key,
                    selected = normalizeStorageKey(appSettings.lutName) == key
                )
            }
        return choices
    }

    private fun outputFormatChoices(): List<Choice> {
        return listOf(
            Choice(
                label = "JPEG · Metadata + broad compatibility",
                outputFormat = ImageOutputFormat.JPEG,
                selected = appSettings.imageOutputFormat == ImageOutputFormat.JPEG
            ),
            Choice(
                label = "PNG · Lossless, larger files",
                outputFormat = ImageOutputFormat.PNG,
                selected = appSettings.imageOutputFormat == ImageOutputFormat.PNG
            )
        )
    }

    private fun toneMapChoices(): List<Choice> {
        return ToneMapPreset.entries.map { preset ->
            Choice(
                label = "${preset.displayName} · ${preset.description}",
                toneMapPreset = preset,
                selected = appSettings.toneMapPreset == preset
            )
        }
    }

    private fun jpegQualityChoices(): List<Choice> {
        return JpegQuality.choices.map { quality ->
            Choice(
                label = "$quality${if (quality == JpegQuality.DEFAULT) " · Recommended" else ""}",
                intValue = quality,
                selected = appSettings.jpegQuality == quality
            )
        }
    }

    private fun chromaDenoiseChoices(): List<Choice> {
        return listOf(
            Choice(
                label = "Auto · Adjust from capture ISO",
                chromaDenoiseMode = ChromaDenoiseMode.AUTO,
                selected = appSettings.chromaDenoiseMode == ChromaDenoiseMode.AUTO
            ),
            Choice(
                label = "Off",
                chromaDenoiseMode = ChromaDenoiseMode.OFF,
                selected = appSettings.chromaDenoiseMode == ChromaDenoiseMode.OFF
            ),
            Choice(
                label = "Low · Preserve fine color detail",
                chromaDenoiseMode = ChromaDenoiseMode.LOW,
                selected = appSettings.chromaDenoiseMode == ChromaDenoiseMode.LOW
            ),
            Choice(
                label = "Medium · Balanced",
                chromaDenoiseMode = ChromaDenoiseMode.MEDIUM,
                selected = appSettings.chromaDenoiseMode == ChromaDenoiseMode.MEDIUM
            ),
            Choice(
                label = "High · Strong dark-area cleanup",
                chromaDenoiseMode = ChromaDenoiseMode.HIGH,
                selected = appSettings.chromaDenoiseMode == ChromaDenoiseMode.HIGH
            )
        )
    }

    private fun inactivityTimeoutChoices(): List<Choice> {
        return InactivityTimeout.choicesMinutes.map { minutes ->
            Choice(
                label = "${InactivityTimeout.label(minutes)}${
                    if (minutes == InactivityTimeout.DEFAULT_MINUTES) " · Default" else ""
                }",
                intValue = minutes,
                selected = appSettings.inactivityTimeoutMinutes == minutes
            )
        }
    }

    private fun xpanInstrumentThemeChoices(): List<Choice> {
        return XpanInstrumentTheme.entries.map { theme ->
            Choice(
                label = "${theme.displayName}${
                    if (theme == XpanInstrumentTheme.GREEN) " · Default" else ""
                }",
                xpanInstrumentTheme = theme,
                selected = appSettings.xpanInstrumentTheme == theme
            )
        }
    }

    private fun xpanUiLayoutChoices(): List<Choice> {
        return XpanUiLayout.entries.map { layout ->
            Choice(
                label = "${layout.displayName} · ${layout.description}",
                xpanUiLayout = layout,
                selected = appSettings.xpanUiLayout == layout
            )
        }
    }

    private fun updateSummaries() {
        targetRatioValue.text = targetRatioLabel(appSettings.targetAspectRatio)
        focalLengthValue.text = "${appSettings.focalLength} mm"
        noCropRatioValue.text = noCropRatioLabel(appSettings.noCropAspectRatio)
        lutValue.text = lutLabel(appSettings.lutName)
        toneMapValue.text = appSettings.toneMapPreset.displayName
        outputFormatValue.text = appSettings.imageOutputFormat.name
        jpegQualityValue.text = appSettings.jpegQuality.toString()
        chromaDenoiseValue.text = appSettings.chromaDenoiseMode.displayName
        inactivityTimeoutValue.text = InactivityTimeout.label(
            appSettings.inactivityTimeoutMinutes
        )
        xpanUiLayoutValue.text = appSettings.xpanUiLayout.displayName
        xpanInstrumentThemeValue.text = appSettings.xpanInstrumentTheme.displayName
    }

    private fun targetRatioLabel(value: Float): String {
        return when {
            nearlyEqual(value, 0f) -> "Original"
            nearlyEqual(value, 1.414f) -> "A4"
            nearlyEqual(value, 1.294f) -> "Letter"
            nearlyEqual(value, 1.333f) -> "4:3"
            nearlyEqual(value, 1.778f) -> "16:9"
            else -> String.format(Locale.US, "%.2f:1", value)
        }
    }

    private fun noCropRatioLabel(value: Float): String {
        return when {
            nearlyEqual(value, 0f) -> "Original"
            nearlyEqual(value, 1.5f) -> "3:2"
            nearlyEqual(value, 16f / 9f) -> "16:9"
            nearlyEqual(value, 2.35f) -> "2.35:1"
            nearlyEqual(value, 2.55f) -> "2.55:1"
            nearlyEqual(value, 1f) -> "1:1"
            else -> String.format(Locale.US, "%.2f:1", value)
        }
    }

    private fun lutLabel(storageKey: String?): String {
        if (storageKey == null) return "None"
        return storageKey
            .substringAfter(':', storageKey)
            .substringBeforeLast('.')
            .take(18)
    }

    private fun importLut(uri: Uri) {
        try {
            val parsed = contentResolver.openInputStream(uri)?.use { LutUtils.loadLut(it) }
            if (parsed == null) {
                Toast.makeText(this, "This is not a valid LUT file", Toast.LENGTH_SHORT).show()
                return
            }

            val copiedFile = copyImportedLut(uri)
            if (copiedFile == null) {
                Toast.makeText(this, "Could not import LUT", Toast.LENGTH_SHORT).show()
                return
            }

            appSettings.lutName = "$LUT_KEY_PREFIX_IMPORTED:${copiedFile.name}"
            saveSettings()
            updateSummaries()
            Toast.makeText(this, "${copiedFile.name} imported", Toast.LENGTH_SHORT).show()

            if (selectionMode == SelectionMode.LUT) {
                openSelectionPage(SelectionMode.LUT)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not import LUT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyImportedLut(uri: Uri): File? {
        val sourceName = queryDisplayName(uri)
        val safeName = sanitizeImportedLutName(sourceName)
        val importedDir = ensureImportedLutDir()
        val baseName = safeName.substringBeforeLast('.', safeName)
        var duplicateIndex = 0
        var destination = File(importedDir, safeName)
        while (destination.exists()) {
            duplicateIndex += 1
            destination = File(importedDir, "${baseName}_$duplicateIndex.cube")
        }

        val copied = contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        return destination.takeIf { copied }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun sanitizeImportedLutName(rawName: String?): String {
        val fallback = "lut_${UUID.randomUUID()}.cube"
        val normalized = (rawName ?: fallback)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val safe = normalized
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("^\\.+"), "")
            .replace(Regex("\\.{2,}"), ".")
            .ifEmpty { fallback }
        return if (safe.endsWith(".cube", ignoreCase = true)) safe else "$safe.cube"
    }

    private fun ensureImportedLutDir(): File {
        return File(filesDir, IMPORTED_LUT_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun normalizeStorageKey(storageKey: String?): String? {
        if (storageKey == null) return null
        return if (storageKey.startsWith("$LUT_KEY_PREFIX_ASSET:") ||
            storageKey.startsWith("$LUT_KEY_PREFIX_IMPORTED:")
        ) {
            storageKey
        } else {
            "$LUT_KEY_PREFIX_ASSET:$storageKey"
        }
    }

    private fun saveSettings() {
        appSettings.save(emptyList())
        setResult(RESULT_OK)
    }

    private fun nearlyEqual(a: Float, b: Float): Boolean = abs(a - b) < 0.001f

    private fun formatZoom(value: Float): String {
        return if (nearlyEqual(value, value.toInt().toFloat())) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun performButtonHaptic(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onResume() {
        super.onResume()
        AppInactivityController.onActivityResumed(
            this,
            appSettings.inactivityTimeoutMinutes
        )
    }

    override fun onPause() {
        AppInactivityController.onActivityPaused(this)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppInactivityController.onUserInteraction(this)
    }

    private enum class SelectionMode {
        TARGET_RATIO,
        FOCAL_LENGTH,
        NO_CROP_RATIO,
        LUT,
        TONE_MAP,
        OUTPUT_FORMAT,
        JPEG_QUALITY,
        CHROMA_DENOISE,
        INACTIVITY_TIMEOUT,
        XPAN_UI_LAYOUT,
        XPAN_INSTRUMENT_THEME
    }

    private data class Choice(
        val label: String,
        val floatValue: Float? = null,
        val intValue: Int? = null,
        val storageKey: String? = null,
        val outputFormat: ImageOutputFormat? = null,
        val toneMapPreset: ToneMapPreset? = null,
        val chromaDenoiseMode: ChromaDenoiseMode? = null,
        val xpanUiLayout: XpanUiLayout? = null,
        val xpanInstrumentTheme: XpanInstrumentTheme? = null,
        val selected: Boolean = false
    )

    companion object {
        private const val LUT_KEY_PREFIX_ASSET = "asset"
        private const val LUT_KEY_PREFIX_IMPORTED = "imported"
        private const val IMPORTED_LUT_DIR_NAME = "imported_luts"
    }
}
