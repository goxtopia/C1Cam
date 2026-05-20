package com.zhuo.c1cam

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAP_TO_FOCUS_DURATION_SECONDS = 15L
private const val TAP_TO_FOCUS_POINT_SIZE = 0.2f

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
    private var latestTapToFocusRequestId: Long = 0L
    private var latestAutoFocusDistanceDiopter: Float? = null
    private var pendingAfLockAfterFocus = false
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder)
            .setSessionCaptureCallback(createAfStateCaptureCallback())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = previewBuilder.build().also {
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
                        imageProxy,
                        points,
                        viewW,
                        viewH,
                        appSettings.targetAspectRatio,
                        lutProvider(),
                        appSettings.isCropModeOff,
                        appSettings.focalLength,
                        appSettings.noCropAspectRatio
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
                applyFocusMode()
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
                        image,
                        points,
                        viewW,
                        viewH,
                        ratio,
                        lut,
                        appSettings.isChromaDenoiseOn,
                        appSettings.isCropModeOff,
                        appSettings.focalLength,
                        appSettings.noCropAspectRatio
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
        applyCaptureRequestOptions()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCaptureRequestOptions() {
        val cam = camera ?: return
        val cameraControl = Camera2CameraControl.from(cam.cameraControl)
        cameraControl.setCaptureRequestOptions(buildCaptureRequestOptions(cam))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildCaptureRequestOptions(cam: Camera): CaptureRequestOptions {
        val optionsBuilder = CaptureRequestOptions.Builder()
        val cameraInfo = Camera2CameraInfo.from(cam.cameraInfo)

        if (appSettings.isSportsMode) {
            val availableSceneModes = cameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)

            if (availableSceneModes.contains(CaptureRequest.CONTROL_SCENE_MODE_ACTION)) {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_ACTION)
            } else if (availableSceneModes.contains(CaptureRequest.CONTROL_SCENE_MODE_SPORTS)) {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_SPORTS)
            } else {
                Log.w("CameraManager", "Sport/Action scene mode not supported on this device")
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            }
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

        optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, appSettings.isAeLocked)

        if (appSettings.isAfLocked) {
            latestAutoFocusDistanceDiopter?.let { diopter ->
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
            } ?: Log.w("CameraManager", "AF lock requested but no autofocus distance has been captured yet")
        } else if (appSettings.focusMode == FocusMode.MANUAL) {
            val diopter = sliderValueToDiopter(appSettings.focusVal, cameraInfo)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        }

        return optionsBuilder.build()
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

        val index = (evValue / stepVal).roundToInt()
        val clampedIndex = index.coerceIn(range.lower, range.upper)

        cam.cameraControl.setExposureCompensationIndex(clampedIndex)
    }

    fun setAeLocked(locked: Boolean) {
        appSettings.isAeLocked = locked
        applyCaptureRequestOptions()
    }

    fun setAfLocked(locked: Boolean) {
        val cam = camera
        if (!locked) {
            pendingAfLockAfterFocus = false
            appSettings.isAfLocked = false
            applyFocusMode()
            return
        }

        if (appSettings.focusMode != FocusMode.AUTO) {
            Log.w("CameraManager", "AF lock requires AUTO focus mode")
            appSettings.isAfLocked = false
            return
        }

        if (latestAutoFocusDistanceDiopter != null) {
            pendingAfLockAfterFocus = false
            appSettings.isAfLocked = true
            applyCaptureRequestOptions()
            return
        }

        if (cam == null) {
            Log.w("CameraManager", "AF lock requested before camera is ready")
            appSettings.isAfLocked = false
            return
        }

        pendingAfLockAfterFocus = true
        appSettings.isAfLocked = false
        val future = cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(
                viewFinder.meteringPointFactory.createPoint(viewFinder.width / 2f, viewFinder.height / 2f, TAP_TO_FOCUS_POINT_SIZE),
                FocusMeteringAction.FLAG_AF
            ).setAutoCancelDuration(TAP_TO_FOCUS_DURATION_SECONDS, TimeUnit.SECONDS).build()
        )
        future.addListener(
            {
                try {
                    val success = future.get().isFocusSuccessful
                    Log.d("CameraManager", "Center autofocus for AF lock completed, success=$success")
                    if (!success) {
                        pendingAfLockAfterFocus = false
                    }
                } catch (e: Exception) {
                    pendingAfLockAfterFocus = false
                    Log.w("CameraManager", "Center autofocus for AF lock failed", e)
                }
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyFocusMode() {
        when (appSettings.focusMode) {
            FocusMode.AUTO -> enableAutoFocus()
            FocusMode.MANUAL -> applyCaptureRequestOptions()
        }
    }

    fun focusOnPoint(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val cam = camera ?: return
        if (appSettings.focusMode != FocusMode.AUTO) return

        val requestId = ++latestTapToFocusRequestId
        val factory: MeteringPointFactory = viewFinder.meteringPointFactory
        val afPoint = factory.createPoint(x, y, TAP_TO_FOCUS_POINT_SIZE)
        val aePoint = factory.createPoint(x, y, TAP_TO_FOCUS_POINT_SIZE * 1.5f)
        val action = FocusMeteringAction.Builder(
            afPoint,
            FocusMeteringAction.FLAG_AF
        )
            .addPoint(aePoint, FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(TAP_TO_FOCUS_DURATION_SECONDS, TimeUnit.SECONDS)
            .build()

        if (!cam.cameraInfo.isFocusMeteringSupported(action)) {
            Log.w("CameraManager", "Tap-to-focus is not supported on this device")
            onResult?.invoke(false)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val cancelFuture = cam.cameraControl.cancelFocusAndMetering()
        cancelFuture.addListener(
            {
                if (requestId != latestTapToFocusRequestId) {
                    Log.d("CameraManager", "Ignoring outdated tap-to-focus request $requestId")
                    return@addListener
                }
                try {
                    cancelFuture.get()
                } catch (e: Exception) {
                    Log.d("CameraManager", "Previous focus/metering cancel finished with non-fatal exception", e)
                }
                startFocusMeteringAction(cam, action, requestId, onResult)
            },
            executor
        )
    }

    private fun startFocusMeteringAction(
        cam: Camera,
        action: FocusMeteringAction,
        requestId: Long,
        onResult: ((Boolean) -> Unit)?
    ) {
        if (requestId != latestTapToFocusRequestId) {
            Log.d("CameraManager", "Skipping start for outdated tap-to-focus request $requestId")
            return
        }

        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener(
            {
                if (requestId != latestTapToFocusRequestId) {
                    Log.d("CameraManager", "Ignoring completion for outdated tap-to-focus request $requestId")
                    return@addListener
                }
                val success = try {
                    future.get().isFocusSuccessful.also {
                        Log.d("CameraManager", "Tap-to-focus completed, success=$it, requestId=$requestId")
                    }
                } catch (e: Exception) {
                    Log.w("CameraManager", "Tap-to-focus failed for requestId=$requestId", e)
                    false
                }
                activity.runOnUiThread {
                    if (requestId == latestTapToFocusRequestId) {
                        onResult?.invoke(success)
                    }
                }
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setFocusDistance(sliderValue: Float) {
        val cam = camera ?: return
        val cameraInfo = Camera2CameraInfo.from(cam.cameraInfo)
        val diopter = sliderValueToDiopter(sliderValue, cameraInfo)
        appSettings.focusVal = sliderValue

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
            .build()

        val cameraControl = Camera2CameraControl.from(cam.cameraControl)
        cameraControl.setCaptureRequestOptions(options)
        updateCameraSettings()
    }

    private fun sliderValueToDiopter(sliderValue: Float, cameraInfo: Camera2CameraInfo): Float {
        val minDistance = cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val maxDiopter = if (minDistance > 2.0f) minDistance else 10.0f

        return if (sliderValue <= 0.5f) {
            sliderValue * 4.0f
        } else {
            val t = (sliderValue - 0.5f) * 2.0f
            2.0f + t * (maxDiopter - 2.0f)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enableAutoFocus() {
        val cam = camera ?: return
        val cameraInfo = Camera2CameraInfo.from(cam.cameraInfo)
        val availableModes = cameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val hasAutoFocusSupport = availableModes.any {
            it == CaptureRequest.CONTROL_AF_MODE_AUTO ||
                it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
                it == CaptureRequest.CONTROL_AF_MODE_MACRO
        }

        Log.d("CameraManager", "Available AF modes=${availableModes.joinToString()}")

        if (!hasAutoFocusSupport) {
            Log.w("CameraManager", "Auto focus not supported on this device, keeping manual focus")
            setFocusDistance(appSettings.focusVal)
            return
        }

        if (!appSettings.isAfLocked) {
            latestAutoFocusDistanceDiopter = null
        }

        // In AUTO mode, do not pin CONTROL_AF_MODE via Camera2 interop.
        // Let CameraX choose the default repeating AF mode, and let tap-to-focus
        // temporarily switch to CONTROL_AF_MODE_AUTO internally when needed.
        applyCaptureRequestOptions()
    }

    private fun createAfStateCaptureCallback(): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val lensDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (lensDistance != null && lensDistance > 0f) {
                    latestAutoFocusDistanceDiopter = lensDistance
                }

                if (!pendingAfLockAfterFocus || appSettings.isAfLocked) return

                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                ) {
                    val lockedDistance = latestAutoFocusDistanceDiopter
                    if (lockedDistance != null && lockedDistance > 0f) {
                        pendingAfLockAfterFocus = false
                        appSettings.isAfLocked = true
                        activity.runOnUiThread {
                            applyCaptureRequestOptions()
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        try {
            if (!cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cameraExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            cameraExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        GlRectificationUtils.release()
    }
}
