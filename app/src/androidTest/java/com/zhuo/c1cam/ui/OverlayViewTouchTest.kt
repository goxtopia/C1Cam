package com.zhuo.c1cam.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayViewTouchTest {
    @Test
    fun allFourCornersMoveInEditModeAndStayFixedOutsideIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val overlay = OverlayView(instrumentation.targetContext)
            overlay.layout(0, 0, 800, 1000)
            overlay.isEditMode = true
            var taps = 0
            overlay.onSingleTapListener = { _, _ -> taps++ }
            overlay.onDoubleTapListener = { taps++ }
            repeat(4) { index ->
                val before = overlay.getNormalizedPoints()
                val x = before[index].x * overlay.width
                val y = before[index].y * overlay.height
                drag(overlay, x, y, x + 30, y + 40)
                val after = overlay.getNormalizedPoints()
                assertEquals((x + 30) / overlay.width, after[index].x, 0.001f)
                assertEquals((y + 40) / overlay.height, after[index].y, 0.001f)
                before.indices.filter { it != index }.forEach {
                    assertEquals(before[it], after[it])
                }
            }
            assertEquals(0, taps)
            overlay.isEditMode = false
            val before = overlay.getNormalizedPoints()
            drag(overlay, before[0].x * 800, before[0].y * 1000, 250f, 300f)
            assertEquals(before, overlay.getNormalizedPoints())
        }
    }

    @Test
    fun draggingClampsToViewAndCancelReleasesCorner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val overlay = OverlayView(instrumentation.targetContext)
            overlay.layout(0, 0, 800, 1000)
            overlay.isEditMode = true
            send(overlay, MotionEvent.ACTION_DOWN, 100f, 100f)
            send(overlay, MotionEvent.ACTION_MOVE, -50f, 1200f)
            send(overlay, MotionEvent.ACTION_CANCEL, -50f, 1200f)
            val before = overlay.getNormalizedPoints()
            assertEquals(0f, before[0].x, 0f)
            assertEquals(1f, before[0].y, 0f)
            send(overlay, MotionEvent.ACTION_DOWN, 400f, 500f)
            send(overlay, MotionEvent.ACTION_MOVE, 450f, 550f)
            send(overlay, MotionEvent.ACTION_UP, 450f, 550f)
            assertEquals(before, overlay.getNormalizedPoints())
        }
    }

    private fun drag(view: OverlayView, x: Float, y: Float, endX: Float, endY: Float) {
        send(view, MotionEvent.ACTION_DOWN, x, y)
        send(view, MotionEvent.ACTION_MOVE, endX, endY)
        send(view, MotionEvent.ACTION_UP, endX, endY)
    }

    private fun send(view: OverlayView, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also {
            assertTrue(view.dispatchTouchEvent(it))
            it.recycle()
        }
    }
}
