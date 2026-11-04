package com.mojaloss.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleStreamPolicyTest {
    @Test fun onlyDecoderAndContainerFailuresRequestAConvertedStream() {
        assertTrue(CompatibleStreamPolicy.isDecodeFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(CompatibleStreamPolicy.isDecodeFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(CompatibleStreamPolicy.isDecodeFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertFalse(CompatibleStreamPolicy.isDecodeFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(CompatibleStreamPolicy.isDecodeFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test fun switchHappensOnceForOnlineDirectPlayOnly() {
        assertTrue(CompatibleStreamPolicy.canRequest(isDirectPlay = true, offline = false, alreadyRequested = false, hasProvider = true))
        assertFalse(CompatibleStreamPolicy.canRequest(isDirectPlay = false, offline = false, alreadyRequested = false, hasProvider = true))
        assertFalse(CompatibleStreamPolicy.canRequest(isDirectPlay = true, offline = true, alreadyRequested = false, hasProvider = true))
        assertFalse(CompatibleStreamPolicy.canRequest(isDirectPlay = true, offline = false, alreadyRequested = true, hasProvider = true))
        assertFalse(CompatibleStreamPolicy.canRequest(isDirectPlay = true, offline = false, alreadyRequested = false, hasProvider = false))
    }

    @Test fun silentlySkippedPictureOrSoundCountsAsUndecodable() {
        assertTrue(CompatibleStreamPolicy.hasUndecodableTrack(containsVideo = true, videoSupported = true, containsAudio = true, audioSupported = false))
        assertTrue(CompatibleStreamPolicy.hasUndecodableTrack(containsVideo = true, videoSupported = false, containsAudio = true, audioSupported = true))
        assertFalse(CompatibleStreamPolicy.hasUndecodableTrack(containsVideo = true, videoSupported = true, containsAudio = true, audioSupported = true))
        assertFalse(CompatibleStreamPolicy.hasUndecodableTrack(containsVideo = true, videoSupported = true, containsAudio = false, audioSupported = false))
        assertFalse(CompatibleStreamPolicy.hasUndecodableTrack(containsVideo = false, videoSupported = false, containsAudio = false, audioSupported = false))
    }
}
