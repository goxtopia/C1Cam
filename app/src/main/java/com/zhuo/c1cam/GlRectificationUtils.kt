package com.zhuo.c1cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlRectificationUtils {
    private const val TAG = "GlRectificationUtils"

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosition = 0
    private var uTexture = 0
    private var aDstCoord = 0
    private var uHomography = 0

    private var sourceTexId = 0
    private var sourceTexW = 0
    private var sourceTexH = 0
    private var sourceTexConfig: Bitmap.Config? = null

    private var outputTexId = 0
    private var fboId = 0
    private var outputW = 0
    private var outputH = 0

    private var readBuffer: ByteBuffer? = null
    private var readBitmap: Bitmap? = null
    private val flipMatrix = Matrix().apply {
        preScale(1f, -1f)
    }
    private val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f,
                    1f, -1f,
                    -1f, 1f,
                    1f, 1f
                )
            )
            position(0)
        }

    private val dstCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    0f, 1f,
                    1f, 1f,
                    0f, 0f,
                    1f, 0f
                )
            )
            position(0)
        }

    /**
     * Rectifies a 4-point source quadrilateral to fill the destination bitmap using GLES.
     *
     * @param sourceBitmap Source frame bitmap (ARGB_8888 expected for best performance).
     * @param destBitmap Destination bitmap that receives the rectified image.
     * @param normalizedPoints Source quadrilateral in normalized source-view space (0..1),
     * ordered as top-left, top-right, bottom-right, bottom-left.
     * @return true if GL rectification succeeded; false means caller should use CPU fallback.
     *
     * Threading: call from a dedicated processing thread.
     */
    @Synchronized
    fun rectifyToBitmap(sourceBitmap: Bitmap, destBitmap: Bitmap, normalizedPoints: List<PointF>): Boolean {
        if (normalizedPoints.size != 4) return false

        return try {
            ensureInitialized()
            ensureOutputTarget(destBitmap.width, destBitmap.height)
            ensureSourceTexture(sourceBitmap)
            val homography = calculateHomography(normalizedPoints)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, destBitmap.width, destBitmap.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            dstCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aDstCoord)
            GLES20.glVertexAttribPointer(aDstCoord, 2, GLES20.GL_FLOAT, false, 0, dstCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix3fv(uHomography, 1, false, homography, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val pixelCount = destBitmap.width * destBitmap.height * 4
            if (readBuffer == null || readBuffer!!.capacity() != pixelCount) {
                readBuffer = ByteBuffer.allocateDirect(pixelCount).order(ByteOrder.nativeOrder())
            }

            val buffer = readBuffer!!
            buffer.position(0)
            GLES20.glReadPixels(
                0,
                0,
                destBitmap.width,
                destBitmap.height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )

            if (readBitmap?.width != destBitmap.width || readBitmap?.height != destBitmap.height) {
                readBitmap?.recycle()
                readBitmap = Bitmap.createBitmap(destBitmap.width, destBitmap.height, Bitmap.Config.ARGB_8888)
            }

            buffer.position(0)
            readBitmap!!.copyPixelsFromBuffer(buffer)

            val canvas = Canvas(destBitmap)
            val translatedFlip = Matrix(flipMatrix).apply {
                postTranslate(0f, destBitmap.height.toFloat())
            }
            canvas.drawBitmap(readBitmap!!, translatedFlip, copyPaint)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            true
        } catch (e: Exception) {
            Log.e(TAG, "GL rectification failed, fallback to CPU", e)
            false
        }
    }

    private fun ensureInitialized() {
        if (program != 0) return

        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                "eglChooseConfig failed"
            }
            val config = configs[0] ?: error("No EGL config")

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttribs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            check(program != 0) { "createProgram failed" }

            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aDstCoord = GLES20.glGetAttribLocation(program, "aDstCoord")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            uHomography = GLES20.glGetUniformLocation(program, "uHomography")

            sourceTexId = createTexture()
        } catch (e: Exception) {
            releaseInternal()
            throw e
        }
    }

    private fun ensureOutputTarget(width: Int, height: Int) {
        if (outputTexId != 0 && width == outputW && height == outputH) return

        if (outputTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(outputTexId), 0)
            outputTexId = 0
        }
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }

        outputTexId = createTexture()
        outputW = width
        outputH = height

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            outputTexId,
            0
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer incomplete"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun ensureSourceTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexId)
        if (sourceTexW != bitmap.width || sourceTexH != bitmap.height || sourceTexConfig != bitmap.config) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            sourceTexW = bitmap.width
            sourceTexH = bitmap.height
            sourceTexConfig = bitmap.config
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun calculateHomography(normalizedPoints: List<PointF>): FloatArray {
        fun clampedX(point: PointF): Float = point.x.coerceIn(0f, 1f)
        fun clampedY(point: PointF): Float = (1f - point.y).coerceIn(0f, 1f)

        val p0 = normalizedPoints[0]
        val p1 = normalizedPoints[1]
        val p2 = normalizedPoints[2]
        val p3 = normalizedPoints[3]

        val sourceQuadCoords = floatArrayOf(
            clampedX(p0), clampedY(p0),
            clampedX(p1), clampedY(p1),
            clampedX(p2), clampedY(p2),
            clampedX(p3), clampedY(p3)
        )
        val targetRectCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        )
        return solveHomography(targetRectCoords, sourceQuadCoords)
    }

    private fun solveHomography(from: FloatArray, to: FloatArray): FloatArray {
        val a = Array(8) { FloatArray(8) }
        val b = FloatArray(8)

        for (i in 0 until 4) {
            val x = from[i * 2]
            val y = from[i * 2 + 1]
            val u = to[i * 2]
            val v = to[i * 2 + 1]

            val r0 = i * 2
            val r1 = r0 + 1

            a[r0][0] = x
            a[r0][1] = y
            a[r0][2] = 1f
            a[r0][3] = 0f
            a[r0][4] = 0f
            a[r0][5] = 0f
            a[r0][6] = -u * x
            a[r0][7] = -u * y
            b[r0] = u

            a[r1][0] = 0f
            a[r1][1] = 0f
            a[r1][2] = 0f
            a[r1][3] = x
            a[r1][4] = y
            a[r1][5] = 1f
            a[r1][6] = -v * x
            a[r1][7] = -v * y
            b[r1] = v
        }

        val homographyCoeffs = gaussianElimination(a, b)
        return floatArrayOf(
            homographyCoeffs[0], homographyCoeffs[3], homographyCoeffs[6],
            homographyCoeffs[1], homographyCoeffs[4], homographyCoeffs[7],
            homographyCoeffs[2], homographyCoeffs[5], 1f
        )
    }

    private fun gaussianElimination(a: Array<FloatArray>, b: FloatArray): FloatArray {
        for (col in 0 until 8) {
            var pivot = col
            var maxAbs = kotlin.math.abs(a[col][col])
            for (row in col + 1 until 8) {
                val abs = kotlin.math.abs(a[row][col])
                if (abs > maxAbs) {
                    maxAbs = abs
                    pivot = row
                }
            }

            if (pivot != col) {
                val tmpRow = a[col]
                a[col] = a[pivot]
                a[pivot] = tmpRow
                val tmpB = b[col]
                b[col] = b[pivot]
                b[pivot] = tmpB
            }

            val diag = a[col][col]
            if (kotlin.math.abs(diag) < 1e-6f) error("Homography computation failed: matrix is singular or ill-conditioned")

            for (j in col until 8) {
                a[col][j] /= diag
            }
            b[col] /= diag

            for (row in 0 until 8) {
                if (row == col) continue
                val factor = a[row][col]
                if (factor == 0f) continue
                for (j in col until 8) {
                    a[row][j] -= factor * a[col][j]
                }
                b[row] -= factor * b[col]
            }
        }
        return b
    }

    private fun createTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Error linking program: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Error compiling shader: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aDstCoord;
        varying vec2 vDstCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vDstCoord = aDstCoord;
        }
    """

    private const val FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vDstCoord;
        uniform sampler2D uTexture;
        uniform mat3 uHomography;
        void main() {
            vec3 src = uHomography * vec3(vDstCoord, 1.0);
            vec2 uv = src.xy / src.z;
            gl_FragColor = texture2D(uTexture, uv);
        }
    """

    @Synchronized
    fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
            if (sourceTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(sourceTexId), 0)
            }
            if (outputTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(outputTexId), 0)
            }
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            }

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }

        resetCachedState()
    }

    private fun resetCachedState() {
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        aPosition = 0
        aDstCoord = 0
        uTexture = 0
        uHomography = 0
        sourceTexId = 0
        sourceTexW = 0
        sourceTexH = 0
        sourceTexConfig = null
        outputTexId = 0
        fboId = 0
        outputW = 0
        outputH = 0
        readBitmap?.recycle()
        readBitmap = null
        readBuffer = null
    }
}
