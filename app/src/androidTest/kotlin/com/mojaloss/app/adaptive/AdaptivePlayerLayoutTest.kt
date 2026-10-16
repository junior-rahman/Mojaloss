package com.mojaloss.app.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptivePlayerLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun immersivePictureKeepsItsFullBoundsWhileOnlyOverlayAvoidsTheCameraAndBars() {
        val originY = mutableStateOf(0)
        compose.setContent {
            Box(Modifier.size(320.dp, 400.dp).onGloballyPositioned { originY.value = it.positionInWindow().y.toInt() }) {
                AdaptivePlayerLayout(immersive = true, controlsInVideo = true, fold = null,
                    controlInsets = WindowInsets(left = 0, top = originY.value + 30, right = 0, bottom = 20),
                    video = { Box(Modifier.fillMaxSize().testTag("edge-to-edge-video")) },
                    controls = { Box(Modifier.fillMaxSize().testTag("safe-video-controls")) })
            }
        }
        val video = compose.onNodeWithTag("edge-to-edge-video").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("safe-video-controls").fetchSemanticsNode().boundsInRoot
        assertEquals(video.left, controls.left, 1f)
        assertEquals(video.right, controls.right, 1f)
        assertEquals(video.top + 30f, controls.top, 1f)
        assertTrue(controls.bottom <= video.bottom)
        assertTrue(controls.height < video.height)
    }

    @Test fun resizingAndPostureChangesRetainExactlyOneVideoAndControlComposition() {
        val fold = mutableStateOf<FoldGeometry?>(null)
        val height = mutableStateOf(600.dp)
        val immersive = mutableStateOf(false)
        var measuredSplit = false
        val videoInstances = mutableSetOf<Any>()
        val controlsInstances = mutableSetOf<Any>()
        compose.setContent {
            Box(Modifier.size(400.dp, height.value)) {
                AdaptivePlayerLayout(
                    fold = fold.value,
                    immersive = immersive.value,
                    onSplitModeChanged = { measuredSplit = it },
                    video = {
                        val instance = remember { Any() }
                        SideEffect { videoInstances += instance }
                        Box(Modifier.fillMaxSize().testTag("retained-video"))
                    },
                    controls = {
                        val instance = remember { Any() }
                        SideEffect { controlsInstances += instance }
                        Box(Modifier.fillMaxSize().testTag("retained-controls"))
                    },
                )
            }
        }
        compose.runOnIdle {
            fold.value = FoldGeometry(PaneBounds(0, 300, 10000, 320), FoldOrientation.HORIZONTAL, true, true)
            height.value = 500.dp
            immersive.value = true
        }
        compose.onNodeWithTag("retained-video").assertExists()
        compose.onNodeWithTag("retained-controls").assertExists()
        compose.runOnIdle {
            assertTrue(measuredSplit)
            fold.value = null
            height.value = 600.dp
            immersive.value = false
        }
        compose.runOnIdle {
            assertFalse(measuredSplit)
            assertEquals(1, videoInstances.size)
            assertEquals(1, controlsInstances.size)
        }
    }
}
