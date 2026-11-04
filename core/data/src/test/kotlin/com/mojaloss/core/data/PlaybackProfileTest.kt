package com.mojaloss.core.data

import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackProfileTest {
    private val measured = DeviceCodecs(
        video = mapOf("h264" to VideoDecoderLimit(4096, 2304, 8), "hevc" to VideoDecoderLimit(3840, 2160, 10)),
        audio = setOf("aac", "mp3", "ac3"),
    )

    @Test fun directPlayOffersOnlyMeasuredCodecsInReadableContainers() {
        val profile = streamingProfile(measured)
        val direct = profile.directPlayProfiles.orEmpty().single()
        assertEquals("mp4,m4v,mov,mkv,webm", direct.container)
        assertEquals("h264,hevc", direct.videoCodec)
        assertEquals("aac,mp3,ac3", direct.audioCodec)
    }

    @Test fun eachDirectCodecIsBoundedByItsDecoderAndStaysSdr() {
        val hevc = streamingProfile(measured).codecProfiles.orEmpty().single { it.codec == "hevc" }
        fun value(property: ProfileConditionValue) = hevc.conditions.single { it.property == property }
        assertEquals("3840", value(ProfileConditionValue.WIDTH).value)
        assertEquals("2160", value(ProfileConditionValue.HEIGHT).value)
        assertEquals("10", value(ProfileConditionValue.VIDEO_BIT_DEPTH).value)
        assertEquals(ProfileConditionType.LESS_THAN_EQUAL, value(ProfileConditionValue.WIDTH).condition)
        assertEquals("SDR", value(ProfileConditionValue.VIDEO_RANGE_TYPE).value)
        assertEquals("main|main 10", value(ProfileConditionValue.VIDEO_PROFILE).value)
        val h264 = streamingProfile(measured).codecProfiles.orEmpty().single { it.codec == "h264" }
        assertEquals("8", h264.conditions.single { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }.value)
    }

    @Test fun transcodeTargetIsUnchangedHlsH264Aac() {
        val transcode = streamingProfile(measured).transcodingProfiles.orEmpty().single()
        assertEquals("ts", transcode.container)
        assertEquals("h264", transcode.videoCodec)
        assertEquals("aac", transcode.audioCodec)
        assertEquals(MediaStreamProtocol.HLS, transcode.protocol)
    }

    @Test fun missingTranscodeDecoderKeepsTheConservativeProfile() {
        assertSame(CONSERVATIVE_STREAMING_PROFILE, streamingProfile(DeviceCodecs(mapOf("hevc" to VideoDecoderLimit(1920, 1080, 8)), setOf("aac"))))
        assertSame(CONSERVATIVE_STREAMING_PROFILE, streamingProfile(DeviceCodecs(mapOf("h264" to VideoDecoderLimit(1920, 1080, 8)), setOf("mp3"))))
    }

    @Test fun deliveredSubtitleFormatComesFromTheDeliveryUrl() {
        assertEquals("application/x-subrip", deliveredSubtitleMime("/Videos/a/b/Subtitles/3/0/Stream.srt?ApiKey=x", "mov_text"))
        assertEquals("text/vtt", deliveredSubtitleMime("/Videos/a/b/Subtitles/4/0/Stream.vtt", null))
        assertEquals("text/x-ssa", deliveredSubtitleMime("/Videos/a/b/Subtitles/5/0/Stream.ass", "ass"))
        assertEquals("application/x-subrip", deliveredSubtitleMime("/Videos/a/b/Subtitles/6/0/Stream", "subrip"))
        assertNull(deliveredSubtitleMime("/Videos/a/b/Subtitles/7/0/Stream.sup", "pgssub"))
    }

    @Test fun preferredAudioMatchesTwoAndThreeLetterLanguageCodes() {
        val streams = listOf(1 to "eng", 2 to "hin", 3 to null, 4 to "bn")
        assertEquals(2, preferredAudioIndex(streams, "Hindi"))
        assertEquals(1, preferredAudioIndex(streams, "English"))
        assertEquals(4, preferredAudioIndex(streams, "Bengali"))
        assertEquals(2, preferredAudioIndex(streams, "System", systemLanguage = "hi"))
        assertNull(preferredAudioIndex(streams, "Spanish"))
        assertNull(preferredAudioIndex(streams, "Unknown label"))
        assertNull(preferredAudioIndex(emptyList(), "English"))
    }
}
