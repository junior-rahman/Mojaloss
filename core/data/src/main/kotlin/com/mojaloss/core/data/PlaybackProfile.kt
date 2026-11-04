package com.mojaloss.core.data

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile
import java.net.URI
import java.util.Locale

/** The largest stream one real decoder on this device accepts for a codec. */
internal data class VideoDecoderLimit(val maxWidth: Int, val maxHeight: Int, val maxBitDepth: Int)

/** Codecs measured from this device's actual decoders, keyed by Jellyfin codec name. */
internal data class DeviceCodecs(val video: Map<String, VideoDecoderLimit>, val audio: Set<String>)

/** Jellyfin codec names mapped to Android decoder MIME types, in preference order. */
private val VIDEO_DECODER_MIMES = linkedMapOf(
    "h264" to "video/avc", "hevc" to "video/hevc", "vp8" to "video/x-vnd.on2.vp8",
    "vp9" to "video/x-vnd.on2.vp9", "av1" to "video/av01",
)
private val AUDIO_DECODER_MIMES = linkedMapOf(
    "aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "ac3" to "audio/ac3", "eac3" to "audio/eac3",
    "flac" to "audio/flac", "opus" to "audio/opus", "vorbis" to "audio/vorbis",
)

/** Software decoders above 1080p are not dependable for smooth phone playback. */
private const val SOFTWARE_MAX_WIDTH = 1920
private const val SOFTWARE_MAX_HEIGHT = 1088

/** Containers Media3's progressive extractors read directly. */
private const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm"

/**
 * The previous fixed profile: MP4/H.264/AAC direct play, everything else as an H.264/AAC HLS
 * transcode. Used when decoder measurement is unavailable, and as the transcoding baseline.
 */
internal val CONSERVATIVE_STREAMING_PROFILE = DeviceProfile(name = "MojaLoss conservative Media3", directPlayProfiles = listOf(
    DirectPlayProfile("mp4,m4v", "aac,mp3", "h264", DlnaProfileType.VIDEO)),
    transcodingProfiles = listOf(hlsTranscodingProfile()), containerProfiles = emptyList(), codecProfiles = emptyList(),
    subtitleProfiles = externalSubtitleProfiles())

private fun hlsTranscodingProfile() = TranscodingProfile("ts", DlnaProfileType.VIDEO, "h264", "aac", MediaStreamProtocol.HLS,
    maxAudioChannels = "2", conditions = emptyList())

private fun externalSubtitleProfiles() = listOf("srt", "vtt", "ttml").map { SubtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL) }

/**
 * Direct play is offered only for codecs a real decoder here accepts, bounded by that decoder's
 * size and bit depth. HDR/Dolby Vision still transcode: display and tone-mapping support are not
 * measured. Anything outside these limits keeps the conservative H.264/AAC HLS transcode.
 */
internal fun streamingProfile(codecs: DeviceCodecs): DeviceProfile {
    val video = VIDEO_DECODER_MIMES.keys.filter { it in codecs.video }
    val audio = AUDIO_DECODER_MIMES.keys.filter { it in codecs.audio }
    // The transcode target must remain decodable; without it, keep the proven fixed profile.
    if ("h264" !in video || "aac" !in audio) return CONSERVATIVE_STREAMING_PROFILE
    val codecProfiles = video.map { codec ->
        val limit = codecs.video.getValue(codec)
        CodecProfile(type = CodecType.VIDEO, codec = codec, applyConditions = emptyList(), conditions = listOfNotNull(
            ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, limit.maxWidth.toString(), isRequired = true),
            ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, limit.maxHeight.toString(), isRequired = true),
            ProfileCondition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, limit.maxBitDepth.toString(), isRequired = false),
            ProfileCondition(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, "SDR", isRequired = false),
            videoProfiles(codec, limit)?.let { ProfileCondition(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_PROFILE, it, isRequired = false) },
        ))
    }
    return DeviceProfile(name = "MojaLoss measured Media3",
        directPlayProfiles = listOf(DirectPlayProfile(DIRECT_PLAY_CONTAINERS, audio.joinToString(","), video.joinToString(","), DlnaProfileType.VIDEO)),
        transcodingProfiles = listOf(hlsTranscodingProfile()), containerProfiles = emptyList(), codecProfiles = codecProfiles,
        subtitleProfiles = externalSubtitleProfiles())
}

