package com.mojaloss.app.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.BuildConfig
import com.mojaloss.app.adaptive.FoldGeometry
import com.mojaloss.app.adaptive.FoldOrientation
import com.mojaloss.app.adaptive.PaneBounds
import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.PlaybackSnapshot
import com.mojaloss.core.model.TrackOption
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Compose interactions, local renderers only: no PlayerView, WebView, YouTube or provider IO. */
class PlayerPresentationRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortViewportKeepsTrailerRecoveryReachableAndDoesNotCreateAnEmbed() {
        var rendererCalls = 0
        var externalCalls = 0
        compose.setContent {
            MojaLossTheme {
                Box(Modifier.size(360.dp, 280.dp)) {
                    TrailerContent(true, TrailerLoadState.Loading, {}, { externalCalls++ }, {}, renderer = {
                        SideEffect { rendererCalls++ }
                        Text("LOCAL RENDERER ONLY", it)
                    })
                }
            }
        }
        compose.onNodeWithText("Open on YouTube").assertIsDisplayed().performClick()
        compose.onNodeWithText("Loading trailer…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, rendererCalls); assertEquals(1, externalCalls) }
        capture("trailer-short-viewport-local-fixture")
    }

    @Test fun largeTextCanScrollToFallbackWithoutCreatingUndersizedEmbed() {
        var externalCalls = 0
        var rendererCalls = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MojaLossTheme {
                    Box(Modifier.size(360.dp, 280.dp)) {
                        TrailerContent(true, TrailerLoadState.TimedOut, {}, { externalCalls++ }, {}, renderer = {
                            SideEffect { rendererCalls++ }
                            Text("LOCAL RENDERER ONLY", it)
                        })
                    }
                }
            }
        }
        compose.onNodeWithText("Open on YouTube").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, rendererCalls); assertEquals(1, externalCalls) }
        capture("trailer-200percent-font-local-fixture")
    }

    @Test fun lateReadyRemovesTimeoutCopyInTheActualTrailerPresentation() {
        val state = mutableStateOf<TrailerLoadState>(TrailerLoadState.TimedOut)
        var rendererCalls = 0
        compose.setContent {
            MojaLossTheme {
                Box(Modifier.size(360.dp, 600.dp)) {
                    TrailerContent(true, state.value, {}, {}, { state.value = TrailerLoadState.Loading }, renderer = {
                        SideEffect { rendererCalls++ }
                        Box(it.background(Color.Black)) { Text("LOCAL TRAILER FIXTURE", color = Color.White) }
                    })
                }
            }
        }
        compose.onNodeWithText("The trailer is taking longer to load. You can open it on YouTube.").assertExists()
        compose.runOnIdle { state.value = trailerReady(state.value) }
        compose.onNodeWithText("The trailer is taking longer to load. You can open it on YouTube.").assertDoesNotExist()
        compose.onNodeWithText("Retry trailer").assertDoesNotExist()
        compose.onNodeWithText("Open on YouTube").assertIsDisplayed()
        compose.runOnIdle { assertTrue(rendererCalls > 0) }
        capture("trailer-late-ready-local-fixture")
    }

    @Test fun playbackStartsImmersiveAndSystemBackLeavesTheTitleWithoutAnExtraFullscreenStep() {
        var exits = 0
        val instances = mutableSetOf<Any>()
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local video fixture", snapshot(), onBack = { exits++ }, onPlayPause = {},
                    onSeek = {}, onRetry = {}, fold = null, accessibilityEnabled = true,
                    video = {
                        val instance = remember { Any() }
                        SideEffect { instances += instance }
                        Box(Modifier.fillMaxSize().background(Color.Black)) { Text("LOCAL VIDEO FIXTURE", color = Color.White) }
                    })
            }
        }
        compose.onNodeWithContentDescription("Picture size: Fit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Full screen").assertDoesNotExist()
        capture("player-fullscreen-retained-local-fixture")
        compose.runOnIdle { assertEquals(0, exits); assertEquals(1, instances.size) }
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(1, exits); assertEquals(1, instances.size) }
    }

    @Test fun optionsAreDisclosedOnDemandAndSeekUsesMeaningfulTime() {
        val selected = mutableListOf<String?>()
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local video fixture", snapshot(),
                    audio = listOf(TrackOption("en", "English", "en")),
                    subtitles = listOf(TrackOption("es", "Spanish", "es")),
                    canSeek = true, onBack = {}, onPlayPause = {}, onSeek = { seeks += it }, onRetry = {},
                    onSubtitle = { selected += it }, fold = null, accessibilityEnabled = true,
                    video = { Box(Modifier.fillMaxSize().background(Color.Black)) })
            }
        }
        compose.onNodeWithText("Audio: Automatic").assertDoesNotExist()
        compose.onNodeWithContentDescription("Playback position").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 minute 1 second of 10 minutes 0 seconds"))
        compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
        compose.runOnIdle { assertEquals(listOf(71_000L), seeks) }
        compose.onNodeWithContentDescription("Playback options").performClick()
        compose.onNodeWithText("Subtitles: Off").assertIsDisplayed().performClick()
        compose.onNodeWithText("Spanish").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("es"), selected) }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Audio: Automatic").assertDoesNotExist()
    }

    @Test fun playingFullscreenHidesControlsTemporarilyAndVideoTapRevealsThem() {
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local video fixture", snapshot(), onBack = {}, onPlayPause = {},
                    onSeek = {}, onRetry = {}, fold = null, accessibilityEnabled = false,
                    video = { Box(Modifier.fillMaxSize().background(Color.Black)) })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(13_000)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { click(Offset(width * .15f, height * .30f)) }
        // A single tap waits for the platform double-tap interval before it toggles controls.
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        capture("player-fullscreen-revealed-local-fixture")
    }

    @Test fun reportedFoldOutsideViewportUsesActualFullscreenOverlayAndAutoHide() {
        val offscreenFold = FoldGeometry(PaneBounds(10_000, 0, 10_020, 10_000), FoldOrientation.VERTICAL,
            separating = true, halfOpened = true)
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local offscreen fold fixture", snapshot(), onBack = {}, onPlayPause = {},
                    onSeek = {}, onRetry = {}, fold = offscreenFold, accessibilityEnabled = false,
                    video = { Box(Modifier.fillMaxSize().background(Color.Black)) })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(13_000)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
        compose.onNodeWithTag("player-gesture-plane").performTouchInput { click(Offset(width * .15f, height * .30f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun validatedTabletopKeepsControlsInsideTheVideoAndRetainsTwelveSecondTimeout() {
        val tabletop = FoldGeometry(PaneBounds(0, 240, 10_000, 260), FoldOrientation.HORIZONTAL,
            separating = true, halfOpened = true)
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local tabletop fixture", snapshot(), onBack = {}, onPlayPause = {},
                    onSeek = {}, onRetry = {}, fold = tabletop, accessibilityEnabled = false,
                    video = { Box(Modifier.fillMaxSize().background(Color.Black)) })
            }
        }
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to title").assertIsDisplayed()
        val video = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("playback-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(controls.left >= video.left - 1 && controls.top >= video.top - 1 &&
            controls.right <= video.right + 1 && controls.bottom <= video.bottom + 1)
        compose.mainClock.advanceTimeBy(8_500)
        compose.onNodeWithTag("playback-controls").assertDoesNotExist()
    }

    @Test fun seekDragShowsProspectiveTimeAndCommitsOnlyOnRelease() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local seek fixture", snapshot(), canSeek = true,
                    onBack = {}, onPlayPause = {}, onSeek = { seeks += it }, onRetry = {}, fold = null,
                    accessibilityEnabled = true, video = { Box(Modifier.fillMaxSize().background(Color.Black)) })
            }
        }
        compose.onNodeWithTag("playback-seek").performTouchInput {
            down(Offset(width * .25f, height / 2f))
            moveTo(Offset(width * .70f, height / 2f))
        }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        val preview = compose.onNodeWithTag("playback-time").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(preview != "1:01 / 10:00")
        capture("player-seek-prospective-local-fixture")
        compose.onNodeWithTag("playback-seek").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, seeks.size); assertTrue(seeks.single() > 61_000L) }
    }

    @Test fun playbackErrorDoesNotDisappearWhenImmersiveControlsWouldOtherwiseHide() {
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Local error fixture", snapshot().copy(phase = PlaybackPhase.ERROR, error = "Fixture connection interrupted"),
                    canRetry = true, onBack = {}, onPlayPause = {}, onSeek = {}, onRetry = {}, fold = null,
                    video = { Box(Modifier.fillMaxSize().background(Color.Black)) })
            }
        }
        compose.mainClock.advanceTimeBy(13_000)
        compose.onNodeWithText("Fixture connection interrupted").assertIsDisplayed()
        compose.onNodeWithText("Retry once").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to title").assertIsDisplayed()
        capture("player-fullscreen-error-local-fixture")
    }

    private fun snapshot() = PlaybackSnapshot(mediaId = "fixture", phase = PlaybackPhase.PLAYING,
        positionMs = 61_000L, durationMs = 600_000L)

    private fun capture(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "ux-${BuildConfig.VERSION_NAME}-native-fixtures").apply { mkdirs() }
        val method = Throwable().stackTrace.firstOrNull {
            it.className == PlayerPresentationRegressionTest::class.java.name && it.methodName != "capture"
        }?.methodName ?: "unknown"
        val provenance = "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "class=${PlayerPresentationRegressionTest::class.java.name}\nmethod=$method\n" +
            "Illustrative native Compose fixture; no live media, YouTube, service calls or physical Fold proof.\n"
        // Preserve the earlier device-buffer files and explicitly identify their missing pixel proof.
        val deviceFile = File(directory, "$label.png")
        if (deviceFile.exists()) {
            val old = BitmapFactory.decodeFile(deviceFile.absolutePath)
            val colors = old?.let(::sampledColors) ?: emptySet()
            File(directory, "$label.capture-status.txt").writeText(provenance +
                "capture_method=UiAutomation.takeScreenshot\n" +
                if (colors.size < 8) "status=BLOCKED_UNIFORM_DEVICE_BUFFER\nsampled_colors=${colors.size}\n" else
                    "status=PREVIOUS_DEVICE_BUFFER_NOT_CURRENT_CAPTURE\nsampled_colors=${colors.size}\n")
            old?.recycle()
        }
        val statusFile = File(directory, "$label.compose.capture-status.txt")
        try {
            // Every capture call is made outside options-sheet/dialog composition; ambiguity blocks capture.
            val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            val colors = sampledColors(bitmap)
            if (colors.size < 8) {
                statusFile.writeText(provenance + "capture_method=Compose.onRoot.captureToImage\n" +
                    "status=BLOCKED_UNIFORM_COMPOSE_BUFFER\nsampled_colors=${colors.size}\n")
            } else {
                val imageFile = File(directory, "$label.compose.png")
                val saved = imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                statusFile.writeText(provenance + "capture_method=Compose.onRoot.captureToImage\n" +
                    "status=${if (saved) "CAPTURED_NONBLANK_NATIVE_FIXTURE" else "BLOCKED_PNG_WRITE"}\n" +
                    "width=${bitmap.width}\nheight=${bitmap.height}\nsampled_colors=${colors.size}\n")
            }
        } catch (failure: Exception) {
            statusFile.writeText(provenance + "capture_method=Compose.onRoot.captureToImage\n" +
                "status=BLOCKED_CAPTURE_UNAVAILABLE_OR_AMBIGUOUS_ROOT\nfailure_type=${failure.javaClass.simpleName}\n")
        }
    }

    private fun sampledColors(bitmap: Bitmap): Set<Int> {
        val colors = mutableSetOf<Int>()
        val stepX = (bitmap.width / 64).coerceAtLeast(1)
        val stepY = (bitmap.height / 64).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) for (x in 0 until bitmap.width step stepX)
            colors += bitmap.getPixel(x, y)
        return colors
    }
}
