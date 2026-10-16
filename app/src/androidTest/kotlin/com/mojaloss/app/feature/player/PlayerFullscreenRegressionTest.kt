package com.mojaloss.app.feature.player

import android.os.Build
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mojaloss.core.model.PlaybackPhase
import com.mojaloss.core.model.PlaybackSnapshot
import com.mojaloss.core.ui.MojaLossTheme
import com.mojaloss.app.BuildConfig
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.espresso.Espresso

/** Native window behavior with a local picture: no stream, credentials or live provider. */
class PlayerFullscreenRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playbackUsesFullWindowCutoutModeAndRestoresWindowAttributesOnExit() {
        val mounted = mutableStateOf(true)
        val window = compose.activity.window
        var originalFlags = 0
        var originalCutout = 0
        var originalBehavior = 0
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            originalBehavior = controller.systemBarsBehavior
            if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            originalFlags = window.attributes.flags
            originalCutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
        }
        compose.setContent {
            MojaLossTheme {
                if (mounted.value) MainPlayerContent("Full-window local fixture", snapshot(), onBack = { mounted.value = false },
                    onPlayPause = {}, onSeek = {}, onRetry = {}, fold = null, accessibilityEnabled = true,
                    video = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
                else Text("Title page fixture")
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("main-video").fetchSemanticsNode().boundsInRoot
        assertEquals(root.left, video.left, 1f); assertEquals(root.top, video.top, 1f)
        assertEquals(root.right, video.right, 1f); assertEquals(root.bottom, video.bottom, 1f)
        compose.runOnIdle {
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior)
            if (Build.VERSION.SDK_INT >= 30) assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                window.attributes.layoutInDisplayCutoutMode)
        }
        compose.onNodeWithContentDescription("Back to title").performClick()
        compose.onNodeWithText("Title page fixture").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(originalBehavior, WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior)
            assertEquals(originalFlags, window.attributes.flags)
            if (Build.VERSION.SDK_INT >= 28) assertEquals(originalCutout, window.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test fun directPictureButtonSelectsFitCropAndStretchWithoutRecreatingTheVideo() {
        val picture = mutableStateOf(PlayerPictureMode.FIT)
        val changes = mutableListOf<PlayerPictureMode>()
        val videoInstances = mutableSetOf<Any>()
        compose.setContent {
            MojaLossTheme {
                MainPlayerContent("Picture-size local fixture", snapshot(), onBack = {}, onPlayPause = {},
                    onSeek = {}, onRetry = {}, fold = null, accessibilityEnabled = true,
                    pictureMode = picture.value, onPictureMode = { picture.value = it; changes += it },
                    video = {
                        val instance = remember { Any() }
                        SideEffect { videoInstances += instance }
                        Box(Modifier.fillMaxSize().background(Color.DarkGray))
                    })
            }
        }
        compose.onNodeWithContentDescription("Picture size: Fit").performClick()
        compose.onNodeWithText("Fit").assertIsSelected()
        compose.onNodeWithText("Crop").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Picture size: Crop").assertIsDisplayed().performClick()
        compose.onNodeWithText("Crop").assertIsSelected()
        compose.onNodeWithText("Stretch").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Picture size: Stretch").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stretch").assertIsSelected()
        compose.onNodeWithText("Fit").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PlayerPictureMode.CROP, PlayerPictureMode.STRETCH, PlayerPictureMode.FIT), changes)
            assertEquals(1, videoInstances.size)
        }
    }

    @Test fun bothPlayerSheetsOwnTheirImmersiveWindowWithoutChangingActivityOwnership() {
        assumeTrue("WindowInspector requires Android 10 or newer.", Build.VERSION.SDK_INT >= 29)
        val mounted = mutableStateOf(true)
        val activityWindow = compose.activity.window
        var originalCutout = 0
        var originalBehavior = 0
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(activityWindow, false)
            val controller = WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            originalBehavior = controller.systemBarsBehavior
            activityWindow.attributes = activityWindow.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            originalCutout = activityWindow.attributes.layoutInDisplayCutoutMode
        }
        compose.setContent {
            MojaLossTheme {
                if (mounted.value) MainPlayerContent("Sheet ownership fixture", snapshot(),
                    onBack = { mounted.value = false }, onPlayPause = {}, onSeek = {}, onRetry = {},
                    fold = null, accessibilityEnabled = true,
                    video = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
                else Text("Title page after window ownership")
            }
        }
        fun assertImmersiveAttributes(window: Window) {
            compose.runOnIdle {
                assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior)
                assertEquals(if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    window.attributes.layoutInDisplayCutoutMode)
            }
        }
        assertImmersiveAttributes(activityWindow)
        for (action in listOf("Picture size: Fit", "Playback options")) {
            compose.onNodeWithContentDescription(action).performClick()
            compose.waitUntil(5_000) { currentDialogWindow() != null }
            val dialog = checkNotNull(currentDialogWindow())
            assertNotSame("Each sheet must own its actual dialog window.", activityWindow, dialog)
            assertImmersiveAttributes(dialog)
            assertImmersiveAttributes(activityWindow)
            capturePlatformSheet(if (action == "Picture size: Fit") "picture-size-actual-dialog-local-fixture"
                else "playback-options-actual-dialog-local-fixture")
            if (action == "Picture size: Fit") compose.onNodeWithText("Fit").performClick()
            else compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.waitUntil(5_000) { currentDialogWindow() == null }
            assertImmersiveAttributes(activityWindow)
        }
        compose.onNodeWithContentDescription("Back to title").performClick()
        compose.onNodeWithText("Title page after window ownership").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(originalBehavior, WindowCompat.getInsetsController(activityWindow, activityWindow.decorView).systemBarsBehavior)
            assertEquals(originalCutout, activityWindow.attributes.layoutInDisplayCutoutMode)
        }
    }

    @Test fun enteringAndLeavingDoesNotShowBarsThatWereAlreadyHidden() {
        requireSystemUi()
        val window = compose.activity.window
        compose.runOnUiThread {
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        compose.waitUntil(5_000) {
            systemBarsHidden(window)
        }
        compose.runOnIdle {
            val session = PlayerWindowImmersion(window)
            session.hideSystemBars()
            session.close()
            session.close() // Repeated disposal must be harmless.
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            assertFalse(insets?.isVisible(WindowInsetsCompat.Type.statusBars()) == true)
            assertFalse(insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true)
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Test fun realSystemBarsHideDuringPlaybackAndRestoreAfterExit() {
        requireSystemUi()
        val window = compose.activity.window
        compose.runOnUiThread {
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(window.decorView)?.let {
                it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars())
            } == true
        }
        lateinit var session: PlayerWindowImmersion
        compose.runOnIdle { session = PlayerWindowImmersion(window) }
        compose.waitUntil(5_000) {
            systemBarsHidden(window)
        }
        compose.runOnIdle { session.close() }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(window.decorView)?.let {
                it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars())
            } == true
        }
    }

    @Test fun pictureSizeAndPlaybackOptionsKeepTheirOwnWindowsImmersiveUntilDismissed() {
        requireSystemUi()
        assumeTrue("WindowInspector requires Android 10 or newer.", Build.VERSION.SDK_INT >= 29)
        val mounted = mutableStateOf(true)
        val activityWindow = compose.activity.window
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(activityWindow, false)
            WindowCompat.getInsetsController(activityWindow, activityWindow.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        compose.waitUntil(5_000) { systemBarsVisible(activityWindow) }
        compose.setContent {
            MojaLossTheme {
                if (mounted.value) MainPlayerContent("Immersive sheet fixture", snapshot(),
                    onBack = { mounted.value = false }, onPlayPause = {}, onSeek = {}, onRetry = {},
                    fold = null, accessibilityEnabled = true,
                    video = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
                else Text("Title page after sheets")
            }
        }
        compose.waitUntil(5_000) { systemBarsHidden(activityWindow) }
        for (action in listOf("Picture size: Fit", "Playback options")) {
            compose.onNodeWithContentDescription(action).performClick()
            compose.onNodeWithText("Picture size").assertIsDisplayed()
            compose.waitUntil(5_000) { currentDialogWindow() != null }
            val dialog = checkNotNull(currentDialogWindow())
            assertNotSame("The assertion must target the actual sheet window, not the Activity.", activityWindow, dialog)
            compose.waitUntil(5_000) { systemBarsHidden(dialog) }
            compose.runOnIdle {
                assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    WindowCompat.getInsetsController(dialog, dialog.decorView).systemBarsBehavior)
            }
            Espresso.pressBack()
            compose.waitUntil(5_000) { currentDialogWindow() == null }
            compose.waitUntil(5_000) { systemBarsHidden(activityWindow) }
        }
        compose.onNodeWithContentDescription("Back to title").performClick()
        compose.onNodeWithText("Title page after sheets").assertIsDisplayed()
        compose.waitUntil(5_000) { systemBarsVisible(activityWindow) }
    }

    private fun systemBarsHidden(window: Window): Boolean = ViewCompat.getRootWindowInsets(window.decorView)?.let {
        !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
    } == true

    private fun systemBarsVisible(window: Window): Boolean = ViewCompat.getRootWindowInsets(window.decorView)?.let {
        it.isVisible(WindowInsetsCompat.Type.statusBars()) && it.isVisible(WindowInsetsCompat.Type.navigationBars())
    } == true

    private fun currentDialogWindow(): Window? {
        if (Build.VERSION.SDK_INT < 29) return null
        var found: Window? = null
        compose.runOnUiThread {
            fun find(view: View): Window? {
                if (view is DialogWindowProvider) return view.window
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
        }
        return found
    }

    /** Capture the actual platform window buffer, not Compose's underlying player surface. */
    private fun capturePlatformSheet(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir,
            "ux-${BuildConfig.VERSION_NAME}-platform-sheet-fixtures").apply { mkdirs() }
        val provenance = "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "class=${PlayerFullscreenRegressionTest::class.java.name}\n" +
            "method=bothPlayerSheetsOwnTheirImmersiveWindowWithoutChangingActivityOwnership\n" +
            "capture_method=UiAutomation.takeScreenshot\n" +
            "Local native Compose fixture, no decoded movie, provider IO or SystemUI visibility proof.\n"
        val status = File(directory, "$label.capture-status.txt")
        try {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap == null) status.writeText(provenance + "status=BLOCKED_NULL_PLATFORM_BUFFER\n")
            else try {
                val saved = File(directory, "$label.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                status.writeText(provenance +
                    "status=${if (saved) "CAPTURED_PLATFORM_BUFFER_REQUIRES_VISUAL_REVIEW" else "BLOCKED_PNG_WRITE"}\n" +
                    "width=${bitmap.width}\nheight=${bitmap.height}\n")
            } finally { bitmap.recycle() }
        } catch (failure: Exception) {
            status.writeText(provenance + "status=BLOCKED_PLATFORM_CAPTURE\nfailure_type=${failure.javaClass.simpleName}\n")
        }
    }

    private fun requireSystemUi() {
        val installed = runCatching {
            @Suppress("DEPRECATION")
            compose.activity.packageManager.getApplicationInfo("com.android.systemui", 0).enabled
        }.getOrDefault(false)
        assumeTrue("This image omits SystemUI; actual bar visibility needs a full Android image or device.", installed)
    }

    private fun snapshot() = PlaybackSnapshot(mediaId = "local-window-fixture", phase = PlaybackPhase.PLAYING,
        positionMs = 61_000L, durationMs = 600_000L)
}
