package com.zhuo.c1cam

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

object ChromaNoiseReduction {

    private const val TAG = "ChromaNoiseReduction"

    private const val VERTEX_SHADER_CODE = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private const val FRAGMENT_SHADER_CODE = """
        precision mediump float;
        varying vec2 vTexCoord;

        uniform sampler2D texY;
        uniform sampler2D texU;
        uniform sampler2D texV;
        uniform float width;
        uniform float height;
        uniform float sigmaSpatial;
        uniform float sigmaRange;
        uniform int radius;

        // Convert YUV to RGB (BT.601 Full Range)
        // Y: [0, 1]
        // U, V: [0, 1] (internally 0..255 mapped to 0..1)
        // Center of U/V is 0.5
        vec3 yuv2rgb(float y, float u, float v) {
            u -= 0.5;
            v -= 0.5;
            float r = y + 1.402 * v;
            float g = y - 0.344136 * u - 0.714136 * v;
            float b = y + 1.772 * u;
            return clamp(vec3(r, g, b), 0.0, 1.0);
        }

        void main() {
            vec2 texSize = vec2(width, height);
            vec2 texelSize = 1.0 / texSize;

            float centerY = texture2D(texY, vTexCoord).r;

            float sumU = 0.0;
            float sumV = 0.0;
            float sumW = 0.0;

            for (int y = -radius; y <= radius; y++) {
                for (int x = -radius; x <= radius; x++) {
                    vec2 offset = vec2(float(x), float(y)) * texelSize;
                    vec2 neighborCoord = vTexCoord + offset;

                    float neighborY = texture2D(texY, neighborCoord).r;
                    float neighborU = texture2D(texU, neighborCoord).r;
                    float neighborV = texture2D(texV, neighborCoord).r;

                    float distSq = float(x*x + y*y);
                    float rangeDiff = abs(neighborY - centerY);

                    // Gaussian weights
                    float wSpatial = exp(-distSq / (2.0 * sigmaSpatial * sigmaSpatial));
                    float wRange = exp(-(rangeDiff * rangeDiff) / (2.0 * sigmaRange * sigmaRange));

                    float w = wSpatial * wRange;

                    sumU += neighborU * w;
                    sumV += neighborV * w;
                    sumW += w;
                }
            }

            float finalU = sumU / sumW;
            float finalV = sumV / sumW;

            // Reconstruct RGB with original Y and filtered U/V
            vec3 rgb = yuv2rgb(centerY, finalU, finalV);
            gl_FragColor = vec4(rgb, 1.0);
        }
    """

