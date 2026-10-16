package com.mojaloss.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.feature.ui.ConnectScreen
import com.mojaloss.app.feature.ui.SettingsScreen
import com.mojaloss.app.update.AppUpdatePhase
import com.mojaloss.app.update.AppUpdateRelease
import com.mojaloss.app.update.AppUpdateScreen
import com.mojaloss.app.update.AppUpdateState
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import com.mojaloss.core.model.AppPreferences
import com.mojaloss.core.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native UI fixtures only: no GitHub request, APK transfer, permission settings, or installation. */
class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val release = AppUpdateRelease(8, "0.1.7", 26, "com.mojaloss.android.debug", "MojaLoss-Sage-0.1.7-debug.apk",
        4L * MIB, "a".repeat(64), "Fullscreen playback and smoother Home.",
        "https://github.com/fixture/fixture/releases/download/v0.1.7/MojaLoss-Sage-0.1.7-debug.apk", "")

    @Test fun checkingShowsPendingStateAndCancelWithoutAnInstallAction() {
        compose.setContent { screen(AppUpdateState(AppUpdatePhase.CHECKING, "0.1.6")) }
        compose.onNodeWithText("Looking for an update").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithText("Download update").assertDoesNotExist()
        compose.onNodeWithText("Install update").assertDoesNotExist()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Cancel", substring = false).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel"), calls) }
    }

    @Test fun availableVersionShowsRealMetadataAndRequiresExplicitDownload() {
        compose.setContent { screen(AppUpdateState(AppUpdatePhase.AVAILABLE, "0.1.6", release, totalBytes = release.size)) }
        compose.onNodeWithText("Version 0.1.7 · 4.0 MB").assertIsDisplayed()
        compose.onNodeWithText(release.notes).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Download update").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("download"), calls) }
        compose.onNodeWithText("Install update").assertDoesNotExist()
    }

    @Test fun progressReflectsTransferredBytesAndCancelNeverInvokesInstallation() {
        val state = mutableStateOf(AppUpdateState(AppUpdatePhase.DOWNLOADING, "0.1.6", release, MIB, release.size))
        compose.setContent { screen(state.value) }
        compose.onNodeWithText("1.0 MB of 4.0 MB").assertIsDisplayed()
        assertProgress(.25f)
        compose.runOnIdle { assertTrue(calls.isEmpty()); state.value = state.value.copy(downloadedBytes = 2 * MIB) }
        compose.onNodeWithText("2.0 MB of 4.0 MB").assertIsDisplayed()
        assertProgress(.5f)
        compose.onNodeWithText("Install update").assertDoesNotExist()
        compose.onNodeWithText("Cancel download").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel"), calls) }
    }

    @Test fun permissionAndInstallerStatesRequireAnExplicitInstallAndNeverClaimSuccess() {
        val explanation = "Allow updates from MojaLoss, then return here and tap Install update."
        val state = mutableStateOf(AppUpdateState(AppUpdatePhase.INSTALL_PERMISSION, "0.1.6", release,
            release.size, release.size, explanation))
        compose.setContent { screen(state.value) }
        compose.onNodeWithText("One Android permission").assertIsDisplayed()
        compose.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Install update").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("install"), calls)
            state.value = state.value.copy(phase = AppUpdatePhase.READY, message = "Ready. Tap Install update to continue.")
        }
        compose.onNodeWithText("Ready to install").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Returning from settings must not trigger another installation", listOf("install"), calls)
            state.value = state.value.copy(phase = AppUpdatePhase.INSTALLER_OPENED,
                message = "Complete the Android installation. If you cancelled, you can try again.")
        }
        compose.onNodeWithText("Continue in Android").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("You’re up to date").assertDoesNotExist()
        compose.onNodeWithText("Install again").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("install", "install"), calls) }
    }

    @Test fun errorsDistinguishRetryingATransferFromRetryingAReleaseCheck() {
        val state = mutableStateOf(AppUpdateState(AppUpdatePhase.ERROR, "0.1.6", release,
            message = "The download was interrupted. Please try again."))
        compose.setContent { screen(state.value) }
        compose.onNodeWithText("The download was interrupted. Please try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try download again").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("download"), calls)
            state.value = AppUpdateState(AppUpdatePhase.ERROR, "0.1.6", message = "Could not reach the update server.")
        }
        compose.onNodeWithText("Try download again").assertDoesNotExist()
        compose.onNodeWithText("Could not reach the update server.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again", substring = false).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("download", "check"), calls) }
        compose.onNodeWithText("Install update").assertDoesNotExist()
    }

    @Test fun upToDateShowsInstalledVersionAndNeverAutomaticallyChecksAgain() {
        compose.setContent { screen(AppUpdateState(AppUpdatePhase.UP_TO_DATE, "0.1.7", message = "You have the latest version.")) }
        compose.onNodeWithText("MojaLoss 0.1.7").assertIsDisplayed()
        compose.onNodeWithText("You’re up to date").assertIsDisplayed()
        compose.onNodeWithText("Download update").assertDoesNotExist()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("check"), calls) }
    }

    @Test fun signedOutConnectionScreenUpdatesAreReachableWithoutConnecting() {
        compose.setContent { MojaLossTheme { MojaLossBackdrop(Modifier.fillMaxSize()) {
            connectionScreen(SessionState.SignedOut)
        } } }
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        assertReachableUpdateEntry("connect-app-updates").performClick()
        compose.runOnIdle { assertEquals(listOf("check"), calls) }
    }

    @Test fun failedConnectionScreenUpdatesDoNotRetryTheBrokenServer() {
        compose.setContent { MojaLossTheme { MojaLossBackdrop(Modifier.fillMaxSize()) {
            connectionScreen(SessionState.Failed("The saved account could not be verified."))
        } } }
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        assertReachableUpdateEntry("connect-app-updates").performClick()
        compose.runOnIdle { assertEquals(listOf("check"), calls) }
    }

    @Test fun savedUnverifiedAccountSettingsUpdatesAreIndependentFromReconnectAndSignOut() {
        compose.setContent { MojaLossTheme { MojaLossBackdrop(Modifier.fillMaxSize()) {
            SettingsScreen(preferences = AppPreferences(), accountName = "Fixture saved account",
                onPreferences = { calls += "preferences" }, onSignOut = { calls += "sign-out" }, onBack = { calls += "back" },
                accountVerified = false, onReconnect = { calls += "reconnect" }, onCheckUpdates = { calls += "check" },
                appVersion = "0.1.7 (8)")
        } } }
        compose.onNodeWithText("Saved account").assertIsDisplayed()
        compose.onNodeWithText("Reconnect account").assertIsEnabled()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        val update = assertReachableUpdateEntry("settings-app-updates")
        compose.onNodeWithText("Version 0.1.7 (8)").assertIsDisplayed()
        update.performClick()
        compose.runOnIdle { assertEquals(listOf("check"), calls) }
    }

    @Composable private fun screen(value: AppUpdateState) {
        MojaLossTheme {
            MojaLossBackdrop(Modifier.fillMaxSize()) {
                AppUpdateScreen(value, { calls += "check" }, { calls += "download" }, { calls += "cancel" },
                    { calls += "install" }, { calls += "back" })
            }
        }
    }

    @Composable private fun connectionScreen(session: SessionState) {
        ConnectScreen(session = session, onQuickConnect = { calls += "quick-connect" },
            onPassword = { _, _, _ -> calls += "password-connect" }, onCancel = { calls += "cancel-connection" },
            initialServerUrl = "https://fixture.invalid/jelly", onCheckUpdates = { calls += "check" })
    }

    private fun assertReachableUpdateEntry(tag: String): SemanticsNodeInteraction {
        // Search through the actual lazy screen, including entries initially below the viewport.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val touch = node.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$tag touch width must be at least 48dp", touch.width / density >= 47.5f)
        assertTrue("$tag touch height must be at least 48dp", touch.height / density >= 47.5f)
        return node
    }

    private fun assertProgress(expected: Float) {
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        assertEquals(expected, node.config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
    }

    private companion object { const val MIB = 1024L * 1024 }
}
