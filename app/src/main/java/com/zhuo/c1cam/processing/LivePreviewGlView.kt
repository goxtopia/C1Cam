package com.zhuo.c1cam.processing

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.camera.core.ImageProxy
import com.zhuo.c1cam.camera.StillCaptureGeometry
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Sends YUV directly to a display surface, with no Bitmap allocation or GPU readback. */
class LivePreviewGlView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private data class Frame(val image: ImageProxy, val geometry: StillCaptureGeometry, val lut: Lut3D?)
    private val pending = AtomicReference<Frame?>()
    private var renderer = CaptureGlRenderer(ownsEglContext = false)
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastGeometry: StillCaptureGeometry? = null
    @Volatile private var acceptingFrames = false
    private var failureReported = false
    var onRenderFailure: (() -> Unit)? = null
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var totalRenderNanos = 0L
        private set

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setActive(active: Boolean) {
        acceptingFrames = active
        if (!active) pending.getAndSet(null)?.image?.close()
    }

    /** Takes ownership even when hidden; every submitted image is eventually closed. */
    fun submit(image: ImageProxy, geometry: StillCaptureGeometry, lut: Lut3D?) {
        if (!acceptingFrames) {
            image.close()
            return
        }
        pending.getAndSet(Frame(image, geometry, lut))?.image?.close()
        // A hide/detach may race with submission from the analyzer.
        if (!acceptingFrames) pending.getAndSet(null)?.image?.close()
        else requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        renderer = CaptureGlRenderer(ownsEglContext = false)
        lastGeometry = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = pending.getAndSet(null)
        if (frame == null) {
            // Surface resize/redraw callbacks still swap buffers. Repaint cached GPU
            // textures so an otherwise idle view cannot swap in a blank buffer.
            lastGeometry?.let { renderer.redrawDisplay(it, PreviewRenderTarget(surfaceWidth, surfaceHeight)) }
            return
        }
        try {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            val before = renderer.renderedFrames
            val started = System.nanoTime()
            renderer.process(0, frame.image, frame.geometry, frame.lut, ChromaDenoiseMode.OFF,
                null, PreviewRenderTarget(surfaceWidth, surfaceHeight))
            if (renderer.renderedFrames > before) {
                lastGeometry = frame.geometry
                totalRenderNanos += System.nanoTime() - started
                renderedFrames++
            } else if (!failureReported && acceptingFrames) {
                failureReported = true
                onRenderFailure?.invoke()
            }
        } catch (error: Exception) {
            Log.w("LivePreviewGl", "Preview frame skipped", error)
        } finally {
            frame.image.close()
        }
    }

    override fun onDetachedFromWindow() {
        setActive(false)
        super.onDetachedFromWindow()
    }
}
