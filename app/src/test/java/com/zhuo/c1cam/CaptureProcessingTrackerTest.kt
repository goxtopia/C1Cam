package com.zhuo.c1cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureProcessingTrackerTest {
    @Test
    fun oldestUnfinishedCaptureRemainsInForeground() {
        val tracker = CaptureProcessingTracker()
        tracker.enqueue(10)
        tracker.enqueue(11)
        tracker.update(11, CaptureProcessingStage.PROCESSING)

        val status = tracker.update(10, CaptureProcessingStage.SAVING)

        assertEquals(2, status.pendingCount)
        assertEquals(10L, status.foregroundCaptureId)
        assertEquals(CaptureProcessingStage.SAVING, status.foregroundStage)
    }

    @Test
    fun completingForegroundPromotesNextCapture() {
        val tracker = CaptureProcessingTracker()
        tracker.enqueue(20)
        tracker.enqueue(21)
        tracker.update(21, CaptureProcessingStage.PROCESSING)

        val status = tracker.complete(20)

        assertEquals(1, status.pendingCount)
        assertEquals(21L, status.foregroundCaptureId)
        assertEquals(CaptureProcessingStage.PROCESSING, status.foregroundStage)
    }

    @Test
    fun completingAllCapturesReturnsReadyState() {
        val tracker = CaptureProcessingTracker()
        tracker.enqueue(30)

        val status = tracker.complete(30)

        assertEquals(0, status.pendingCount)
        assertNull(status.foregroundCaptureId)
        assertNull(status.foregroundStage)
    }
}
