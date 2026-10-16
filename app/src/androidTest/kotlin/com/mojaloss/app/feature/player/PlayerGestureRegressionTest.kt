package com.mojaloss.app.feature.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.BuildConfig
import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.PlaybackSnapshot
import com.mojaloss.core.model.TrackOption
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Native Compose input only. Local video Box; no player, stream, provider, or OS level writes. */
class PlayerGestureRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pictureSizeSheetKeepsOneRetainedVideoViewportAndControlsInsideIt() {
        val instances = mutableSetOf<Any>()
        compose.setContent {
            Fixture(onVideoInstance = { instances += it })
        }
        assertControlsInsideVideo()
        capture("normal-controls-inside-video-local-fixture")
        compose.onNodeWithContentDescription("Picture size: Fit").performClick()
        compose.onNodeWithText("Crop").assertIsDisplayed()
        compose.onNodeWithText("Stretch").assertIsDisplayed()
        capture("picture-size-fit-crop-stretch-local-fixture", pictureSheet = true)
        Espresso.pressBack()
        assertControlsInsideVideo()
        compose.runOnIdle { assertEquals(1, instances.size) }
        capture("fullscreen-controls-inside-video-local-fixture")
    }

    @Test fun shortViewportAtTwoHundredPercentFontKeepsDistinctReachableControlTargets() {
        var playPauseCalls = 0
        var exits = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(360.dp, 180.dp)) {
                    Fixture(canSeek = true, onPlayPause = { playPauseCalls++ }, onBack = { exits++ })
                }
            }
        }
        assertControlsInsideVideo()
        capture("short-viewport-200percent-distinct-controls-local-fixture")
        val video = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        val pause = compose.onNodeWithContentDescription("Pause").assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
        val topActions = listOf("Back to title", "Lock controls", "Picture size: Fit", "Playback options")
        recordBounds("short-viewport-200percent", topActions + "Pause", includeSeek = true)
        topActions.forEach { description ->
            val action = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$description touch=$action must remain inside video=$video", containsWithPixelTolerance(video, action))
            assertTrue("Pause must not overlap $description in the short viewport: pause=$pause action=$action",
                !overlapsBeyondPixelTolerance(pause, action))
        }
        val seekNode = compose.onNodeWithTag("playback-seek").assertIsDisplayed().fetchSemanticsNode()
        val seek = seekNode.touchBoundsInRoot
        assertTrue("Seek target must remain inside the short viewport: video=$video visual=${seekNode.boundsInRoot} touch=$seek", containsWithPixelTolerance(video, seek))
        assertTrue("Pause must not overlap the seek touch target: pause=$pause seek=$seek",
            !overlapsBeyondPixelTolerance(pause, seek))
        assertTrue("Pause must remain inside the short viewport", containsWithPixelTolerance(video, pause))
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle { assertEquals(1, playPauseCalls) }
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Picture size").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Lock controls").performClick()
        compose.onNodeWithContentDescription("Unlock controls").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Picture size: Fit").performClick()
        compose.onNodeWithText("Stretch").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to title").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun doubleTapUsesActualLeftAndRightVideoCoordinatesAndSeekCapability() {
        val seeks = mutableListOf<Long>()
        val seekable = mutableStateOf(true)
        compose.setContent { Fixture(canSeek = seekable.value, onSeek = { seeks += it }) }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            doubleClick(Offset(width * .15f, height * .30f))
        }
        compose.runOnIdle { assertEquals(listOf(51_000L), seeks) }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            doubleClick(Offset(width * .85f, height * .30f))
        }
        compose.runOnIdle {
            assertEquals(listOf(51_000L, 71_000L), seeks)
            seekable.value = false
        }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            doubleClick(Offset(width * .85f, height * .30f))
        }
        compose.runOnIdle { assertEquals(2, seeks.size) }
    }

    @Test fun horizontalGesturePreviewsWithoutSeekingUntilRelease() {
        val seeks = mutableListOf<Long>()
        compose.setContent { Fixture(canSeek = true, onSeek = { seeks += it }) }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .15f, height * .30f))
            moveTo(Offset(width * .70f, height * .30f))
        }
        compose.onNodeWithTag("player-gesture-feedback").assertIsDisplayed()
        compose.runOnIdle { assertTrue("A drag preview must not commit playback progress", seeks.isEmpty()) }
        capture("horizontal-seek-preview-local-fixture")
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertTrue(seeks.single() > 61_000L)
            assertTrue(seeks.single() <= 600_000L)
        }
    }

    @Test fun resizingActualVideoDuringDragCancelsPreviewWithoutCommittingAnyDeviceOrSeekChange() {
        val viewportWidth = mutableStateOf(320.dp)
        val viewportHeight = mutableStateOf(440.dp)
        val seeks = mutableListOf<Long>()
        val brightnessWrites = mutableListOf<Float>()
        val volumeWrites = mutableListOf<Float>()
        compose.setContent {
            Box(Modifier.size(viewportWidth.value, viewportHeight.value)) {
                Fixture(canSeek = true, onSeek = { seeks += it }, brightness = .50f, volume = .50f,
                    onBrightness = { brightnessWrites += it }, onVolume = { volumeWrites += it })
            }
        }
        val original = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .15f, height * .30f))
            moveTo(Offset(width * .70f, height * .30f))
        }
        compose.onNodeWithTag("player-gesture-feedback").assertIsDisplayed()
        compose.runOnIdle { viewportWidth.value = 240.dp; viewportHeight.value = 360.dp }
        val resized = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        assertTrue("The fixture must change actual video geometry", original.width != resized.width && original.height != resized.height)
        compose.onNodeWithTag("player-gesture-feedback").assertDoesNotExist()
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("Resize must cancel the held seek preview", seeks.isEmpty())
            assertTrue(brightnessWrites.isEmpty()); assertTrue(volumeWrites.isEmpty())
        }
        capture("resized-drag-cancelled-local-fixture")
        // Cancellation must not permanently disable subsequent gestures in the new viewport.
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .15f, height * .30f))
            moveTo(Offset(width * .70f, height * .30f))
            up()
        }
        compose.runOnIdle { assertEquals(1, seeks.size); assertTrue(brightnessWrites.isEmpty()); assertTrue(volumeWrites.isEmpty()) }
    }

    @Test fun narrowWindowKeepsBackPauseOptionsDistinctAndMovesPictureSizeAndLockIntoTheSheet() {
        var playPauseCalls = 0
        compose.setContent {
            Box(Modifier.size(180.dp, 400.dp)) {
                Fixture(canSeek = true, onPlayPause = { playPauseCalls++ })
            }
        }
        assertControlsInsideVideo()
        capture("narrow-window-distinct-controls-local-fixture")
        val video = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        val actions = listOf("Back to title", "Pause", "Playback options", "Back 10 seconds", "Forward 10 seconds")
        recordBounds("narrow-window", actions, includeSeek = true)
        val bounds = actions.map { description ->
            val node = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
            node.touchBoundsInRoot.also {
                assertTrue("$description touch=$it must stay inside video=$video", containsWithPixelTolerance(video, it))
                assertTrue("$description must keep a full touch target: visual=${node.boundsInRoot} touch=$it density=${compose.density.density}", it.width / compose.density.density >= 47f &&
                    it.height / compose.density.density >= 47f)
            }
        }
        for (first in bounds.indices) for (second in first + 1 until bounds.size)
            assertTrue("${actions[first]} must not overlap ${actions[second]} in the narrow video",
                !overlapsBeyondPixelTolerance(bounds[first], bounds[second]))
        compose.onNodeWithContentDescription("Picture size: Fit").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lock controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, playPauseCalls) }
        compose.onNodeWithContentDescription("Playback options").performTouchInput { click() }
        compose.onNodeWithText("Fit").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Crop").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stretch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithContentDescription("Lock controls").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Unlock controls").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun verticalGesturesPreviewThenCommitOnlyTheirOwnSideOnce() {
        val brightnessWrites = mutableListOf<Float>()
        val volumeWrites = mutableListOf<Float>()
        val seeks = mutableListOf<Long>()
        compose.setContent {
            Fixture(canSeek = true, onSeek = { seeks += it }, brightness = .40f, volume = .50f,
                onBrightness = { brightnessWrites += it }, onVolume = { volumeWrites += it })
        }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .15f, height * .35f))
            moveTo(Offset(width * .15f, height * .15f))
        }
        compose.onNodeWithTag("player-gesture-feedback").assertIsDisplayed()
        compose.runOnIdle { assertTrue(brightnessWrites.isEmpty()); assertTrue(volumeWrites.isEmpty()) }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, brightnessWrites.size)
            assertTrue(brightnessWrites.single() > .40f && brightnessWrites.single() <= 1f)
            assertTrue(volumeWrites.isEmpty())
        }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .85f, height * .35f))
            moveTo(Offset(width * .85f, height * .15f))
        }
        compose.onNodeWithTag("player-gesture-feedback").assertIsDisplayed()
        compose.runOnIdle { assertTrue(volumeWrites.isEmpty()) }
        capture("right-volume-preview-local-fixture")
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, brightnessWrites.size)
            assertEquals(1, volumeWrites.size)
            assertTrue(volumeWrites.single() > .50f && volumeWrites.single() <= 1f)
            assertTrue("A vertical gesture must not turn into a seek", seeks.isEmpty())
        }
    }

    @Test fun lockRejectsGesturesAndSystemBackUnlocksBeforeLeavingTheTitle() {
        var exits = 0
        val seeks = mutableListOf<Long>()
        val volumeWrites = mutableListOf<Float>()
        compose.setContent {
            Fixture(canSeek = true, onBack = { exits++ }, onSeek = { seeks += it }, volume = .50f,
                onVolume = { volumeWrites += it })
        }
        compose.onNodeWithContentDescription("Lock controls").performClick()
        compose.onNodeWithContentDescription("Unlock controls").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            doubleClick(Offset(width * .85f, height * .30f))
        }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .85f, height * .35f))
            moveTo(Offset(width * .85f, height * .15f))
            up()
        }
        compose.runOnIdle { assertTrue(seeks.isEmpty()); assertTrue(volumeWrites.isEmpty()); assertEquals(0, exits) }
        capture("locked-controls-local-fixture")
        Espresso.pressBack()
        compose.onNodeWithContentDescription("Picture size: Fit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, exits) }
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun optionsSelectRealSpeedAndDisplayCallbacksWithoutDecorativeQuality() {
        val current = mutableStateOf(playingSnapshot())
        val picture = mutableStateOf(PlayerPictureMode.FIT)
        val speedChanges = mutableListOf<Float>()
        val pictureChanges = mutableListOf<PlayerPictureMode>()
        compose.setContent {
            Fixture(snapshot = current.value, canChangeSpeed = true,
                audio = listOf(TrackOption("en", "English", "en")),
                subtitles = listOf(TrackOption("es", "Spanish", "es")),
                onSpeed = { speedChanges += it; current.value = current.value.copy(playbackSpeed = it) },
                pictureMode = picture.value, onPictureMode = { pictureChanges += it; picture.value = it })
        }
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Audio: Automatic").assertIsDisplayed()
        compose.onNodeWithText("Subtitles: Off").assertIsDisplayed()
        compose.onNodeWithText("Speed: 1×").performClick()
        compose.onNodeWithText("1×").performScrollTo().assertIsSelected()
        compose.onNodeWithText("1.5×").performScrollTo().performClick()
        compose.onNodeWithText("Speed: 1.5×").assertIsDisplayed()
        compose.onNodeWithText("Fit").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Crop").performScrollTo().performClick()
        compose.onNodeWithText("Crop").assertIsSelected()
        compose.onNodeWithText("Stretch").performScrollTo().performClick()
        compose.onNodeWithText("Stretch").assertIsSelected()
        compose.onNodeWithText("Quality").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(1.5f), speedChanges); assertEquals(listOf(PlayerPictureMode.CROP, PlayerPictureMode.STRETCH), pictureChanges) }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("LOCAL VIDEO STRETCH FIXTURE").assertExists()
        capture("fill-video-callback-local-fixture")
    }

    @Test fun lockedPlaybackUnlocksToExposeErrorRecoveryAndCompletedReplay() {
        val current = mutableStateOf(playingSnapshot())
        var replayRequests = 0
        compose.setContent {
            Fixture(snapshot = current.value, canRetry = true, accessibilityEnabled = false,
                onPlayPause = { replayRequests++ })
        }
        compose.onNodeWithContentDescription("Lock controls").performClick()
        compose.onNodeWithContentDescription("Unlock controls").assertIsDisplayed()
        compose.runOnIdle {
            current.value = current.value.copy(phase = PlaybackPhase.ERROR, error = "Locked fixture interrupted")
        }
        compose.onNodeWithText("Locked fixture interrupted").assertIsDisplayed()
        compose.onNodeWithText("Retry once").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unlock controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lock controls").assertIsNotEnabled()
        compose.runOnIdle { current.value = playingSnapshot() }
        compose.onNodeWithContentDescription("Lock controls").performClick()
        compose.runOnIdle { current.value = current.value.copy(phase = PlaybackPhase.ENDED) }
        compose.onNodeWithContentDescription("Unlock controls").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play again").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Lock controls").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, replayRequests) }
        capture("locked-terminal-recovery-local-fixture")
    }

    @Test fun defaultPlayingControlsWaitTwelveSecondsAndActualSingleTapsToggleThem() {
        compose.setContent { Fixture(accessibilityEnabled = false) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(11_000)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            click(Offset(width * .15f, height * .30f))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            click(Offset(width * .15f, height * .30f))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
    }

    @Test fun accessibilityPausedAndErrorStatesDoNotAutoHideRecoveryOrControls() {
        val current = mutableStateOf(playingSnapshot())
        val accessible = mutableStateOf(true)
        compose.setContent { Fixture(snapshot = current.value, accessibilityEnabled = accessible.value, canRetry = true) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(13_000)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle { accessible.value = false; current.value = current.value.copy(phase = PlaybackPhase.PAUSED) }
        compose.mainClock.advanceTimeBy(13_000)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.runOnIdle {
            current.value = current.value.copy(phase = PlaybackPhase.ERROR, error = "Local fixture interruption")
        }
        compose.mainClock.advanceTimeBy(13_000)
        compose.onNodeWithText("Local fixture interruption").assertIsDisplayed()
        compose.onNodeWithText("Retry once").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to title").assertIsDisplayed()
        capture("persistent-error-recovery-local-fixture")
    }

    @Test fun openOptionsAndActiveDragSuppressAutoHideThenReleaseRearmsIt() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            Fixture(canSeek = true, onSeek = { seeks += it }, canChangeSpeed = true,
                accessibilityEnabled = false, hideAfterMillis = 1_200)
        }
        compose.onNodeWithContentDescription("Playback options").performClick()
        // Settle and scroll the actual sheet before freezing time. The recovery action must
        // remain reachable rather than relying on its initial position or opening animation.
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("player-gesture-plane").performTouchInput {
            down(Offset(width * .15f, height * .30f))
            moveTo(Offset(width * .70f, height * .30f))
        }
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithTag("player-gesture-feedback").assertIsDisplayed()
        compose.onNodeWithTag("playback-controls").assertIsDisplayed()
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, seeks.size) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
    }

    @Composable
    private fun Fixture(
        snapshot: PlaybackSnapshot = playingSnapshot(),
        audio: List<TrackOption> = emptyList(), subtitles: List<TrackOption> = emptyList(),
        canSeek: Boolean = false, canRetry: Boolean = false,
        onBack: () -> Unit = {}, onPlayPause: () -> Unit = {}, onSeek: (Long) -> Unit = {},
        canChangeSpeed: Boolean = false, onSpeed: (Float) -> Unit = {},
        pictureMode: PlayerPictureMode = PlayerPictureMode.FIT, onPictureMode: (PlayerPictureMode) -> Unit = {},
        brightness: Float? = null, onBrightness: (Float) -> Unit = {},
        volume: Float? = null, onVolume: (Float) -> Unit = {},
        accessibilityEnabled: Boolean = true, hideAfterMillis: Long = 12_000,
        onVideoInstance: (Any) -> Unit = {},
    ) {
        MojaLossTheme {
            MainPlayerContent("Local gesture fixture", snapshot, audio = audio, subtitles = subtitles,
                canSeek = canSeek, canRetry = canRetry, onBack = onBack, onPlayPause = onPlayPause,
                onSeek = onSeek, onRetry = {}, fold = null, accessibilityEnabled = accessibilityEnabled,
                canChangeSpeed = canChangeSpeed, onSpeed = onSpeed, pictureMode = pictureMode,
                onPictureMode = onPictureMode, brightness = brightness, onBrightness = onBrightness,
                volume = volume, onVolume = onVolume, hideAfterMillis = hideAfterMillis,
                video = {
                    val instance = remember { Any() }
                    SideEffect { onVideoInstance(instance) }
                    Box(Modifier.fillMaxSize().background(Color(0xFF182620))) {
                        Text("LOCAL VIDEO ${pictureMode.name} FIXTURE",
                            Modifier.align(Alignment.Center), color = Color(0xFFD7E4D9))
                    }
                })
        }
    }

    private fun assertControlsInsideVideo() {
        val video = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("playback-controls").fetchSemanticsNode().boundsInRoot
        assertTrue("Controls must be contained by the actual video viewport: video=$video controls=$controls",
            containsWithPixelTolerance(video, controls))
        assertTrue(video.width > 0 && video.height > 0 && controls.width > 0 && controls.height > 0)
    }

    private fun containsWithPixelTolerance(parent: Rect, child: Rect) =
        child.left >= parent.left - 1f && child.top >= parent.top - 1f &&
            child.right <= parent.right + 1f && child.bottom <= parent.bottom + 1f

    private fun overlapsBeyondPixelTolerance(first: Rect, second: Rect) =
        first.left < second.right - 1f && first.right > second.left + 1f &&
            first.top < second.bottom - 1f && first.bottom > second.top + 1f

    private fun playingSnapshot() = PlaybackSnapshot(mediaId = "local-gesture-fixture", phase = PlaybackPhase.PLAYING,
        positionMs = 61_000L, durationMs = 600_000L)

    private fun recordBounds(label: String, actions: List<String>, includeSeek: Boolean) {
        val video = compose.onNodeWithTag("main-video").fetchSemanticsNode()
        val records = actions.map { description ->
            val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            "$description visual=${node.boundsInRoot} touch=${node.touchBoundsInRoot}"
        }.toMutableList()
        if (includeSeek) {
            val seek = compose.onNodeWithTag("playback-seek").fetchSemanticsNode()
            records += "Playback position visual=${seek.boundsInRoot} touch=${seek.touchBoundsInRoot}"
        }
        val evidence = "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Native Compose local fixture geometry; no stream or physical-device proof.\n" +
            "density=${compose.density.density}\nvideo=${video.boundsInRoot}\n" + records.joinToString("\n") + "\n"
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "ux-${BuildConfig.VERSION_NAME}-gesture-fixtures").apply { mkdirs() }
        File(directory, "$label.bounds.txt").writeText(evidence)
        android.util.Log.i("MojaLossFixtureBounds", evidence)
    }

    private fun capture(label: String, pictureSheet: Boolean = false) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "ux-${BuildConfig.VERSION_NAME}-gesture-fixtures").apply { mkdirs() }
        val method = Throwable().stackTrace.firstOrNull {
            it.className == PlayerGestureRegressionTest::class.java.name && it.methodName != "capture"
        }?.methodName ?: "unknown"
        val provenance = "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "class=${PlayerGestureRegressionTest::class.java.name}\nmethod=$method\n" +
            "Native Compose local video fixture; no stream, provider, OS brightness/volume, or physical Fold proof.\n"
        val status = File(directory, "$label.capture-status.txt")
        try {
            val target = if (pictureSheet) compose.onNode(isRoot() and hasAnyDescendant(hasText("Picture size")), useUnmergedTree = true)
                else compose.onRoot(useUnmergedTree = true)
            val bitmap = target.captureToImage().asAndroidBitmap()
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step (bitmap.height / 64).coerceAtLeast(1))
                for (x in 0 until bitmap.width step (bitmap.width / 64).coerceAtLeast(1)) colors += bitmap.getPixel(x, y)
            if (colors.size < 8) {
                status.writeText(provenance + "capture_method=Compose.onRoot.captureToImage\n" +
                    "status=BLOCKED_UNIFORM_COMPOSE_BUFFER\nsampled_colors=${colors.size}\n")
            } else {
                val saved = File(directory, "$label.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                status.writeText(provenance + "capture_method=Compose.onRoot.captureToImage\n" +
                    "status=${if (saved) "CAPTURED_NONBLANK_NATIVE_FIXTURE" else "BLOCKED_PNG_WRITE"}\n" +
                    "width=${bitmap.width}\nheight=${bitmap.height}\nsampled_colors=${colors.size}\n")
            }
        } catch (failure: Exception) {
            status.writeText(provenance + "capture_method=Compose.onRoot.captureToImage\n" +
                "status=BLOCKED_CAPTURE_UNAVAILABLE_OR_AMBIGUOUS_ROOT\nfailure_type=${failure.javaClass.simpleName}\n")
        }
    }
}
