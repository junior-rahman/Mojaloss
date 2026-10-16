package com.mojaloss.core.model

/** Pure rules: a seek target and a trailer position are never confirmed watch progress. */
data class ProgressState(
    val confirmedMs: Long = 0,
    val lastAdvancingMs: Long = 0,
    val requestedSeekMs: Long? = null,
    val pendingSyncMs: Long? = null
) {
    fun requestSeek(positionMs: Long) = copy(requestedSeekMs = positionMs.coerceAtLeast(0))
    fun observe(positionMs: Long, advancing: Boolean): ProgressState =
        if (!advancing) this else copy(
            lastAdvancingMs = positionMs.coerceAtLeast(0),
            requestedSeekMs = null,
            pendingSyncMs = positionMs.coerceAtLeast(0)
        )
    fun failedSeek() = copy(requestedSeekMs = null)
    fun confirm(positionMs: Long) = copy(
        confirmedMs = positionMs.coerceAtLeast(0),
        pendingSyncMs = pendingSyncMs?.takeUnless { it == positionMs }
    )
}

object PlaybackRecoveryPolicy {
    fun shouldOfferRecovery(
        phase: PlaybackPhase,
        playRequested: Boolean,
        appActive: Boolean,
        restoring: Boolean,
        preparingTracks: Boolean,
        stalledForMs: Long
    ) = playRequested && appActive && !restoring && !preparingTracks &&
        phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING) &&
        stalledForMs >= 12_000
}

