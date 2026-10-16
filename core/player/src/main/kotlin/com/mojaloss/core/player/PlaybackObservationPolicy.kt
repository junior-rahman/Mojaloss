package com.mojaloss.core.player

import com.mojaloss.core.model.PlaybackDescriptor
import com.mojaloss.core.model.PlaybackObservation

/** Session cleanup has no authority to invent watched progress. */
internal fun playbackObservation(
    source: PlaybackDescriptor,
    observedPositionMs: Long?,
    paused: Boolean,
    advancing: Boolean = false,
    ended: Boolean = false,
    stopped: Boolean = false
): PlaybackObservation? {
    if (observedPositionMs == null && !stopped) return null
    return PlaybackObservation(
        scope = source.sessionScope,
        mediaId = source.title.id,
        positionMs = observedPositionMs?.coerceAtLeast(0) ?: source.title.confirmedResumeMs.coerceAtLeast(0),
        advancing = advancing && observedPositionMs != null,
        paused = paused,
        ended = ended && observedPositionMs != null,
        playSessionId = source.playSessionId,
        mediaSourceId = source.mediaSourceId,
        stopped = stopped,
        observed = observedPositionMs != null
    )
}
