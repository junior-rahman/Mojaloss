package com.mojaloss.core.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import com.mojaloss.core.model.MainPlayerController
import com.mojaloss.core.model.PlaybackDescriptor
import com.mojaloss.core.model.PlaybackObservation
import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.PlaybackRecoveryPolicy
import com.mojaloss.core.model.PlaybackSnapshot
import com.mojaloss.core.model.TrackOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One controller per navigation playback session, retained outside recomposition. */
@UnstableApi
class Media3MainPlayerController(
    context: Context,
    scope: CoroutineScope,
    private val offlineDataSourceFactory: DataSource.Factory? = null,
    private val onObservation: (PlaybackObservation) -> Unit
) : MainPlayerController {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()
    private val audioState = MutableStateFlow<List<TrackOption>>(emptyList())
    private val subtitleState = MutableStateFlow<List<TrackOption>>(emptyList())
    private val qualityState = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioOptions: StateFlow<List<TrackOption>> = audioState.asStateFlow()
    val subtitleOptions: StateFlow<List<TrackOption>> = subtitleState.asStateFlow()
    val qualityOptions: StateFlow<List<TrackOption>> = qualityState.asStateFlow()
    private val qualitySelection = MutableStateFlow<String?>(null)
    val selectedQualityId: StateFlow<String?> = qualitySelection.asStateFlow()
    val mediaTitle: String get() = descriptor?.title?.title.orEmpty()
    private data class Selection(val group: Tracks.Group, val index: Int)
    private var selections = emptyMap<String, Selection>()
    private var descriptor: PlaybackDescriptor? = null
    private val progress = AdvancingProgressTracker()
    private val observationGate = PlaybackObservationGate()
    private var released = false
    private var appActive = true
    private var restoring = false
    private var requestedPlay = false
    private var preparationStartedAt = 0L
    private var stalledSince = 0L
    private var trackPreparationUntil = 0L
    private var lastReportAt = 0L
    private var retryCount = 0
    private var timeoutMessage: String? = null
    private val samplingJob: Job

    init {
        checkMainThread()
        exoPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (released) return
                // Focus loss/system pause must cancel the stall timer too.
                if (!player.playWhenReady) requestedPlay = false
                if (player.playbackState == Player.STATE_READY) preparationStartedAt = 0L
                publish()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    progress.requestSeek(newPosition.positionMs)
                } else progress.resetBaseline()
                stalledSince = SystemClock.elapsedRealtime()
                publish()
            }

            override fun onTracksChanged(tracks: Tracks) {
                rebuildTracks(tracks)
                // Time-limited exclusion; an unplayable track cannot suspend recovery forever.
                trackPreparationUntil = SystemClock.elapsedRealtime() + 2_000
                progress.resetBaseline()
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                progress.failSeek()
                requestedPlay = false
                publish(errorMessage(error))
                emitObservation(paused = true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    requestedPlay = false
                    val observed = progress.lastAdvancingPositionMs
                    val observedEnd = progress.requestedSeekMs == null && observed != null &&
                        safeDuration() > 0 && safeDuration() - observed in 0..1_500
                    emitObservation(paused = !observedEnd, ended = observedEnd)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) emitObservation(paused = true)
            }
        })
        samplingJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && !released) {
                sample()
                delay(500)
            }
        }
    }

    override fun prepare(descriptor: PlaybackDescriptor, autoPlay: Boolean) {
        checkUsable()
        if (this.descriptor == descriptor && exoPlayer.currentMediaItem != null) return
        val offline = descriptor.offlineCacheKey
        if (!MediaOriginPolicy(descriptor.sessionScope.serverUrl).allows(descriptor.streamUrl) ||
            (offline != null && (offline.isBlank() || descriptor.headers.isNotEmpty() || offlineDataSourceFactory == null))) {
            emitObservation(paused = true, stopped = true)
            this.descriptor = null
            requestedPlay = false
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            state.value = PlaybackSnapshot(mediaId = descriptor.title.id,
                phase = PlaybackPhase.ERROR, error = if (offline != null) "This download is not available. Download it again when connected."
                    else "This stream is not available securely.")
            return
        }
        emitObservation(paused = true, stopped = true)
        this.descriptor = descriptor
        progress.reset()
        observationGate.reset()
        retryCount = 0
        lastReportAt = 0L
        selections = emptyMap()
        audioState.value = emptyList()
        subtitleState.value = emptyList()
        qualityState.value = emptyList()
        qualitySelection.value = null
        state.value = PlaybackSnapshot(mediaId = descriptor.title.id,
            phase = PlaybackPhase.PREPARING, positionMs = descriptor.resumeMs.coerceAtLeast(0))
        prepareSource(descriptor.resumeMs, autoPlay)
    }

    private fun prepareSource(positionMs: Long, autoPlay: Boolean) {
        val source = descriptor ?: return
        timeoutMessage = null
        progress.resetBaseline()
        preparationStartedAt = SystemClock.elapsedRealtime()
        stalledSince = preparationStartedAt
        val origin = MediaOriginPolicy(source.sessionScope.serverUrl)
        // An offline descriptor can never fall back to HTTP, including retry and cache misses.
        // Its factory is supplied by the account-bound download owner and has no upstream.
        val dataSourceFactory = if (source.offlineCacheKey != null) offlineDataSourceFactory ?: run {
            requestedPlay = false
            state.value = state.value.copy(phase = PlaybackPhase.ERROR,
                error = "This download is not available. Download it again when connected.")
            return
        } else GuardedMediaDataSourceFactory(httpClient, source.sessionScope.serverUrl, source.headers)
        val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val item = MediaItem.Builder().setMediaId(source.title.id)
            .setUri(source.streamUrl)
            .setCustomCacheKey(source.offlineCacheKey)
            .setSubtitleConfigurations((if (source.offlineCacheKey == null) source.subtitleTracks else emptyList()).mapNotNull { option ->
                val subtitleUri = option.externalUri?.let(Uri::parse) ?: return@mapNotNull null
                if (!origin.allows(subtitleUri.toString()) || option.mimeType !in supportedSubtitleMimeTypes) return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                    .setId(option.id).setLabel(option.label).setLanguage(option.language)
                    .setMimeType(option.mimeType).build()
            })
            .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title.title).build())
            .build()
        exoPlayer.setMediaSource(sourceFactory.createMediaSource(item), positionMs.coerceAtLeast(0))
        requestedPlay = autoPlay && appActive && !restoring
        exoPlayer.playWhenReady = requestedPlay
        exoPlayer.prepare()
        publish()
    }

    override fun play() {
        checkUsable()
        if (!appActive || restoring || state.value.phase in listOf(PlaybackPhase.ERROR, PlaybackPhase.ENDED)) return
        requestedPlay = true
        stalledSince = SystemClock.elapsedRealtime()
        progress.resetBaseline()
        exoPlayer.play()
    }

    override fun pause() {
        checkUsable()
        requestedPlay = false
        exoPlayer.pause()
        progress.resetBaseline()
        emitObservation(paused = true)
        publish()
    }

    override fun seekTo(positionMs: Long) {
        checkUsable()
        // Completed server sessions are replayed only after a fresh descriptor is negotiated.
        if (state.value.phase == PlaybackPhase.ENDED) return
        if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val duration = safeDuration()
        val target = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        progress.requestSeek(target)
        stalledSince = SystemClock.elapsedRealtime()
        exoPlayer.seekTo(target)
        publish()
    }

    override fun selectAudio(id: String?) = selectTrack(C.TRACK_TYPE_AUDIO, id)
    override fun selectSubtitle(id: String?) = selectTrack(C.TRACK_TYPE_TEXT, id)
    fun selectQuality(id: String?) = selectTrack(C.TRACK_TYPE_VIDEO, id)

    override fun setPlaybackSpeed(speed: Float) {
        checkUsable()
        if (!speed.isFinite() || speed !in .5f..2f ||
            !exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        // Change the retained player's clock, without opening another stream or preparing again.
        // A clock transition is not evidence of observed viewing progress.
        progress.resetBaseline()
        stalledSince = SystemClock.elapsedRealtime()
        exoPlayer.setPlaybackSpeed(speed)
        publish()
    }

    private fun selectTrack(type: Int, id: String?) {
        checkUsable()
        val selection = id?.let { selections[it] }
        if (id != null && (selection == null || selection.group.type != type)) return
        val parameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && id == null)
        if (selection != null) parameters.setOverrideForType(
            TrackSelectionOverride(selection.group.mediaTrackGroup, selection.index)
        )
        trackPreparationUntil = SystemClock.elapsedRealtime() + 2_000
        progress.resetBaseline()
        exoPlayer.trackSelectionParameters = parameters.build()
        if (type == C.TRACK_TYPE_VIDEO) qualitySelection.value = id
        publish()
    }

    override fun retry() {
        checkUsable()
        if (!canRetry) return
        retryCount++
        progress.failSeek()
        val fallback = progress.lastAdvancingPositionMs ?: descriptor?.resumeMs ?: 0L
        prepareSource(fallback, autoPlay = true)
    }

    val canRetry: Boolean get() = !released && descriptor != null && retryCount < 1 && state.value.phase == PlaybackPhase.ERROR

    /** ON_STOP pauses; ON_START never implicitly starts media. Fold resize does not recreate this owner. */
    fun setAppActive(active: Boolean) {
        if (released) return
        checkUsable()
        if (appActive == active) return
        appActive = active
        stalledSince = SystemClock.elapsedRealtime()
        progress.resetBaseline()
        if (!active) pause()
    }

    fun setLifecycleRestoring(value: Boolean) {
        if (released) return
        checkUsable()
        if (restoring == value) return
        restoring = value
        stalledSince = SystemClock.elapsedRealtime()
        progress.resetBaseline()
        if (value) pause()
    }

    override fun release() {
        checkMainThread()
        if (released) return
        emitObservation(paused = true, stopped = true)
        descriptor = null
        released = true
        samplingJob.cancel()
        exoPlayer.release()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        selections = emptyMap()
    }

    private fun sample() {
        if (descriptor == null || released) return
        val now = SystemClock.elapsedRealtime()
        val advancing = progress.observe(exoPlayer.currentPosition, now,
            exoPlayer.isPlaying && appActive && !restoring && now >= trackPreparationUntil)
        if (advancing) {
            stalledSince = now
            if (now - lastReportAt >= 5_000) {
                emitObservation(paused = false, advancing = true)
                lastReportAt = now
            }
        }
        publish()
        if (appActive && !restoring && requestedPlay && preparationStartedAt > 0 &&
            now - preparationStartedAt >= 20_000) {
            failWithRecovery("This video could not start. Retry once, or go back.")
        } else if (PlaybackRecoveryPolicy.shouldOfferRecovery(
                state.value.phase, requestedPlay, appActive, restoring,
                now < trackPreparationUntil, now - stalledSince)) {
            failWithRecovery("Playback stopped advancing. Retry from the last playable position, or go back.")
        }
    }

    private fun failWithRecovery(message: String) {
        progress.failSeek()
        requestedPlay = false
        timeoutMessage = message
        exoPlayer.pause()
        publish(message)
        emitObservation(paused = true)
    }

    private fun emitObservation(
        paused: Boolean,
        advancing: Boolean = false,
        ended: Boolean = false,
        stopped: Boolean = false
    ) {
        val source = descriptor ?: return
        // Terminal cleanup without observed motion retains the service's existing baseline.
        // Play-from-start resumeMs=0 and any requested seek must never reset service progress.
        playbackObservation(source, progress.lastAdvancingPositionMs, paused, advancing, ended, stopped)
            ?.takeIf(observationGate::admit)
            ?.let(onObservation)
    }

    private fun publish(message: String? = null) {
        if (released || descriptor == null) return
        val error = message ?: timeoutMessage ?: exoPlayer.playerError?.let(::errorMessage)
        val phase = when {
            error != null -> PlaybackPhase.ERROR
            exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
            !exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.PAUSED
            preparationStartedAt > 0 -> PlaybackPhase.PREPARING
            exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            exoPlayer.isPlaying -> PlaybackPhase.PLAYING
            exoPlayer.playbackState == Player.STATE_READY -> PlaybackPhase.PAUSED
            else -> PlaybackPhase.IDLE
        }
        state.value = state.value.copy(
            phase = phase, positionMs = exoPlayer.currentPosition.coerceAtLeast(0),
            durationMs = safeDuration(), requestedSeekMs = progress.requestedSeekMs,
            lastAdvancingPositionMs = progress.lastAdvancingPositionMs ?: 0L,
            audioTrackId = selectedTrackId(C.TRACK_TYPE_AUDIO),
            subtitleTrackId = selectedTrackId(C.TRACK_TYPE_TEXT), error = error,
            playbackSpeed = exoPlayer.playbackParameters.speed
        )
    }

    private fun safeDuration() = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    private fun selectedTrackId(type: Int) = selections.entries.firstOrNull {
        it.value.group.type == type && it.value.group.isTrackSelected(it.value.index)
    }?.key

    private fun rebuildTracks(tracks: Tracks) {
        val found = mutableMapOf<String, Selection>()
        val options = mutableMapOf<Int, MutableList<TrackOption>>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                if (group.type !in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_VIDEO)) continue
                val format = group.getTrackFormat(index)
                val id = "${group.type}:$groupIndex:$index"
                val label = when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (format.height > 0) "${format.height}p" else format.label ?: "Video ${index + 1}"
                    else -> listOfNotNull(format.label, format.language?.takeUnless { it == "und" })
                        .distinct().joinToString(" · ").ifBlank {
                            if (group.type == C.TRACK_TYPE_AUDIO) "Audio ${index + 1}" else "Subtitles ${index + 1}"
                        }
                }
                found[id] = Selection(group, index)
                options.getOrPut(group.type) { mutableListOf() }.add(TrackOption(id, label, format.language))
            }
        }
        selections = found
        audioState.value = options[C.TRACK_TYPE_AUDIO].orEmpty()
        subtitleState.value = options[C.TRACK_TYPE_TEXT].orEmpty()
        qualityState.value = options[C.TRACK_TYPE_VIDEO].orEmpty()
    }

    private fun errorMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "This stream could not be opened. Reconnect if your sign-in has expired."
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This video format is not supported on this device."
        else -> "This video could not play. Retry once, or go back."
    }

    private fun checkUsable() { checkMainThread(); check(!released) { "Player already released" } }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Use the player on the main thread" } }

    private companion object {
        val supportedSubtitleMimeTypes = setOf(MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML,
            MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA)
    }
}

/** Media3 pause callbacks and lifecycle owners may deliver the same sample twice. */
internal class PlaybackObservationGate {
    private var previous: PlaybackObservation? = null

    fun admit(observation: PlaybackObservation): Boolean {
        // Terminal cleanup always retains its authority, including a stop before any motion.
        if (!observation.stopped && !observation.ended && observation == previous) return false
        previous = observation
        return true
    }

    fun reset() { previous = null }
}
