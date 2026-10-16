package com.zhuo.c1cam.capture

import java.util.concurrent.CompletableFuture

/** Matches RAW buffers to their own sensor metadata even when callbacks arrive out of order. */
internal class FrameResultStore<T>(private val capacity: Int = 16) {
    private val frames = linkedMapOf<Long, CompletableFuture<T>>()

    @Synchronized
    fun future(timestamp: Long): CompletableFuture<T> = frames.getOrPut(timestamp) {
        while (frames.size >= capacity) {
            val oldest = frames.keys.first()
            frames.remove(oldest)?.completeExceptionally(IllegalStateException("Frame expired"))
        }
        CompletableFuture()
    }

    fun put(timestamp: Long, result: T) {
        future(timestamp).complete(result)
    }

    @Synchronized
    fun remove(timestamp: Long) {
        frames.remove(timestamp)
    }
}
