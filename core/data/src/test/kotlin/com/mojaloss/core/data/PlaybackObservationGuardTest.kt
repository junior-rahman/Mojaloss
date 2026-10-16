package com.mojaloss.core.data

import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackObservationGuardTest {
    private val scope = SessionScope("https://example.com/", "account", 1)
    private val old = PlaybackDescriptor(MediaTitle("movie", "Movie"), "https://example.com/movie.mp4",
        sessionScope = scope, playSessionId = "old-playback", mediaSourceId = "source")
    private val oldObservation = PlaybackObservation(scope, "movie", 6_000, false, true,
        playSessionId = "old-playback", mediaSourceId = "source", stopped = true)

    @Test fun delayedOldStopIsRejectedAfterFreshSameTitlePreparation() {
        val fresh = old.copy(playSessionId = "new-playback")
        assertFalse(isCurrentPlaybackObservation(oldObservation, fresh, fresh))
        assertTrue(isCurrentPlaybackObservation(oldObservation.copy(playSessionId = "new-playback"), fresh, fresh))
    }

    @Test fun anAwaitedOldResponseCannotCommitAfterTheRecordWasReplaced() = runBlocking {
        withTimeout(5_000) {
            var current = old
            val requestStarted = CompletableDeferred<Unit>()
            val responseArrived = CompletableDeferred<Unit>()
            var mutations = 0
            val delayed = async {
                assertTrue(isCurrentPlaybackObservation(oldObservation, old, current))
                requestStarted.complete(Unit)
                responseArrived.await()
                if (isCurrentPlaybackObservation(oldObservation, old, current)) mutations++
            }
            requestStarted.await()
            current = old.copy(playSessionId = "new-playback")
            responseArrived.complete(Unit)
            delayed.await()
            assertEquals(0, mutations)
        }
    }

    @Test fun mediaSourceAndScopeAreIndependentGuardsAndMissingSessionIdsAreRejected() {
        assertFalse(isCurrentPlaybackObservation(oldObservation.copy(mediaSourceId = "other-source"), old, old))
        assertFalse(isCurrentPlaybackObservation(oldObservation.copy(scope = scope.copy(generation = 2)), old, old))
        val unidentified = old.copy(playSessionId = null)
        assertFalse(isCurrentPlaybackObservation(oldObservation.copy(playSessionId = null), unidentified, unidentified))
    }
}
