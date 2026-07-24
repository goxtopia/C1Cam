package com.zhuo.c1cam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
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
    private val lutProvider: () -> Lut3D?,
    private val savedImageRotationDegreesProvider: () -> Int,
    private val onPreviewSourceAspectRatioChanged: () -> Unit,
    private val onXpanTelemetryUpdated: (XpanTelemetry) -> Unit,
    private val onAfLockStateChanged: () -> Unit,
    private val onCaptureProcessingStatusChanged: (CaptureProcessingStatus) -> Unit
) {

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var latestTapToFocusRequestId: Long = 0L
    private var latestAutoFocusDistanceDiopter: Float? = null
    private var pendingAfLockAfterFocus = false
    private var analysisFrameCounter: Long = 0L
    private var lastTelemetryDispatchElapsedMs = 0L
    private var lastSoftwareMeteringUpdateElapsedMs = 0L
    private var lastLoggedMeteringMode: XpanMeteringMode? = null
    private var lastAppliedExposureCompensationIndex: Int? = null
    @Volatile
    private var xpanSoftwareMeteringCorrectionEv = 0f
    @Volatile
    private var latestPreviewSourceAspectRatio: Float = 3f / 4f
    @Volatile
    private var latestHistogram = FloatArray(64)
    @Volatile
    private var latestCaptureMetadata: CaptureMetadata? = null
    @Volatile
    private var latestStillCaptureMetadata: CaptureMetadata? = null
    @Volatile
    private var targetRotation: Int = Surface.ROTATION_0
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureProcessingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureSaveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureInFlightLimiter = CaptureInFlightLimiter(MAX_CAPTURES_IN_FLIGHT)
    private val captureProcessingTracker = CaptureProcessingTracker()
    private val captureSequence = AtomicLong(0L)

    fun startCamera() {
        if (appSettings.isXpanMode) {
            xpanSoftwareMeteringCorrectionEv = 0f
            lastSoftwareMeteringUpdateElapsedMs = 0L
        }
        lastAppliedExposureCompensationIndex = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

        val cameraCaptureCallback = createAfStateCaptureCallback()

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder()
                .setTargetRotation(targetRotation)
            if (appSettings.isXpanMode) {
                previewBuilder.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(XpanMode.PREVIEW_WIDTH, XpanMode.PREVIEW_HEIGHT),
                                FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
            }
            Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(cameraCaptureCallback)
            val previewUseCase = previewBuilder.build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            preview = previewUseCase

            val imageCaptureBuilder = ImageCapture.Builder()
                .setBufferFormat(ImageFormat.YUV_420_888)
                .setTargetRotation(targetRotation)
            Camera2Interop.Extender(imageCaptureBuilder)
                .setSessionCaptureCallback(cameraCaptureCallback)
            val imageCaptureUseCase = imageCaptureBuilder.build()
            imageCapture = imageCaptureUseCase

            val previewAnalysisPolicy = PreviewAnalysisPolicy.forMode(
                appSettings.previewDisplayMode,
                appSettings.isXpanMode
            )
            val imageAnalysisUseCase = createImageAnalysis(previewAnalysisPolicy)
            imageAnalysis = imageAnalysisUseCase
            analysisFrameCounter = 0L

            imageAnalysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                val frameIndex = ++analysisFrameCounter
                updateLatestPreviewSourceAspectRatio(PreviewFrameAspectRatioModel.fromFrame(
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees
                ))
                if (!previewAnalysisPolicy.shouldProcessFrame(frameIndex)) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                if (appSettings.isXpanMode) {
                    val lumaAnalysis = analyzeXpanLuma(imageProxy)
                    latestHistogram = lumaAnalysis.histogram
                    updateXpanSoftwareMetering(lumaAnalysis.meteredLuma)
                    dispatchXpanTelemetry()
                    imageProxy.close()
                    return@setAnalyzer
                }

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
                        if (appSettings.previewDisplayMode == PreviewDisplayMode.RECTIFIED) {
                            previewRectified.setImageBitmap(finalPreview)
                        }
                    }
                }

                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    activity,
                    cameraSelector,
                    previewUseCase,
                    imageCaptureUseCase,
                    imageAnalysisUseCase
                )

                updateCameraSettings()
                applyFocusMode()
                setExposureCompensation(appSettings.evVal)
                if (appSettings.isAfLocked && latestAutoFocusDistanceDiopter == null) {
                    setAfLocked(true)
                }

            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(activity))
    }

    fun updatePreviewAnalysisMode() {
        startCamera()
    }

    fun getLatestPreviewSourceAspectRatio(): Float = latestPreviewSourceAspectRatio

    fun updateTargetRotation(rotation: Int) {
        targetRotation = rotation
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
    }

    private fun updateLatestPreviewSourceAspectRatio(aspectRatio: Float) {
        if (kotlin.math.abs(aspectRatio - latestPreviewSourceAspectRatio) < 0.001f) return
        latestPreviewSourceAspectRatio = aspectRatio
        activity.runOnUiThread(onPreviewSourceAspectRatioChanged)
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (!captureInFlightLimiter.tryAcquire()) {
            Toast.makeText(activity, "Still processing photos", Toast.LENGTH_SHORT).show()
            return
        }
        val captureId = captureSequence.incrementAndGet()
        publishCaptureProcessingStatus(captureProcessingTracker.enqueue(captureId))

        val viewW = viewFinder.width
        val viewH = viewFinder.height
        val points = overlay.getNormalizedPoints()
        val ratio = appSettings.targetAspectRatio
        val lut = lutProvider()
        val captureStartedAt = System.currentTimeMillis()
        val captureStartedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val outputFormat = appSettings.imageOutputFormat
        val jpegQuality = appSettings.jpegQuality
        val chromaDenoiseMode = appSettings.chromaDenoiseMode
        val isCropModeOff = XpanMode.effectiveCropModeOff(
            appSettings.isXpanMode,
            appSettings.isCropModeOff
        )
        val focalLength = appSettings.focalLength
        val processingFocalLength = XpanMode.effectiveProcessingFocalLength(
            appSettings.isXpanMode,
            focalLength
        )
        val noCropAspectRatio = XpanMode.effectiveNoCropAspectRatio(
            appSettings.isXpanMode,
            appSettings.noCropAspectRatio
        )
        val exposureCompensationEv = appSettings.evVal
        // Freeze orientation at shutter press; sensor changes during processing must not
        // alter the direction of the photo being saved.
        val savedImageRotationDegrees = savedImageRotationDegreesProvider()
        latestStillCaptureMetadata = null

        try {
            capture.takePicture(
                captureProcessingExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onError(exc: ImageCaptureException) {
                        captureInFlightLimiter.release()
                        publishCaptureProcessingStatus(
                            captureProcessingTracker.complete(captureId)
                        )
                        Log.e("CameraManager", "Photo capture failed: ${exc.message}", exc)
                        showCaptureResult("Capture failed")
                    }

                    override fun onCaptureSuccess(image: ImageProxy) {
                        publishCaptureProcessingStatus(
                            captureProcessingTracker.update(
                                captureId,
                                CaptureProcessingStage.PROCESSING
                            )
                        )
                        Log.i(
                            CAPTURE_PERF_TAG,
                            "capture=$captureId stage=image_received " +
                                "latencyMs=${elapsedMillis(captureStartedElapsedNanos)} " +
                                "size=${image.width}x${image.height} " +
                                "rotation=${image.imageInfo.rotationDegrees}"
                        )
                        val captureMetadata = (latestStillCaptureMetadata ?: latestCaptureMetadata)?.copy(
                            capturedAtMillis = captureStartedAt,
                            equivalentFocalLengthMm = focalLength,
                            exposureCompensationEv = exposureCompensationEv
                        ) ?: CaptureMetadata(
                            capturedAtMillis = captureStartedAt,
                            iso = null,
                            exposureTimeNs = null,
                            aperture = null,
                            physicalFocalLengthMm = null,
                            equivalentFocalLengthMm = focalLength,
                            exposureCompensationEv = exposureCompensationEv,
                            isAutoWhiteBalance = null,
                            isFlashFired = null
                        )

                        val processedCapture = try {
                            imageProcessor.processCapturedImage(
                                captureId,
                                image,
                                points,
                                viewW,
                                viewH,
                                ratio,
                                lut,
                                chromaDenoiseMode,
                                isCropModeOff,
                                processingFocalLength,
                                noCropAspectRatio,
                                outputFormat,
                                jpegQuality,
                                captureMetadata,
                                savedImageRotationDegrees
                            )
                        } catch (error: Exception) {
                            captureInFlightLimiter.release()
                            publishCaptureProcessingStatus(
                                captureProcessingTracker.complete(captureId)
                            )
                            Log.e("CameraManager", "Photo processing failed", error)
                            showCaptureResult("Could not process photo")
                            return
                        }

                        publishCaptureProcessingStatus(
                            captureProcessingTracker.update(
                                captureId,
                                CaptureProcessingStage.SAVING
                            )
                        )
                        try {
                            captureSaveExecutor.execute {
                                val saved = try {
                                    imageProcessor.saveProcessedImage(processedCapture)
                                } catch (error: Exception) {
                                    Log.e("CameraManager", "Photo save failed", error)
                                    false
                                } finally {
                                    captureInFlightLimiter.release()
                                    publishCaptureProcessingStatus(
                                        captureProcessingTracker.complete(captureId)
                                    )
                                }
                                showCaptureResult(
                                    if (saved) "Saved to Gallery" else "Could not save photo"
                                )
                            }
                        } catch (error: RejectedExecutionException) {
                            processedCapture.recycle()
                            captureInFlightLimiter.release()
                            publishCaptureProcessingStatus(
                                captureProcessingTracker.complete(captureId)
                            )
                            Log.w("CameraManager", "Photo save rejected during shutdown", error)
                        }
                    }
                }
            )
        } catch (error: RuntimeException) {
            captureInFlightLimiter.release()
            publishCaptureProcessingStatus(captureProcessingTracker.complete(captureId))
            Log.e("CameraManager", "Could not submit photo capture", error)
            showCaptureResult("Capture failed")
        }
    }

    private fun publishCaptureProcessingStatus(status: CaptureProcessingStatus) {
        activity.runOnUiThread {
            onCaptureProcessingStatusChanged(status)
        }
    }

    private fun showCaptureResult(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageAnalysis(policy: PreviewAnalysisPolicy): ImageAnalysis {
        val resolutionStrategy = if (policy.useHighestAvailableResolution) {
            ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
        } else {
            ResolutionStrategy(
                Size(policy.analysisWidth, policy.analysisHeight),
                FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            )
        }

        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(targetRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(resolutionStrategy)
                    .build()
            )
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun updateCameraSettings() {
        applyCaptureRequestOptions()
        applyZoomRatio()
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

        // Scene modes are allowed to take ownership of the vendor 3A strategy.
        // Keep them disabled in XPAN so its explicit metering pattern stays authoritative.
        if (appSettings.isSportsMode && !appSettings.isXpanMode) {
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
        if (appSettings.isXpanMode) {
            buildXpanAeRegions(cameraInfo)?.let { regions ->
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, regions)
            }
        }

        if (appSettings.isAfLocked) {
            latestAutoFocusDistanceDiopter?.let { diopter ->
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
            } ?: Log.w("CameraManager", "AF lock requested but no autofocus distance has been captured yet")
        } else if (XpanMode.effectiveFocusMode(
                appSettings.isXpanMode,
                appSettings.focusMode
            ) == FocusMode.MANUAL
        ) {
            val diopter = sliderValueToDiopter(appSettings.focusVal, cameraInfo)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        }

        return optionsBuilder.build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildXpanAeRegions(
        cameraInfo: Camera2CameraInfo
    ): Array<MeteringRectangle>? {
        val maxRegionCount = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_MAX_REGIONS_AE
        ) ?: 0
        val activeArray = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
        ) ?: return null
        val normalizedRegions = XpanMeteringRegionModel.regionsFor(
            appSettings.xpanMeteringMode,
            maxRegionCount
        )
        if (normalizedRegions.isEmpty()) {
            Log.w("CameraManager", "Custom AE metering regions are not supported on this device")
            return null
        }
        val meteringFrame = calculateXpanMeteringFrame(activeArray)

        return normalizedRegions.map { region ->
            MeteringRectangle(
                normalizedRegionToSensorRect(region, meteringFrame),
                region.weight.coerceIn(
                    MeteringRectangle.METERING_WEIGHT_MIN,
                    MeteringRectangle.METERING_WEIGHT_MAX
                )
            )
        }.toTypedArray()
    }

    private fun calculateXpanMeteringFrame(activeArray: Rect): Rect {
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
        val zoomRatio = (appSettings.focalLength / 24f).coerceIn(1f, maxZoom)
        val zoomWidth = (activeArray.width() / zoomRatio).roundToInt().coerceAtLeast(1)
        val zoomHeight = (activeArray.height() / zoomRatio).roundToInt().coerceAtLeast(1)
        val zoomLeft = activeArray.left + (activeArray.width() - zoomWidth) / 2
        val zoomTop = activeArray.top + (activeArray.height() - zoomHeight) / 2

        val zoomAspectRatio = zoomWidth.toFloat() / zoomHeight.toFloat()
        val frameWidth: Int
        val frameHeight: Int
        if (zoomAspectRatio > XpanMode.ASPECT_RATIO) {
            frameHeight = zoomHeight
            frameWidth = (frameHeight * XpanMode.ASPECT_RATIO).roundToInt()
        } else {
            frameWidth = zoomWidth
            frameHeight = (frameWidth / XpanMode.ASPECT_RATIO).roundToInt()
        }
        val frameLeft = zoomLeft + (zoomWidth - frameWidth) / 2
        val frameTop = zoomTop + (zoomHeight - frameHeight) / 2
        return Rect(
            frameLeft,
            frameTop,
            frameLeft + frameWidth,
            frameTop + frameHeight
        )
    }

    private fun normalizedRegionToSensorRect(
        region: NormalizedMeteringRegion,
        activeArray: Rect
    ): Rect {
        val left = (
            activeArray.left + activeArray.width() * region.left.coerceIn(0f, 1f)
            ).roundToInt().coerceIn(activeArray.left, activeArray.right - 1)
        val top = (
            activeArray.top + activeArray.height() * region.top.coerceIn(0f, 1f)
            ).roundToInt().coerceIn(activeArray.top, activeArray.bottom - 1)
        val right = (
            activeArray.left + activeArray.width() * region.right.coerceIn(0f, 1f)
            ).roundToInt().coerceIn(left + 1, activeArray.right)
        val bottom = (
            activeArray.top + activeArray.height() * region.bottom.coerceIn(0f, 1f)
            ).roundToInt().coerceIn(top + 1, activeArray.bottom)
        return Rect(left, top, right, bottom)
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
        val meteringCorrection = if (appSettings.isXpanMode) {
            xpanSoftwareMeteringCorrectionEv
        } else {
            0f
        }
        applyExposureCompensationEv(evValue + meteringCorrection)
    }

    private fun applyExposureCompensationEv(evValue: Float) {
        val cam = camera ?: return
        val exposureState = cam.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return

        val step = exposureState.exposureCompensationStep
        val range = exposureState.exposureCompensationRange

        val stepVal = step.toFloat()
        if (stepVal == 0f) return

        val index = (evValue / stepVal).roundToInt()
        val clampedIndex = index.coerceIn(range.lower, range.upper)
        if (lastAppliedExposureCompensationIndex == clampedIndex) return

        lastAppliedExposureCompensationIndex = clampedIndex
        cam.cameraControl.setExposureCompensationIndex(clampedIndex)
    }

    fun setAeLocked(locked: Boolean) {
        appSettings.isAeLocked = locked
        applyCaptureRequestOptions()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setXpanMeteringMode(mode: XpanMeteringMode) {
        appSettings.xpanMeteringMode = mode
        // A new metering pattern needs a fresh AE convergence pass.
        appSettings.isAeLocked = false
        xpanSoftwareMeteringCorrectionEv = 0f
        lastSoftwareMeteringUpdateElapsedMs = 0L
        lastLoggedMeteringMode = null
        applyCaptureRequestOptions()
        applyExposureCompensationEv(appSettings.evVal)
    }

    fun setAfLocked(locked: Boolean) {
        val cam = camera
        if (!locked) {
            pendingAfLockAfterFocus = false
            appSettings.isAfLocked = false
            applyFocusMode()
            return
        }

        if (XpanMode.effectiveFocusMode(
                appSettings.isXpanMode,
                appSettings.focusMode
            ) != FocusMode.AUTO
        ) {
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
                        activity.runOnUiThread { onAfLockStateChanged() }
                    }
                } catch (e: Exception) {
                    pendingAfLockAfterFocus = false
                    Log.w("CameraManager", "Center autofocus for AF lock failed", e)
                    activity.runOnUiThread { onAfLockStateChanged() }
                }
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyFocusMode() {
        when (XpanMode.effectiveFocusMode(appSettings.isXpanMode, appSettings.focusMode)) {
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
                val whiteBalanceMode = result.get(CaptureResult.CONTROL_AWB_MODE)
                val flashState = result.get(CaptureResult.FLASH_STATE)
                val metadata = CaptureMetadata(
                    capturedAtMillis = System.currentTimeMillis(),
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    aperture = result.get(CaptureResult.LENS_APERTURE),
                    physicalFocalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                    equivalentFocalLengthMm = appSettings.focalLength,
                    exposureCompensationEv = appSettings.evVal,
                    isAutoWhiteBalance = whiteBalanceMode?.let {
                        it == CaptureResult.CONTROL_AWB_MODE_AUTO
                    },
                    isFlashFired = flashState?.let {
                        it == CaptureResult.FLASH_STATE_FIRED ||
                        it == CaptureResult.FLASH_STATE_PARTIAL
                    }
                )
                latestCaptureMetadata = metadata
                dispatchXpanTelemetry()
                logAppliedXpanMeteringRequest(request)
                val captureIntent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
                if (captureIntent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE ||
                    captureIntent == CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG
                ) {
                    latestStillCaptureMetadata = metadata
                }

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
                            onAfLockStateChanged()
                        }
                    }
                }
            }
        }
    }

    fun setEquivalentFocalLength(focalLength: Int) {
        appSettings.focalLength = focalLength.coerceIn(24, 50)
        applyZoomRatio()
        if (appSettings.isXpanMode) {
            applyCaptureRequestOptions()
        }
    }

    private fun applyZoomRatio() {
        val cam = camera ?: return
        val desiredZoom = if (appSettings.isXpanMode) {
            appSettings.focalLength / 24f
        } else {
            1f
        }
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        cam.cameraControl.setZoomRatio(desiredZoom.coerceIn(1f, maxZoom))
    }

    private fun analyzeXpanLuma(image: ImageProxy): XpanLumaAnalysis {
        val bins = IntArray(64)
        val plane = image.planes.firstOrNull()
            ?: return XpanLumaAnalysis(FloatArray(64), XPAN_METERING_TARGET_LUMA)
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val sampleStep = 4
        var sampledPixels = 0
        var weightedLumaSum = 0f
        var meteringWeightSum = 0f
        val meteringFrame = XpanSoftwareMeteringModel.frameFor(image.width, image.height)

        var y = meteringFrame.top
        while (y < meteringFrame.bottom) {
            var x = meteringFrame.left
            while (x < meteringFrame.right) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    val luminance = buffer.get(index).toInt() and 0xFF
                    bins[(luminance * bins.size / 256).coerceAtMost(bins.lastIndex)] += 1
                    sampledPixels += 1
                    val normalizedX = (x - meteringFrame.left + 0.5f) / meteringFrame.width
                    val normalizedY = (y - meteringFrame.top + 0.5f) / meteringFrame.height
                    val weight = XpanSoftwareMeteringModel.sampleWeight(
                        appSettings.xpanMeteringMode,
                        normalizedX,
                        normalizedY
                    )
                    if (weight > 0f) {
                        weightedLumaSum +=
                            XpanSoftwareMeteringModel.normalizeVideoLuma(luminance) * weight
                        meteringWeightSum += weight
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }
        if (sampledPixels == 0) {
            return XpanLumaAnalysis(FloatArray(bins.size), XPAN_METERING_TARGET_LUMA)
        }
        val peak = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        val histogram = FloatArray(bins.size) { index ->
            sqrt(bins[index].toFloat() / peak.toFloat())
        }
        val meteredLuma = if (meteringWeightSum > 0f) {
            weightedLumaSum / meteringWeightSum
        } else {
            XPAN_METERING_TARGET_LUMA
        }
        return XpanLumaAnalysis(histogram, meteredLuma)
    }

    private fun updateXpanSoftwareMetering(meteredLuma: Float) {
        if (!appSettings.isXpanMode || appSettings.isAeLocked) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSoftwareMeteringUpdateElapsedMs < SOFTWARE_METERING_INTERVAL_MS) return
        lastSoftwareMeteringUpdateElapsedMs = now

        val safeLuma = meteredLuma.coerceIn(0.03f, 0.97f)
        val errorEv = (
            ln(XPAN_METERING_TARGET_LUMA / safeLuma) / LN_2
            ).coerceIn(-SOFTWARE_METERING_MAX_STEP_EV, SOFTWARE_METERING_MAX_STEP_EV)
        if (abs(errorEv) < SOFTWARE_METERING_DEAD_BAND_EV) return

        val previousCorrection = xpanSoftwareMeteringCorrectionEv
        val updatedCorrection = (
            previousCorrection + errorEv * SOFTWARE_METERING_RESPONSE
            ).coerceIn(-SOFTWARE_METERING_MAX_CORRECTION_EV, SOFTWARE_METERING_MAX_CORRECTION_EV)
        if (abs(updatedCorrection - previousCorrection) < SOFTWARE_METERING_MIN_UPDATE_EV) return

        xpanSoftwareMeteringCorrectionEv = updatedCorrection
        applyExposureCompensationEv(appSettings.evVal + updatedCorrection)
    }

    private fun logAppliedXpanMeteringRequest(request: CaptureRequest) {
        if (!appSettings.isXpanMode ||
            lastLoggedMeteringMode == appSettings.xpanMeteringMode
        ) {
            return
        }
        lastLoggedMeteringMode = appSettings.xpanMeteringMode
        val regions = request.get(CaptureRequest.CONTROL_AE_REGIONS)
        val sceneMode = request.get(CaptureRequest.CONTROL_SCENE_MODE)
        Log.i(
            "CameraManager",
            "XPAN metering=${appSettings.xpanMeteringMode.storageValue}, " +
                "submittedRegions=${regions?.contentToString() ?: "none"}, " +
                "sceneMode=$sceneMode"
        )
    }

    private fun dispatchXpanTelemetry() {
        if (!appSettings.isXpanMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTelemetryDispatchElapsedMs < TELEMETRY_INTERVAL_MS) return
        lastTelemetryDispatchElapsedMs = now
        val metadata = latestCaptureMetadata
        val telemetry = XpanTelemetry(
            histogram = latestHistogram,
            iso = metadata?.iso,
            exposureTimeNs = metadata?.exposureTimeNs
        )
        activity.runOnUiThread {
            if (appSettings.isXpanMode) {
                onXpanTelemetryUpdated(telemetry)
            }
        }
    }

    fun shutdown() {
        shutdownExecutor(cameraExecutor)
        // Keep the save executor alive until all GPU work has had a chance to enqueue
        // its completed bitmap.
        shutdownExecutor(captureProcessingExecutor)
        shutdownExecutor(captureSaveExecutor)
        imageProcessor.shutdown()
        GlRectificationUtils.release()
    }

    private fun shutdownExecutor(executor: ExecutorService) {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun elapsedMillis(startNanos: Long): String {
        return String.format(
            java.util.Locale.US,
            "%.2f",
            (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
        )
    }

    companion object {
        private const val CAPTURE_PERF_TAG = "C1CapturePerf"
        private const val MAX_CAPTURES_IN_FLIGHT = 2
        private const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val TELEMETRY_INTERVAL_MS = 120L
        private const val SOFTWARE_METERING_INTERVAL_MS = 280L
        private const val XPAN_METERING_TARGET_LUMA = 0.42f
        private const val SOFTWARE_METERING_RESPONSE = 0.32f
        private const val SOFTWARE_METERING_MAX_STEP_EV = 0.75f
        private const val SOFTWARE_METERING_MAX_CORRECTION_EV = 2f
        private const val SOFTWARE_METERING_DEAD_BAND_EV = 0.08f
        private const val SOFTWARE_METERING_MIN_UPDATE_EV = 0.04f
        private const val LN_2 = 0.6931472f
    }
}

private data class XpanLumaAnalysis(
    val histogram: FloatArray,
    val meteredLuma: Float
)
