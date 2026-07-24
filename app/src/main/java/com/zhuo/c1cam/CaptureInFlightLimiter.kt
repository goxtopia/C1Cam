package com.zhuo.c1cam

import java.util.concurrent.atomic.AtomicInteger

internal class CaptureInFlightLimiter(
    private val maximum: Int
) {
    private val count = AtomicInteger(0)

    init {
        require(maximum > 0)
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = count.get()
            if (current >= maximum) {
                return false
            }
            if (count.compareAndSet(current, current + 1)) {
                return true
            }
        }
    }

    fun release() {
        while (true) {
            val current = count.get()
            check(current > 0) { "Capture in-flight count cannot be negative" }
            if (count.compareAndSet(current, current - 1)) {
                return
            }
        }
    }

    fun currentCount(): Int = count.get()
}
