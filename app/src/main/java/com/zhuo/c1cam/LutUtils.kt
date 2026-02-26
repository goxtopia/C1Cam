package com.zhuo.c1cam

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

class Lut3D(val size: Int, val data: FloatArray) {
    // data is flat array of R,G,B floats. Index = (b * size * size + g * size + r) * 3

    fun lookup(r: Float, g: Float, b: Float, result: IntArray) {
        val maxIndex = size - 1
        val rScaled = r * maxIndex
        val gScaled = g * maxIndex
        val bScaled = b * maxIndex

        val r0 = floor(rScaled).toInt().coerceIn(0, maxIndex)
        val g0 = floor(gScaled).toInt().coerceIn(0, maxIndex)
        val b0 = floor(bScaled).toInt().coerceIn(0, maxIndex)

        val r1 = (r0 + 1).coerceAtMost(maxIndex)
        val g1 = (g0 + 1).coerceAtMost(maxIndex)
        val b1 = (b0 + 1).coerceAtMost(maxIndex)

        val dr = rScaled - r0
        val dg = gScaled - g0
        val db = bScaled - b0

        // Helper to get index for (r, g, b)
        // Standard .cube usually has R inner, G middle, B outer.
        fun idx(ri: Int, gi: Int, bi: Int): Int = (bi * size * size + gi * size + ri) * 3

        // Fetch 8 corners
        val idx000 = idx(r0, g0, b0)
        val idx100 = idx(r1, g0, b0)
        val idx010 = idx(r0, g1, b0)
        val idx001 = idx(r0, g0, b1)
        val idx110 = idx(r1, g1, b0)
        val idx101 = idx(r1, g0, b1)
        val idx011 = idx(r0, g1, b1)
        val idx111 = idx(r1, g1, b1)

        // Interpolate Red
        val r00 = lerp(data[idx000], data[idx100], dr)
        val r01 = lerp(data[idx001], data[idx101], dr)
        val r10 = lerp(data[idx010], data[idx110], dr)
        val r11 = lerp(data[idx011], data[idx111], dr)
        val r0_val = lerp(r00, r10, dg)
        val r1_val = lerp(r01, r11, dg)
        val r_final = lerp(r0_val, r1_val, db)

        // Interpolate Green
        val g00 = lerp(data[idx000+1], data[idx100+1], dr)
        val g01 = lerp(data[idx001+1], data[idx101+1], dr)
        val g10 = lerp(data[idx010+1], data[idx110+1], dr)
        val g11 = lerp(data[idx011+1], data[idx111+1], dr)
        val g0_val = lerp(g00, g10, dg)
        val g1_val = lerp(g01, g11, dg)
        val g_final = lerp(g0_val, g1_val, db)

        // Interpolate Blue
        val b00 = lerp(data[idx000+2], data[idx100+2], dr)
        val b01 = lerp(data[idx001+2], data[idx101+2], dr)
        val b10 = lerp(data[idx010+2], data[idx110+2], dr)
        val b11 = lerp(data[idx011+2], data[idx111+2], dr)
        val b0_val = lerp(b00, b10, dg)
        val b1_val = lerp(b01, b11, dg)
        val b_final = lerp(b0_val, b1_val, db)

        result[0] = (r_final * 255).roundToInt().coerceIn(0, 255)
        result[1] = (g_final * 255).roundToInt().coerceIn(0, 255)
        result[2] = (b_final * 255).roundToInt().coerceIn(0, 255)
    }

    private fun lerp(v0: Float, v1: Float, t: Float): Float {
        return v0 + (v1 - v0) * t
    }
}

object LutUtils {
    private const val TAG = "LutUtils"
    private val glRenderer = OpenGlLutRenderer()
    @Volatile
    private var glDisabled = false

