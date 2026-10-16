package com.zhuo.c1cam.processing

import android.graphics.Bitmap
import kotlin.math.*

/** Single-frame, log-luminance base/detail mapping. RAW data is never modified. */
object LocalToneMapping {
    const val HIGHLIGHT_BIAS_EV = -0.7f
    const val GUIDE_SIZE = 256
    private const val FLOOR = 1f / 4096f

    fun linear(v: Float): Float = if (v <= .04045f) v / 12.92f else ((v + .055f) / 1.055f).pow(2.4f)
    fun encoded(v: Float): Float = if (v <= .0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - .055f
    fun logLuma(v: Float): Float = log2(v.coerceAtLeast(FLOOR))
    private fun smooth(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Unit detail gain: outputLog = compressedBase + (inputLog - base). */
    fun gainStops(pixelLog: Float, baseLog: Float, ambientLog: Float): Float {
        val shadow = 2f * (1f - smooth(log2(.01f), log2(.5f), baseLog))
        val darkEnvironment = 1f - smooth(log2(.12f), log2(.5f), ambientLog)
        val materialGuard = 1f - smooth(2.5f, 4.5f, ambientLog - baseLog)
        val blackGate = smooth(log2(.0006f), log2(.006f), pixelLog)
        val highlight = .3f * smooth(log2(.35f), log2(.9f), baseLog)
        return shadow * darkEnvironment * materialGuard * blackGate - highlight
    }

    /** Keep XPAN's software AE from cancelling the physical highlight-protection bias. */
    fun meteringTarget(displayLuma: Float, enabled: Boolean, userEv: Float = 0f): Float {
        val bias = userEv + if (enabled) HIGHLIGHT_BIAS_EV else 0f
        return if (bias == 0f) displayLuma else
            encoded(linear(displayLuma) * 2f.pow(bias)).coerceIn(.03f, .97f)
    }

    /** Bounded-memory fallback chroma bilateral filter, applied after LTM, before LUT. */
    fun denoiseChroma(bitmap: Bitmap, mode: ChromaDenoiseMode) {
        if (mode == ChromaDenoiseMode.OFF) return
        val radius = when (mode) { ChromaDenoiseMode.LOW -> 1; ChromaDenoiseMode.MEDIUM -> 2; else -> 3 }
        val strength = when (radius) { 1 -> .45f; 2 -> .72f; else -> 1f }
        val sigma = when (radius) { 1 -> .04f; 2 -> .07f; else -> .10f }
        val w = bitmap.width; val h = bitmap.height
        // Cache original rows before writing them, so later pixels never sample filtered data.
        val rows = Array(radius * 2 + 1) { IntArray(w) }
        val rowIds = IntArray(rows.size) { -1 }
        val output = IntArray(w)
        fun yy(c: Int) = (.299f * ((c ushr 16) and 255) + .587f * ((c ushr 8) and 255) + .114f * (c and 255)) / 255f
        for (y in 0 until h) {
            for (sy in max(0,y-radius)..min(h-1,y+radius)) {
                val slot = sy % rows.size
                if (rowIds[slot] != sy) {
                    bitmap.getPixels(rows[slot],0,w,0,sy,w,1); rowIds[slot] = sy
                }
            }
            val centerRow = rows[y % rows.size]
            for (x in 0 until w) {
                val center = centerRow[x]; val luma = yy(center)
                var u = 0f; var v = 0f; var total = 0f
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val c = rows[(y+dy).coerceIn(0,h-1) % rows.size][(x+dx).coerceIn(0,w-1)]
                    val l = yy(c); val d = l-luma
                    val weight = exp(-(dx*dx+dy*dy)/(2f*radius*radius)-d*d/(2f*sigma*sigma))
                    u += (((c and 255)/255f-l)/1.772f)*weight
                    v += ((((c ushr 16) and 255)/255f-l)/1.402f)*weight
                    total += weight
                }
                val cu = ((center and 255)/255f-luma)/1.772f
                val cv = (((center ushr 16) and 255)/255f-luma)/1.402f
                u = cu + (u/total-cu)*strength; v = cv + (v/total-cv)*strength
                fun channel(f: Float) = (f*255).roundToInt().coerceIn(0,255)
                output[x] = (center and -0x1000000) or (channel(luma+1.402f*v) shl 16) or
                    (channel(luma-.344136f*u-.714136f*v) shl 8) or channel(luma+1.772f*u)
            }
            bitmap.setPixels(output,0,w,0,y,w,1)
        }
    }

    // CPU fallback uses the same spatial scales and mapping as the GLES path. Work on
    // a small illumination guide; retain full-size pixels and details during reconstruction.
    fun apply(bitmap: Bitmap) {
        val scale = min(1f, GUIDE_SIZE.toFloat() / max(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val linearTable = FloatArray(256) { linear(it / 255f) }
        fun luminance(c: Int) = .2126f * linearTable[(c ushr 16) and 255] +
            .7152f * linearTable[(c ushr 8) and 255] + .0722f * linearTable[c and 255]
        val logs = FloatArray(w * h) { logLuma(luminance(pixels[it])) }
        val bases = FloatArray(w * h)
        val ambient = FloatArray(w * h)
        val localStep = max(1f, min(w, h) * .035f / 3f)
        val ambientStep = max(1f, min(w, h) * .18f / 3f)
        for (y in 0 until h) for (x in 0 until w) {
            val center = logs[y * w + x]
            var sum = 0f; var weight = 0f; var surround = 0f; var surroundWeight = 0f
            for (dy in -3..3) for (dx in -3..3) {
                val spatial = exp(-(dx * dx + dy * dy) / 8f)
                val i = (y + dy * localStep).roundToInt().coerceIn(0, h - 1) * w +
                    (x + dx * localStep).roundToInt().coerceIn(0, w - 1)
                val delta = logs[i] - center
                val bw = spatial * exp(-delta * delta / (2f * .9f * .9f))
                sum += logs[i] * bw; weight += bw
                val j = (y + dy * ambientStep).roundToInt().coerceIn(0, h - 1) * w +
                    (x + dx * ambientStep).roundToInt().coerceIn(0, w - 1)
                surround += logs[j] * spatial; surroundWeight += spatial
            }
            bases[y * w + x] = sum / weight
            ambient[y * w + x] = surround / surroundWeight
        }
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices) {
                val c = row[x]; val l = logLuma(luminance(c))
                val gx = (x + .5f) * w / bitmap.width - .5f
                val gy = (y + .5f) * h / bitmap.height - .5f
                val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
                val fx = gx - ix; val fy = gy - iy
                var base = 0f; var total = 0f; var surrounding = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val i = (iy + dy).coerceIn(0, h - 1) * w + (ix + dx).coerceIn(0, w - 1)
                    val bilinear = (if (dx == 0) 1f - fx else fx) * (if (dy == 0) 1f - fy else fy)
                    val delta = l - logs[i]
                    val wt = bilinear * (exp(-delta * delta / .98f) + .00001f)
                    base += bases[i] * wt; total += wt
                    surrounding += ambient[i] * bilinear
                }
                val gain = 2f.pow(gainStops(l, base / total, surrounding))
                var r = linearTable[(c ushr 16) and 255] * gain
                var g = linearTable[(c ushr 8) and 255] * gain
                var b = linearTable[c and 255] * gain
                val peak = max(1f, max(r, max(g, b)))
                r /= peak; g /= peak; b /= peak
                row[x] = (c and -0x1000000) or ((encoded(r) * 255).roundToInt().coerceIn(0,255) shl 16) or
                    ((encoded(g) * 255).roundToInt().coerceIn(0,255) shl 8) or (encoded(b) * 255).roundToInt().coerceIn(0,255)
            }
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
    }
}
