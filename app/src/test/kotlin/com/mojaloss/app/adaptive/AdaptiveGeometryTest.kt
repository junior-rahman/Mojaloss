package com.mojaloss.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGeometryTest {
    @Test fun tabletopUsesReportedHingeInsteadOfMidpoint() {
        val fold = FoldGeometry(PaneBounds(0, 410, 900, 430), FoldOrientation.HORIZONTAL, true, true)
        val panes = playerPaneBounds(900, 1000, fold)
        assertTrue(panes.tabletop)
        assertEquals(PaneBounds(0, 0, 900, 410), panes.video)
        assertEquals(PaneBounds(0, 430, 900, 1000), panes.controls)
    }

    @Test fun separatingVerticalHingeKeepsBothPanesOffOcclusion() {
        val fold = FoldGeometry(PaneBounds(510, 0, 530, 800), FoldOrientation.VERTICAL, true, false)
        val panes = playerPaneBounds(1200, 800, fold)
        assertFalse(panes.tabletop)
        assertEquals(510, panes.video.right)
        assertEquals(530, panes.controls.left)
        assertEquals(PaneBounds(530, 0, 1200, 800), unobstructedPane(1200, 800, fold))
    }

    @Test fun windowRelativeHingeIsTranslatedForInsetsAndRail() {
        val fold = FoldGeometry(PaneBounds(0, 600, 1000, 620), FoldOrientation.HORIZONTAL, true, true)
        val local = fold.relativeTo(20, 100)
        val panes = playerPaneBounds(960, 900, local)
        assertEquals(500, panes.video.bottom)
        assertEquals(520, panes.controls.top)
    }

    @Test fun flatOrOutOfViewportFoldDoesNotManufactureTabletop() {
        val flat = FoldGeometry(PaneBounds(0, 500, 900, 500), FoldOrientation.HORIZONTAL, false, false)
        assertFalse(playerPaneBounds(900, 1000, flat).tabletop)
        val outside = flat.copy(bounds = PaneBounds(0, 1200, 900, 1220), halfOpened = true)
        assertFalse(playerPaneBounds(900, 1000, outside).tabletop)
        assertEquals(PaneBounds(0, 0, 900, 1000), unobstructedPane(900, 1000, outside))
    }

    @Test fun zeroThicknessCreaseSplitsWithoutADeviceSpecificGap() {
        val fold = FoldGeometry(PaneBounds(0, 480, 900, 480), FoldOrientation.HORIZONTAL, false, true)
        val panes = playerPaneBounds(900, 1000, fold)
        assertTrue(panes.tabletop)
        assertEquals(480, panes.video.bottom)
        assertEquals(480, panes.controls.top)
    }

    @Test fun immersivePlayerOverlaysControlsWithoutReservingVideoSpace() {
        val viewport = PaneBounds(0, 0, 1200, 650)
        val panes = playerPaneBounds(1200, 650, null, immersive = true)
        assertEquals(viewport, panes.video)
        assertEquals(viewport, panes.controls)
        assertFalse(panes.tabletop)
    }

    @Test fun immersivePlayerRetainsReportedTabletopPanes() {
        val fold = FoldGeometry(PaneBounds(0, 410, 900, 430), FoldOrientation.HORIZONTAL, true, true)
        val ordinary = playerPaneBounds(900, 1000, fold)
        assertEquals(ordinary, playerPaneBounds(900, 1000, fold, immersive = true))
    }

    @Test fun immersivePlayerRetainsVerticalOcclusionInsteadOfSpanningHinge() {
        val fold = FoldGeometry(PaneBounds(510, 0, 530, 800), FoldOrientation.VERTICAL, true, false)
        val ordinary = playerPaneBounds(1200, 800, fold)
        assertEquals(ordinary, playerPaneBounds(1200, 800, fold, immersive = true))
    }

    @Test fun immersivePlayerDoesNotSplitAtAnOutOfViewportHinge() {
        val fold = FoldGeometry(PaneBounds(0, 1200, 900, 1220), FoldOrientation.HORIZONTAL, true, true)
        assertEquals(PaneBounds(0, 0, 900, 650), playerPaneBounds(900, 650, fold, immersive = true).video)
    }

    @Test fun supportingLayoutRequiresAdequateUsableWidthAndHeight() {
        assertFalse(AdaptiveWindowInfo(599f, 900f).supportsSupportingPane)
        assertFalse(AdaptiveWindowInfo(760f, 479f).supportsSupportingPane)
        assertTrue(AdaptiveWindowInfo(600f, 480f).supportsSupportingPane)
    }

    @Test fun artworkCapRespondsToShortWindowsWithoutGrowingOnWideScreens() {
        assertEquals(138f, AdaptiveWindowInfo(760f, 300f).heroArtworkMaxHeightDp, .01f)
        assertEquals(360f, AdaptiveWindowInfo(1600f, 1200f).heroArtworkMaxHeightDp, .01f)
        assertEquals(360f, AdaptiveWindowInfo().heroArtworkMaxHeightDp, .01f)
    }
}
