package com.zhuo.c1cam.capture

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class CaptureRequestQueueTest {
    @Test fun nextExposureWaitsForBothImagesButNotForProcessingOrSaving() {
        val queue = CaptureRequestQueue(Executor { it.run() })
        val started = mutableListOf<Int>()
        val deliveries = mutableListOf<() -> Unit>()
        repeat(3) { index ->
            queue.enqueue { done ->
                started.add(index)
                deliveries.add(done)
            }
        }
        assertEquals(listOf(0), started)
        deliveries[0]()
        assertEquals(listOf(0, 1), started)
        deliveries[0]() // Duplicate terminal callbacks must not skip a request.
        assertEquals(listOf(0, 1), started)
        deliveries[1]()
        assertEquals(listOf(0, 1, 2), started)
        deliveries[2]()
        queue.enqueue { done -> started.add(3); done() }
        assertEquals(listOf(0, 1, 2, 3), started)
    }
}
