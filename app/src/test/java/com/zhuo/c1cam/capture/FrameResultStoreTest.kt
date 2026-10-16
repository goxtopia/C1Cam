package com.zhuo.c1cam.capture

import org.junit.Assert.*
import org.junit.Test

class FrameResultStoreTest {
    @Test fun matchesEachFrameWhenBuffersAndMetadataArriveInEitherOrder() {
        val store = FrameResultStore<String>()
        store.put(20L, "second")
        val first = store.future(10L)
        assertFalse(first.isDone)
        store.put(10L, "first")
        assertEquals("first", first.get())
        assertEquals("second", store.future(20L).get())
        store.remove(10L)
        assertFalse(store.future(10L).isDone)
    }

    @Test fun boundedStoreExpiresMissingMetadataInsteadOfLeaking() {
        val store = FrameResultStore<String>(2)
        val first = store.future(1L)
        store.put(2L, "second")
        store.put(3L, "third")
        assertTrue(first.isCompletedExceptionally)
        assertEquals("third", store.future(3L).get())
    }
}
