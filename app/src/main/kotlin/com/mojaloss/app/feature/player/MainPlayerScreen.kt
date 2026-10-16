package com.mojaloss.app.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mojaloss.app.adaptive.AdaptivePlayerLayout
import com.mojaloss.app.adaptive.FoldGeometry
import com.mojaloss.app.adaptive.rememberWindowFold
import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.PlaybackSnapshot
import com.mojaloss.core.model.TrackOption
import com.mojaloss.core.player.Media3MainPlayerController
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@UnstableApi
@Composable
fun MainPlayerScreen(controller: Media3MainPlayerController, onBack: () -> Unit, onReplay: () -> Unit) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val audio by controller.audioOptions.collectAsStateWithLifecycle()
    val subtitles by controller.subtitleOptions.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember(context) { context.playerActivity() }
    val device = remember(activity, context) { PlayerDeviceLevels(context, activity) }
    var brightness by remember(device) { mutableStateOf(device.brightness()) }
    var volume by remember(device) { mutableStateOf(device.volume()) }
    val picturePreferences = remember(context) { context.getSharedPreferences("player-presentation", Context.MODE_PRIVATE) }
    var pictureModeName by rememberSaveable { mutableStateOf(PlayerPictureMode.fromName(picturePreferences.getString("picture-mode", null)).name) }
    val pictureMode = PlayerPictureMode.fromName(pictureModeName)
    val screenBindings = remember(controller) { mutableMapOf<PlayerView, PlayerScreenOnBinding>() }
    DisposableEffect(controller) {
        onDispose { screenBindings.values.forEach(PlayerScreenOnBinding::close); screenBindings.clear() }
    }
    DisposableEffect(device) { onDispose { device.restoreBrightness() } }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.setAppActive(true)
                Lifecycle.Event.ON_STOP -> controller.setAppActive(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        controller.setAppActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            // Navigation owns the retained session. Backgrounding never resumes automatically.
            controller.setAppActive(false)
        }
    }
    MainPlayerContent(
        title = controller.mediaTitle, snapshot = snapshot, audio = audio, subtitles = subtitles,
        canSeek = controller.exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        canRetry = controller.canRetry,
        onBack = { controller.pause(); onBack() },
        onPlayPause = {
            if (snapshot.phase == PlaybackPhase.ENDED) onReplay()
            else if (snapshot.phase == PlaybackPhase.PLAYING || snapshot.phase == PlaybackPhase.BUFFERING) controller.pause()
            else controller.play()
        },
        onSeek = controller::seekTo, onRetry = controller::retry,
        onAudio = controller::selectAudio, onSubtitle = controller::selectSubtitle,
        canChangeSpeed = controller.exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
        onSpeed = controller::setPlaybackSpeed, pictureMode = pictureMode, onPictureMode = { mode ->
            pictureModeName = mode.name
            picturePreferences.edit().putString("picture-mode", mode.name).apply()
        },
        brightness = brightness, onBrightness = { brightness = device.setBrightness(it) },
        volume = volume, onVolume = { volume = device.setVolume(it) },
        readDeviceLevels = {
            brightness = device.brightness(); volume = device.volume()
            brightness to volume
        },
        video = {
            AndroidView(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                factory = { viewContext -> PlayerView(viewContext).apply {
                    player = controller.exoPlayer
                    useController = false
                    resizeMode = pictureMode.resizeMode
                    setKeepContentOnPlayerReset(false)
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    screenBindings[this] = PlayerScreenOnBinding(this, controller.exoPlayer)
                } },
                update = { view ->
                    if (view.player !== controller.exoPlayer) {
                        screenBindings.remove(view)?.close()
                        view.player = controller.exoPlayer
                    }
                    if (view !in screenBindings) screenBindings[view] = PlayerScreenOnBinding(view, controller.exoPlayer)
                    view.resizeMode = pictureMode.resizeMode
                },
                onRelease = { screenBindings.remove(it)?.close(); it.keepScreenOn = false; it.player = null }
            )
        }
    )
}

private enum class GestureAxis { SEEK, BRIGHTNESS, VOLUME }
private data class GesturePreview(val axis: GestureAxis, val position: Long = 0, val level: Float = 0f)

