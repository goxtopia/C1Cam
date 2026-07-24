package com.zhuo.c1cam

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import java.io.File
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var previewRectified: ImageView
    private lateinit var focusSlider: Slider
    private lateinit var focusModeButton: Button
    private lateinit var aeLockButton: Button
    private lateinit var afLockButton: Button
    private lateinit var evSlider: Slider
    private lateinit var captureButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var settingsButton: com.google.android.material.button.MaterialButton
    private lateinit var previewDisplayToggle: com.google.android.material.button.MaterialButton
    private lateinit var editModeToggle: com.google.android.material.button.MaterialButton
    private lateinit var topControls: View
    private lateinit var bottomControls: View
    private lateinit var mainViewContainer: android.widget.FrameLayout
    private lateinit var cameraContainer: View
    private lateinit var liveViewLabel: View
    private lateinit var xpanDashboard: XpanDashboardView
    private lateinit var xpanProcessingPanel: XpanProcessingPanelView
    private lateinit var xpanBottomControls: View
    private lateinit var xpanViewfinderLabel: TextView
    private lateinit var xpanViewfinderOverlay: View
    private lateinit var xpanModeBadge: View
    private lateinit var cameraStatus: View
    private lateinit var xpanEvLabel: TextView
    private lateinit var xpanEvSlider: Slider
    private lateinit var xpanFocalSlider: Slider
    private lateinit var xpanFocalValue: TextView
    private lateinit var xpanMeteringButton: ImageView
    private lateinit var xpanAeLockButton: TextView
    private lateinit var xpanAfLockButton: TextView
    private lateinit var xpanCaptureButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var deviceOrientationManager: DeviceOrientationManager

    private var isFullscreen = false
    private var isRectifiedMain = false
    private var lastEvHapticStep: Int? = null
    private var lastFocalHapticStep: Int? = null
    private var hasDeviceRotationSample = false
    @Volatile
    private var latestDeviceRotation = Surface.ROTATION_0
    @Volatile
    private var latestDisplayRotation = Surface.ROTATION_0

    private lateinit var appSettings: AppSettings
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var cameraManager: CameraManager
    
    @Volatile
    private var currentLut: Lut3D? = null
    @Volatile
    private var pipelineLut: Lut3D? = null

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

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshSettingsFromStorage()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }


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
        focusModeButton = findViewById(R.id.focus_mode_button)
        aeLockButton = findViewById(R.id.ae_lock_button)
        afLockButton = findViewById(R.id.af_lock_button)
        evSlider = findViewById(R.id.ev_slider)
        captureButton = findViewById(R.id.capture_button)
        settingsButton = findViewById(R.id.settings_button)
        previewDisplayToggle = findViewById(R.id.preview_display_toggle)
        editModeToggle = findViewById(R.id.edit_mode_toggle)
        topControls = findViewById(R.id.top_controls)
        bottomControls = findViewById(R.id.bottom_controls)
        cameraContainer = findViewById(R.id.camera_container)
        mainViewContainer = findViewById(R.id.main_view_container)
        liveViewLabel = findViewById(R.id.live_view_label)
        xpanDashboard = findViewById(R.id.xpan_dashboard)
        xpanProcessingPanel = findViewById(R.id.xpan_processing_panel)
        xpanBottomControls = findViewById(R.id.xpan_bottom_controls)
        xpanViewfinderLabel = findViewById(R.id.xpan_viewfinder_label)
        xpanViewfinderOverlay = findViewById(R.id.xpan_viewfinder_overlay)
        xpanModeBadge = findViewById(R.id.xpan_mode_badge)
        cameraStatus = findViewById(R.id.camera_status)
        xpanEvLabel = findViewById(R.id.xpan_ev_label)
        xpanEvSlider = findViewById(R.id.xpan_ev_slider)
        xpanFocalSlider = findViewById(R.id.xpan_focal_slider)
        xpanFocalValue = findViewById(R.id.xpan_focal_value)
        xpanMeteringButton = findViewById(R.id.xpan_metering_button)
        xpanAeLockButton = findViewById(R.id.xpan_ae_lock_button)
        xpanAfLockButton = findViewById(R.id.xpan_af_lock_button)
        xpanCaptureButton = findViewById(R.id.xpan_capture_button)

        appSettings = AppSettings(this)
        imageProcessor = ImageProcessor(this)
        
        appSettings.lutName?.let { savedKey ->
            val normalizedKey = normalizeLutStorageKey(savedKey)
            val loadedLut = loadLutFromStorageKey(normalizedKey)
            if (loadedLut != null) {
                appSettings.lutName = normalizedKey
                currentLut = loadedLut
            } else {
                appSettings.lutName = null
            }
        }
        rebuildPipelineLut()

        cameraManager = CameraManager(
            activity = this,
            viewFinder = viewFinder,
            previewRectified = previewRectified,
            overlay = overlay,
            appSettings = appSettings,
            imageProcessor = imageProcessor,
            lutProvider = { pipelineLut },
            savedImageRotationDegreesProvider = {
                OrientationMath.savedImageRotationDegrees(
                    deviceRotation = latestDeviceRotation,
                    displayRotation = latestDisplayRotation
                )
            },
            onPreviewSourceAspectRatioChanged = { updateCropFrameGuideUi() },
            onXpanTelemetryUpdated = { telemetry ->
                xpanDashboard.updateTelemetry(telemetry)
                xpanProcessingPanel.updateTelemetry(telemetry)
            },
            onAfLockStateChanged = {
                updateLockButtonsUi()
            },
            onCaptureProcessingStatusChanged = { status ->
                xpanProcessingPanel.updateStatus(status)
            }
        )
        deviceOrientationManager = DeviceOrientationManager(this) { rotation ->
            runOnUiThread {
                latestDeviceRotation = rotation
                hasDeviceRotationSample = true
                // Controls stay screen-fixed so their borders cannot be clipped by their
                // layout slots. Physical orientation still drives capture rotation and
                // the XPAN dashboard instruments.
                updateOrientationDependentUi(rotation)
            }
        }

        // Restore UI values
        focusSlider.value = appSettings.focusVal
        updateFocusModeUi()
        updateLockButtonsUi()
        updatePreviewDisplayUi()
        updateEditModeButtonUi()
        applyPreviewDisplayMode(shouldSave = false)
        evSlider.value = appSettings.evVal
        xpanEvSlider.value = appSettings.evVal
        xpanFocalSlider.value = appSettings.focalLength.coerceIn(24, 50).toFloat()
        updateXpanFocalValue()
        updateXpanMeteringButtonUi()
        if (appSettings.savedPoints != null) {
            overlay.setNormalizedPoints(appSettings.savedPoints!!)
        }

        overlay.isOverlayVisible = !appSettings.isCropModeOff
        applyXpanModeUi()

        focusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                appSettings.focusVal = value
                if (appSettings.focusMode == FocusMode.MANUAL) {
                    cameraManager.setFocusDistance(value)
                }
            }
        }

        focusModeButton.setOnClickListener {
            performCrispButtonHaptic(it)
            appSettings.focusMode = appSettings.focusMode.toggled()
            if (appSettings.focusMode == FocusMode.MANUAL) {
                appSettings.isAfLocked = false
            }
            updateFocusModeUi()
            updateLockButtonsUi()
            cameraManager.applyFocusMode()
            appSettings.save(overlay.getNormalizedPoints())
        }

        aeLockButton.setOnClickListener {
            performCrispButtonHaptic(it)
            val locked = !appSettings.isAeLocked
            cameraManager.setAeLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }

        afLockButton.setOnClickListener {
            if (appSettings.focusMode != FocusMode.AUTO) {
                return@setOnClickListener
            }
            performCrispButtonHaptic(it)
            val locked = !appSettings.isAfLocked
            cameraManager.setAfLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }
        xpanAeLockButton.setOnClickListener {
            performCrispButtonHaptic(it)
            val locked = !appSettings.isAeLocked
            cameraManager.setAeLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }
        xpanEvLabel.setOnClickListener {
            performCrispButtonHaptic(it)
            val locked = !appSettings.isAeLocked
            cameraManager.setAeLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }
        xpanAfLockButton.setOnClickListener {
            performCrispButtonHaptic(it)
            val locked = !appSettings.isAfLocked
            cameraManager.setAfLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }
        xpanMeteringButton.setOnClickListener {
            performCrispButtonHaptic(it)
            val mode = appSettings.xpanMeteringMode.next()
            cameraManager.setXpanMeteringMode(mode)
            updateXpanMeteringButtonUi()
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
            Toast.makeText(
                this,
                mode.displayName,
                Toast.LENGTH_SHORT
            ).show()
        }

        evSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                performEvTickIfNeeded(slider = evSlider, value = value)
                appSettings.evVal = value
                xpanEvSlider.value = value
                cameraManager.setExposureCompensation(value)
            }
        }
        xpanEvSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                performEvTickIfNeeded(slider = xpanEvSlider, value = value)
                appSettings.evVal = value
                evSlider.value = value
                cameraManager.setExposureCompensation(value)
            }
        }
        xpanFocalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val step = value.roundToInt()
                if (lastFocalHapticStep != step) {
                    if (FocalLengthDetents.isClassicDetent(step)) {
                        performFocalDetentHaptic()
                    } else {
                        xpanFocalSlider.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CLOCK_TICK
                        )
                    }
                    lastFocalHapticStep = step
                }
                appSettings.focalLength = value.toInt()
                updateXpanFocalValue()
                cameraManager.setEquivalentFocalLength(appSettings.focalLength)
            }
        }

        editModeToggle.setOnClickListener {
            performCrispButtonHaptic(it)
            val isChecked = !overlay.isEditMode
            if (isChecked && isRectifiedMain) {
                Toast.makeText(this, "Can only edit when camera is visible", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            overlay.isEditMode = isChecked
            updateEditModeButtonUi()
            overlay.invalidate()
        }

        overlay.onDoubleTapListener = {
            if (!appSettings.isXpanMode) {
                toggleFullscreen()
            }
        }
        previewRectified.setOnTouchListener(object : View.OnTouchListener {
            private var lastClickTime: Long = 0

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    val clickTime = System.currentTimeMillis()
                    if (clickTime - lastClickTime < 300 && !appSettings.isXpanMode) {
                        toggleFullscreen()
                    }
                    lastClickTime = clickTime
                }
                return true
            }
        })
        overlay.onSingleTapListener = { x, y ->
            if (!appSettings.isXpanMode &&
                FocusModeUiModel.isTapToFocusEnabled(
                    appSettings.focusMode,
                    appSettings.isTapToFocusEnabled
                )
            ) {
                overlay.showFocusIndicator(x, y, Color.WHITE)
                cameraManager.focusOnPoint(x, y) { success ->
                    overlay.showFocusIndicator(
                        x,
                        y,
                        if (success) Color.parseColor("#66FF66") else Color.parseColor("#FF6B6B")
                    )
                }
            }
        }

        settingsButton.setOnClickListener {
            performCrispButtonHaptic(it)
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        previewDisplayToggle.setOnClickListener {
            performCrispButtonHaptic(it)
            if (overlay.isEditMode && appSettings.previewDisplayMode == PreviewDisplayMode.CAMERA) {
                overlay.isEditMode = false
                updateEditModeButtonUi()
            }
            appSettings.previewDisplayMode = appSettings.previewDisplayMode.toggled()
            applyPreviewDisplayMode()
            cameraManager.updatePreviewAnalysisMode()
        }

        captureButton.setOnClickListener {
            triggerCapture()
        }
        xpanCaptureButton.setOnClickListener {
            triggerCapture()
        }

        if (allPermissionsGranted()) {
            cameraManager.startCamera()
        } else {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                // 在锁屏状态下，绝对不能请求权限，只能提示用户并退出或展示占位界面
                Toast.makeText(this, "首次使用或权限丢失，请先解锁手机授予相机权限", Toast.LENGTH_LONG).show()
                finish() // 建议直接关闭，让用户去正常桌面打开授权
            } else {
                // 正常解锁状态，安全地请求权限
                requestPermissions()
            }
        }
    }

    private fun refreshSettingsFromStorage() {
        appSettings.load()

        currentLut = appSettings.lutName?.let { savedKey ->
            val normalizedKey = normalizeLutStorageKey(savedKey)
            loadLutFromStorageKey(normalizedKey)?.also {
                appSettings.lutName = normalizedKey
            }
        }
        if (appSettings.lutName != null && currentLut == null) {
            appSettings.lutName = null
        }
        rebuildPipelineLut()

        focusSlider.value = appSettings.focusVal
        evSlider.value = appSettings.evVal
        xpanEvSlider.value = appSettings.evVal
        xpanFocalSlider.value = appSettings.focalLength.coerceIn(24, 50).toFloat()
        updateXpanFocalValue()
        updateXpanMeteringButtonUi()
        overlay.isOverlayVisible = !appSettings.isXpanMode && !appSettings.isCropModeOff
        updateFocusModeUi()
        updateLockButtonsUi()
        applyPreviewDisplayMode(shouldSave = false)
        applyXpanModeUi()

        cameraManager.updateCameraSettings()
        cameraManager.applyFocusMode()
        cameraManager.setExposureCompensation(appSettings.evVal)
        cameraManager.updatePreviewAnalysisMode()
        updateCropFrameGuideUi()
        appSettings.save(overlay.getNormalizedPoints())
    }

    private fun updateFocusModeUi() {
        focusModeButton.text = FocusModeUiModel.buttonLabel(appSettings.focusMode)
        focusSlider.isEnabled = FocusModeUiModel.isFocusSliderEnabled(appSettings.focusMode)
        focusSlider.alpha = if (focusSlider.isEnabled) 1f else 0.4f
    }

    private fun updateLockButtonsUi() {
        aeLockButton.text = if (appSettings.isAeLocked) "AE•" else "AE"
        afLockButton.text = if (appSettings.isAfLocked) "AF•" else "AF"
        aeLockButton.alpha = if (appSettings.isAeLocked) 1f else 0.72f
        afLockButton.isEnabled = appSettings.focusMode == FocusMode.AUTO
        afLockButton.alpha = if (appSettings.focusMode != FocusMode.AUTO) 0.35f else if (appSettings.isAfLocked) 1f else 0.72f
        aeLockButton.setBackgroundResource(
            if (appSettings.isAeLocked) R.drawable.bg_metal_button_active
            else R.drawable.bg_metal_button
        )
        afLockButton.setBackgroundResource(
            if (appSettings.isAfLocked) R.drawable.bg_metal_button_active
            else R.drawable.bg_metal_button
        )

        xpanAeLockButton.text = "AEL"
        xpanAfLockButton.text = "AFL"
        val xpanAfEnabled = XpanMode.effectiveFocusMode(
            appSettings.isXpanMode,
            appSettings.focusMode
        ) == FocusMode.AUTO
        xpanAfLockButton.isEnabled = xpanAfEnabled
        applyXpanLockVisual(xpanAeLockButton, appSettings.isAeLocked, enabled = true)
        applyXpanLockVisual(xpanEvLabel, appSettings.isAeLocked, enabled = true)
        applyXpanLockVisual(xpanAfLockButton, appSettings.isAfLocked, xpanAfEnabled)
    }

    private fun applyXpanLockVisual(button: TextView, locked: Boolean, enabled: Boolean) {
        button.isSelected = locked && enabled
        button.alpha = if (enabled) 1f else 0.62f
        button.elevation = when {
            !enabled -> 0f
            locked -> 0f
            else -> dp(3).toFloat()
        }
        button.translationZ = 0f
    }

    private fun updatePreviewDisplayUi() {
        previewDisplayToggle.text = PreviewDisplayUiModel.toggleButtonLabel(appSettings.previewDisplayMode)
    }

    private fun updateCropFrameGuideUi() {
        val showGuide = CropFrameGuideModel.shouldShowGuide(
            isSettingEnabled = appSettings.isCropFrameGuideVisible,
            isCropModeOff = appSettings.isCropModeOff && !appSettings.isXpanMode,
            previewDisplayMode = appSettings.previewDisplayMode
        )
        overlay.isCropFrameGuideVisible = showGuide
        overlay.cropFrameGuideLabel = if (showGuide) {
            "${appSettings.focalLength} MM  •  ${cropGuideRatioLabel(appSettings.noCropAspectRatio)}"
        } else {
            null
        }
        overlay.cropFrameGuideRect = if (showGuide) {
            CropFrameGuideModel.projectedFrameRectInView(
                sourceAspectRatio = cameraManager.getLatestPreviewSourceAspectRatio(),
                viewAspectRatio = getCameraPreviewViewAspectRatio(),
                focalLength = appSettings.focalLength,
                aspectRatio = appSettings.noCropAspectRatio
            )
        } else {
            null
        }
    }

    private fun cropGuideRatioLabel(aspectRatio: Float): String {
        return when {
            aspectRatio <= 0f -> "FULL"
            kotlin.math.abs(aspectRatio - 1f) < 0.001f -> "1:1"
            kotlin.math.abs(aspectRatio - 1.5f) < 0.001f -> "3:2"
            kotlin.math.abs(aspectRatio - 16f / 9f) < 0.001f -> "16:9"
            kotlin.math.abs(aspectRatio - 2.35f) < 0.001f -> "2.35:1"
            kotlin.math.abs(aspectRatio - 2.55f) < 0.001f -> "2.55:1"
            else -> "${"%.2f".format(java.util.Locale.US, aspectRatio)}:1"
        }
    }

    private fun getCameraPreviewViewAspectRatio(): Float {
        if (viewFinder.width > 0 && viewFinder.height > 0) {
            return viewFinder.width.toFloat() / viewFinder.height.toFloat()
        }
        return 3f / 4f
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // The display rotation can settle after onStart when returning from the
            // background. Re-read it once the activity window is actually focused.
            syncOrientationFromDisplay()
        }
    }

    private fun triggerCapture() {
        captureButton.performHapticFeedback(
            android.view.HapticFeedbackConstants.KEYBOARD_TAP,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        cameraManager.takePhoto()
    }

    private fun applyPreviewDisplayMode(shouldSave: Boolean = true) {
        if (appSettings.isXpanMode) {
            isRectifiedMain = false
            cameraContainer.visibility = View.VISIBLE
            previewRectified.visibility = View.GONE
            overlay.isEnabled = false
            updatePreviewDisplayUi()
            updateCropFrameGuideUi()
            return
        }
        isRectifiedMain = appSettings.previewDisplayMode == PreviewDisplayMode.RECTIFIED
        val showCamera = appSettings.previewDisplayMode == PreviewDisplayMode.CAMERA

        cameraContainer.visibility = if (showCamera) View.VISIBLE else View.GONE
        previewRectified.visibility = if (showCamera) View.GONE else View.VISIBLE
        overlay.isEnabled = showCamera

        if (!showCamera && overlay.isEditMode) {
            overlay.isEditMode = false
        }

        updatePreviewDisplayUi()
        updateCropFrameGuideUi()
        updateEditModeButtonUi()
        overlay.invalidate()

        if (shouldSave) {
            appSettings.save(overlay.getNormalizedPoints())
        }
    }

    private fun updateEditModeButtonUi() {
        val isEditing = overlay.isEditMode
        editModeToggle.isChecked = isEditing
        editModeToggle.alpha = if (isRectifiedMain) 0.45f else 1f
        editModeToggle.icon = ContextCompat.getDrawable(
            this,
            if (isEditing) R.drawable.ic_frame_done else R.drawable.ic_frame_edit
        )
        editModeToggle.text = ""
    }

    private fun applyXpanModeUi() {
        val enabled = appSettings.isXpanMode
        if (enabled) {
            overlay.isEditMode = false
        }

        xpanDashboard.setActive(enabled)
        xpanBottomControls.visibility = if (enabled) View.VISIBLE else View.GONE
        bottomControls.visibility = if (enabled) View.GONE else View.VISIBLE
        editModeToggle.visibility = if (enabled) View.GONE else View.VISIBLE
        previewDisplayToggle.visibility = if (enabled) View.GONE else View.VISIBLE
        cameraStatus.visibility = if (enabled) View.GONE else View.VISIBLE
        xpanModeBadge.visibility = if (enabled) View.VISIBLE else View.GONE
        xpanViewfinderLabel.visibility = if (enabled) View.VISIBLE else View.GONE
        xpanViewfinderOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        xpanProcessingPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        liveViewLabel.visibility = if (enabled) View.GONE else View.VISIBLE
        overlay.isEnabled = !enabled
        overlay.isOverlayVisible = !enabled && !appSettings.isCropModeOff
        viewFinder.scaleType = if (enabled) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }

        val mainParams = mainViewContainer.layoutParams as
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        mainParams.bottomToTop = if (enabled) R.id.xpan_bottom_controls else R.id.bottom_controls
        mainViewContainer.layoutParams = mainParams

        mainViewContainer.post {
            val params = cameraContainer.layoutParams as android.widget.FrameLayout.LayoutParams
            if (enabled) {
                val frame = XpanFrameLayoutModel.calculate(
                    mainViewContainer.width,
                    mainViewContainer.height
                )
                params.width = frame.width
                params.height = frame.height
                val isVerticalFrame = frame.height > frame.width
                xpanViewfinderLabel.text = if (isVerticalFrame) {
                    "24 × 65  ·  AF-C"
                } else {
                    "65 × 24  ·  AF-C"
                }
                params.gravity = Gravity.TOP or if (isVerticalFrame) Gravity.END else Gravity.START
                params.setMargins(
                    if (isVerticalFrame) 0 else dp(16),
                    dp(18),
                    if (isVerticalFrame) dp(16) else 0,
                    0
                )
                cameraContainer.setBackgroundResource(R.drawable.bg_xpan_viewfinder)
                cameraContainer.setPadding(dp(4), dp(4), dp(4), dp(4))
                cameraContainer.clipToOutline = true

                val labelParams = xpanViewfinderLabel.layoutParams as
                    android.widget.FrameLayout.LayoutParams
                labelParams.gravity = Gravity.TOP or if (isVerticalFrame) Gravity.END else Gravity.START
                labelParams.setMargins(
                    if (isVerticalFrame) 0 else dp(27),
                    dp(29),
                    if (isVerticalFrame) dp(27) else 0,
                    0
                )
                xpanViewfinderLabel.layoutParams = labelParams

                layoutXpanProcessingPanel()
            } else {
                params.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.NO_GRAVITY
                params.setMargins(0, 0, 0, 0)
                cameraContainer.background = null
                cameraContainer.setPadding(0, 0, 0, 0)
                cameraContainer.clipToOutline = false
            }
            cameraContainer.layoutParams = params
        }
        updateFocusModeUi()
        updateLockButtonsUi()
        updateEditModeButtonUi()
        updateCropFrameGuideUi()
    }

    private fun updateXpanFocalValue() {
        xpanFocalValue.text = "${appSettings.focalLength.coerceIn(24, 50)} MM"
    }

    private fun updateXpanMeteringButtonUi() {
        xpanMeteringButton.setImageResource(
            when (appSettings.xpanMeteringMode) {
                XpanMeteringMode.AVERAGE -> R.drawable.ic_metering_average
                XpanMeteringMode.CENTER_WEIGHTED -> R.drawable.ic_metering_center
                XpanMeteringMode.SPOT -> R.drawable.ic_metering_spot
            }
        )
        xpanMeteringButton.contentDescription = appSettings.xpanMeteringMode.displayName
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun performEvTickIfNeeded(slider: Slider, value: Float) {
        val step = value.roundToInt()
        if (lastEvHapticStep == step) return
        slider.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        lastEvHapticStep = step
    }

    private fun performCrispButtonHaptic(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun performFocalDetentHaptic() {
        val feedbackConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.HapticFeedbackConstants.CONFIRM
        } else {
            android.view.HapticFeedbackConstants.LONG_PRESS
        }
        xpanFocalSlider.performHapticFeedback(feedbackConstant)
        xpanFocalValue.animate().cancel()
        xpanFocalValue.scaleX = 1f
        xpanFocalValue.scaleY = 1f
        xpanFocalValue.animate()
            .scaleX(1.07f)
            .scaleY(1.07f)
            .setDuration(75L)
            .withEndAction {
                xpanFocalValue.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(90L)
                    .start()
            }
            .start()
    }

    private fun loadLutFromStorageKey(storageKey: String): Lut3D? {
        return when {
            storageKey.startsWith("$LUT_KEY_PREFIX_ASSET:") -> {
                val fileName = storageKey.removePrefix("$LUT_KEY_PREFIX_ASSET:")
                LutUtils.loadLut(this, fileName)
            }
            storageKey.startsWith("$LUT_KEY_PREFIX_IMPORTED:") -> {
                val fileName = storageKey.removePrefix("$LUT_KEY_PREFIX_IMPORTED:")
                if (!isSafeImportedFileName(fileName)) return null
                val file = resolveImportedLutFile(fileName) ?: return null
                if (!file.exists()) return null
                file.inputStream().use { LutUtils.loadLut(it) }
            }
            else -> LutUtils.loadLut(this, storageKey)
        }
    }

    private fun rebuildPipelineLut() {
        pipelineLut = ToneMapLutFactory.compose(
            preset = appSettings.toneMapPreset,
            creativeLut = currentLut
        )
    }

    private fun normalizeLutStorageKey(storedValue: String): String {
        return when {
            storedValue.startsWith("$LUT_KEY_PREFIX_ASSET:") || storedValue.startsWith("$LUT_KEY_PREFIX_IMPORTED:") -> storedValue
            else -> "$LUT_KEY_PREFIX_ASSET:$storedValue"
        }
    }

    private fun ensureImportedLutDir(): File {
        val dir = File(filesDir, IMPORTED_LUT_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun resolveImportedLutFile(fileName: String): File? {
        if (!isSafeImportedFileName(fileName)) return null
        return try {
            val dir = ensureImportedLutDir().canonicalFile
            val file = File(dir, fileName).canonicalFile
            if (file.toPath().startsWith(dir.toPath())) file else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeImportedFileName(fileName: String): Boolean {
        if (fileName.contains('/') || fileName.contains('\\')) return false
        if (fileName.contains("..")) return false
        if (fileName.startsWith('.')) return false
        return fileName.matches(Regex("^[A-Za-z0-9._-]+\\.cube$", RegexOption.IGNORE_CASE))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        appSettings.save(overlay.getNormalizedPoints())
        cameraManager.shutdown()
        super.onDestroy()
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

    override fun onStart() {
        super.onStart()
        hasDeviceRotationSample = false
        syncOrientationFromDisplay()
        deviceOrientationManager.start()
    }

    override fun onStop() {
        deviceOrientationManager.stop()
        super.onStop()
        appSettings.save(overlay.getNormalizedPoints())
    }

    @Suppress("DEPRECATION")
    private fun syncOrientationFromDisplay() {
        val displayRotation = viewFinder.display?.rotation
            ?: windowManager.defaultDisplay.rotation
        latestDisplayRotation = displayRotation
        if (!hasDeviceRotationSample) {
            latestDeviceRotation = displayRotation
        }
        cameraManager.updateTargetRotation(displayRotation)
        updateOrientationDependentUi(displayRotation)
        viewFinder.post { updateCropFrameGuideUi() }
    }

    private fun updateOrientationDependentUi(deviceRotation: Int) {
        val displayRotation = viewFinder.display?.rotation ?: Surface.ROTATION_0
        xpanDashboard.setOrientation(deviceRotation, displayRotation)
        if (appSettings.isXpanMode) {
            mainViewContainer.post { layoutXpanProcessingPanel() }
        }
    }

    private fun layoutXpanProcessingPanel() {
        if (mainViewContainer.width <= 0 || mainViewContainer.height <= 0) return
        val displayRotation = viewFinder.display?.rotation ?: Surface.ROTATION_0
        val infoLayout = XpanInfoColumnLayoutModel.calculate(
            containerWidth = mainViewContainer.width,
            containerHeight = mainViewContainer.height,
            density = resources.displayMetrics.density,
            displayRotation = displayRotation
        )
        val column = infoLayout.column
        val processingPanelParams = xpanProcessingPanel.layoutParams as
            android.widget.FrameLayout.LayoutParams
        processingPanelParams.width = column.right - column.left
        processingPanelParams.height = column.lcdBottom - column.lcdTop
        processingPanelParams.gravity = Gravity.TOP or Gravity.START
        processingPanelParams.setMargins(
            infoLayout.lcdViewLeft,
            infoLayout.lcdViewTop,
            0,
            0
        )
        xpanProcessingPanel.animate().cancel()
        xpanProcessingPanel.rotation = infoLayout.rotationDegrees.toFloat()
        xpanProcessingPanel.layoutParams = processingPanelParams
    }

    private fun toggleFullscreen() {
        if (appSettings.isXpanMode) return
        isFullscreen = !isFullscreen
        val params = mainViewContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isFullscreen) {
            params.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
            params.width = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT

            topControls.visibility = View.GONE
            bottomControls.visibility = View.GONE
        } else {
            params.height = 0
            params.width = 0

            topControls.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
        }
        mainViewContainer.layoutParams = params
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    triggerCapture()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val LUT_KEY_PREFIX_ASSET = "asset"
        private const val LUT_KEY_PREFIX_IMPORTED = "imported"
        private const val IMPORTED_LUT_DIR_NAME = "imported_luts"
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
