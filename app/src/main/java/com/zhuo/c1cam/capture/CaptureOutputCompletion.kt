package com.zhuo.c1cam.capture

/** Holds a capture slot until every requested output has finished, including partial failures. */
internal class CaptureOutputCompletion(
    includeRaw: Boolean,
    private val onComplete: (Map<Output, Boolean>) -> Unit
) {
    enum class Output { BITMAP, RAW }
    private enum class State { PENDING, RUNNING, DONE }
    private val states = linkedMapOf(Output.BITMAP to State.PENDING).apply {
        if (includeRaw) put(Output.RAW, State.PENDING)
    }
    private val results = linkedMapOf<Output, Boolean>()
    private var reported = false

    @Synchronized
    fun start(output: Output): Boolean {
        if (states[output] != State.PENDING) return false
        states[output] = State.RUNNING
        return true
    }

    @Synchronized
    fun finish(output: Output, success: Boolean) {
        if (states[output] != State.RUNNING) return
        states[output] = State.DONE
        results[output] = success
        reportIfFinished()
    }

    /** CameraX may fail before delivering one or both images; running writes still own their slot. */
    @Synchronized
    fun failPending() {
        states.keys.toList().forEach { output ->
            if (states[output] == State.PENDING) {
                states[output] = State.DONE
                results[output] = false
            }
        }
        reportIfFinished()
    }

    private fun reportIfFinished() {
        if (!reported && states.values.all { it == State.DONE }) {
            reported = true
            onComplete(results.toMap())
        }
    }
}
