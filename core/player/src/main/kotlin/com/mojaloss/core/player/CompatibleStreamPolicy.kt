package com.mojaloss.core.player

import androidx.media3.common.PlaybackException

/**
 * Direct play sends the original file. When this device cannot decode it, the player asks the
 * server once for a converted stream instead of stopping at an error. Offline copies never
 * switch: they must play from local bytes only.
 */
internal object CompatibleStreamPolicy {
    private val decodeFailures = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    fun isDecodeFailure(errorCode: Int): Boolean = errorCode in decodeFailures

    fun canRequest(isDirectPlay: Boolean, offline: Boolean, alreadyRequested: Boolean, hasProvider: Boolean): Boolean =
        isDirectPlay && !offline && !alreadyRequested && hasProvider

    /** Media3 silently skips a track it cannot decode; a missing picture or sound is a failure too. */
    fun hasUndecodableTrack(containsVideo: Boolean, videoSupported: Boolean, containsAudio: Boolean, audioSupported: Boolean): Boolean =
        containsVideo && !videoSupported || containsAudio && !audioSupported
}
