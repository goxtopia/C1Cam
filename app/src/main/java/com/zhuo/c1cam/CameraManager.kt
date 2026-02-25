package com.zhuo.c1cam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val activity: AppCompatActivity,
    private val viewFinder: PreviewView,
    private val previewRectified: ImageView,
    private val overlay: OverlayView,
    private val appSettings: AppSettings,
    private val imageProcessor: ImageProcessor,
    private val lutProvider: () -> Lut3D?
) {

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                val points = overlay.getNormalizedPoints()
                val viewW = viewFinder.width
                val viewH = viewFinder.height

                if (points.size == 4 && viewW > 0 && viewH > 0) {
                    val finalPreview = imageProcessor.processForPreview(
                        imageProxy, points, viewW, viewH, appSettings.targetAspectRatio, lutProvider()
                    )

                    activity.runOnUiThread {
                        previewRectified.setImageBitmap(finalPreview)
                    }
                }

                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    activity, cameraSelector, preview, imageCapture, imageAnalysis
                )

                updateCameraSettings()
                setFocusDistance(appSettings.focusVal)
                setExposureCompensation(appSettings.evVal)

            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(activity))
    }

    fun takePhoto() {
        val capture = imageCapture ?: return

        val viewW = viewFinder.width
        val viewH = viewFinder.height
        val points = overlay.getNormalizedPoints()
        val ratio = appSettings.targetAspectRatio
        val lut = lutProvider()

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraManager", "Photo capture failed: ${exc.message}", exc)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Capture failed", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    imageProcessor.processAndSaveImage(
                        image, points, viewW, viewH, ratio, lut, appSettings.isChromaDenoiseOn
                    )
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun updateCameraSettings() {
        val cam = camera ?: return
        val cameraControl = Camera2CameraControl.from(cam.cameraControl)

        val optionsBuilder = CaptureRequestOptions.Builder()

        if (appSettings.isSportsMode) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_ACTION)
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        }

        if (appSettings.isNoiseReductionOff) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }

        if (appSettings.isEdgeModeOff) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        }

        cameraControl.addCaptureRequestOptions(optionsBuilder.build())
    }

    fun setExposureCompensation(evValue: Float) {
        val cam = camera ?: return
        val exposureState = cam.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return

        val step = exposureState.exposureCompensationStep
        val range = exposureState.exposureCompensationRange

        val stepVal = step.toFloat()
        if (stepVal == 0f) return

        val index = (evValue / stepVal).toInt()
        val clampedIndex = index.coerceIn(range.lower, range.upper)

        cam.cameraControl.setExposureCompensationIndex(clampedIndex)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setFocusDistance(sliderValue: Float) {
        val cam = camera ?: return
        val cameraControl = Camera2CameraControl.from(cam.cameraControl)

        val cameraInfo = Camera2CameraInfo.from(cam.cameraInfo)
        val minDistance = cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val maxDiopter = if (minDistance > 2.0f) minDistance else 10.0f

        val diopter = if (sliderValue <= 0.5f) {
            sliderValue * 4.0f
        } else {
            val t = (sliderValue - 0.5f) * 2.0f
            2.0f + t * (maxDiopter - 2.0f)
        }

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
            .build()

        cameraControl.addCaptureRequestOptions(options)
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