private fun videoProfiles(codec: String, limit: VideoDecoderLimit): String? = when (codec) {
    "h264" -> "high|main|baseline|constrained baseline"
    "hevc" -> if (limit.maxBitDepth >= 10) "main|main 10" else "main"
    else -> null
}

/** Reads this device's decoders. Any failure falls back to the conservative profile. */
internal fun probeDeviceCodecs(): DeviceCodecs {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    val video = VIDEO_DECODER_MIMES.mapNotNull { (codec, mime) ->
        // Prefer one hardware decoder's complete limits; never combine limits from different decoders.
        decoders.mapNotNull { videoLimit(it, mime) }
            .maxWithOrNull(compareBy<Pair<Boolean, VideoDecoderLimit>> { it.first }
                .thenBy { it.second.maxWidth.toLong() * it.second.maxHeight }.thenBy { it.second.maxBitDepth })
            ?.let { codec to it.second }
    }.toMap()
    val audio = AUDIO_DECODER_MIMES.filter { (_, mime) ->
        decoders.any { decoder -> decoder.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
    }.keys
    return DeviceCodecs(video, audio)
}

/** Returns (hardware, limit) for one decoder, or null when it cannot decode [mime]. */
private fun videoLimit(decoder: MediaCodecInfo, mime: String): Pair<Boolean, VideoDecoderLimit>? = runCatching {
    if (decoder.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return null
    val capabilities = decoder.getCapabilitiesForType(mime)
    val video = capabilities.videoCapabilities ?: return null
    val software = if (Build.VERSION.SDK_INT >= 29) decoder.isSoftwareOnly
        else decoder.name.startsWith("OMX.google.", ignoreCase = true) || decoder.name.startsWith("c2.android.", ignoreCase = true)
    var width = video.supportedWidths.upper
    var height = video.supportedHeights.upper
    if (software) { width = minOf(width, SOFTWARE_MAX_WIDTH); height = minOf(height, SOFTWARE_MAX_HEIGHT) }
    if (width <= 0 || height <= 0) return null
    val profiles = capabilities.profileLevels.map { it.profile }.toSet()
    val tenBit = when (mime) {
        "video/hevc" -> CodecProfileLevel.HEVCProfileMain10 in profiles
        "video/x-vnd.on2.vp9" -> CodecProfileLevel.VP9Profile2 in profiles
        "video/av01" -> CodecProfileLevel.AV1ProfileMain10 in profiles
        else -> false
    }
    !software to VideoDecoderLimit(width, height, if (tenBit) 10 else 8)
}.getOrNull()

/** MIME for a server-delivered subtitle, from its delivered format rather than the source codec. */
internal fun deliveredSubtitleMime(url: String, codec: String?): String? {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase(Locale.ROOT)
    return when {
        path.endsWith(".srt") -> "application/x-subrip"
        path.endsWith(".vtt") -> "text/vtt"
        path.endsWith(".ttml") -> "application/ttml+xml"
        path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
        else -> sourceSubtitleMime(codec)
    }
}

internal fun sourceSubtitleMime(codec: String?): String? = when (codec?.lowercase(Locale.ROOT)) {
    "srt", "subrip" -> "application/x-subrip"
    "vtt", "webvtt" -> "text/vtt"
    "ttml" -> "application/ttml+xml"
    else -> null
}

/**
 * The audio stream whose language matches the viewer's Audio language setting. A transcoded
 * stream carries one audio track, so the server must be told which one before it starts.
 * [streams] are (stream index, language) pairs; returns null when nothing matches.
 */
internal fun preferredAudioIndex(streams: List<Pair<Int, String?>>, preference: String,
    systemLanguage: String = Locale.getDefault().language): Int? {
    val wanted = when (preference) {
        "English" -> "en"; "Bengali" -> "bn"; "Hindi" -> "hi"; "Spanish" -> "es"
        "System" -> systemLanguage
        else -> return null
    }
    val target = normalizedLanguage(wanted) ?: return null
    return streams.firstOrNull { (_, language) -> language != null && normalizedLanguage(language) == target }?.first
}

private fun normalizedLanguage(value: String): String? = runCatching {
    Locale.forLanguageTag(value.trim().replace('_', '-')).isO3Language
}.getOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != "und" }
