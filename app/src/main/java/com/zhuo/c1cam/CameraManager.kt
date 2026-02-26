package com.zhuo.c1cam

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
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

            imageCapture = ImageCapture.Builder()
                .setBufferFormat(ImageFormat.YUV_420_888)
                .build()

            // Optimized: Set target resolution to reduce processing load for preview analysis
            // 720p is sufficient for the on-screen preview overlay
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                val points = overlay.getNormalizedPoints()
                val viewW = viewFinder.width
                val viewH = viewFinder.height

                if (points.size == 4 && viewW > 0 && viewH > 0) {
                    val finalPreview = imageProcessor.processForPreview(
                        imageProxy, points, viewW, viewH, appSettings.targetAspectRatio, lutProvider(), appSettings.isCropModeOff, appSettings.focalLength
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
                        image, points, viewW, viewH, ratio, lut, appSettings.isChromaDenoiseOn, appSettings.isCropModeOff, appSettings.focalLength
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

        if (appSettings.isWdrMode) {
            // CONTROL_TONEMAP_MODE_CONTRAST_CURVE = 0
            optionsBuilder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, 0)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, createWdrCurve())
        } else {
            // CONTROL_TONEMAP_MODE_FAST = 1
            optionsBuilder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, 1)
        }

        cameraControl.addCaptureRequestOptions(optionsBuilder.build())
    }

    private fun createWdrCurve(): android.hardware.camera2.params.TonemapCurve {
        val size = 64
        val curve = FloatArray(size * 2)
        for (i in 0 until size) {
            val inVal = i.toFloat() / (size - 1)
            // WDR Curve: Lift shadows. Standard sRGB is approx x^(1/2.2) = x^0.45
            // WDR we want to preserve more shadow detail, so effectively a lower gamma power?
            // Actually, linear raw -> output.
            // If we use standard gamma 2.2, out = in^(1/2.2).
            // To lift shadows more, we need a smaller exponent, e.g. 1/3.0 = 0.33.
            // Or use a custom log-like curve.
            // Let's use x^0.35
            val outVal = Math.pow(inVal.toDouble(), 0.35).toFloat().coerceIn(0f, 1f)
            curve[i * 2] = inVal
            curve[i * 2 + 1] = outVal
        }
        return android.hardware.camera2.params.TonemapCurve(curve, curve, curve)
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
