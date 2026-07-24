package com.zhuo.c1cam

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Photo-only pipeline. Live LUT preview intentionally continues to use ImageProcessor's
 * existing Bitmap renderers.
 *
 * This renderer uploads Y/U/V once and performs YUV conversion, optional chroma denoise,
 * crop/perspective correction, both orientation transforms, and LUT grading in one pass.
 */
object StillCaptureGlPipeline {
    private const val TAG = "StillCaptureGl"
    private const val CAPTURE_PERF_TAG = "C1CapturePerf"
    private val QUAD_VERTICES = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var positionLocation = -1
    private var outputCoordLocation = -1
    private var homographyLocation = -1
    private var yTextureLocation = -1
    private var uTextureLocation = -1
    private var vTextureLocation = -1
    private var chromaSizeLocation = -1
    private var denoiseEnabledLocation = -1
    private var radiusLocation = -1
    private var sigmaSpatialLocation = -1
    private var sigmaRangeLocation = -1
    private var darkThresholdLocation = -1
    private var filterStrengthLocation = -1
    private var hasLutLocation = -1
    private var lutTextureLocation = -1
    private var lutSizeLocation = -1

    private val planeTextureIds = IntArray(3)
    private val planeTextureWidths = IntArray(3)
    private val planeTextureHeights = IntArray(3)
    private val packedPlaneBuffers = arrayOfNulls<ByteBuffer>(3)

    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var readBuffer: ByteBuffer? = null

