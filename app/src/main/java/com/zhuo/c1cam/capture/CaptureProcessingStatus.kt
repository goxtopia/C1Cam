package com.zhuo.c1cam.capture

enum class CaptureProcessingStage {
    EXPOSING,
    PROCESSING,
    SAVING
}

data class CaptureProcessingStatus(
    val pendingCount: Int = 0,
    val foregroundCaptureId: Long? = null,
    val foregroundStage: CaptureProcessingStage? = null
)

/**
 * Keeps insertion order so the UI always represents the oldest unfinished capture,
 * even when processing and gallery writes overlap.
 */
internal class CaptureProcessingTracker {
    private val stages = linkedMapOf<Long, CaptureProcessingStage>()

    @Synchronized
    fun enqueue(captureId: Long): CaptureProcessingStatus {
        stages[captureId] = CaptureProcessingStage.EXPOSING
        return snapshot()
    }

    @Synchronized
    fun update(
        captureId: Long,
        stage: CaptureProcessingStage
    ): CaptureProcessingStatus {
        if (stages.containsKey(captureId)) {
            stages[captureId] = stage
        }
        return snapshot()
    }

    @Synchronized
    fun complete(captureId: Long): CaptureProcessingStatus {
        stages.remove(captureId)
        return snapshot()
    }

    @Synchronized
    fun snapshot(): CaptureProcessingStatus {
        val foreground = stages.entries.firstOrNull()
        return CaptureProcessingStatus(
            pendingCount = stages.size,
            foregroundCaptureId = foreground?.key,
            foregroundStage = foreground?.value
        )
    }
}
