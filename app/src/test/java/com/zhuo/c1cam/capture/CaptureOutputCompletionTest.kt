package com.zhuo.c1cam.capture

import com.zhuo.c1cam.capture.CaptureOutputCompletion.Output.*
import org.junit.Assert.*
import org.junit.Test

class CaptureOutputCompletionTest {
    @Test fun bothCallbackOrdersWaitForBothWritesAndReportOnce() {
        listOf(listOf(RAW, BITMAP), listOf(BITMAP, RAW)).forEach { order ->
            val reports = mutableListOf<Map<CaptureOutputCompletion.Output, Boolean>>()
            val capture = CaptureOutputCompletion(true) { reports.add(it) }
            assertTrue(capture.start(order[0]))
            capture.finish(order[0], true)
            assertTrue(reports.isEmpty())
            assertTrue(capture.start(order[1]))
            capture.finish(order[1], true)
            capture.finish(order[1], false)
            capture.failPending()
            assertEquals(listOf(mapOf(RAW to true, BITMAP to true)), reports)
        }
    }

    @Test fun cameraErrorKeepsSlotUntilAlreadyRunningWriteFinishes() {
        val reports = mutableListOf<Map<CaptureOutputCompletion.Output, Boolean>>()
        val capture = CaptureOutputCompletion(true) { reports.add(it) }
        assertTrue(capture.start(RAW))
        capture.failPending()
        assertTrue(reports.isEmpty())
        assertFalse(capture.start(BITMAP))
        capture.finish(RAW, true)
        assertEquals(listOf(mapOf(RAW to true, BITMAP to false)), reports)
    }

    @Test fun singleFormatAndEarlyCameraFailureCompleteExactlyOnce() {
        var reports = 0
        val capture = CaptureOutputCompletion(false) { reports++ }
        assertFalse(capture.start(RAW))
        capture.failPending()
        capture.failPending()
        assertFalse(capture.start(BITMAP))
        assertEquals(1, reports)
    }
}
