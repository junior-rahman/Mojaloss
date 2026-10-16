package com.mojaloss.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.mojaloss.core.ui.MojaLossTheme
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.MotionPolicy
import com.mojaloss.core.ui.motion.motionArrival
import com.mojaloss.core.ui.motion.pageTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Local draw/navigation fixtures only; no remote media, server, downloads or timers. */
class PageMotionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledMotionIsImmediatelyOpaqueAndDoesNotDelayTouch() {
        compose.mainClock.autoAdvance = false
        var taps = 0
        compose.setContent { MojaLossTheme {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxSize().motionArrival("page", MotionPolicy(false))
                    .background(Color.Red).testTag("motion-reveal").clickable { taps++ })
            }
        } }
        compose.onNodeWithTag("motion-reveal").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        assertTrue("Reduced motion must render the final frame immediately", centerGreen() < .03f)
    }

    @Test fun arrivalHasFiniteVisibleFramesAndSwitchingOffRestoresOpacityWithoutBlockingTouch() {
        compose.mainClock.autoAdvance = false
        val policy = mutableStateOf(MotionPolicy(true))
        var taps = 0
        compose.setContent { MojaLossTheme {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxSize().motionArrival("page", policy.value)
                    .background(Color.Red).testTag("motion-reveal").clickable { taps++ })
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        val initial = centerGreen()
        compose.onNodeWithTag("motion-reveal").performClick()
        compose.runOnIdle { assertEquals("Actions are immediate even during the arrival", 1, taps) }
        compose.mainClock.advanceTimeBy(80)
        val middle = centerGreen()
        assertTrue("An intermediate drawn frame must differ from the initial one", middle < initial - .03f)
        assertTrue("The transition must not jump straight to opaque", middle > .03f)
        compose.runOnIdle { policy.value = MotionPolicy(false) }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Turning motion off must finish the current reveal immediately", centerGreen() < .03f)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("The final frame stays settled; there is no idle animation", centerGreen() < .03f)
    }

    @Test fun finiteNavigationPreservesBackStateAndNewPageControlsStayResponsive() {
        var actions = 0
        compose.setContent { MojaLossTheme {
            val policy = MotionPolicy(true)
            val stack = remember { mutableStateListOf("home") }
            CompositionLocalProvider(LocalMotionPolicy provides policy) {
                NavDisplay(backStack = stack, onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                    transitionSpec = { pageTransition(policy) }, popTransitionSpec = { pageTransition(policy) },
                    predictivePopTransitionSpec = { _ -> pageTransition(policy) },
                    entryProvider = entryProvider {
                        entry("home") {
                            var selected by rememberSaveable { mutableIntStateOf(0) }
                            Column {
                                Text("Saved choice $selected")
                                TextButton(onClick = { selected++ }) { Text("Choose") }
                                TextButton(onClick = { stack += "settings" }) { Text("Open settings") }
                            }
                        }
                        entry("settings") {
                            Column {
                                TextButton(onClick = { actions++ }) { Text("Immediate action") }
                                TextButton(onClick = { stack.removeAt(stack.lastIndex) }) { Text("Back to saved Home") }
                            }
                        }
                    })
            }
        } }
        compose.onNodeWithText("Choose").performClick()
        compose.onNodeWithText("Saved choice 1").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open settings").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Immediate action").performClick()
        compose.runOnIdle { assertEquals(1, actions) }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("Back to saved Home").performClick()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("Saved choice 1").assertIsDisplayed()
    }

    private fun centerGreen(): Float {
        val image = compose.onRoot().captureToImage()
        return image.toPixelMap()[image.width / 2, image.height / 2].green
    }
}
