package com.mojaloss.app.feature.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.BuildConfig
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Native UI fixtures, no remote artwork, real server, file transfer, or decoder. */
class DownloadsUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val owner = "fixture-owner"

    @Test fun onlyReadyOwnedRowsPlayAndSwitchingAccountsHidesOldDownloads() {
        val ready = item("ready", DownloadStatus.READY)
        val pending = item("pending", DownloadStatus.DOWNLOADING)
        val state = mutableStateOf(library(ready, pending, item("foreign", DownloadStatus.READY).copy(ownerKey = "other")))
        compose.setContent { MojaLossTheme { screen(state.value) } }
        compose.onNodeWithText("Fixture foreign").assertDoesNotExist()
        compose.onNodeWithText("Fixture pending").assertHasNoClickAction()
        compose.onNodeWithText("Fixture ready").performClick()
        compose.runOnIdle { assertEquals(listOf("play:ready"), calls); state.value = state.value.copy(ownerKey = "other") }
        compose.onNodeWithText("Fixture ready").assertDoesNotExist()
        compose.onNodeWithText("Fixture foreign").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(ownerKey = null) }
        compose.onNodeWithText("Fixture foreign").assertDoesNotExist()
    }

    @Test fun pauseResumeRetryAndRemoveAreDistinctNamed48DpControls() {
        compose.setContent { MojaLossTheme { screen(library(item("active", DownloadStatus.DOWNLOADING), item("paused", DownloadStatus.PAUSED), item("failed", DownloadStatus.ERROR))) } }
        val targets = listOf("Pause Fixture active", "Resume download Fixture paused", "Retry download Fixture failed")
        targets.forEach { label ->
            val node = compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().touchBoundsInRoot
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            assertTrue("$label actual touch width", bounds.width / density >= 47f)
            assertTrue("$label actual touch height", bounds.height / density >= 47f)
            node.performClick()
        }
        compose.runOnIdle { assertEquals(listOf("pause:active", "resume:paused", "retry:failed"), calls) }
        compose.onNodeWithContentDescription("Remove download Fixture failed").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, calls.size) }
        compose.onNodeWithText("Keep download").performClick()
        compose.runOnIdle { assertEquals(3, calls.size) }
    }

    @Test fun removalAndClearAllNeedConfirmationAndAffectOnlyCurrentOwner() {
        compose.setContent { MojaLossTheme { screen(library(item("one", DownloadStatus.READY), item("two", DownloadStatus.READY), item("foreign", DownloadStatus.READY).copy(ownerKey = "other"))) } }
        compose.onNodeWithContentDescription("Remove download Fixture one").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("remove:one"), calls) }
        compose.onNodeWithText("Remove all downloads").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls.size) }
        compose.onNodeWithText("Remove all", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("remove:one", "remove:one", "remove:two"), calls) }
    }

    @Test fun unknownTransferSizeDoesNotInventPercentageOrFreeSpace() {
        val active = item("active", DownloadStatus.DOWNLOADING).copy(bytesDownloaded = 1024, totalBytes = null)
        val state = mutableStateOf(library(active).copy(freeBytes = null))
        compose.setContent { MojaLossTheme { screen(state.value) } }
        compose.onNodeWithText("Downloading · 1 KB").assertIsDisplayed()
        compose.onNode(hasText("free on device", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("%", substring = true)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(items = listOf(active.copy(bytesDownloaded = 50, totalBytes = 100))) }
        compose.onNodeWithText("Downloading 50%").assertIsDisplayed()
    }

    @Test fun settingsExposeOneWifiToggleAndExplicitOriginalFileStorageLimits() {
        val state = mutableStateOf(library())
        compose.setContent { MojaLossTheme { screen(state.value, update = { state.value = state.value.copy(settings = it) }) } }
        compose.onNodeWithContentDescription("Download settings").performClick()
        val wifi = hasText("Wi-Fi only") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onNode(wifi).assertIsOn().performClick().assertIsOff()
        val twoGb = hasText("2 GB") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onNode(twoGb).performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(state.value.settings.wifiOnly); assertEquals(2L * GIB, state.value.settings.storageLimitBytes) }
        compose.onNodeWithText("Original files only.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Quality").assertDoesNotExist()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("download-settings").assertDoesNotExist()
    }

    @Test fun confirmationUsesRealSizeQuotaAndNeverAddsAnUnsupportedQualityChoice() {
        var confirms = 0
        val state = mutableStateOf(DownloadSettings(storageLimitBytes = GIB))
        val source = DownloadSource(MediaTitle("film", "Offline film"), "https://fixture.invalid/file", SessionScope("https://fixture.invalid", "fixture", 1), expectedBytes = 2L * GIB)
        compose.setContent { MojaLossTheme { DownloadConfirmSheet(source, state.value, usedBytes = 0, freeBytes = 4L * GIB,
            onSettings = { state.value = it }, onConfirm = { confirms++ }, onDismiss = {}) } }
        compose.onNodeWithText("Original file · 2.0 GB").assertIsDisplayed()
        compose.onNodeWithText("Download on Wi-Fi").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(storageLimitBytes = 5L * GIB) }
        compose.onNodeWithText("Download on Wi-Fi").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
        compose.onNodeWithText("Quality").assertDoesNotExist()
    }

    @Test fun freshDownloadControlWorksBeforePlaybackPermissionHydration() {
        val title = MediaTitle("unhydrated", "Fixture film", isPlayable = false)
        val busy = mutableStateOf(false)
        compose.setContent { MojaLossTheme { Column {
            DownloadControl(title, null, busy.value, { calls += "download:${it.id}" }, onArtwork = true)
        } } }
        compose.onNodeWithContentDescription("Download Fixture film").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("download:unhydrated"), calls); busy.value = true }
        compose.onNodeWithContentDescription("Preparing download… Fixture film").assertIsNotEnabled()
    }

    @Test fun downloadsRemainReadableAt200PercentFontInBothPalettes() {
        val dark = mutableStateOf(false)
        compose.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, 2f)) {
                MojaLossTheme(darkTheme = dark.value) { MojaLossBackdrop(Modifier.fillMaxSize()) {
                    screen(library(item("ready", DownloadStatus.READY), item("waiting", DownloadStatus.WAITING_FOR_WIFI)))
                } }
            }
        }
        compose.onNodeWithText("Fixture ready").assertIsDisplayed().performClick()
        assertDownloadStatusKeepsItsFirstWordReadable()
        capture("downloads-light-200percent-local-fixture")
        compose.runOnIdle { dark.value = true }
        assertDownloadStatusKeepsItsFirstWordReadable()
        compose.onNodeWithContentDescription("Pause Fixture waiting").performScrollTo().assertIsDisplayed().performClick()
        capture("downloads-dark-200percent-local-fixture")
        compose.runOnIdle { assertEquals(listOf("play:ready", "pause:waiting"), calls) }
    }

    private fun assertDownloadStatusKeepsItsFirstWordReadable() {
        compose.onNodeWithText("Downloaded · 500 B", useUnmergedTree = true)
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                val layout = layouts.single()
                assertTrue("Downloaded must not break in the middle of its first word at 200% text",
                    layout.getLineEnd(0, visibleEnd = true) >= "Downloaded".length)
            }
    }

    @androidx.compose.runtime.Composable
    private fun screen(value: DownloadLibrary, update: (DownloadSettings) -> Unit = {}) = DownloadsScreen(value, {},
        { calls += "play:${it.id}" }, { calls += "pause:${it.id}" }, { calls += "resume:${it.id}" },
        { calls += "retry:${it.id}" }, { calls += "remove:${it.id}" }, update)
    private fun item(id: String, status: DownloadStatus) = DownloadItem(id, owner, MediaTitle(id, "Fixture $id", year = 2026), status,
        bytesDownloaded = 500, totalBytes = 1000)
    private fun library(vararg items: DownloadItem) = DownloadLibrary(items.toList(), initialized = true, ownerKey = owner,
        usedBytes = 1024L * 1024, freeBytes = 4L * GIB)

    private fun capture(label: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "downloads-${BuildConfig.VERSION_NAME}-native-fixtures").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val samples = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step (bitmap.height / 64).coerceAtLeast(1))
            for (x in 0 until bitmap.width step (bitmap.width / 64).coerceAtLeast(1)) samples += bitmap.getPixel(x, y)
        assertTrue("Native download fixture must contain nonblank pixels", samples.size >= 8)
        File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$label.txt").writeText("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nclass=${javaClass.name}\ncapture_method=Compose.onRoot.captureToImage\n" +
            "status=NONBLANK_NATIVE_DOWNLOAD_UI_FIXTURE\nsampled_colors=${samples.size}\nLocal metadata and transfer state only. No media transfer, server, decoder, or actual offline playback proof.\n")
    }
    private companion object { const val GIB = 1024L * 1024 * 1024 }
}
