package com.mojaloss.core.player

/** Confirms observed movement, never a discontinuity or a requested seek. */
internal class AdvancingProgressTracker {
    var lastAdvancingPositionMs: Long? = null
        private set
    var requestedSeekMs: Long? = null
        private set
    private var samplePositionMs: Long? = null
    private var sampleTimeMs: Long? = null

    fun reset() {
        lastAdvancingPositionMs = null
        requestedSeekMs = null
        samplePositionMs = null
        sampleTimeMs = null
    }

    fun requestSeek(positionMs: Long) {
        requestedSeekMs = positionMs.coerceAtLeast(0)
        resetBaseline()
    }

    fun resetBaseline() {
        samplePositionMs = null
        sampleTimeMs = null
    }

    fun failSeek() {
        requestedSeekMs = null
        resetBaseline()
    }

    fun observe(positionMs: Long, nowMs: Long, actuallyPlaying: Boolean): Boolean {
        val previousPosition = samplePositionMs
        val previousTime = sampleTimeMs
        samplePositionMs = positionMs.coerceAtLeast(0)
        sampleTimeMs = nowMs
        if (!actuallyPlaying || previousPosition == null || previousTime == null) return false
        val delta = positionMs - previousPosition
        val elapsed = nowMs - previousTime
        // A large jump is a seek/timeline change, not evidence of decoded playback.
        if (elapsed <= 0 || delta <= 0 || delta > elapsed * 4 + 750) return false
        lastAdvancingPositionMs = positionMs.coerceAtLeast(0)
        requestedSeekMs = null
        return true
    }
}
