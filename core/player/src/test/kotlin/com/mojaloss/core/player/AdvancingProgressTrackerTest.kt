package com.mojaloss.core.player

import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.PlaybackRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test

class AdvancingProgressTrackerTest {
    @Test fun failedSeekDoesNotCreateObservedProgress() {
        val tracker = AdvancingProgressTracker()
        tracker.observe(46_000, 0, true)
        assertTrue(tracker.observe(46_500, 500, true))
        tracker.requestSeek(1_200_000)
        assertFalse(tracker.observe(1_200_000, 1_000, false))
        tracker.failSeek()
        assertEquals(46_500L, tracker.lastAdvancingPositionMs)
        assertNull(tracker.requestedSeekMs)
    }

    @Test fun successfulSeekRequiresMovementAfterLanding() {
        val tracker = AdvancingProgressTracker()
        tracker.requestSeek(120_000)
        assertFalse(tracker.observe(120_000, 1_000, true))
        assertEquals(120_000L, tracker.requestedSeekMs)
        assertTrue(tracker.observe(120_500, 1_500, true))
        assertEquals(120_500L, tracker.lastAdvancingPositionMs)
        assertNull(tracker.requestedSeekMs)
    }

    @Test fun timelineJumpAndPausedSamplesAreNotMovementEvidence() {
        val tracker = AdvancingProgressTracker()
        tracker.observe(1_000, 0, true)
        assertFalse(tracker.observe(900_000, 500, true))
        assertNull(tracker.lastAdvancingPositionMs)
        assertFalse(tracker.observe(900_500, 1_000, false))
        assertNull(tracker.lastAdvancingPositionMs)
    }

    @Test fun lifecycleRestorationResetsBaselineBeforePlaybackContinues() {
        val tracker = AdvancingProgressTracker()
        tracker.observe(1_000, 0, true)
        tracker.observe(1_500, 500, true)
        tracker.resetBaseline()
        assertFalse(tracker.observe(600_000, 10_000, true))
        assertEquals(1_500L, tracker.lastAdvancingPositionMs)
    }

    @Test fun recoveryExcludesPauseEndBackgroundRestoreAndTrackPreparation() {
        fun offer(phase: PlaybackPhase = PlaybackPhase.BUFFERING, play: Boolean = true,
            active: Boolean = true, restoring: Boolean = false, tracks: Boolean = false) =
            PlaybackRecoveryPolicy.shouldOfferRecovery(phase, play, active, restoring, tracks, 12_000)
        assertTrue(offer())
        assertFalse(offer(PlaybackPhase.PAUSED))
        assertFalse(offer(PlaybackPhase.ENDED))
        assertFalse(offer(play = false))
        assertFalse(offer(active = false))
        assertFalse(offer(restoring = true))
        assertFalse(offer(tracks = true))
    }
}
