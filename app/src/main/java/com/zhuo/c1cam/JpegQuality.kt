package com.zhuo.c1cam

object JpegQuality {
    const val DEFAULT = 95
    val choices = listOf(80, 85, 90, 95, 100)

    fun sanitize(value: Int): Int = value.coerceIn(1, 100)
}