/** Controller-free presentation seam: offline tests never create a network-backed player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainPlayerContent(
    title: String,
    snapshot: PlaybackSnapshot,
    audio: List<TrackOption> = emptyList(),
    subtitles: List<TrackOption> = emptyList(),
    canSeek: Boolean = false,
    canRetry: Boolean = false,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onAudio: (String?) -> Unit = {},
    onSubtitle: (String?) -> Unit = {},
    fold: FoldGeometry? = rememberWindowFold(),
    accessibilityEnabled: Boolean = rememberAccessibilityActive(),
    canChangeSpeed: Boolean = false,
    onSpeed: (Float) -> Unit = {},
    pictureMode: PlayerPictureMode = PlayerPictureMode.FIT,
    onPictureMode: (PlayerPictureMode) -> Unit = {},
    brightness: Float? = null,
    onBrightness: (Float) -> Unit = {},
    volume: Float? = null,
    onVolume: (Float) -> Unit = {},
    hideAfterMillis: Long = 12_000,
    readDeviceLevels: (() -> Pair<Float?, Float?>)? = null,
    video: @Composable (() -> Unit) -> Unit,
) {
    var controlsVisible by remember(snapshot.mediaId) { mutableStateOf(true) }
    var locked by rememberSaveable(snapshot.mediaId) { mutableStateOf(false) }
    var optionsOpen by remember(snapshot.mediaId) { mutableStateOf(false) }
    var pictureSizeOpen by remember(snapshot.mediaId) { mutableStateOf(false) }
    var dragPosition by remember(snapshot.mediaId) { mutableStateOf<Float?>(null) }
    var preview by remember(snapshot.mediaId) { mutableStateOf<GesturePreview?>(null) }
    var gestureActive by remember(snapshot.mediaId) { mutableStateOf(false) }
    var shortFeedback by remember(snapshot.mediaId) { mutableStateOf<String?>(null) }
    var interaction by remember(snapshot.mediaId) { mutableIntStateOf(0) }
    var gestureViewport by remember(snapshot.mediaId) { mutableStateOf(IntSize.Zero) }
    var controlViewport by remember(snapshot.mediaId) { mutableStateOf(IntSize.Zero) }
    val accessibilityManager = LocalAccessibilityManager.current
    val density = LocalDensity.current
    val feedbackDuration = if (LocalMotionPolicy.current.animationsEnabled) 160 else 0
    val secondaryActionsInOptions = with(density) { controlViewport.width.toDp() < 288.dp || controlViewport.height.toDp() < 96.dp }
    val hideTimeout = remember(accessibilityManager, hideAfterMillis) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(hideAfterMillis.coerceAtLeast(1),
            containsIcons = true, containsText = true, containsControls = true) ?: hideAfterMillis.coerceAtLeast(1)
    }
    val seekEnabled = canSeek && snapshot.durationMs > 0 && snapshot.phase !in
        listOf(PlaybackPhase.ERROR, PlaybackPhase.ENDED, PlaybackPhase.PREPARING, PlaybackPhase.IDLE)
    val reveal: () -> Unit = { controlsVisible = true; interaction++; Unit }
    val toggle: () -> Unit = {
        if (!locked) { controlsVisible = !controlsVisible; interaction++ }; Unit
    }
    val seekBy: (Long) -> Unit = { delta ->
        if (!locked && seekEnabled) {
            onSeek((snapshot.positionMs + delta).coerceIn(0L, snapshot.durationMs))
            shortFeedback = if (delta < 0) "Back 10 seconds" else "Forward 10 seconds"
            interaction++
        }
    }
    // Pointer coroutines stay alive through the 500 ms position samples and preview recompositions.
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentSeekEnabled by rememberUpdatedState(seekEnabled)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentBrightness by rememberUpdatedState(brightness)
    val currentVolume by rememberUpdatedState(volume)
    val currentReadLevels by rememberUpdatedState(readDeviceLevels)
    val currentBrightnessWrite by rememberUpdatedState(onBrightness)
    val currentVolumeWrite by rememberUpdatedState(onVolume)
    val currentToggle by rememberUpdatedState(toggle)
    val currentSeekBy by rememberUpdatedState(seekBy)
    val showControls = !locked && (controlsVisible || accessibilityEnabled || snapshot.phase != PlaybackPhase.PLAYING)
    ImmersivePlayerWindow()
    LaunchedEffect(snapshot.phase) {
        // Lock protects an active viewing session, not a failed or completed one.
        // Recovery and replay must remain visible without guessing why the video stopped.
        if (snapshot.phase == PlaybackPhase.ERROR || snapshot.phase == PlaybackPhase.ENDED) {
            locked = false
            preview = null; shortFeedback = null; gestureActive = false
            reveal()
        }
    }
    BackHandler {
        when {
            locked -> { locked = false; reveal() }
            pictureSizeOpen -> { pictureSizeOpen = false; reveal() }
            optionsOpen -> { optionsOpen = false; reveal() }
            else -> onBack()
        }
    }
    LaunchedEffect(controlsVisible, snapshot.phase, optionsOpen, pictureSizeOpen, dragPosition != null, gestureActive,
        accessibilityEnabled, interaction, locked, hideTimeout) {
        if (!locked && controlsVisible && shouldAutoHideControls(true, snapshot.phase, optionsOpen || pictureSizeOpen,
                dragPosition != null || gestureActive, accessibilityEnabled)) {
            delay(hideTimeout)
            controlsVisible = false
        }
    }
    LaunchedEffect(shortFeedback, interaction) {
        if (shortFeedback != null) { delay(1_200); shortFeedback = null }
    }
    AdaptivePlayerLayout(
        modifier = Modifier.fillMaxSize().background(Color.Black), fold = fold,
        immersive = true, controlsInVideo = true,
        video = {
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("main-video").semantics {
                onClick(if (locked) "Unlock controls" else "Show or hide playback controls") {
                    if (locked) { locked = false; reveal() } else toggle()
                    true
                }
                contentDescription = title
                stateDescription = if (locked) "Controls locked" else if (showControls) "Controls visible" else "Controls hidden"
                customActions = if (locked) emptyList() else buildList {
                    if (snapshot.phase !in listOf(PlaybackPhase.IDLE, PlaybackPhase.PREPARING, PlaybackPhase.ERROR))
                        add(CustomAccessibilityAction("Play or pause") { onPlayPause(); reveal(); true })
                    if (seekEnabled) {
                        add(CustomAccessibilityAction("Back 10 seconds") { seekBy(-10_000); true })
                        add(CustomAccessibilityAction("Forward 10 seconds") { seekBy(10_000); true })
                    }
                }
            }) {
                video(reveal)
                Box(Modifier.fillMaxSize().testTag("player-gesture-plane")
                    .onSizeChanged { measured ->
                        if (gestureViewport != measured) {
                            gestureViewport = measured
                            preview = null; gestureActive = false; dragPosition = null
                            shortFeedback = null
                        }
                    }
                    .pointerInput(snapshot.mediaId, locked, gestureViewport) {
                        detectTapGestures(
                            onTap = { if (!locked) currentToggle() },
                            onDoubleTap = { point -> if (!locked) currentSeekBy(if (point.x < size.width / 2f) -10_000 else 10_000) }
                        )
                    }
                    .pointerInput(snapshot.mediaId, locked, gestureViewport) {
                        var start = Offset.Zero
                        var delta = Offset.Zero
                        var axis: GestureAxis? = null
                        var basePosition = 0L
                        var duration = 0L
                        var baseBrightness: Float? = null
                        var baseVolume: Float? = null
                        var startViewport = IntSize.Zero
                        detectDragGestures(
                            onDragStart = { point ->
                                start = point; delta = Offset.Zero; axis = null
                                startViewport = size
                                basePosition = currentSnapshot.positionMs; duration = currentSnapshot.durationMs
                                if (!locked && startViewport == size && startViewport == gestureViewport) {
                                    gestureActive = true
                                    val reader = currentReadLevels
                                    val levels = reader?.invoke()
                                    baseBrightness = if (reader != null) levels?.first else currentBrightness
                                    baseVolume = if (reader != null) levels?.second else currentVolume
                                    reveal()
                                }
                            },
                            onDrag = { change, movement ->
                                change.consume()
                                if (!locked && startViewport == size && startViewport == gestureViewport) {
                                    delta += movement
                                    if (axis == null) axis = if (abs(delta.x) >= abs(delta.y)) GestureAxis.SEEK
                                        else if (start.x < size.width / 2f) GestureAxis.BRIGHTNESS else GestureAxis.VOLUME
                                    preview = when (axis) {
                                        GestureAxis.SEEK -> if (currentSeekEnabled)
                                            GesturePreview(GestureAxis.SEEK, position = gestureSeekPreview(basePosition, duration, delta.x, size.width.toFloat())) else null
                                        GestureAxis.BRIGHTNESS -> baseBrightness?.let { GesturePreview(GestureAxis.BRIGHTNESS,
                                            level = gestureLevelPreview(it, delta.y, size.height.toFloat(), min = .05f)) }
                                        GestureAxis.VOLUME -> baseVolume?.let { GesturePreview(GestureAxis.VOLUME,
                                            level = gestureLevelPreview(it, delta.y, size.height.toFloat())) }
                                        null -> null
                                    }
                                } else { preview = null; gestureActive = false }
                            },
                            onDragEnd = {
                                if (!locked && startViewport == size && startViewport == gestureViewport) preview?.let { final ->
                                    when (final.axis) {
                                        GestureAxis.SEEK -> if (currentSeekEnabled) currentSeek(final.position)
                                        GestureAxis.BRIGHTNESS -> currentBrightnessWrite(final.level)
                                        GestureAxis.VOLUME -> currentVolumeWrite(final.level)
                                    }
                                }
                                preview = null; gestureActive = false; reveal()
                            },
                            onDragCancel = { preview = null; gestureActive = false; reveal() }
                        )
                    })
            }
        },
        controls = {
            BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { controlViewport = it }) {
                val paneHeight = maxHeight
                val paneWidth = maxWidth
                val narrowPane = paneWidth < 288.dp
                val scrollableFallback = paneWidth < 144.dp || paneHeight < 96.dp
                val compact = paneHeight < 260.dp || narrowPane
                val compactTimeWidth = (paneWidth - 80.dp).coerceIn(48.dp, 160.dp)
                val shortPane = paneHeight < 200.dp && snapshot.phase != PlaybackPhase.ERROR
                if (locked) IconButton(onClick = { locked = false; reveal() },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).background(Color.Black.copy(alpha = .75f), MaterialTheme.shapes.large)) {
                    Icon(Icons.Outlined.LockOpen, "Unlock controls", tint = Color.White)
                }
                if (showControls) {
                    val backButton: @Composable () -> Unit = {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back to title", tint = Color.White) }
                    }
                    val lockButton: @Composable () -> Unit = {
                        val lockEnabled = snapshot.phase != PlaybackPhase.ERROR && snapshot.phase != PlaybackPhase.ENDED
                        IconButton(enabled = lockEnabled,
                            onClick = { preview = null; shortFeedback = null; gestureActive = false; dragPosition = null; locked = true; optionsOpen = false }) {
                            Icon(Icons.Outlined.Lock, "Lock controls", tint = Color.White.copy(alpha = if (lockEnabled) 1f else .4f))
                        }
                    }
                    val pictureSizeButton: @Composable () -> Unit = {
                        TextButton(onClick = { pictureSizeOpen = true; reveal() },
                            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp, max = 88.dp).testTag("picture-size-button").semantics {
                                contentDescription = "Picture size: ${pictureMode.label}"
                                stateDescription = pictureMode.description
                            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Outlined.Fullscreen, null, Modifier.size(20.dp), tint = Color.White)
                            Spacer(Modifier.size(4.dp))
                            Text(pictureMode.label, Modifier.weight(1f, fill = false).clearAndSetSemantics {}, color = Color.White,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val optionsButton: @Composable () -> Unit = {
                        IconButton(onClick = { reveal(); optionsOpen = true }) {
                            Icon(Icons.Outlined.Settings, "Playback options", tint = Color.White)
                        }
                    }
                    val pauseButton: @Composable () -> Unit = {
                        val active = snapshot.phase == PlaybackPhase.PLAYING || snapshot.phase == PlaybackPhase.BUFFERING
                        val playEnabled = snapshot.phase !in listOf(PlaybackPhase.IDLE, PlaybackPhase.PREPARING)
                        IconButton(onClick = { reveal(); onPlayPause() }, enabled = playEnabled,
                            modifier = Modifier.size(if (compact) 48.dp else 64.dp)) {
                            Icon(if (active) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                if (active) "Pause" else if (snapshot.phase == PlaybackPhase.ENDED) "Play again" else "Play",
                                Modifier.size(if (compact) 32.dp else 40.dp), tint = Color.White.copy(alpha = if (playEnabled) 1f else .4f))
                        }
                    }
                    val duration = snapshot.durationMs.toFloat()
                    val displayedPosition = (dragPosition ?: preview?.takeIf { it.axis == GestureAxis.SEEK }?.position?.toFloat()
                        ?: snapshot.positionMs.toFloat()).coerceIn(0f, duration.coerceAtLeast(0f))
                    val seekSlider: @Composable (Modifier) -> Unit = { modifier ->
                        Slider(value = displayedPosition, onValueChange = { controlsVisible = true; dragPosition = it }, valueRange = 0f..duration,
                            onValueChangeFinished = { dragPosition?.let { if (seekEnabled) onSeek(it.toLong()) }; dragPosition = null; reveal() },
                            enabled = seekEnabled, colors = SliderDefaults.colors(thumbColor = Color.White,
                                activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .35f)),
                            thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                            track = {
                                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .35f), CircleShape)) {
                                    Box(Modifier.fillMaxWidth(if (duration > 0) (displayedPosition / duration).coerceIn(0f, 1f) else 0f)
                                        .height(3.dp).background(Color.White, CircleShape))
                                }
                            },
                            modifier = modifier.heightIn(min = 48.dp).testTag("playback-seek").semantics {
                                contentDescription = "Playback position"
                                stateDescription = "${spokenTime(displayedPosition.toLong())} of ${spokenTime(snapshot.durationMs)}"
                            })
                    }
                    val message = when (snapshot.phase) {
                        PlaybackPhase.PREPARING -> "Preparing video…"
                        PlaybackPhase.BUFFERING -> "Buffering…"
                        PlaybackPhase.ERROR -> snapshot.error ?: "This video could not play."
                        PlaybackPhase.ENDED -> "Video ended"
                        PlaybackPhase.IDLE -> "Choose a video to play"
                        else -> null
                    }
                    Box(Modifier.fillMaxSize().testTag("playback-controls")) {
                        if (scrollableFallback) {
                            // A genuinely constrained pane keeps full-size targets reachable by
                            // scrolling instead of clipping them or borrowing space across a hinge.
                            Column(Modifier.fillMaxSize().background(playerControlGradient())
                                .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (paneWidth >= 96.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    backButton(); optionsButton()
                                } else {
                                    backButton(); optionsButton()
                                }
                                if (snapshot.phase != PlaybackPhase.ERROR) pauseButton()
                                if (message != null) Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                if (snapshot.phase == PlaybackPhase.ERROR) {
                                    if (canRetry) Button(onClick = { reveal(); onRetry() }) { Text("Retry once") }
                                    else Text("Go back to choose another video or reconnect.", color = Color.White)
                                } else if (snapshot.durationMs > 0) {
                                    Text("${timeLabel(displayedPosition.toLong())} / ${timeLabel(snapshot.durationMs)}",
                                        Modifier.testTag("playback-time"), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    seekSlider(Modifier.fillMaxWidth())
                                }
                            }
                        } else if (shortPane) {
                            // A short tabletop/landscape pane cannot fit three 48dp transport rows.
                            // Keep distinct full-size targets in one central strip and one seek row.
                            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                .padding(top = ((paneHeight - 96.dp) / 2).coerceAtLeast(0.dp))
                                .background(playerControlGradient()),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                                backButton()
                                if (!narrowPane) lockButton()
                                pauseButton()
                                if (!narrowPane) pictureSizeButton()
                                optionsButton()
                            }
                        } else {
                            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(playerControlGradient(top = true))
                                .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                backButton()
                                Text(title, Modifier.weight(1f), color = Color.White,
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!narrowPane) { lockButton(); pictureSizeButton() }
                                optionsButton()
                            }
                            if (snapshot.phase != PlaybackPhase.ERROR) {
                                Row(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .30f), MaterialTheme.shapes.extraLarge),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 20.dp)) {
                                    IconButton(onClick = { reveal(); seekBy(-10_000) }, enabled = seekEnabled) {
                                        Icon(Icons.Outlined.Replay10, "Back 10 seconds", tint = Color.White.copy(alpha = if (seekEnabled) 1f else .4f))
                                    }
                                    pauseButton()
                                    IconButton(onClick = { reveal(); seekBy(10_000) }, enabled = seekEnabled) {
                                        Icon(Icons.Outlined.Forward10, "Forward 10 seconds", tint = Color.White.copy(alpha = if (seekEnabled) 1f else .4f))
                                    }
                                }
                            }
                        }
                        if (!scrollableFallback && shortPane) {
                            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(playerControlGradient())
                                // Material3 extends horizontal slider semantics by10dp on both
                                // ends. Keep that accessible target within the video viewport.
                                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (snapshot.durationMs > 0) seekSlider(Modifier.weight(1f))
                                Text(message ?: snapshot.requestedSeekMs?.let { "Seeking…" } ?:
                                    "${timeLabel(displayedPosition.toLong())} / ${timeLabel(snapshot.durationMs)}",
                                    Modifier.widthIn(max = compactTimeWidth).testTag("playback-time"), color = Color.White,
                                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else if (!scrollableFallback) {
                            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(playerControlGradient())
                                .heightIn(max = (paneHeight - 48.dp).coerceAtLeast(1.dp)).verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 8.dp)) {
                                if (message != null) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (snapshot.phase == PlaybackPhase.PREPARING || snapshot.phase == PlaybackPhase.BUFFERING)
                                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                    Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (snapshot.phase == PlaybackPhase.ERROR) {
                                    if (canRetry) Button(onClick = { reveal(); onRetry() }) { Text("Retry once") }
                                    else Text("Go back to choose another video or reconnect.", color = Color.White)
                                } else if (snapshot.durationMs > 0) {
                                    snapshot.requestedSeekMs?.let { Text("Seeking to ${timeLabel(it)}…", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                                    Text("${timeLabel(displayedPosition.toLong())} / ${timeLabel(snapshot.durationMs)}",
                                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1, modifier = Modifier.testTag("playback-time"))
                                    seekSlider(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                val feedback = preview?.let { when (it.axis) {
                    GestureAxis.SEEK -> "Seek preview ${timeLabel(it.position)}"
                    GestureAxis.BRIGHTNESS -> "Brightness preview ${(it.level * 100).roundToInt()}%"
                    GestureAxis.VOLUME -> "Volume preview ${(it.level * 100).roundToInt()}%"
                } } ?: shortFeedback
                val feedbackAlignment = when (preview?.axis) {
                    GestureAxis.BRIGHTNESS -> Alignment.CenterStart
                    GestureAxis.VOLUME -> Alignment.CenterEnd
                    else -> if (shortFeedback?.startsWith("Back") == true) Alignment.CenterStart else Alignment.CenterEnd
                }
                AnimatedVisibility(visible = !locked && feedback != null,
                    modifier = Modifier.align(feedbackAlignment).padding(12.dp),
                    enter = fadeIn(tween(feedbackDuration)), exit = fadeOut(tween(feedbackDuration))) {
                    // Remove text/semantics at cancellation immediately; an exiting animation
                    // must never leave a hidden, active gesture target or stale seek label.
                    feedback?.takeIf { !locked }?.let { value -> Text(value,
                        Modifier.widthIn(max = 190.dp).background(Color.Black.copy(alpha = .76f), MaterialTheme.shapes.large)
                            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("player-gesture-feedback"), color = Color.White,
                        style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    )
    if (pictureSizeOpen) {
        ModalBottomSheet(onDismissRequest = { pictureSizeOpen = false; reveal() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ImmersivePlayerSheetWindow()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PictureSizeChoices(pictureMode) { mode -> onPictureMode(mode); pictureSizeOpen = false; reveal() }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (optionsOpen) {
        ModalBottomSheet(onDismissRequest = { optionsOpen = false; reveal() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ImmersivePlayerSheetWindow()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Playback options", style = MaterialTheme.typography.titleLarge)
                if (secondaryActionsInOptions) {
                    TextButton(enabled = snapshot.phase != PlaybackPhase.ERROR && snapshot.phase != PlaybackPhase.ENDED,
                        onClick = { preview = null; shortFeedback = null; gestureActive = false; dragPosition = null; locked = true; optionsOpen = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Lock controls" }) { Text("Lock controls") }
                }
                TrackChooser("Audio", audio, snapshot.audioTrackId, "Automatic", onAudio)
                TrackChooser("Subtitles", subtitles, snapshot.subtitleTrackId, "Off", onSubtitle)
                if (canChangeSpeed) ValueChooser("Speed: ${speedLabel(snapshot.playbackSpeed)}",
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f), snapshot.playbackSpeed, ::speedLabel, onSpeed)
                PictureSizeChoices(pictureMode, onPictureMode)
                TextButton(onClick = { optionsOpen = false; reveal() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** All three modes are immediately visible; changing size never recreates the player. */
