package com.zhuo.c1cam.processing

import kotlin.math.max
import kotlin.math.pow

enum class ToneMapPreset(
    val storageValue: String,
    val displayName: String,
    val description: String
) {
    NONE("none", "None", "No additional display mapping"),
    ACES("aces", "ACES Filmic", "Cinematic highlight roll-off and contrast"),
    HABLE("hable", "Hable Filmic", "Deep toe with a broad, soft shoulder"),
    REINHARD("reinhard", "Reinhard", "Natural highlight compression"),
    CINEON("cineon", "Cineon Film", "Dense midtones with a filmic shoulder"),
    FILM_PRINT("film_print", "Film Print", "Warm highlights and restrained saturation"),
    SOFT_NEGATIVE("soft_negative", "Soft Negative", "Open shadows and gentle pastel contrast");

    companion object {
        fun fromStorageValue(value: String?): ToneMapPreset {
            return entries.firstOrNull { it.storageValue == value } ?: NONE
        }
    }
}

object ToneMapMath {
    fun apply(preset: ToneMapPreset, r: Float, g: Float, b: Float): FloatArray {
        if (preset == ToneMapPreset.NONE) {
            return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }

        val linear = floatArrayOf(toLinear(r), toLinear(g), toLinear(b))
        val mapped = when (preset) {
            ToneMapPreset.NONE -> linear
            ToneMapPreset.ACES -> linear.mapToFloatArray { aces(it * 1.12f) }
            ToneMapPreset.HABLE -> {
                val white = hable(11.2f)
                linear.mapToFloatArray { hable(it * 2f) / white }
            }
            ToneMapPreset.REINHARD -> {
                val displayWhite = 1.65f / (1f + 1.65f)
                linear.mapToFloatArray {
                val exposed = it * 1.65f
                    (exposed / (1f + exposed)) / displayWhite
                }
            }
            ToneMapPreset.CINEON -> linear.mapToFloatArray { cineon(it * 1.35f) }
            ToneMapPreset.FILM_PRINT -> filmPrint(linear)
            ToneMapPreset.SOFT_NEGATIVE -> softNegative(linear)
        }
        return FloatArray(3) { index -> toSrgb(mapped[index].coerceIn(0f, 1f)) }
    }

    private fun aces(value: Float): Float {
        val a = 2.51f
        val b = 0.03f
        val c = 2.43f
        val d = 0.59f
        val e = 0.14f
        return (value * (a * value + b)) / (value * (c * value + d) + e)
    }

    private fun hable(value: Float): Float {
        val a = 0.15f
        val b = 0.50f
        val c = 0.10f
        val d = 0.20f
        val e = 0.02f
        val f = 0.30f
        return ((value * (a * value + c * b) + d * e) /
            (value * (a * value + b) + d * f)) - e / f
    }

    private fun cineon(value: Float): Float {
        val x = max(0f, value - 0.004f)
        return (x * (6.2f * x + 0.5f)) / (x * (6.2f * x + 1.7f) + 0.06f)
    }

    private fun filmPrint(linear: FloatArray): FloatArray {
        val base = linear.mapToFloatArray { aces(it * 0.96f) }
        val luminance = base[0] * 0.2126f + base[1] * 0.7152f + base[2] * 0.0722f
        val saturation = 0.91f
        return floatArrayOf(
            mix(luminance, base[0], saturation) * 1.035f,
            mix(luminance, base[1], saturation) * 1.005f,
            mix(luminance, base[2], saturation) * 0.965f
        )
    }

    private fun softNegative(linear: FloatArray): FloatArray {
        val lifted = linear.mapToFloatArray {
            val toeLifted = (it + 0.014f) / 1.014f
            toeLifted.pow(0.88f) / (1f + 0.22f * toeLifted)
        }
        val luminance = lifted[0] * 0.2126f + lifted[1] * 0.7152f + lifted[2] * 0.0722f
        return floatArrayOf(
            mix(luminance, lifted[0], 0.82f) * 1.015f,
            mix(luminance, lifted[1], 0.82f) * 1.005f,
            mix(luminance, lifted[2], 0.82f) * 0.99f
        )
    }

    private fun toLinear(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped <= 0.04045f) {
            clamped / 12.92f
        } else {
            ((clamped + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun toSrgb(value: Float): Float {
        return if (value <= 0.0031308f) {
            value * 12.92f
        } else {
            1.055f * value.pow(1f / 2.4f) - 0.055f
        }
    }

    private fun mix(a: Float, b: Float, amount: Float): Float = a + (b - a) * amount

    private inline fun FloatArray.mapToFloatArray(transform: (Float) -> Float): FloatArray {
        return FloatArray(size) { index -> transform(this[index]) }
    }
}

object ToneMapLutFactory {
    fun compose(preset: ToneMapPreset, creativeLut: Lut3D?): Lut3D? {
        if (preset == ToneMapPreset.NONE) return creativeLut

        val size = creativeLut?.size?.coerceIn(MIN_LUT_SIZE, MAX_LUT_SIZE) ?: DEFAULT_LUT_SIZE
        val maxIndex = size - 1
        val data = FloatArray(size * size * size * 3)
        val creativeResult = IntArray(3)
        var outputIndex = 0

        for (blueIndex in 0 until size) {
            val blue = blueIndex.toFloat() / maxIndex
            for (greenIndex in 0 until size) {
                val green = greenIndex.toFloat() / maxIndex
                for (redIndex in 0 until size) {
                    val red = redIndex.toFloat() / maxIndex
                    val mapped = ToneMapMath.apply(preset, red, green, blue)
                    if (creativeLut != null) {
                        creativeLut.lookup(mapped[0], mapped[1], mapped[2], creativeResult)
                        data[outputIndex++] = creativeResult[0] / 255f
                        data[outputIndex++] = creativeResult[1] / 255f
                        data[outputIndex++] = creativeResult[2] / 255f
                    } else {
                        data[outputIndex++] = mapped[0]
                        data[outputIndex++] = mapped[1]
                        data[outputIndex++] = mapped[2]
                    }
                }
            }
        }
        return Lut3D(size, data)
    }

    private const val DEFAULT_LUT_SIZE = 33
    private const val MIN_LUT_SIZE = 17
    private const val MAX_LUT_SIZE = 64
}
