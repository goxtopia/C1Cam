package com.zhuo.c1cam

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var previewRectified: ImageView
    private lateinit var focusSlider: Slider
    private lateinit var evSlider: Slider
    private lateinit var captureButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var settingsButton: Button
    private lateinit var editModeToggle: ToggleButton
    private lateinit var topControls: View
    private lateinit var bottomControls: View
    private lateinit var mainViewContainer: android.widget.FrameLayout
    private lateinit var pipFrame: android.widget.FrameLayout
    private lateinit var pipViewContainer: androidx.cardview.widget.CardView
    private lateinit var pipTouchBlocker: View
    private lateinit var cameraContainer: View

    private var isFullscreen = false
    private var isRectifiedMain = false

    private lateinit var appSettings: AppSettings
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var cameraManager: CameraManager
    
    @Volatile
    private var currentLut: Lut3D? = null

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext, "Permission request denied", Toast.LENGTH_SHORT).show()
            } else {
                cameraManager.startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        previewRectified = findViewById(R.id.preview_rectified)
        focusSlider = findViewById(R.id.focus_slider)
        evSlider = findViewById(R.id.ev_slider)
        captureButton = findViewById(R.id.capture_button)
        settingsButton = findViewById(R.id.settings_button)
        editModeToggle = findViewById(R.id.edit_mode_toggle)
        topControls = findViewById(R.id.top_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        cameraContainer = findViewById(R.id.camera_container)
        mainViewContainer = findViewById(R.id.main_view_container)
        pipFrame = findViewById(R.id.pip_frame)
        pipViewContainer = findViewById(R.id.pip_view_container)
        pipTouchBlocker = findViewById(R.id.pip_touch_blocker)

        appSettings = AppSettings(this)
        imageProcessor = ImageProcessor(this)
        
        if (appSettings.lutName != null) {
            currentLut = LutUtils.loadLut(this, appSettings.lutName!!)
        }

        cameraManager = CameraManager(
            activity = this,
            viewFinder = viewFinder,
            previewRectified = previewRectified,
            overlay = overlay,
            appSettings = appSettings,
            imageProcessor = imageProcessor,
            lutProvider = { currentLut }
        )

        // Restore UI values
        focusSlider.value = appSettings.focusVal
        evSlider.value = appSettings.evVal
        if (appSettings.savedPoints != null) {
            overlay.setNormalizedPoints(appSettings.savedPoints!!)
        }

        overlay.isOverlayVisible = !appSettings.isCropModeOff

        focusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                appSettings.focusVal = value
                cameraManager.setFocusDistance(value)
            }
        }

        evSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                appSettings.evVal = value
                cameraManager.setExposureCompensation(value)
            }
        }

        editModeToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && isRectifiedMain) {
                // Not allowed when camera is not main view
                editModeToggle.isChecked = false
                Toast.makeText(this, "Can only edit when camera is main view", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            
            overlay.isEditMode = isChecked
            
            if (isChecked) {
                pipViewContainer.visibility = View.GONE
            } else {
                if (!isFullscreen) {
                    pipViewContainer.visibility = View.VISIBLE
                }
            }
            overlay.invalidate()
        }

        overlay.onDoubleTapListener = {
            toggleFullscreen()
        }

        settingsButton.setOnClickListener {
            showSettingsMenu()
        }

        pipTouchBlocker.setOnClickListener {
            swapViews()
        }

        captureButton.setOnClickListener {
            it.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            cameraManager.takePhoto()
        }

        if (allPermissionsGranted()) {
            cameraManager.startCamera()
        } else {
            requestPermissions()
        }
    }

    private fun showSettingsMenu() {
        val options = arrayOf("Target Aspect Ratio", "Select LUT", "Advanced Settings", "Select Focal Length")

        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAspectRatioDialog()
                    1 -> showLutDialog()
                    2 -> showAdvancedSettingsDialog()
                    3 -> showFocalLengthDialog()
                }
            }
            .show()
    }

    private fun showFocalLengthDialog() {
        val options = arrayOf("24mm (1x)", "28mm (1.17x)", "35mm (1.46x)", "40mm (1.67x)", "50mm (2.08x)")
        val values = intArrayOf(24, 28, 35, 40, 50)

        // Find current selection index
        val currentVal = appSettings.focalLength
        var selectedIndex = 0
        for (i in values.indices) {
            if (values[i] == currentVal) {
                selectedIndex = i
                break
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Focal Length")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                appSettings.focalLength = values[which]
                appSettings.save(overlay.getNormalizedPoints())
                Toast.makeText(this, "Focal Length set to ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdvancedSettingsDialog() {
        val options = arrayOf("Sports Mode", "Disable Noise Reduction", "Disable Edge Sharpening", "Chroma Noise Reduction", "Disable Crop Mode")
        val checkedItems = booleanArrayOf(appSettings.isSportsMode, appSettings.isNoiseReductionOff, appSettings.isEdgeModeOff, appSettings.isChromaDenoiseOn, appSettings.isCropModeOff)

        MaterialAlertDialogBuilder(this)
            .setTitle("Advanced Settings")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                when (which) {
                    0 -> appSettings.isSportsMode = isChecked
                    1 -> appSettings.isNoiseReductionOff = isChecked
                    2 -> appSettings.isEdgeModeOff = isChecked
                    3 -> appSettings.isChromaDenoiseOn = isChecked
                    4 -> appSettings.isCropModeOff = isChecked
                }
            }
            .setPositiveButton("OK") { _, _ ->
                appSettings.save(overlay.getNormalizedPoints())
                cameraManager.updateCameraSettings()
                overlay.isOverlayVisible = !appSettings.isCropModeOff
                // Re-apply manual focus in case mode changed
                cameraManager.setFocusDistance(focusSlider.value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf("Original", "A4 (1.414)", "Letter (1.294)", "4:3 (1.333)", "16:9 (1.778)")
        val values = floatArrayOf(0f, 1.414f, 1.294f, 1.333f, 1.778f)

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Target Aspect Ratio")
            .setItems(options) { _, which ->
                appSettings.targetAspectRatio = values[which]
                Toast.makeText(this, "Aspect Ratio set to ${options[which]}", Toast.LENGTH_SHORT).show()
                appSettings.save(overlay.getNormalizedPoints())
            }
            .show()
    }

    private fun showLutDialog() {
        val lutFiles = loadAvailableLutFiles()

        val originalLutName = appSettings.lutName
        val originalLut = currentLut

        var selectedLutName = originalLutName
        var selectedLut = currentLut
        val lutCache = mutableMapOf<String, Lut3D?>()
        originalLutName?.let { lutCache[it] = originalLut }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lut_preview, null)
        val previewImage = dialogView.findViewById<ImageView>(R.id.lut_preview_image)
        val previewName = dialogView.findViewById<TextView>(R.id.lut_preview_name)
        val previewProgress = dialogView.findViewById<ProgressBar>(R.id.lut_preview_progress)
        val lutList = dialogView.findViewById<ListView>(R.id.lut_list)

        val adapter = ArrayAdapter(this, R.layout.item_lut_option, android.R.id.text1, lutFiles)
        lutList.adapter = adapter
        lutList.choiceMode = ListView.CHOICE_MODE_SINGLE

        val initialLabel = selectedLutName ?: LUT_NONE_LABEL
        val initialIndex = lutFiles.indexOf(initialLabel).takeIf { it >= 0 } ?: 0
        lutList.setItemChecked(initialIndex, true)

        val previewBaseBitmap = captureCurrentPreviewBitmap()
        val previewExecutor = Executors.newSingleThreadExecutor()
        var previewRequestId = 0
        var committed = false
        var dialogClosed = false

        fun renderPreview(name: String?, lut: Lut3D?) {
            previewName.text = if (name != null) "Current: $name" else "Current: None (Original)"
            previewProgress.visibility = View.VISIBLE
            val requestId = ++previewRequestId

            previewExecutor.execute {
                val rendered = try {
                    lut?.let { LutUtils.applyLut(previewBaseBitmap, it) } ?: previewBaseBitmap
                } catch (e: Exception) {
                    Log.e(TAG, "LUT dialog preview render failed", e)
                    previewBaseBitmap
                }

                runOnUiThread {
                    if (dialogClosed || requestId != previewRequestId) return@runOnUiThread
                    previewImage.setImageBitmap(rendered)
                    previewProgress.visibility = View.GONE
                }
            }
        }

        fun applyTemporarySelection(index: Int) {
            val previousName = selectedLutName
            val previousLut = selectedLut
            val label = lutFiles[index]

            if (label == LUT_NONE_LABEL) {
                selectedLutName = null
                selectedLut = null
            } else {
                val loaded = lutCache.getOrPut(label) { LutUtils.loadLut(this, label) }
                if (loaded == null) {
                    Toast.makeText(this, "Failed to load LUT: $label", Toast.LENGTH_SHORT).show()
                    val fallbackLabel = previousName ?: LUT_NONE_LABEL
                    val fallbackIndex = lutFiles.indexOf(fallbackLabel).takeIf { it >= 0 } ?: 0
                    lutList.setItemChecked(fallbackIndex, true)
                    selectedLutName = previousName
                    selectedLut = previousLut
                    return
                }
                selectedLutName = label
                selectedLut = loaded
            }

            // Apply instantly so the camera preview updates in real time.
            currentLut = selectedLut
            renderPreview(selectedLutName, selectedLut)
        }

        lutList.setOnItemClickListener { _, _, position, _ ->
            applyTemporarySelection(position)
        }

        renderPreview(selectedLutName, selectedLut)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Select LUT")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                committed = true
                appSettings.lutName = selectedLutName
                currentLut = selectedLut
                appSettings.save(overlay.getNormalizedPoints())
                val message = if (selectedLutName == null) {
                    "LUT cleared"
                } else {
                    "LUT applied: $selectedLutName"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            dialogClosed = true
            previewExecutor.shutdownNow()
            if (!committed) {
                appSettings.lutName = originalLutName
                currentLut = originalLut
            }
        }

        dialog.show()
    }

    private fun loadAvailableLutFiles(): List<String> {
        val lutFiles = mutableListOf(LUT_NONE_LABEL)
        try {
            val files = assets.list("luts")
            files
                ?.filter { it.endsWith(".cube", ignoreCase = true) }
                ?.sorted()
                ?.forEach { lutFiles.add(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing assets", e)
        }
        return lutFiles
    }

    private fun captureCurrentPreviewBitmap(): Bitmap {
        val drawable = previewRectified.drawable
        if (drawable != null) {
            val targetW = drawable.intrinsicWidth.takeIf { it > 0 } ?: 480
            val targetH = drawable.intrinsicHeight.takeIf { it > 0 } ?: 640
            return drawable.toBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        }

        val fallbackW = previewRectified.width.takeIf { it > 0 } ?: 480
        val fallbackH = previewRectified.height.takeIf { it > 0 } ?: 640
        return createFallbackPreviewBitmap(fallbackW, fallbackH)
    }

    private fun createFallbackPreviewBitmap(width: Int, height: Int): Bitmap {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            safeW.toFloat(),
            safeH.toFloat(),
            intArrayOf(Color.parseColor("#455A64"), Color.parseColor("#1C2833"), Color.parseColor("#0B0F14")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, safeW.toFloat(), safeH.toFloat(), paint)
        return bitmap
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        appSettings.save(overlay.getNormalizedPoints())
        cameraManager.shutdown()
    }

    override fun onStop() {
        super.onStop()
        appSettings.save(overlay.getNormalizedPoints())
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val params = mainViewContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isFullscreen) {
            params.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
            params.width = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT

            topControls.visibility = View.GONE
            bottomControls.visibility = View.GONE
            pipViewContainer.visibility = View.GONE
        } else {
            params.height = 0
            params.width = 0

            topControls.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
            pipViewContainer.visibility = View.VISIBLE
        }
        mainViewContainer.layoutParams = params
    }

    private fun swapViews() {
        if (overlay.isEditMode) {
            Toast.makeText(this, "Exit edit mode before swapping views", Toast.LENGTH_SHORT).show()
            return
        }
        
        isRectifiedMain = !isRectifiedMain

        val cameraParent = cameraContainer.parent as android.view.ViewGroup
        val previewParent = previewRectified.parent as android.view.ViewGroup
        
        cameraParent.removeView(cameraContainer)
        previewParent.removeView(previewRectified)
        
        // Save absolute points using camera dimensions before view resizes
        val oldW = cameraContainer.width.toFloat()
        val oldH = cameraContainer.height.toFloat()
        val rawPoints = overlay.getNormalizedPoints().map { android.graphics.PointF(it.x * oldW, it.y * oldH) }

        if (isRectifiedMain) {
            mainViewContainer.addView(previewRectified)
            pipFrame.addView(cameraContainer)
            
            overlay.isEnabled = false
            
            previewRectified.setOnClickListener(null)
            previewRectified.setOnTouchListener(object : View.OnTouchListener {
                private var lastClickTime: Long = 0
                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        val clickTime = System.currentTimeMillis()
                        if (clickTime - lastClickTime < 300) {
                            toggleFullscreen()
                        }
                        lastClickTime = clickTime
                    }
                    return true
                }
            })
            
        } else {
            mainViewContainer.addView(cameraContainer)
            pipFrame.addView(previewRectified)
            
            overlay.isEnabled = true
            previewRectified.setOnTouchListener(null)
        }
        
        // Restore points to match new aspect ratio preserving physical image coordinates
        // This is tricky because layout hasn't happened yet. We can use a ViewTreeObserver
        // or just post it to the message queue so layout finishes.
        cameraContainer.post {
            val newW = cameraContainer.width.toFloat()
            val newH = cameraContainer.height.toFloat()
            if (oldW > 0 && oldH > 0 && newW > 0 && newH > 0) {
                // Determine the image scales and letterbox offsets for old and new bounds.
                // We don't have exact image size here, but we know the camera aspect ratio is fixed (e.g. 4:3).
                // Usually it's 4:3 backwards (3:4 portrait)
                // We'll estimate the aspect ratio by taking the PreviewView's latest frame, 
                // but we don't have it explicitly.
                // For a simpler heuristic, since we know ImageAnalysis gets a specific resolution (or we can just let 
                // OverlayView keep its original normalized points relative to the image).
                // Actually, OverlayView's built-in onSizeChanged blindly scales the points by (newW/oldW), 
                // warping the aspect ratio if newW/newH != oldW/oldH.
                // Let's counteract that warping!
                // To do this properly, we should compute the letterbox Rect of the image inside old bounds,
                // map points to [0..1] of that Rect, then compute letterbox Rect inside new bounds,
                // and map points back.
                
                // Assuming typical 3:4 portrait camera aspect ratio (height > width):
                val imgVertRatio = 4f/3f 
                
                fun getLetterboxRect(vw: Float, vh: Float, imgRatio: Float): android.graphics.RectF {
                    val scale = kotlin.math.min(vw, vh / imgRatio)
                    // If vw / vh < 1 / imgRatio (i.e. vw is bottleneck), scale is vw. (Since imgRatio > 1, 1/imgRatio < 1).
                    // Wait, standard fitCenter sets scale = min(vw / imgW, vh / imgH).
                    val scaleActual = kotlin.math.min(vw, vh / imgRatio)
                    val outW = scaleActual
                    val outH = scaleActual * imgRatio
                    val dx = (vw - outW) / 2
                    val dy = (vh - outH) / 2
                    return android.graphics.RectF(dx, dy, dx + outW, dy + outH)
                }
                
                val oldRect = getLetterboxRect(oldW, oldH, imgVertRatio)
                val newRect = getLetterboxRect(newW, newH, imgVertRatio)
                
                // Map rawPoints (old layout) to normalized Image space
                val imgPoints = rawPoints.map { p ->
                    android.graphics.PointF(
                        (p.x - oldRect.left) / oldRect.width(),
                        (p.y - oldRect.top) / oldRect.height()
                    )
                }
                
                // Map normalized Image space to new layout space
                val mappedNewPoints = imgPoints.map { p ->
                    android.graphics.PointF(
                        newRect.left + p.x * newRect.width(),
                        newRect.top + p.y * newRect.height()
                    )
                }
                
                // Convert back to [0..1] normalized View coordinates for OverlayView
                val newNormPoints = mappedNewPoints.map { p ->
                    android.graphics.PointF(p.x / newW, p.y / newH)
                }
                
                overlay.setNormalizedPoints(newNormPoints)
            }
        }
    }

    companion object {
        private const val TAG = "C1Cam"
        private const val LUT_NONE_LABEL = "None"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
