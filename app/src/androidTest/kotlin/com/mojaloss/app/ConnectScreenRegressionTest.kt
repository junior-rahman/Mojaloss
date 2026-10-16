package com.mojaloss.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.mojaloss.app.feature.ui.ConnectScreen
import com.mojaloss.core.model.SessionState
import com.mojaloss.core.ui.MojaLossBackdrop
import com.mojaloss.core.ui.MojaLossTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the actual connection UI without constructing a repository or making HTTP requests.
 * Compact/expanded constraints are layout fixtures, not claims about a physical Fold device.
 */
class ConnectScreenRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactFormSurvivesSubmittedUrlFailureAndWaitingCancellation() {
        connectionJourney(viewportWidthDp = 360f)
    }

    @Test fun expandedFormSurvivesSubmittedUrlFailureAndWaitingCancellation() {
        connectionJourney(viewportWidthDp = 840f)
    }

    private fun connectionJourney(viewportWidthDp: Float) {
        val session = mutableStateOf<SessionState>(SessionState.SignedOut)
        val submitted = mutableListOf<String>()
        var cancelCount = 0
        var observedWidthDp = 0f

        compose.setContent {
            // Fit a real dp constraint fixture into any emulator's physical test window. This
            // avoids silently clamping an 840dp test to the emulator's compact window width.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val originalDensity = LocalDensity.current
                val fittingScale = minOf(maxWidth.value / viewportWidthDp, maxHeight.value / 720f)
                val fixtureDensity = Density(originalDensity.density * fittingScale, originalDensity.fontScale)
                CompositionLocalProvider(LocalDensity provides fixtureDensity) {
                    MojaLossTheme {
                        MojaLossBackdrop(Modifier.size(viewportWidthDp.dp, 720.dp)) {
                            BoxWithConstraints(Modifier.fillMaxSize().testTag("connect-viewport")) {
                                SideEffect { observedWidthDp = maxWidth.value }
                                ConnectScreen(
                                    session = session.value,
                                    onQuickConnect = { value ->
                                        submitted += value
                                        session.value = if (submitted.size == 1) SessionState.Connecting
                                        else SessionState.Waiting(CONNECTION_CODE, System.currentTimeMillis() + 300_000)
                                    },
                                    onPassword = { _, _, _ -> error("This journey must not use password authentication") },
                                    onCancel = { cancelCount++; session.value = SessionState.SignedOut },
                                )
                            }
                        }
                    }
                }
            }
        }

        compose.runOnIdle { assertEquals(viewportWidthDp, observedWidthDp, 1f) }
        compose.onNodeWithText("Use Quick Connect").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Server URL").performScrollTo().performClick().assertIsFocused()
        // A complete pasted string is inserted through the editable semantics action. The
        // field is focused, exercising the native IME/focus path before the submit gesture.
        compose.onNodeWithText("Server URL").performTextReplacement(SERVER_URL)
        compose.onNodeWithText("Server URL").assertTextContains(SERVER_URL)
        compose.onNodeWithText("Use Quick Connect").performScrollTo().assertIsEnabled().performClick()

        compose.onNodeWithText("Connecting…").performScrollTo().assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertIsDisplayed()
        compose.onNodeWithText("Use Quick Connect").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(SERVER_URL), submitted)
            session.value = SessionState.Failed(CONNECTION_ERROR)
        }

        compose.onNodeWithText(CONNECTION_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertTextContains(SERVER_URL).assertIsEnabled()
        compose.onNodeWithText("Use Quick Connect").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Approve this connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CONNECTION_CODE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CONNECTION_ERROR).assertDoesNotExist()
        compose.onNodeWithText("Use Quick Connect").assertIsNotEnabled()
        compose.onNodeWithText("Cancel connection").performScrollTo().assertIsDisplayed().performClick()

        compose.onNodeWithText(CONNECTION_CODE).assertDoesNotExist()
        compose.onNodeWithText("Cancel connection").assertDoesNotExist()
        compose.onNodeWithText("Server URL").performScrollTo().assertIsDisplayed()
            .assertTextContains(SERVER_URL).assertIsEnabled()
        compose.onNodeWithText("Use Quick Connect").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf(SERVER_URL, SERVER_URL), submitted)
            assertEquals(1, cancelCount)
            assertEquals(SessionState.SignedOut, session.value)
        }
        Espresso.closeSoftKeyboard()
    }

    private companion object {
        const val SERVER_URL = "https://www.mojaloss.stream/jelly"
        const val CONNECTION_ERROR = "The connection could not start. Try again."
        const val CONNECTION_CODE = "482911"
    }
}
