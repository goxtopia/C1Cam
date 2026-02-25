package com.zhuo.c1cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedReader
import java.io.InputStreamReader
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

                if (line.startsWith("LUT_3D_SIZE")) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        size = parts[1].toInt()
                    }
                } else if (line.startsWith("TITLE") || line.startsWith("DOMAIN_")) {
                    // ignore title/domain keywords
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
                            // ignore
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

    fun applyLut(bitmap: Bitmap, lut: Lut3D): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val newPixels = IntArray(width * height)
        val tempRgb = IntArray(3)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8 and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            lut.lookup(r, g, b, tempRgb)

            newPixels[i] = (0xFF shl 24) or (tempRgb[0] shl 16) or (tempRgb[1] shl 8) or tempRgb[2]
        }

        return Bitmap.createBitmap(newPixels, width, height, bitmap.config)
    }
}
