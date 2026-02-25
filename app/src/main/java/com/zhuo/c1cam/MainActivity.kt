package com.zhuo.c1cam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider

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
            overlay.isEditMode = isChecked
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
        val options = arrayOf("Target Aspect Ratio", "Select LUT", "Advanced Settings")

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAspectRatioDialog()
                    1 -> showLutDialog()
                    2 -> showAdvancedSettingsDialog()
                }
            }
            .show()
    }

    private fun showAdvancedSettingsDialog() {
        val options = arrayOf("Sports Mode", "Disable Noise Reduction", "Disable Edge Sharpening")
        val checkedItems = booleanArrayOf(appSettings.isSportsMode, appSettings.isNoiseReductionOff, appSettings.isEdgeModeOff)

        AlertDialog.Builder(this)
            .setTitle("Advanced Settings")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                when (which) {
                    0 -> appSettings.isSportsMode = isChecked
                    1 -> appSettings.isNoiseReductionOff = isChecked
                    2 -> appSettings.isEdgeModeOff = isChecked
                }
            }
            .setPositiveButton("OK") { _, _ ->
                appSettings.save(overlay.getNormalizedPoints())
                cameraManager.updateCameraSettings()
                // Re-apply manual focus in case mode changed
                cameraManager.setFocusDistance(focusSlider.value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf("Original", "A4 (1.414)", "Letter (1.294)", "4:3 (1.333)", "16:9 (1.778)")
        val values = floatArrayOf(0f, 1.414f, 1.294f, 1.333f, 1.778f)

        AlertDialog.Builder(this)
            .setTitle("Select Target Aspect Ratio")
            .setItems(options) { _, which ->
                appSettings.targetAspectRatio = values[which]
                Toast.makeText(this, "Aspect Ratio set to ${options[which]}", Toast.LENGTH_SHORT).show()
                appSettings.save(overlay.getNormalizedPoints())
            }
            .show()
    }

    private fun showLutDialog() {
        val lutFiles = mutableListOf<String>()
        lutFiles.add("None")
        try {
            val files = assets.list("luts")
            files?.filter { it.endsWith(".cube", ignoreCase = true) }?.forEach { lutFiles.add(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing assets", e)
        }

        AlertDialog.Builder(this)
            .setTitle("Select LUT")
            .setItems(lutFiles.toTypedArray()) { _, which ->
                val selected = lutFiles[which]
                if (selected == "None") {
                    appSettings.lutName = null
                    currentLut = null
                    Toast.makeText(this, "LUT cleared", Toast.LENGTH_SHORT).show()
                } else {
                    appSettings.lutName = selected
                    currentLut = LutUtils.loadLut(this, selected)
                    Toast.makeText(this, "LUT loaded: $selected", Toast.LENGTH_SHORT).show()
                }
                appSettings.save(overlay.getNormalizedPoints())
            }
            .show()
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
        isRectifiedMain = !isRectifiedMain

        val cameraParent = cameraContainer.parent as android.view.ViewGroup
        val previewParent = previewRectified.parent as android.view.ViewGroup
        
        cameraParent.removeView(cameraContainer)
        previewParent.removeView(previewRectified)

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
    }

    companion object {
        private const val TAG = "C1Cam"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
