package com.zhuo.c1cam

object FocalLengthDetents {
    val classicFocalLengths = setOf(24, 28, 35, 40, 50)

    fun isClassicDetent(focalLength: Int): Boolean {
        return focalLength in classicFocalLengths
    }

    fun normalizedPositions(minimum: Int = 24, maximum: Int = 50): List<Float> {
        val range = (maximum - minimum).coerceAtLeast(1)
        return classicFocalLengths
            .filter { it in minimum..maximum }
            .sorted()
            .map { (it - minimum).toFloat() / range }
    }
}
