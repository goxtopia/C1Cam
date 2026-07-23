package com.zhuo.c1cam

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
    private lateinit var outputFormatValue: TextView
    private lateinit var chromaDenoiseValue: TextView

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
        outputFormatValue = findViewById(R.id.output_format_value)
        chromaDenoiseValue = findViewById(R.id.chroma_denoise_value)

        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }
        findViewById<View>(R.id.selection_back).setOnClickListener { closeSelectionPage() }
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
        findViewById<View>(R.id.output_format_row).setOnClickListener {
            openSelectionPage(SelectionMode.OUTPUT_FORMAT)
        }
        findViewById<View>(R.id.chroma_denoise_row).setOnClickListener {
            openSelectionPage(SelectionMode.CHROMA_DENOISE)
        }
        findViewById<View>(R.id.import_lut_row).setOnClickListener {
            importLutLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
        }
        findViewById<View>(R.id.custom_ratio_apply).setOnClickListener {
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
            SelectionMode.OUTPUT_FORMAT -> outputFormatChoices()
            SelectionMode.CHROMA_DENOISE -> chromaDenoiseChoices()
        }

        selectionTitle.text = when (mode) {
            SelectionMode.TARGET_RATIO -> "Target aspect ratio"
            SelectionMode.FOCAL_LENGTH -> "Equivalent focal length"
            SelectionMode.NO_CROP_RATIO -> "No-crop framing ratio"
            SelectionMode.LUT -> "Color profile"
            SelectionMode.OUTPUT_FORMAT -> "File format"
            SelectionMode.CHROMA_DENOISE -> "Chroma noise reduction"
        }
        selectionHint.text = when (mode) {
            SelectionMode.TARGET_RATIO -> "Choose the corrected output shape."
            SelectionMode.FOCAL_LENGTH -> "Choose the lens equivalent used by the framing guide."
            SelectionMode.NO_CROP_RATIO -> "Choose the guide shown when crop mode is disabled."
            SelectionMode.LUT -> "Choose a look. The live preview updates after returning to the camera."
            SelectionMode.OUTPUT_FORMAT -> "JPEG stores capture metadata. PNG preserves pixels losslessly."
            SelectionMode.CHROMA_DENOISE -> "Auto selects strength from the ISO recorded for each capture."
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

            SelectionMode.OUTPUT_FORMAT -> {
                appSettings.imageOutputFormat = choice.outputFormat ?: return
            }

            SelectionMode.CHROMA_DENOISE -> {
                appSettings.chromaDenoiseMode = choice.chromaDenoiseMode ?: return
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

    private fun updateSummaries() {
        targetRatioValue.text = targetRatioLabel(appSettings.targetAspectRatio)
        focalLengthValue.text = "${appSettings.focalLength} mm"
        noCropRatioValue.text = noCropRatioLabel(appSettings.noCropAspectRatio)
        lutValue.text = lutLabel(appSettings.lutName)
        outputFormatValue.text = appSettings.imageOutputFormat.name
        chromaDenoiseValue.text = appSettings.chromaDenoiseMode.displayName
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

    private enum class SelectionMode {
        TARGET_RATIO,
        FOCAL_LENGTH,
        NO_CROP_RATIO,
        LUT,
        OUTPUT_FORMAT,
        CHROMA_DENOISE
    }

    private data class Choice(
        val label: String,
        val floatValue: Float? = null,
        val intValue: Int? = null,
        val storageKey: String? = null,
        val outputFormat: ImageOutputFormat? = null,
        val chromaDenoiseMode: ChromaDenoiseMode? = null,
        val selected: Boolean = false
    )

    companion object {
        private const val LUT_KEY_PREFIX_ASSET = "asset"
        private const val LUT_KEY_PREFIX_IMPORTED = "imported"
        private const val IMPORTED_LUT_DIR_NAME = "imported_luts"
    }
}