    fun process(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        Log.d(TAG, "Starting Chroma NR. Image: ${width}x${height} format=${image.format}")

        val eglEnv = EglEnvironment(width, height)
        var bitmap: Bitmap? = null

        try {
            eglEnv.makeCurrent()

            val program = createProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE)
            if (program == 0) {
                Log.e(TAG, "Failed to create program")
                return image.toBitmap() // Fallback
            }

            GLES20.glUseProgram(program)

            // Prepare buffers (Y, U, V)
            // Log plane details
            for (i in 0 until image.planes.size) {
                val p = image.planes[i]
                Log.d(TAG, "Plane $i: rowStride=${p.rowStride} pixelStride=${p.pixelStride} remaining=${p.buffer.remaining()}")
            }

            val yBuffer = extractPlane(image.planes[0], width, height, "Y")
            val uBuffer = extractPlane(image.planes[1], width / 2, height / 2, "U")
            val vBuffer = extractPlane(image.planes[2], width / 2, height / 2, "V")

            // IMPORTANT: Unpack alignment. Default is 4. If width/2 is odd (e.g. 540 is ok, 960 is ok, but some resolutions might not be)
            // But also, we are uploading byte arrays.
            // Row length of our packed buffer is 'width' bytes (or width/2).
            // If that length is not a multiple of 4, we must set alignment to 1.
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

            val texY = uploadTexture(yBuffer, width, height, 0)
            val texU = uploadTexture(uBuffer, width / 2, height / 2, 1)
            val texV = uploadTexture(vBuffer, width / 2, height / 2, 2)

            // Set Uniforms
            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

            val vertexData = floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            )
            val vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertexData)
            vertexBuffer.position(0)

            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(posHandle)

            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texHandle)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "texY"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "texU"), 1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "texV"), 2)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "width"), width.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "height"), height.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "sigmaSpatial"), 3.0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "sigmaRange"), 0.1f) // Normalized range [0, 1]
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "radius"), 3)

            // Setup FBO
            val fboIds = IntArray(1)
            GLES20.glGenFramebuffers(1, fboIds, 0)
            val fboId = fboIds[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val outputTexId = texIds[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTexId, 0)

            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Framebuffer not complete")
            }

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Read pixels
            val buffer = ByteBuffer.allocateDirect(width * height * 4)
            buffer.order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Cleanup
            GLES20.glDeleteTextures(1, texIds, 0)
            GLES20.glDeleteFramebuffers(1, fboIds, 0)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texY), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texU), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texV), 0)

        } catch (e: Exception) {
            Log.e(TAG, "Error in GL processing", e)
            return image.toBitmap() // Fallback
        } finally {
            eglEnv.release()
        }

        return bitmap ?: image.toBitmap()
    }

    private fun extractPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int, name: String): ByteBuffer {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        Log.d(TAG, "extractPlane $name: w=$width h=$height rowStride=$rowStride pixelStride=$pixelStride")

        // Output buffer (tightly packed)
        val output = ByteBuffer.allocateDirect(width * height)

        val startPos = buffer.position()

        // Debug first byte
        if (buffer.remaining() > 0) {
            val firstByte = buffer.get(startPos)
            Log.d(TAG, "$name[0] = $firstByte")
        } else {
            Log.e(TAG, "$name buffer is empty!")
        }

        val rowBuffer = ByteArray(rowStride)

        for (y in 0 until height) {
            val offset = startPos + y * rowStride
            if (offset >= buffer.limit()) {
                Log.e(TAG, "Buffer overflow at row $y. offset=$offset limit=${buffer.limit()}")
                break
            }

            buffer.position(offset)

            if (pixelStride == 1) {
                // If width == rowStride, we could optimize, but rowStride usually has padding
                // Read 'width' bytes
                // Verify we don't read past limit
                val remaining = buffer.remaining()
                if (remaining < width) {
                    Log.w(TAG, "Row $y: Not enough bytes. Needed $width, has $remaining")
                    buffer.get(rowBuffer, 0, remaining)
                    output.put(rowBuffer, 0, remaining)
                } else {
                    buffer.get(rowBuffer, 0, width)
                    output.put(rowBuffer, 0, width)
                }
            } else {
                // Determine how many bytes we need to read to get 'width' pixels
                // Last pixel is at index (width - 1) * pixelStride
                // So we need to read up to that byte
                val neededBytes = (width - 1) * pixelStride + 1
                val remaining = buffer.remaining()

                // We should read min(rowStride, remaining) but also at least neededBytes if possible
                // Actually we just read what's available up to rowStride
                val bytesToRead = kotlin.math.min(rowStride, remaining)

                if (bytesToRead < neededBytes) {
                    Log.w(TAG, "Row $y: Warning, might be partial. Needed $neededBytes, read $bytesToRead")
                }

                buffer.get(rowBuffer, 0, bytesToRead)

                for (x in 0 until width) {
                    val idx = x * pixelStride
                    if (idx < bytesToRead) {
                        output.put(rowBuffer[idx])
                    } else {
                        // Pad with 128 (0.5) if missing? Or just 0
                        output.put(128.toByte())
                    }
                }
            }
        }

        output.position(0)
        buffer.position(startPos)

        return output
    }

    private fun uploadTexture(buffer: ByteBuffer, width: Int, height: Int, unit: Int): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer)

        return textureIds[0]
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
            Log.e(TAG, "Error linking program: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // Helper class to manage EGL context
    private class EglEnvironment(width: Int, height: Int) {
        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            if (numConfigs[0] == 0) throw RuntimeException("eglChooseConfig failed")
            val config = configs[0] ?: throw RuntimeException("Unable to retrieve EGL config")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttribs, 0)
        }

        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
