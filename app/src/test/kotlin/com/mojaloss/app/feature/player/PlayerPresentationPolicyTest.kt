package com.mojaloss.app.feature.player

import com.mojaloss.core.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPresentationPolicyTest {
    @Test fun lateTrailerReadinessRecoversTimeoutWithoutOverwritingKnownUnavailability() {
        assertEquals(TrailerLoadState.TimedOut, trailerTimeout(TrailerLoadState.Loading))
        assertEquals(TrailerLoadState.Ready, trailerReady(TrailerLoadState.TimedOut))
        val unavailable = trailerFailure(100)
        assertEquals(unavailable, trailerReady(unavailable))
        assertEquals(TrailerLoadState.Ready, trailerTimeout(TrailerLoadState.Ready))
    }

    @Test fun trailerRequestsExplicitPlaybackAndNeverStartsOrReportsMovieProgress() {
        val html = trailerHtml("ABCDEFGHIJK", "https://com.mojaloss.android.debug")
        assertTrue(html.contains("autoplay:0"))
        assertTrue(html.contains("controls:1"))
        assertFalse(html.contains("playVideo()"))
        assertFalse(html.contains("reportPlayback"))
    }

    @Test fun controlsHideOnlyForUninterruptedImmersivePlaybackWithoutAccessibility() {
        assertTrue(shouldAutoHideControls(true, PlaybackPhase.PLAYING, false, false, false))
        assertFalse(shouldAutoHideControls(true, PlaybackPhase.PLAYING, false, false, true))
        assertFalse(shouldAutoHideControls(true, PlaybackPhase.PLAYING, true, false, false))
        assertFalse(shouldAutoHideControls(true, PlaybackPhase.PLAYING, false, true, false))
        assertFalse(shouldAutoHideControls(true, PlaybackPhase.ERROR, false, false, false))
        assertFalse(shouldAutoHideControls(false, PlaybackPhase.PLAYING, false, false, false))
    }

    @Test fun seekTimeUsesHoursMinutesAndSecondsInsteadOfRawMilliseconds() {
        assertEquals("1:02:03", timeLabel(3_723_000))
        assertEquals("1 hour 2 minutes 3 seconds", spokenTime(3_723_000))
        assertEquals("0 seconds", spokenTime(-1))
        assertEquals("1 minute 1 second", spokenTime(61_000))
    }
}
