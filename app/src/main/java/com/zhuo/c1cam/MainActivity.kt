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
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var deviceOrientationManager: DeviceOrientationManager
    private lateinit var orientationAwareControls: List<View>

    private var isFullscreen = false
    private var isRectifiedMain = false
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

        cameraManager = CameraManager(
            activity = this,
            viewFinder = viewFinder,
            previewRectified = previewRectified,
            overlay = overlay,
            appSettings = appSettings,
            imageProcessor = imageProcessor,
            lutProvider = { currentLut },
            savedImageRotationDegreesProvider = {
                OrientationMath.savedImageRotationDegrees(
                    deviceRotation = latestDeviceRotation,
                    displayRotation = latestDisplayRotation
                )
            },
            onPreviewSourceAspectRatioChanged = { updateCropFrameGuideUi() }
        )
        orientationAwareControls = listOf<View>(
            findViewById<View>(R.id.brand_mark),
            editModeToggle,
            previewDisplayToggle,
            settingsButton,
            findViewById<View>(R.id.ev_label),
            findViewById<View>(R.id.focus_label),
            focusModeButton,
            aeLockButton,
            afLockButton
        )
        deviceOrientationManager = DeviceOrientationManager(this) { rotation ->
            runOnUiThread {
                latestDeviceRotation = rotation
                hasDeviceRotationSample = true
                // Physical device orientation is only used to keep the controls upright.
                // CameraX must follow the app display orientation; otherwise a landscape
                // grip can rotate the crop/LUT while the activity itself is still portrait.
                rotateControlsToRemainUpright(rotation)
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
        if (appSettings.savedPoints != null) {
            overlay.setNormalizedPoints(appSettings.savedPoints!!)
        }

        overlay.isOverlayVisible = !appSettings.isCropModeOff

        focusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                appSettings.focusVal = value
                if (appSettings.focusMode == FocusMode.MANUAL) {
                    cameraManager.setFocusDistance(value)
                }
            }
        }

        focusModeButton.setOnClickListener {
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
            val locked = !appSettings.isAeLocked
            cameraManager.setAeLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }

        afLockButton.setOnClickListener {
            if (appSettings.focusMode != FocusMode.AUTO) {
                return@setOnClickListener
            }
            val locked = !appSettings.isAfLocked
            cameraManager.setAfLocked(locked)
            updateLockButtonsUi()
            appSettings.save(overlay.getNormalizedPoints())
        }

        evSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                appSettings.evVal = value
                cameraManager.setExposureCompensation(value)
            }
        }

        editModeToggle.setOnClickListener {
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
            toggleFullscreen()
        }
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
        overlay.onSingleTapListener = { x, y ->
            if (FocusModeUiModel.isTapToFocusEnabled(appSettings.focusMode, appSettings.isTapToFocusEnabled)) {
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
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        previewDisplayToggle.setOnClickListener {
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

        focusSlider.value = appSettings.focusVal
        evSlider.value = appSettings.evVal
        overlay.isOverlayVisible = !appSettings.isCropModeOff
        updateFocusModeUi()
        updateLockButtonsUi()
        applyPreviewDisplayMode(shouldSave = false)

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
    }

    private fun updatePreviewDisplayUi() {
        previewDisplayToggle.text = PreviewDisplayUiModel.toggleButtonLabel(appSettings.previewDisplayMode)
    }

    private fun updateCropFrameGuideUi() {
        val showGuide = CropFrameGuideModel.shouldShowGuide(
            isSettingEnabled = appSettings.isCropFrameGuideVisible,
            isCropModeOff = appSettings.isCropModeOff,
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
        super.onDestroy()
        appSettings.save(overlay.getNormalizedPoints())
        cameraManager.shutdown()
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
        rotateControlsToRemainUpright(displayRotation)
        viewFinder.post { updateCropFrameGuideUi() }
    }

    private fun rotateControlsToRemainUpright(deviceRotation: Int) {
        val displayRotation = viewFinder.display?.rotation ?: Surface.ROTATION_0
        val controlRotation = OrientationMath.controlRotationDegrees(
            deviceRotation = deviceRotation,
            displayRotation = displayRotation
        ).toFloat()

        orientationAwareControls.forEach { view ->
            view.animate()
                .rotation(controlRotation)
                .setDuration(ORIENTATION_ANIMATION_DURATION_MS)
                .start()
        }
    }

    private fun toggleFullscreen() {
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
        private const val ORIENTATION_ANIMATION_DURATION_MS = 220L
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