    fun loadLut(context: Context, filename: String): Lut3D? {
        try {
            val inputStream = context.assets.open("luts/$filename")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var size = 0
            val dataList = mutableListOf<Float>()

            var line = reader.readLine()
            while (line != null) {
                line = line.trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    line = reader.readLine()
                    continue
                }

                val upperLine = line.uppercase()

                if (upperLine.startsWith("LUT_3D_SIZE")) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                         try {
                             size = parts[1].toInt()
                         } catch (e: Exception) {
                             // ignore malformed size line
                         }
                    }
                } else if (upperLine.startsWith("TITLE") || upperLine.startsWith("DOMAIN_") || upperLine.startsWith("LUT_1D_SIZE")) {
                    // ignore title/domain/1D size keywords
                } else {
                    // Data lines
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        try {
                            val r = parts[0].toFloat()
                            val g = parts[1].toFloat()
                            val b = parts[2].toFloat()
                            dataList.add(r)
                            dataList.add(g)
                            dataList.add(b)
                        } catch (e: NumberFormatException) {
                            // Not a data line, ignore
                        }
                    }
                }
                line = reader.readLine()
            }
            reader.close()

            if (size > 0 && dataList.size == size * size * size * 3) {
                return Lut3D(size, dataList.toFloatArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun applyLut(bitmap: Bitmap, lut: Lut3D, destBitmap: Bitmap? = null): Bitmap {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        if (!glDisabled) {
            try {
                return glRenderer.applyLut(source, lut, destBitmap)
            } catch (e: Exception) {
                glDisabled = true
                Log.w(TAG, "OpenGL ES LUT failed, fallback to CPU", e)
            }
        }

        return applyLutCpu(source, lut, destBitmap)
    }

    private fun applyLutCpu(bitmap: Bitmap, lut: Lut3D, destBitmap: Bitmap?): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val newPixels = IntArray(width * height)
        val tempRgb = IntArray(3)

        // Optimization: Use direct array access if possible, or just standard loop
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8 and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            lut.lookup(r, g, b, tempRgb)

            newPixels[i] = (0xFF shl 24) or (tempRgb[0] shl 16) or (tempRgb[1] shl 8) or tempRgb[2]
        }

        if (destBitmap != null && destBitmap.width == width && destBitmap.height == height) {
            destBitmap.setPixels(newPixels, 0, width, 0, 0, width, height)
            return destBitmap
        }

        return Bitmap.createBitmap(newPixels, width, height, bitmap.config)
    }

    private class OpenGlLutRenderer {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program = 0
        private var positionLoc = -1
        private var texCoordLoc = -1
        private var inputTexLoc = -1
        private var lutTexLoc = -1
        private var lutSizeLoc = -1

        private var inputTexId = 0
        private var outputTexId = 0
        private var lutTexId = 0
        private var fboId = 0

        // Cache texture dimensions to detect changes
        private var cachedInputW = 0
        private var cachedInputH = 0
        private var cachedOutputW = 0
        private var cachedOutputH = 0

        private var boundLut: Lut3D? = null

        // Reusable buffer for reading pixels
        private var cachedReadBuffer: ByteBuffer? = null
        private var cachedReadBufferCapacity = 0

        private val quadBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_VERTICES)
                position(0)
            }

        @Synchronized
        fun applyLut(bitmap: Bitmap, lut: Lut3D, destBitmap: Bitmap?): Bitmap {
            ensureEgl()
            makeCurrent()
            ensureProgram()

            // Check if we need to re-create textures due to size change
            if (bitmap.width != cachedInputW || bitmap.height != cachedInputH) {
                releaseInputTexture()
            }
            if (bitmap.width != cachedOutputW || bitmap.height != cachedOutputH) {
                releaseOutputTexture()
            }

            if (inputTexId == 0) {
                inputTexId = createTexture()
                cachedInputW = bitmap.width
                cachedInputH = bitmap.height
            }

            // Upload bitmap to input texture
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

            if (outputTexId == 0) {
                outputTexId = createTexture()
                // Allocate storage for output texture
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexId)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    bitmap.width, bitmap.height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
                )
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                cachedOutputW = bitmap.width
                cachedOutputH = bitmap.height
            }

            if (fboId == 0) {
                val framebuffers = IntArray(1)
                GLES30.glGenFramebuffers(1, framebuffers, 0)
                fboId = framebuffers[0]
            }

            // Bind FBO and attach output texture
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                outputTexId,
                0
            )

            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw IllegalStateException("Framebuffer incomplete: 0x${Integer.toHexString(status)}")
            }

            uploadLutIfNeeded(lut)

            GLES30.glViewport(0, 0, bitmap.width, bitmap.height)
            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
            GLES30.glUniform1i(inputTexLoc, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glUniform1i(lutTexLoc, 1)
            GLES30.glUniform1f(lutSizeLoc, lut.size.toFloat())

            drawFullScreenQuad()
            checkGlError("draw")

            val result = readPixelsToBitmap(bitmap.width, bitmap.height, destBitmap)

            // Cleanup binding
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            GLES30.glUseProgram(0)

            // Release current context to allow other threads to use EGL if needed
            releaseCurrent()

            return result
        }

        private fun releaseInputTexture() {
            if (inputTexId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(inputTexId), 0)
                inputTexId = 0
            }
            cachedInputW = 0
            cachedInputH = 0
        }

        private fun releaseOutputTexture() {
            if (outputTexId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(outputTexId), 0)
                outputTexId = 0
            }
            cachedOutputW = 0
            cachedOutputH = 0
        }

        private fun createTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            checkGlError("glGenTextures")
            val texId = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            return texId
        }

        private fun ensureEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) return

            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                throw IllegalStateException("Unable to get EGL display")
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("Unable to initialize EGL")
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                throw IllegalStateException("Unable to choose EGL config")
            }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("Unable to create EGL context")
            }

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("Unable to create EGL surface")
            }

            eglDisplay = display
            eglContext = context
            eglSurface = surface
        }

        private fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("eglMakeCurrent failed")
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
            val newProgram = GLES30.glCreateProgram()
            if (newProgram == 0) {
                throw IllegalStateException("glCreateProgram failed")
            }

            GLES30.glAttachShader(newProgram, vertexShader)
            GLES30.glAttachShader(newProgram, fragmentShader)
            GLES30.glLinkProgram(newProgram)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(newProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES30.glGetProgramInfoLog(newProgram)
                GLES30.glDeleteProgram(newProgram)
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                throw IllegalStateException("Program link failed: $info")
            }

            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)

            program = newProgram
            positionLoc = GLES30.glGetAttribLocation(program, "aPosition")
            texCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
            inputTexLoc = GLES30.glGetUniformLocation(program, "uInputTex")
            lutTexLoc = GLES30.glGetUniformLocation(program, "uLutTex")
            lutSizeLoc = GLES30.glGetUniformLocation(program, "uLutSize")
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) {
                throw IllegalStateException("glCreateShader failed")
            }
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val info = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $info")
            }
            return shader
        }

        private fun uploadLutIfNeeded(lut: Lut3D) {
            if (boundLut === lut && lutTexId != 0) return

            if (lutTexId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTexId), 0)
                lutTexId = 0
            }

            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            checkGlError("glGenTextures LUT")
            lutTexId = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

            val lutBuffer = ByteBuffer.allocateDirect(lut.data.size)
            for (v in lut.data) {
                val value = (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                lutBuffer.put(value.toByte())
            }
            lutBuffer.position(0)

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
                lutBuffer
            )
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            checkGlError("glTexImage3D")
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            boundLut = lut
        }

        private fun drawFullScreenQuad() {
            quadBuffer.position(0)
            GLES30.glEnableVertexAttribArray(positionLoc)
            GLES30.glVertexAttribPointer(positionLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

            quadBuffer.position(2)
            GLES30.glEnableVertexAttribArray(texCoordLoc)
            GLES30.glVertexAttribPointer(texCoordLoc, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(positionLoc)
            GLES30.glDisableVertexAttribArray(texCoordLoc)
        }

        private fun readPixelsToBitmap(width: Int, height: Int, destBitmap: Bitmap?): Bitmap {
            val requiredCapacity = width * height * 4
            if (cachedReadBuffer == null || cachedReadBufferCapacity < requiredCapacity) {
                cachedReadBuffer = ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder())
                cachedReadBufferCapacity = requiredCapacity
            }
            val buffer = cachedReadBuffer!!
            buffer.position(0)

            GLES30.glReadPixels(
                0,
                0,
                width,
                height,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                buffer
            )
            checkGlError("glReadPixels")

            val resultBitmap = if (destBitmap != null && destBitmap.width == width && destBitmap.height == height) {
                destBitmap
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            resultBitmap.copyPixelsFromBuffer(buffer)
            return resultBitmap
        }

        private fun checkGlError(op: String) {
            val err = GLES30.glGetError()
            if (err != GLES30.GL_NO_ERROR) {
                throw IllegalStateException("$op failed with glError=0x${Integer.toHexString(err)}")
            }
        }

        companion object {
            private val QUAD_VERTICES = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )

            private const val VERTEX_SHADER = """
                #version 300 es
                in vec2 aPosition;
                in vec2 aTexCoord;
                out vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = aTexCoord;
                }
            """

            private const val FRAGMENT_SHADER = """
                #version 300 es
                precision mediump float;
                precision highp sampler3D;
                in vec2 vTexCoord;
                uniform sampler2D uInputTex;
                uniform sampler3D uLutTex;
                uniform float uLutSize;
                out vec4 fragColor;
                void main() {
                    vec2 inputUv = vec2(vTexCoord.x, vTexCoord.y);
                    vec4 src = texture(uInputTex, inputUv);
                    float edge = 1.0 / uLutSize;
                    vec3 coord = clamp(src.rgb, 0.0, 1.0) * (1.0 - edge) + 0.5 * edge;
                    vec3 graded = texture(uLutTex, coord).rgb;
                    fragColor = vec4(graded, src.a);
                }
            """
        }
    }
}