@Composable
private fun PictureSizeChoices(selected: PlayerPictureMode, onSelected: (PlayerPictureMode) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text("Picture size", style = MaterialTheme.typography.titleLarge)
        PlayerPictureMode.entries.forEach { mode ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onSelected(mode) })
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp)) {
                    if (mode == selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    Text(mode.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TrackChooser(label: String, options: List<TrackOption>, selected: String?, defaultLabel: String, onSelected: (String?) -> Unit) {
    if (options.isEmpty()) return
    ValueChooser("$label: ${options.firstOrNull { it.id == selected }?.label ?: defaultLabel}",
        listOf<TrackOption?>(null) + options, options.firstOrNull { it.id == selected },
        { it?.label ?: defaultLabel }, { onSelected(it?.id) })
}

@Composable
private fun <T> ValueChooser(label: String, options: List<T>, selectedValue: T, optionLabel: (T) -> String, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "Choices expanded" else "Choices collapsed" }) { Text(label) }
        if (expanded) Column(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = option == selectedValue, role = Role.RadioButton,
                        onClick = { onSelected(option); expanded = false }).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp)) {
                        if (option == selectedValue) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun playerControlGradient(top: Boolean = false): Brush = Brush.verticalGradient(
    if (top) listOf(Color.Black.copy(alpha = .68f), Color.Transparent)
    else listOf(Color.Transparent, Color.Black.copy(alpha = .76f)))

/** A paused buffering stream must release the screen just as a paused ready stream does. */
internal fun shouldKeepVideoAwake(playWhenReady: Boolean, playbackState: Int, hasError: Boolean): Boolean =
    playWhenReady && !hasError && playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)

