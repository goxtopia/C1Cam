package com.zhuo.c1cam

data class PreviewAnalysisPolicy(
    val useHighestAvailableResolution: Boolean,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val processEveryNthFrame: Int
) {
    fun shouldProcessFrame(frameIndex: Long): Boolean {
        if (processEveryNthFrame <= 0) return false
        return frameIndex % processEveryNthFrame == 0L
    }

    companion object {
        private const val CAMERA_ANALYSIS_WIDTH = 1280
        private const val CAMERA_ANALYSIS_HEIGHT = 720

        fun forMode(mode: PreviewDisplayMode): PreviewAnalysisPolicy {
            return when (mode) {
                PreviewDisplayMode.CAMERA -> PreviewAnalysisPolicy(
                    useHighestAvailableResolution = false,
                    analysisWidth = CAMERA_ANALYSIS_WIDTH,
                    analysisHeight = CAMERA_ANALYSIS_HEIGHT,
                    processEveryNthFrame = 0
                )
                PreviewDisplayMode.RECTIFIED -> PreviewAnalysisPolicy(
                    useHighestAvailableResolution = true,
                    analysisWidth = 0,
                    analysisHeight = 0,
                    processEveryNthFrame = 1
                )
            }
        }
    }
}
