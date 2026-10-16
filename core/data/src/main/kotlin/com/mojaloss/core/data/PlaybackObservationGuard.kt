package com.mojaloss.core.data

import com.mojaloss.core.model.PlaybackDescriptor
import com.mojaloss.core.model.PlaybackObservation

/** Recheck after every suspension: the same title/account can now own a different playback. */
internal fun isCurrentPlaybackObservation(
    observation: PlaybackObservation,
    prepared: PlaybackDescriptor,
    current: PlaybackDescriptor?
): Boolean = current === prepared && !prepared.playSessionId.isNullOrBlank() && !prepared.mediaSourceId.isNullOrBlank() &&
    observation.scope == prepared.sessionScope && observation.mediaId == prepared.title.id &&
    observation.playSessionId == prepared.playSessionId && observation.mediaSourceId == prepared.mediaSourceId