    private var lutTextureId = 0
    private var boundLut: Lut3D? = null

    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    @Synchronized
    fun process(
        image: ImageProxy,
        geometry: StillCaptureGeometry,
        lut: Lut3D?,
        configuredDenoiseMode: ChromaDenoiseMode,
        iso: Int?
    ): Bitmap? {
        if (image.planes.size < 3) {
            Log.w(
                CAPTURE_PERF_TAG,
                "stage=gpu_fallback reason=insufficient_planes count=${image.planes.size}"
            )
            return null
        }

        val totalStartedNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            val initializationStartedNanos = SystemClock.elapsedRealtimeNanos()
            ensureEgl()
            makeCurrent()
            ensureProgram()
            val initializationFinishedNanos = SystemClock.elapsedRealtimeNanos()

            val rawWidth = image.width
            val rawHeight = image.height
            val chromaWidth = (rawWidth + 1) / 2
            val chromaHeight = (rawHeight + 1) / 2

            val uploadStartedNanos = SystemClock.elapsedRealtimeNanos()
            uploadPlane(
                index = 0,
                plane = image.planes[0],
                width = rawWidth,
                height = rawHeight,
                defaultValue = 0
            )
            uploadPlane(
                index = 1,
                plane = image.planes[1],
                width = chromaWidth,
                height = chromaHeight,
                defaultValue = 128
            )
            uploadPlane(
                index = 2,
                plane = image.planes[2],
                width = chromaWidth,
                height = chromaHeight,
                defaultValue = 128
            )
            val uploadFinishedNanos = SystemClock.elapsedRealtimeNanos()

            val targetSetupStartedNanos = SystemClock.elapsedRealtimeNanos()
            ensureOutputTarget(geometry.outputWidth, geometry.outputHeight)
            uploadLutIfNeeded(lut)
            val targetSetupFinishedNanos = SystemClock.elapsedRealtimeNanos()

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
            GLES30.glViewport(0, 0, geometry.outputWidth, geometry.outputHeight)
            GLES30.glUseProgram(program)

            quadBuffer.position(0)
            GLES30.glEnableVertexAttribArray(positionLocation)
            GLES30.glVertexAttribPointer(
                positionLocation,
                2,
                GLES30.GL_FLOAT,
                false,
                4 * 4,
                quadBuffer
            )
            quadBuffer.position(2)
            GLES30.glEnableVertexAttribArray(outputCoordLocation)
            GLES30.glVertexAttribPointer(
                outputCoordLocation,
                2,
                GLES30.GL_FLOAT,
                false,
                4 * 4,
                quadBuffer
            )

            bindTexture(planeTextureIds[0], 0, yTextureLocation)
            bindTexture(planeTextureIds[1], 1, uTextureLocation)
            bindTexture(planeTextureIds[2], 2, vTextureLocation)
            GLES30.glUniformMatrix3fv(
                homographyLocation,
                1,
                false,
                geometry.outputToRawHomography,
                0
            )
            GLES30.glUniform2f(
                chromaSizeLocation,
                chromaWidth.toFloat(),
                chromaHeight.toFloat()
            )

            val denoise = denoiseParameters(configuredDenoiseMode.resolveForIso(iso))
            GLES30.glUniform1i(denoiseEnabledLocation, if (denoise == null) 0 else 1)
            GLES30.glUniform1i(radiusLocation, denoise?.radius ?: 0)
            GLES30.glUniform1f(sigmaSpatialLocation, denoise?.sigmaSpatial ?: 1f)
            GLES30.glUniform1f(sigmaRangeLocation, denoise?.sigmaRange ?: 1f)
            GLES30.glUniform1f(darkThresholdLocation, denoise?.darkThreshold ?: 0f)
            GLES30.glUniform1f(filterStrengthLocation, denoise?.filterStrength ?: 0f)

            GLES30.glUniform1i(hasLutLocation, if (lut != null && lutTextureId != 0) 1 else 0)
            GLES30.glUniform1f(lutSizeLocation, lut?.size?.toFloat() ?: 2f)
            if (lut != null && lutTextureId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
                GLES30.glUniform1i(lutTextureLocation, 3)
            }

            val drawStartedNanos = SystemClock.elapsedRealtimeNanos()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("draw still capture")
            val drawSubmittedNanos = SystemClock.elapsedRealtimeNanos()

            val readbackStartedNanos = SystemClock.elapsedRealtimeNanos()
            val bitmap = readOutput(geometry.outputWidth, geometry.outputHeight)
            val readbackFinishedNanos = SystemClock.elapsedRealtimeNanos()

            GLES30.glDisableVertexAttribArray(positionLocation)
            GLES30.glDisableVertexAttribArray(outputCoordLocation)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glUseProgram(0)
            releaseCurrent()
            Log.i(
                CAPTURE_PERF_TAG,
                "stage=gpu_complete " +
                    "totalMs=${elapsedMillis(totalStartedNanos, readbackFinishedNanos)} " +
                    "initMs=${elapsedMillis(initializationStartedNanos, initializationFinishedNanos)} " +
                    "uploadMs=${elapsedMillis(uploadStartedNanos, uploadFinishedNanos)} " +
                    "targetMs=${elapsedMillis(targetSetupStartedNanos, targetSetupFinishedNanos)} " +
                    "drawSubmitMs=${elapsedMillis(drawStartedNanos, drawSubmittedNanos)} " +
                    "readbackMs=${elapsedMillis(readbackStartedNanos, readbackFinishedNanos)} " +
                    "output=${geometry.outputWidth}x${geometry.outputHeight} " +
                    "denoise=${configuredDenoiseMode.resolveForIso(iso).storageValue} " +
                    "lut=${lut != null}"
            )
            bitmap
        } catch (error: Exception) {
            Log.w(TAG, "Merged photo pipeline failed; using legacy Bitmap path", error)
            Log.w(
                CAPTURE_PERF_TAG,
                "stage=gpu_fallback reason=${error.javaClass.simpleName} " +
                    "durationMs=${elapsedMillis(totalStartedNanos)}"
            )
            releaseInternal()
            null
        }
    }

    private fun bindTexture(textureId: Int, unit: Int, uniformLocation: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uniformLocation, unit)
    }

    private fun uploadPlane(
        index: Int,
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        defaultValue: Int
    ) {
        val packed = packPlane(index, plane, width, height, defaultValue.toByte())

        if (planeTextureIds[index] == 0) {
            planeTextureIds[index] = createTexture2d()
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, planeTextureIds[index])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        if (planeTextureWidths[index] != width || planeTextureHeights[index] != height) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_R8,
                width,
                height,
                0,
                GLES30.GL_RED,
                GLES30.GL_UNSIGNED_BYTE,
                packed
            )
            planeTextureWidths[index] = width
            planeTextureHeights[index] = height
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES30.GL_RED,
                GLES30.GL_UNSIGNED_BYTE,
                packed
            )
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        checkGlError("upload YUV plane $index")
    }

    private fun packPlane(
        index: Int,
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        defaultValue: Byte
    ): ByteBuffer {
        val requiredCapacity = width * height
        var output = packedPlaneBuffers[index]
        if (output == null || output.capacity() < requiredCapacity) {
            output = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder())
            packedPlaneBuffers[index] = output
        }
        output.clear()
        output.limit(requiredCapacity)

        val source = plane.buffer
        val start = source.position()
        val limit = source.limit()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 1 && rowStride == width && start + requiredCapacity <= limit) {
            val contiguous = source.duplicate()
            contiguous.position(start)
            contiguous.limit(start + requiredCapacity)
            output.put(contiguous)
        } else {
            for (row in 0 until height) {
                val rowStart = start + row * rowStride
                for (column in 0 until width) {
                    val sourceIndex = rowStart + column * pixelStride
                    output.put(
                        if (sourceIndex in start until limit) {
                            source.get(sourceIndex)
                        } else {
                            defaultValue
                        }
                    )
                }
            }
        }
        output.flip()
        return output
    }

    private fun ensureOutputTarget(width: Int, height: Int) {
        if (outputTextureId != 0 && outputWidth == width && outputHeight == height) return

        if (outputTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
        }
        if (outputFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
        }

        outputTextureId = createTexture2d()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTextureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )

        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        outputFramebufferId = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            outputTextureId,
            0
        )
        check(
            GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                GLES30.GL_FRAMEBUFFER_COMPLETE
        ) { "Still capture framebuffer is incomplete" }
        outputWidth = width
        outputHeight = height
    }

    private fun uploadLutIfNeeded(lut: Lut3D?) {
        if (lut == null) return
        if (boundLut === lut && lutTextureId != 0) return

        if (lutTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        lutTextureId = ids[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_R,
            GLES30.GL_CLAMP_TO_EDGE
        )

        val buffer = ByteBuffer.allocateDirect(lut.data.size)
        lut.data.forEach { value ->
            buffer.put(
                (value.coerceIn(0f, 1f) * 255f)
                    .roundToInt()
                    .coerceIn(0, 255)
                    .toByte()
            )
        }
        buffer.flip()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB8,
            lut.size,
            lut.size,
            lut.size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        checkGlError("upload capture LUT")
        boundLut = lut
    }

    private fun readOutput(width: Int, height: Int): Bitmap {
        val requiredCapacity = width * height * 4
        var buffer = readBuffer
        if (buffer == null || buffer.capacity() < requiredCapacity) {
            buffer = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder())
            readBuffer = buffer
        }
        buffer.clear()
        buffer.limit(requiredCapacity)
        GLES30.glReadPixels(
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
        checkGlError("read still capture")
        buffer.position(0)

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(buffer)
        }
    }

    private fun denoiseParameters(mode: ChromaDenoiseMode): DenoiseParameters? {
        return when (mode) {
            ChromaDenoiseMode.OFF -> null
            ChromaDenoiseMode.LOW -> DenoiseParameters(1, 1.2f, 0.04f, 0.10f, 0.45f)
            ChromaDenoiseMode.MEDIUM -> DenoiseParameters(2, 2.0f, 0.07f, 0.16f, 0.72f)
            ChromaDenoiseMode.HIGH -> DenoiseParameters(3, 3.0f, 0.10f, 0.22f, 1.0f)
            ChromaDenoiseMode.AUTO -> error("AUTO must be resolved before rendering")
        }
    }

    private fun ensureEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "eglInitialize failed"
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        check(
            EGL14.eglChooseConfig(
                display,
                attributes,
                0,
                configs,
                0,
                1,
                configCount,
                0
            ) && configCount[0] > 0
        ) { "No OpenGL ES 3 config" }

        val context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val surface = EGL14.eglCreatePbufferSurface(
            display,
            configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        eglDisplay = display
        eglContext = context
        eglSurface = surface
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun releaseCurrent() {
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
    }

    private fun ensureProgram() {
        if (program != 0) return
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) {
            "Still capture shader link failed: ${GLES30.glGetProgramInfoLog(program)}"
        }
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        positionLocation = GLES30.glGetAttribLocation(program, "aPosition")
        outputCoordLocation = GLES30.glGetAttribLocation(program, "aOutputCoord")
        homographyLocation = GLES30.glGetUniformLocation(program, "uOutputToRaw")
        yTextureLocation = GLES30.glGetUniformLocation(program, "uY")
        uTextureLocation = GLES30.glGetUniformLocation(program, "uU")
        vTextureLocation = GLES30.glGetUniformLocation(program, "uV")
        chromaSizeLocation = GLES30.glGetUniformLocation(program, "uChromaSize")
        denoiseEnabledLocation = GLES30.glGetUniformLocation(program, "uDenoiseEnabled")
        radiusLocation = GLES30.glGetUniformLocation(program, "uRadius")
        sigmaSpatialLocation = GLES30.glGetUniformLocation(program, "uSigmaSpatial")
        sigmaRangeLocation = GLES30.glGetUniformLocation(program, "uSigmaRange")
        darkThresholdLocation = GLES30.glGetUniformLocation(program, "uDarkThreshold")
        filterStrengthLocation = GLES30.glGetUniformLocation(program, "uFilterStrength")
        hasLutLocation = GLES30.glGetUniformLocation(program, "uHasLut")
        lutTextureLocation = GLES30.glGetUniformLocation(program, "uLut")
        lutSizeLocation = GLES30.glGetUniformLocation(program, "uLutSize")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) {
            "Still capture shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun createTexture2d(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        return ids[0]
    }

    private fun checkGlError(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "$operation failed with GL error 0x${Integer.toHexString(error)}"
        }
    }

    private fun elapsedMillis(
        startNanos: Long,
        endNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): String {
        return String.format(
            java.util.Locale.US,
            "%.2f",
            (endNanos - startNanos) / 1_000_000.0
        )
    }

    @Synchronized
    fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            if (program != 0) GLES30.glDeleteProgram(program)
            planeTextureIds.filter { it != 0 }.forEach {
                GLES30.glDeleteTextures(1, intArrayOf(it), 0)
            }
            if (outputTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
            }
            if (lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            }
            if (outputFramebufferId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
            }
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        planeTextureIds.fill(0)
        planeTextureWidths.fill(0)
        planeTextureHeights.fill(0)
        outputTextureId = 0
        outputFramebufferId = 0
        outputWidth = 0
        outputHeight = 0
        lutTextureId = 0
        boundLut = null
        readBuffer = null
        packedPlaneBuffers.fill(null)
    }

    private data class DenoiseParameters(
        val radius: Int,
        val sigmaSpatial: Float,
        val sigmaRange: Float,
        val darkThreshold: Float,
        val filterStrength: Float
    )

    private const val VERTEX_SHADER = """
        #version 300 es
        in vec2 aPosition;
        in vec2 aOutputCoord;
        out vec2 vOutputCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vOutputCoord = aOutputCoord;
        }
    """

    private const val FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        precision highp sampler3D;

        in vec2 vOutputCoord;
        uniform mat3 uOutputToRaw;
        uniform sampler2D uY;
        uniform sampler2D uU;
        uniform sampler2D uV;
        uniform vec2 uChromaSize;

        uniform int uDenoiseEnabled;
        uniform int uRadius;
        uniform float uSigmaSpatial;
        uniform float uSigmaRange;
        uniform float uDarkThreshold;
        uniform float uFilterStrength;

        uniform int uHasLut;
        uniform sampler3D uLut;
        uniform float uLutSize;
        out vec4 fragColor;

        vec3 yuvToRgb(float y, float u, float v) {
            u -= 0.5;
            v -= 0.5;
            return clamp(
                vec3(
                    y + 1.402 * v,
                    y - 0.344136 * u - 0.714136 * v,
                    y + 1.772 * u
                ),
                0.0,
                1.0
            );
        }

        void main() {
            vec3 projected = uOutputToRaw * vec3(vOutputCoord, 1.0);
            vec2 rawCoord = clamp(projected.xy / projected.z, vec2(0.0), vec2(1.0));

            float centerY = texture(uY, rawCoord).r;
            float centerU = texture(uU, rawCoord).r;
            float centerV = texture(uV, rawCoord).r;
            float finalU = centerU;
            float finalV = centerV;

            if (uDenoiseEnabled == 1) {
                vec2 texelSize = 1.0 / uChromaSize;
                float sumU = 0.0;
                float sumV = 0.0;
                float sumWeight = 0.0;

                for (int offsetY = -3; offsetY <= 3; offsetY++) {
                    for (int offsetX = -3; offsetX <= 3; offsetX++) {
                        if (abs(offsetX) > uRadius || abs(offsetY) > uRadius) {
                            continue;
                        }
                        vec2 offset = vec2(float(offsetX), float(offsetY)) * texelSize;
                        vec2 neighborCoord = clamp(
                            rawCoord + offset,
                            vec2(0.0),
                            vec2(1.0)
                        );
                        float neighborY = texture(uY, neighborCoord).r;
                        float neighborU = texture(uU, neighborCoord).r;
                        float neighborV = texture(uV, neighborCoord).r;
                        float distanceSquared = float(
                            offsetX * offsetX + offsetY * offsetY
                        );
                        float rangeDifference = abs(neighborY - centerY);
                        float spatialWeight = exp(
                            -distanceSquared /
                            (2.0 * uSigmaSpatial * uSigmaSpatial)
                        );
                        float rangeWeight = exp(
                            -(rangeDifference * rangeDifference) /
                            (2.0 * uSigmaRange * uSigmaRange)
                        );
                        float weight = spatialWeight * rangeWeight;
                        sumU += neighborU * weight;
                        sumV += neighborV * weight;
                        sumWeight += weight;
                    }
                }

                finalU = mix(centerU, sumU / sumWeight, uFilterStrength);
                finalV = mix(centerV, sumV / sumWeight, uFilterStrength);
                if (centerY < uDarkThreshold) {
                    float luminanceRetention = centerY / uDarkThreshold;
                    float chromaRetention = mix(
                        1.0,
                        luminanceRetention,
                        uFilterStrength
                    );
                    finalU = mix(0.5, finalU, chromaRetention);
                    finalV = mix(0.5, finalV, chromaRetention);
                }
            }

            vec3 rgb = yuvToRgb(centerY, finalU, finalV);
            if (uHasLut == 1) {
                float edge = 1.0 / uLutSize;
                vec3 lutCoord = clamp(rgb, 0.0, 1.0) * (1.0 - edge) + 0.5 * edge;
                rgb = texture(uLut, lutCoord).rgb;
            }
            fragColor = vec4(rgb, 1.0);
        }
    """
}
