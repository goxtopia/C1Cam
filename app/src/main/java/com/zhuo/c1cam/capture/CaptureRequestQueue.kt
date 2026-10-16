package com.zhuo.c1cam.capture

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Serialize image delivery, while allowing processing and disk writes to overlap. */
internal class CaptureRequestQueue(private val executor: Executor) {
    private val requests = ArrayDeque<(() -> Unit) -> Unit>()
    private var running = false

    fun enqueue(request: (() -> Unit) -> Unit) {
        val start = synchronized(this) {
            requests.addLast(request)
            if (running) false else {
                running = true
                true
            }
        }
        if (start) dispatchNext()
    }

    private fun dispatchNext() {
        val request = synchronized(this) {
            requests.pollFirst() ?: run {
                running = false
                return
            }
        }
        val delivered = AtomicBoolean(false)
        executor.execute {
            request {
                if (delivered.compareAndSet(false, true)) dispatchNext()
            }
        }
    }
}
