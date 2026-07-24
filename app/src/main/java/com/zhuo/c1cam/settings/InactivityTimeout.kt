package com.zhuo.c1cam.settings

object InactivityTimeout {
    const val DEFAULT_MINUTES = 5
    val choicesMinutes = listOf(1, 2, 5, 10, 30)

    fun sanitize(minutes: Int): Int {
        return minutes.takeIf { it in choicesMinutes } ?: DEFAULT_MINUTES
    }

    fun label(minutes: Int): String {
        val safeMinutes = sanitize(minutes)
        return if (safeMinutes == 1) "1 minute" else "$safeMinutes minutes"
    }
}
