package com.mojaloss.app.feature.player

import com.mojaloss.app.adaptive.PaneBounds
import com.mojaloss.app.adaptive.PlayerPaneBounds
import com.mojaloss.app.adaptive.videoPaneForOverlayControls
import com.mojaloss.app.adaptive.safePlayerOverlayBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOverlayPanePolicyTest {
    @Test fun cameraAndTransientSystemBarRegionsInsetOnlyTheOverlayNotTheVideoPane() {
        val video = PaneBounds(0, 0, 800, 400)
        val controls = safePlayerOverlayBounds(video, 0, 0, 800, 400, 32, 24, 0, 16)
        assertEquals(PaneBounds(32, 24, 800, 384), controls)
        assertEquals(PaneBounds(0, 0, 800, 400), video)
    }

    @Test fun tabletopInnerHingeEdgeDoesNotAcquireOuterWindowPadding() {
        val upper = PaneBounds(0, 0, 800, 180)
        val lower = PaneBounds(0, 200, 800, 400)
        assertEquals(PaneBounds(0, 24, 800, 180),
            safePlayerOverlayBounds(upper, 0, 0, 800, 400, 0, 24, 0, 16))
        assertEquals(PaneBounds(0, 200, 800, 384),
            safePlayerOverlayBounds(lower, 0, 0, 800, 400, 0, 24, 0, 16))
    }

    @Test fun offsetViewportUsesWindowCoordinatesAndPathologicalInsetsCannotInvertControls() {
        val pane = PaneBounds(0, 0, 600, 200)
        assertEquals(pane, safePlayerOverlayBounds(pane, 100, 100, 800, 400, 32, 24, 0, 16))
        assertEquals(PaneBounds(600, 200, 600, 200),
            safePlayerOverlayBounds(pane, 0, 0, 800, 400, 2000, 2000, 2000, 2000))
    }
    @Test fun adequatePreferredTabletopVideoPaneStaysPreferredEvenWhenOtherPaneIsLarger() {
        val upper = PaneBounds(0, 0, 400, 150)
        val lower = PaneBounds(0, 170, 400, 600)
        assertEquals(upper, videoPaneForOverlayControls(PlayerPaneBounds(upper, lower, true), 240, 96))
    }

    @Test fun tooShortPreferredPaneMovesVideoAndControlsOnlyToTheOtherReportedPane() {
        val upper = PaneBounds(0, 0, 400, 50)
        val lower = PaneBounds(0, 70, 400, 600)
        assertEquals(lower, videoPaneForOverlayControls(PlayerPaneBounds(upper, lower, true), 240, 96))
    }

    @Test fun tooNarrowPreferredPaneUsesAdequateRightPaneWithoutDrawingOverTheHinge() {
        val left = PaneBounds(0, 0, 150, 600)
        val right = PaneBounds(170, 0, 500, 600)
        assertEquals(right, videoPaneForOverlayControls(PlayerPaneBounds(left, right, false), 240, 96))
    }

    @Test fun whenNeitherPaneFitsCompactControlsGetTheLargestReportedPaneAndFlatViewportStaysWhole() {
        val left = PaneBounds(0, 0, 100, 600)
        val right = PaneBounds(120, 0, 230, 600)
        assertEquals(right, videoPaneForOverlayControls(PlayerPaneBounds(left, right, false), 240, 96))
        val whole = PaneBounds(0, 0, 180, 80)
        assertEquals(whole, videoPaneForOverlayControls(PlayerPaneBounds(whole, whole, false), 240, 96))
    }
}
