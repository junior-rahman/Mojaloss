package com.mojaloss.core.player

import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.model.PlaybackDescriptor
import com.mojaloss.core.model.SessionScope
import org.junit.Assert.*
import org.junit.Test

class PlaybackObservationPolicyTest {
    private val source = PlaybackDescriptor(
        title = MediaTitle("movie", "Movie", confirmedResumeMs = 46_000),
        streamUrl = "https://media.example.test/video",
        sessionScope = SessionScope("https://media.example.test", "account", 1),
        playSessionId = "session", mediaSourceId = "source", resumeMs = 0
    )

    @Test fun releaseBeforeAnyMovementPreservesConfirmedBaselineEvenAfterPlayFromStart() {
        val cleanup = playbackObservation(source, null, paused = true, stopped = true)!!
        assertEquals(46_000L, cleanup.positionMs)
        assertTrue(cleanup.stopped)
        assertFalse(cleanup.observed)
        assertFalse(cleanup.advancing)
        assertFalse(cleanup.ended)
    }

    @Test fun normalPauseBeforeAnyMovementDoesNotReportAResumeOrSeekTarget() {
        assertNull(playbackObservation(source, null, paused = true))
    }

    @Test fun releaseAfterMovementReportsLastObservedPositionAndStopsSessionWithoutClaimingEnd() {
        val cleanup = playbackObservation(source, 6_000, paused = true, stopped = true)!!
        assertEquals(6_000L, cleanup.positionMs)
        assertTrue(cleanup.observed)
        assertTrue(cleanup.stopped)
        assertFalse(cleanup.ended)
        assertEquals(source.sessionScope, cleanup.scope)
        assertEquals("session", cleanup.playSessionId)
    }

    @Test fun unobservedCleanupCannotMarkWatchedEvenWithContradictoryFlags() {
        val cleanup = playbackObservation(source, null, paused = true,
            advancing = true, ended = true, stopped = true)!!
        assertFalse(cleanup.advancing)
        assertFalse(cleanup.ended)
    }

    @Test fun repeatedPauseSamplesAreCoalescedWithoutLosingLastObservedPositionOrStop() {
        val tracker = AdvancingProgressTracker()
        tracker.observe(6_000, 0, true)
        assertTrue(tracker.observe(6_500, 500, true))
        tracker.requestSeek(900_000)
        tracker.failSeek()
        tracker.resetBaseline()
        val gate = PlaybackObservationGate()
        val pause = playbackObservation(source, tracker.lastAdvancingPositionMs, paused = true)!!

        assertTrue(gate.admit(pause))
        assertFalse(gate.admit(pause)) // Lifecycle pause and Media3 callback share one report.
        assertEquals(6_500L, pause.positionMs)
        val cleanup = playbackObservation(source, tracker.lastAdvancingPositionMs,
            paused = true, stopped = true)!!
        assertTrue(gate.admit(cleanup))
        assertEquals(6_500L, cleanup.positionMs)
        assertTrue(cleanup.stopped)
        assertFalse(cleanup.ended)
    }

    @Test fun resumedMovementAndANewPreparedSessionAreNotSuppressedByPreviousPause() {
        val gate = PlaybackObservationGate()
        val pause = playbackObservation(source, 6_500, paused = true)!!
        assertTrue(gate.admit(pause))
        assertFalse(gate.admit(pause))
        assertTrue(gate.admit(playbackObservation(source, 7_000,
            paused = false, advancing = true)!!))
        assertTrue(gate.admit(playbackObservation(source, 7_000, paused = true)!!))

        gate.reset()
        assertTrue(gate.admit(pause))
    }

    @Test fun terminalCleanupBeforeMovementIsNeverDiscardedAsADuplicateSample() {
        val gate = PlaybackObservationGate()
        val cleanup = playbackObservation(source, null, paused = true, stopped = true)!!
        assertTrue(gate.admit(cleanup))
        assertTrue(gate.admit(cleanup))
        assertEquals(46_000L, cleanup.positionMs)
        assertFalse(cleanup.observed)
        assertFalse(cleanup.ended)
    }
}
