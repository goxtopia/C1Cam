package com.zhuo.c1cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InactivityTimeoutTest {
    @Test
    fun defaultIsFiveMinutes() {
        assertEquals(5, InactivityTimeout.DEFAULT_MINUTES)
    }

    @Test
    fun invalidStoredValueFallsBackToDefault() {
        assertEquals(
            InactivityTimeout.DEFAULT_MINUTES,
            InactivityTimeout.sanitize(0)
        )
        assertEquals(
            InactivityTimeout.DEFAULT_MINUTES,
            InactivityTimeout.sanitize(999)
        )
    }

    @Test
    fun everyPresentedChoiceIsAccepted() {
        assertTrue(InactivityTimeout.choicesMinutes.isNotEmpty())
        InactivityTimeout.choicesMinutes.forEach { minutes ->
            assertEquals(minutes, InactivityTimeout.sanitize(minutes))
        }
    }

    @Test
    fun labelsHandleSingularAndPlural() {
        assertEquals("1 minute", InactivityTimeout.label(1))
        assertEquals("5 minutes", InactivityTimeout.label(5))
    }
}
