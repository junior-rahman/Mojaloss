package com.mojaloss.core.data

import com.mojaloss.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackReportStateTest {
    private val scope = SessionScope("https://example.com/", "account", 1)
    private val mediaId = "00000000-0000-0000-0000-000000000001"
    private fun sample(position: Long, advancing: Boolean = false, paused: Boolean = false, ended: Boolean = false, stopped: Boolean = false, observed: Boolean = true) =
        PlaybackObservation(scope, mediaId, position, advancing, paused, ended, stopped = stopped, observed = observed)

    @Test fun closeAtSixSecondsReportsFinalObservedPositionAfterTwoSecondPeriodicSample() {
        val progress = PlaybackReportState()
        assertEquals(2_000L, progress.observe(sample(2_000, advancing = true)))
        progress.postAcknowledged(1_000)
        assertTrue(progress.readBack(2_000))
        assertEquals(6_000L, progress.observe(sample(6_000, paused = true)))
        assertEquals(6_000L, progress.pendingSyncMs)
        assertEquals(6_000L, progress.observe(sample(6_000, ended = true)))
    }

    @Test fun failedSeekFinalObservationReportsTheObservedPositionNotTheRequestedTarget() {
        val progress = PlaybackReportState()
        val player = ProgressState().observe(2_000, true).requestSeek(100_000).failedSeek()
        progress.observe(sample(player.lastAdvancingMs, advancing = true))
        assertEquals(2_000L, progress.observe(sample(player.lastAdvancingMs, paused = true)))
        assertNotEquals(100_000L, progress.pendingSyncMs)
    }

    @Test fun finalPositionSurvivesAcknowledgementFailureAndStaleReadBack() {
        val progress = PlaybackReportState()
        progress.observe(sample(2_000, advancing = true))
        progress.readBack(2_000)
        progress.observe(sample(6_000, ended = true))
        progress.postAcknowledged(42)
        progress.acknowledgementFailed()
        assertEquals(0L, progress.reportedAtNs)
        assertEquals(6_000L, progress.pendingSyncMs)
        assertFalse(progress.readBack(2_000))
        assertEquals(6_000L, progress.pendingSyncMs)
        assertTrue(progress.readBack(6_000))
        assertNull(progress.pendingSyncMs)
    }

    @Test fun firstFinalObservationIsNotLostBeforeTheFirstPeriodicReport() {
        val progress = PlaybackReportState()
        assertEquals(800L, progress.observe(sample(800, paused = true)))
        assertEquals(800L, progress.pendingSyncMs)
    }

    @Test fun stoppingAnUnfinishedMovieIncludesLatestObservedMovementWithoutCallingItWatched() {
        val progress = PlaybackReportState()
        progress.observe(sample(2_000, advancing = true))
        val stop = sample(6_000, stopped = true)
        assertFalse(stop.ended)
        assertEquals(6_000L, progress.observe(stop))
        assertEquals(6_000L, progress.pendingSyncMs)
    }

    @Test fun unobservedStopUsesOnlyInitialConfirmedResumeAndDoesNotCreatePendingProgress() {
        val progress = PlaybackReportState()
        val stop = sample(100_000, stopped = true, observed = false)
        // Play from start requests zero; an unstarted session must retain the service's two-minute resume.
        val descriptor = PlaybackDescriptor(MediaTitle(mediaId, "Movie", confirmedResumeMs = 120_000),
            "https://example.com/movie.mp4", sessionScope = scope, resumeMs = 0)
        val cleanup = requireNotNull(progress.unobservedStopInfo(stop, descriptor))
        assertEquals(120_000L * 10_000, cleanup.positionTicks)
        assertTrue(cleanup.failed)
        assertNull(progress.observe(stop))
        assertNull(progress.pendingSyncMs)
        assertNull(progress.observedMs)
        assertNull(progress.unobservedStopInfo(sample(100_000, observed = false), descriptor))
        assertNull(progress.unobservedStopInfo(sample(6_000, stopped = true), descriptor))
    }

    @Test fun completedProviderHistoryMayClearResumeAndObservedBackwardPlaybackMayDecreasePosition() {
        val progress = PlaybackReportState()
        progress.observe(sample(60_000, advancing = true))
        assertEquals(5_000L, progress.observe(sample(5_000, advancing = true)))
        assertTrue(progress.readBack(0, completed = true))
        assertNull(progress.pendingSyncMs)
    }
}