@UnstableApi
internal class PlayerScreenOnBinding(private val view: PlayerView, private val player: Player) : AutoCloseable {
    private var closed = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
        override fun onPlaybackStateChanged(playbackState: Int) = update()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()
        override fun onPlayerError(error: PlaybackException) = update()
    }
    init { player.addListener(listener); update() }
    private fun update() {
        view.keepScreenOn = !closed && shouldKeepVideoAwake(player.playWhenReady, player.playbackState, player.playerError != null)
    }
    override fun close() {
        if (closed) return
        closed = true
        player.removeListener(listener)
        view.keepScreenOn = false
    }
}

@Composable
private fun rememberAccessibilityActive(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

private tailrec fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.playerActivity() else null
    else -> null
}

internal fun gestureSeekPreview(positionMs: Long, durationMs: Long, deltaX: Float, widthPx: Float): Long {
    val base = positionMs.coerceAtLeast(0).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
    if (durationMs <= 0 || !deltaX.isFinite() || !widthPx.isFinite() || widthPx <= 0) return base
    val span = (durationMs.toDouble() * .25).coerceAtMost(120_000.0)
    return (base + deltaX.toDouble() / widthPx * span).toLong().coerceIn(0L, durationMs)
}

internal fun gestureLevelPreview(base: Float, deltaY: Float, heightPx: Float, min: Float = 0f): Float {
    val minimum = if (min.isFinite()) min.coerceIn(0f, 1f) else 0f
    val safeBase = (base.takeIf { it.isFinite() } ?: .5f).coerceIn(minimum, 1f)
    return if (!deltaY.isFinite() || !heightPx.isFinite() || heightPx <= 0f) safeBase
        else (safeBase - deltaY / heightPx).coerceIn(minimum, 1f)
}

internal fun speedLabel(speed: Float): String = "${(speed.takeIf { it.isFinite() && it > 0 } ?: 1f).toBigDecimal().stripTrailingZeros().toPlainString()}×"

internal fun timeLabel(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1_000
    return if (total >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3_600, total / 60 % 60, total % 60)
    else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

internal fun spokenTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1_000
    val hours = total / 3_600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return listOfNotNull(hours.takeIf { it > 0 }?.let { "$it ${if (it == 1L) "hour" else "hours"}" },
        minutes.takeIf { it > 0 }?.let { "$it ${if (it == 1L) "minute" else "minutes"}" },
        "$seconds ${if (seconds == 1L) "second" else "seconds"}").joinToString(" ")
}

internal fun shouldAutoHideControls(overlay: Boolean, phase: PlaybackPhase, optionsOpen: Boolean, scrubbing: Boolean, accessibilityEnabled: Boolean): Boolean =
    overlay && phase == PlaybackPhase.PLAYING && !optionsOpen && !scrubbing && !accessibilityEnabled
