package com.mojaloss.app.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveContentPaneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun boundedContentReportsItsActualViewportAndRetainsStateThroughResize() {
        val width = mutableStateOf(260.dp)
        val instances = mutableSetOf<Any>()
        var measured = AdaptiveWindowInfo()
        compose.setContent {
            Box(Modifier.size(width.value, 320.dp)) {
                AdaptiveContentPane(fold = null, maxContentWidth = 220.dp) {
                    val instance = remember { Any() }
                    val info = LocalAdaptiveWindowInfo.current
                    SideEffect { instances += instance; measured = info }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.runOnIdle {
            assertEquals(220f, measured.widthDp, .1f)
            assertEquals(320f, measured.heightDp, .1f)
            width.value = 180.dp
        }
        compose.runOnIdle {
            assertEquals(180f, measured.widthDp, .1f)
            assertEquals(1, instances.size)
        }
    }

    @Test fun insetSheetOriginKeepsItsContentOutsideWindowRelativeHinge() {
        var bounds = Rect.Zero
        var hingeLeft = 0f
        var hingeRight = 0f
        compose.setContent {
            val density = LocalDensity.current
            val left = with(density) { 110.dp.roundToPx() }
            val right = with(density) { 120.dp.roundToPx() }
            val height = with(density) { 400.dp.roundToPx() }
            val fold = FoldGeometry(PaneBounds(left, 0, right, height), FoldOrientation.VERTICAL, true, false)
            SideEffect { hingeLeft = left.toFloat(); hingeRight = right.toFloat() }
            Box(Modifier.size(280.dp, 360.dp).padding(20.dp)) {
                AdaptiveContentPane(fold = fold) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() })
                }
            }
        }
        compose.runOnIdle {
            assertTrue("The measured sheet content must not cross the reported hinge", bounds.right <= hingeLeft || bounds.left >= hingeRight)
            assertTrue("The larger right pane remains available", bounds.left >= hingeRight)
            assertTrue(bounds.width > 0f && bounds.height > 0f)
        }
    }
}
