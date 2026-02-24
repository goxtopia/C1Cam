package com.zhuo.c1cam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var previewRectified: ImageView
    private lateinit var focusSlider: Slider
    private lateinit var captureButton: Button
    private lateinit var settingsButton: Button
    private lateinit var editModeToggle: ToggleButton

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null

    private var targetAspectRatio: Float = 1.414f // Default A4

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
                startCamera()
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
        captureButton = findViewById(R.id.capture_button)
        settingsButton = findViewById(R.id.settings_button)
        editModeToggle = findViewById(R.id.edit_mode_toggle)

        focusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                setFocusDistance(value)
            }
        }

        editModeToggle.setOnCheckedChangeListener { _, isChecked ->
            overlay.isEditMode = isChecked
            overlay.invalidate()
        }

        settingsButton.setOnClickListener {
            showAspectRatioDialog()
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf("Original", "A4 (1.414)", "Letter (1.294)", "4:3 (1.333)", "16:9 (1.778)")
        val values = floatArrayOf(0f, 1.414f, 1.294f, 1.333f, 1.778f)

        AlertDialog.Builder(this)
            .setTitle("Select Target Aspect Ratio")
            .setItems(options) { dialog, which ->
                targetAspectRatio = values[which]
                Toast.makeText(this, "Aspect Ratio set to ${options[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val viewW = viewFinder.width
        val viewH = viewFinder.height

        // Use a background executor for processing to avoid blocking UI
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread {
                        Toast.makeText(baseContext, "Capture failed", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    processAndSaveImage(image, viewW, viewH)
                }
            }
        )
    }

    private fun processAndSaveImage(imageProxy: ImageProxy, viewW: Int, viewH: Int) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = imageProxy.toBitmap()
        imageProxy.close() // Close immediately after conversion

        // Rotate to upright
        val uprightBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // Map points
        val normalizedPoints = overlay.getNormalizedPoints()
        val mappedPoints = mapPointsToImage(normalizedPoints, uprightBitmap.width, uprightBitmap.height, viewW, viewH)

        // Rectify
        val rectifiedBitmap = RectificationUtils.rectifyBitmap(uprightBitmap, mappedPoints, targetAspectRatio)

        // Save
        saveBitmapToGallery(rectifiedBitmap)
    }

    private fun mapPointsToImage(normalizedViewPoints: List<PointF>, imageW: Int, imageH: Int, viewW: Int, viewH: Int): List<PointF> {
        if (normalizedViewPoints.size != 4) return normalizedViewPoints

        val fViewW = viewW.toFloat()
        val fViewH = viewH.toFloat()

        // Assuming FILL_CENTER logic (scale to fill)
        val scale = max(fViewW / imageW, fViewH / imageH)

        val scaledW = imageW * scale
        val scaledH = imageH * scale

        val dx = (fViewW - scaledW) / 2
        val dy = (fViewH - scaledH) / 2

        return normalizedViewPoints.map { pNorm ->
            // pNorm is normalized to View (0..1)
            val pViewX = pNorm.x * fViewW
            val pViewY = pNorm.y * fViewH

            // Map to Image Pixels
            val pImageX = (pViewX - dx) / scale
            val pImageY = (pViewY - dy) / scale

            // Normalize to Image (0..1)
            PointF(pImageX / imageW, pImageY / imageH)
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/C1Cam")
            }
        }

        val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (outputUri != null) {
            try {
                val outputStream = contentResolver.openOutputStream(outputUri)
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    outputStream.close()
                    runOnUiThread {
                        Toast.makeText(baseContext, "Saved to Gallery: $name", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving image", e)
                runOnUiThread {
                    Toast.makeText(baseContext, "Error saving image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            // ImageAnalysis
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap()

                // Rotate if needed
                val rotatedBitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }

                val points = overlay.getNormalizedPoints()
                val viewW = viewFinder.width
                val viewH = viewFinder.height

                if (points.size == 4 && viewW > 0 && viewH > 0) {
                    val mappedPoints = mapPointsToImage(points, rotatedBitmap.width, rotatedBitmap.height, viewW, viewH)
                    val rectified = RectificationUtils.rectifyBitmap(rotatedBitmap, mappedPoints, targetAspectRatio)
                    runOnUiThread {
                        previewRectified.setImageBitmap(rectified)
                    }
                }

                imageProxy.close()
            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )

                // Initialize focus to current slider value
                setFocusDistance(focusSlider.value)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setFocusDistance(sliderValue: Float) {
        val cam = camera ?: return
        val cameraControl = Camera2CameraControl.from(cam.cameraControl)

        // Get min focus distance (max diopter)
        val cameraInfo = Camera2CameraInfo.from(cam.cameraInfo)
        val minDistance = cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // Mapping:
        // 0.0 -> 0.0 (Infinity)
        // 0.5 -> 2.0 (0.5m)
        // 1.0 -> minDistance (e.g. 10.0 for 10cm)

        // Ensure minDistance is reasonable. If < 2.0, fallback to linear.
        val maxDiopter = if (minDistance > 2.0f) minDistance else 10.0f

        val diopter = if (sliderValue <= 0.5f) {
            // Map [0, 0.5] to [0, 2.0]
            sliderValue * 4.0f
        } else {
            // Map [0.5, 1.0] to [2.0, maxDiopter]
            val t = (sliderValue - 0.5f) * 2.0f // t in [0, 1]
            2.0f + t * (maxDiopter - 2.0f)
        }

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
            .build()

        cameraControl.setCaptureRequestOptions(options)
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "C1Cam"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
