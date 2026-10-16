package com.mojaloss.app.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Both slots remain at the same composition positions through posture changes. This wrapper
 * never creates, prepares or releases a player. The video owner preserves its source aspect
 * ratio within its measured pane; the controls owner supplies scrolling if needed.
 */
@Composable
fun AdaptivePlayerLayout(
    video: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    fold: FoldGeometry? = rememberWindowFold(),
    immersive: Boolean = false,
    onSplitModeChanged: (Boolean) -> Unit = {},
    controlsInVideo: Boolean = false,
    controlInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf<IntSize?>(null) }
    val reportSplit by rememberUpdatedState(onSplitModeChanged)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val edgeToEdgeVideo = immersive && controlsInVideo
    val minimumOverlayWidth = with(density) { 240.dp.roundToPx() }
    val minimumOverlayHeight = with(density) { 96.dp.roundToPx() }
    // Report the same validated local geometry used for placement. Fold changes can keep
    // identical outer bounds, so this also observes fold updates independently of layout.
    LaunchedEffect(viewportSize, origin, fold, controlsInVideo) {
        val size = viewportSize ?: return@LaunchedEffect
        val panes = playerPaneBounds(size.width, size.height,
            fold?.relativeTo(origin.x.roundToInt(), origin.y.roundToInt()), immersive = true)
        reportSplit(!controlsInVideo && panes.video != panes.controls)
    }
    Layout(
        modifier = modifier.fillMaxSize()
            // The picture fills the window behind a camera cutout and transient bars.
            // Only the overlay is moved out of unsafe regions; root padding shrank both.
            .then(if (edgeToEdgeVideo) Modifier else Modifier.windowInsetsPadding(controlInsets))
            .onGloballyPositioned { origin = it.positionInWindow(); viewportSize = it.size },
        content = {
            Box(Modifier.fillMaxSize()) { video() }
            Box(Modifier.fillMaxSize()) { controls() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val panes = playerPaneBounds(width, height, fold?.relativeTo(origin.x.roundToInt(), origin.y.roundToInt()), immersive)
        // A separating hinge can leave the preferred pane too small for readable transport.
        // Move both stable slots into the other valid pane when it offers more room; never
        // draw over the hinge. If neither fits, feature-owned compact/scrollable controls apply.
        val videoBounds = if (controlsInVideo) videoPaneForOverlayControls(panes, minimumOverlayWidth, minimumOverlayHeight) else panes.video
        val videoPane = measurables[0].measure(Constraints.fixed(videoBounds.width, videoBounds.height))
        // Player transport is an overlay even in tabletop posture. Other consumers retain
        // their separate pane contract, and both slots remain composed through the change.
        val controlBounds = if (edgeToEdgeVideo) safePlayerOverlayBounds(videoBounds,
            originX = origin.x.roundToInt(), originY = origin.y.roundToInt(),
            windowWidth = windowSize.width, windowHeight = windowSize.height,
            leftInset = controlInsets.getLeft(density, layoutDirection), topInset = controlInsets.getTop(density),
            rightInset = controlInsets.getRight(density, layoutDirection), bottomInset = controlInsets.getBottom(density))
        else if (controlsInVideo) videoBounds else panes.controls
        val controlsPane = measurables[1].measure(Constraints.fixed(controlBounds.width, controlBounds.height))
        layout(width, height) {
            videoPane.place(videoBounds.left, videoBounds.top)
            controlsPane.place(controlBounds.left, controlBounds.top)
        }
    }
}

/** Intersect only outer window hazards with the actual pane, never pad the inner hinge edge. */
internal fun safePlayerOverlayBounds(
    pane: PaneBounds,
    originX: Int, originY: Int,
    windowWidth: Int, windowHeight: Int,
    leftInset: Int, topInset: Int, rightInset: Int, bottomInset: Int,
): PaneBounds {
    val left = (leftInset.coerceAtLeast(0) - originX).coerceIn(pane.left, pane.right)
    val top = (topInset.coerceAtLeast(0) - originY).coerceIn(pane.top, pane.bottom)
    val right = (windowWidth - rightInset.coerceAtLeast(0) - originX).coerceIn(left, pane.right)
    val bottom = (windowHeight - bottomInset.coerceAtLeast(0) - originY).coerceIn(top, pane.bottom)
    return PaneBounds(left, top, right, bottom)
}

/** The reported pane boundaries are authoritative; a fallback only chooses between them. */
internal fun videoPaneForOverlayControls(panes: PlayerPaneBounds, minimumWidth: Int, minimumHeight: Int): PaneBounds {
    fun fits(pane: PaneBounds) = pane.width >= minimumWidth && pane.height >= minimumHeight
    if (fits(panes.video)) return panes.video
    if (fits(panes.controls)) return panes.controls
    return if (panes.controls.width.toLong() * panes.controls.height > panes.video.width.toLong() * panes.video.height)
        panes.controls else panes.video
}
