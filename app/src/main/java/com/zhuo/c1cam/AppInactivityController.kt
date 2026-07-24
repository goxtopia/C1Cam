package com.zhuo.c1cam

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import java.lang.ref.WeakReference

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

/**
 * Keeps the visible app screen awake and closes the task after a configurable period
 * without touch or key interaction. Timing only runs while an activity is resumed.
 */
object AppInactivityController {
    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity = WeakReference<Activity>(null)
    private var timeoutMillis = minutesToMillis(InactivityTimeout.DEFAULT_MINUTES)
    private var lastInteractionElapsedMs = SystemClock.elapsedRealtime()

    private val timeoutRunnable = Runnable {
        val activity = currentActivity.get() ?: return@Runnable
        val elapsed = SystemClock.elapsedRealtime() - lastInteractionElapsedMs
        if (elapsed < timeoutMillis) {
            schedule(timeoutMillis - elapsed)
            return@Runnable
        }
        finishForInactivity(activity)
    }

    fun onActivityResumed(activity: Activity, timeoutMinutes: Int) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentActivity = WeakReference(activity)
        timeoutMillis = minutesToMillis(InactivityTimeout.sanitize(timeoutMinutes))
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
        schedule(timeoutMillis)
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivity.get() === activity) {
            handler.removeCallbacks(timeoutRunnable)
            currentActivity.clear()
        }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun onUserInteraction(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
        if (currentActivity.get() === activity) {
            schedule(timeoutMillis)
        }
    }

    fun updateTimeout(timeoutMinutes: Int) {
        timeoutMillis = minutesToMillis(InactivityTimeout.sanitize(timeoutMinutes))
        if (currentActivity.get() != null) {
            lastInteractionElapsedMs = SystemClock.elapsedRealtime()
            schedule(timeoutMillis)
        }
    }

    private fun finishForInactivity(activity: Activity) {
        handler.removeCallbacks(timeoutRunnable)
        currentActivity.clear()
        Toast.makeText(
            activity,
            "Closed after ${InactivityTimeout.label((timeoutMillis / 60_000L).toInt())} of inactivity",
            Toast.LENGTH_SHORT
        ).show()
        activity.finishAndRemoveTask()
    }

    private fun schedule(delayMillis: Long) {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, delayMillis.coerceAtLeast(1L))
    }

    private fun minutesToMillis(minutes: Int): Long = minutes * 60_000L
}
