package com.mojaloss.core.data

import com.mojaloss.core.model.PlaybackObservation
import com.mojaloss.core.model.PlaybackDescriptor
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.util.UUID

/** positionMs is the player's observed advancing position, including its final pause/stop sample. */
internal class PlaybackReportState {
    var observedMs: Long? = null
        private set
    var pendingSyncMs: Long? = null
        private set
    var reportedAtNs: Long = 0
        private set
    private var confirmedMs: Long? = null

    fun observe(observation: PlaybackObservation): Long? {
        if (observation.observed && observation.positionMs >= 0 &&
            (observation.advancing || observation.paused || observation.ended || observation.stopped) &&
            (observedMs != null || observation.positionMs > 0 || observation.advancing)) {
            // Do not take max(): actual observed playback may advance after a backward seek.
            observedMs = observation.positionMs
            pendingSyncMs = observation.positionMs.takeUnless { it == confirmedMs }
        }
        return observedMs
    }

    /** Closing an unstarted prepared session must not treat its requested position as watched. */
    fun unobservedStopInfo(observation: PlaybackObservation, descriptor: PlaybackDescriptor): PlaybackStopInfo? {
        if (observation.observed || (!observation.stopped && !observation.ended)) return null
        val confirmed = descriptor.title.confirmedResumeMs.coerceIn(0, Long.MAX_VALUE / 10_000)
        return PlaybackStopInfo(itemId = UUID.fromString(observation.mediaId), mediaSourceId = observation.mediaSourceId,
            positionTicks = confirmed * 10_000, playSessionId = observation.playSessionId, failed = true)
    }

    fun postAcknowledged(nowNs: Long) { reportedAtNs = nowNs }

    /** Completion may deliberately reset provider resume to zero after marking the item played. */
    fun readBack(serverPositionMs: Long, completed: Boolean = false): Boolean {
        confirmedMs = serverPositionMs.coerceAtLeast(0)
        val settled = completed || pendingSyncMs == null || pendingSyncMs == confirmedMs
        if (settled) pendingSyncMs = null
        return settled
    }

    fun acknowledgementFailed() {
        // Keep the observed position pending and allow the next observation to retry immediately.
        reportedAtNs = 0
    }
}
