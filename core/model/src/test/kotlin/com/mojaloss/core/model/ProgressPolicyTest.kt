package com.mojaloss.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressPolicyTest {
    @Test
    fun failedSeekDoesNotReplaceObservedOrConfirmedResumeWithRequestedTarget() {
        val before = ProgressState(confirmedMs = 30_000, lastAdvancingMs = 45_000, pendingSyncMs = 45_000)

        val after = before.requestSeek(3_600_000).observe(3_600_000, advancing = false).failedSeek()

        assertEquals(30_000L, after.confirmedMs)
        assertEquals(45_000L, after.lastAdvancingMs)
        assertEquals(45_000L, after.pendingSyncMs)
        assertNull(after.requestedSeekMs)
    }

    @Test
    fun nonAdvancingSeekPositionNeverCreatesAPendingProgressWrite() {
        val after = ProgressState(confirmedMs = 120_000, lastAdvancingMs = 120_000)
            .requestSeek(7_200_000)
            .observe(7_200_000, advancing = false)

        assertEquals(120_000L, after.confirmedMs)
        assertEquals(120_000L, after.lastAdvancingMs)
        assertEquals(7_200_000L, after.requestedSeekMs)
        assertNull(after.pendingSyncMs)
    }

    @Test
    fun actualAdvancementAfterSeekRemainsUnconfirmedUntilServiceAcknowledgesIt() {
        val requested = ProgressState(confirmedMs = 15_000, lastAdvancingMs = 15_000)
            .requestSeek(90_000)
        val advancing = requested.observe(90_750, advancing = true)

        assertNull(advancing.requestedSeekMs)
        assertEquals(90_750L, advancing.lastAdvancingMs)
        assertEquals(90_750L, advancing.pendingSyncMs)
        assertEquals(15_000L, advancing.confirmedMs)

        val acknowledged = advancing.confirm(90_750)
        assertEquals(90_750L, acknowledged.confirmedMs)
        assertEquals(90_750L, acknowledged.lastAdvancingMs)
        assertNull(acknowledged.pendingSyncMs)
    }

    @Test
    fun acknowledgementOfEarlierObservationDoesNotDropNewerPendingProgress() {
        val after = ProgressState(confirmedMs = 10_000, lastAdvancingMs = 10_000)
            .observe(11_000, advancing = true)
            .observe(12_000, advancing = true)
            .confirm(11_000)

        assertEquals(11_000L, after.confirmedMs)
        assertEquals(12_000L, after.lastAdvancingMs)
        assertEquals(12_000L, after.pendingSyncMs)
    }

    @Test
    fun successfulBackwardSeekCanBecomeProgressWithoutUsingMonotonicMaximum() {
        val advancing = ProgressState(confirmedMs = 180_000, lastAdvancingMs = 180_000)
            .requestSeek(60_000)
            .observe(60_500, advancing = true)

        assertEquals(180_000L, advancing.confirmedMs)
        assertEquals(60_500L, advancing.lastAdvancingMs)
        assertEquals(60_500L, advancing.pendingSyncMs)

        assertEquals(60_500L, advancing.confirm(60_500).confirmedMs)
    }

    @Test
    fun acknowledgementDoesNotResolveASeparateUnobservedSeek() {
        val state = ProgressState(confirmedMs = 20_000, lastAdvancingMs = 21_000, pendingSyncMs = 21_000)
            .requestSeek(600_000)
            .confirm(21_000)

        assertEquals(21_000L, state.confirmedMs)
        assertEquals(600_000L, state.requestedSeekMs)
        assertNull(state.pendingSyncMs)
    }

    @Test
    fun playbackAndBufferingOfferRecoveryOnlyAfterTheStallBudget() {
        for (phase in listOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING)) {
            assertFalse("$phase before budget", recovery(phase = phase, stalledForMs = 11_999))
            assertTrue("$phase at budget", recovery(phase = phase, stalledForMs = 12_000))
            assertTrue("$phase after budget", recovery(phase = phase, stalledForMs = 60_000))
        }
    }

    @Test
    fun intentionalPauseAndEndedOrInactivePlayerNeverOfferStallRecovery() {
        for (phase in listOf(PlaybackPhase.IDLE, PlaybackPhase.PREPARING, PlaybackPhase.PAUSED,
            PlaybackPhase.ENDED, PlaybackPhase.ERROR)) {
            assertFalse("$phase is not active playback", recovery(phase = phase, stalledForMs = 60_000))
        }
        assertFalse(recovery(playRequested = false, stalledForMs = 60_000))
    }

    @Test
    fun backgroundTrackPreparationAndLifecycleRestorationAreNotPlaybackStalls() {
        for (phase in listOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING)) {
            assertFalse("$phase in background", recovery(phase = phase, appActive = false))
            assertFalse("$phase restoring", recovery(phase = phase, restoring = true))
            assertFalse("$phase changing tracks", recovery(phase = phase, preparingTracks = true))
        }
    }

    private fun recovery(
        phase: PlaybackPhase = PlaybackPhase.BUFFERING,
        playRequested: Boolean = true,
        appActive: Boolean = true,
        restoring: Boolean = false,
        preparingTracks: Boolean = false,
        stalledForMs: Long = 60_000
    ) = PlaybackRecoveryPolicy.shouldOfferRecovery(
        phase, playRequested, appActive, restoring, preparingTracks, stalledForMs
    )
}
