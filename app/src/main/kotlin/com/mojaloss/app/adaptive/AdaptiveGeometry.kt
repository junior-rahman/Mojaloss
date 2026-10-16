package com.mojaloss.app.adaptive

enum class FoldOrientation { HORIZONTAL, VERTICAL }

data class PaneBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class FoldGeometry(
    val bounds: PaneBounds,
    val orientation: FoldOrientation,
    val separating: Boolean,
    val halfOpened: Boolean,
) {
    fun relativeTo(x: Int, y: Int): FoldGeometry = copy(bounds = PaneBounds(
        bounds.left - x, bounds.top - y, bounds.right - x, bounds.bottom - y,
    ))
}

data class PlayerPaneBounds(val video: PaneBounds, val controls: PaneBounds, val tabletop: Boolean)

/** Bounds are local to this viewport, after system and keyboard insets. */
fun playerPaneBounds(width: Int, height: Int, fold: FoldGeometry?, immersive: Boolean = false): PlayerPaneBounds {
    require(width >= 0 && height >= 0)
    val panes = separatingPanes(width, height, fold)
    if (panes != null) {
        return PlayerPaneBounds(
            video = panes.first,
            controls = panes.second,
            tabletop = fold?.orientation == FoldOrientation.HORIZONTAL && fold.halfOpened,
        )
    }
    if (immersive) {
        val viewport = PaneBounds(0, 0, width, height)
        return PlayerPaneBounds(viewport, viewport, tabletop = false)
    }
    val videoHeight = (width * 9L / 16L).toInt().coerceAtMost((height * .65f).toInt())
    return PlayerPaneBounds(
        PaneBounds(0, 0, width, videoHeight),
        PaneBounds(0, videoHeight, width, height),
        tabletop = false,
    )
}

/** A one-slot screen uses one unobstructed pane. Feature-owned two-pane content can use fold data. */
fun unobstructedPane(width: Int, height: Int, fold: FoldGeometry?): PaneBounds {
    val panes = separatingPanes(width, height, fold) ?: return PaneBounds(0, 0, width, height)
    return if (panes.first.width.toLong() * panes.first.height >= panes.second.width.toLong() * panes.second.height)
        panes.first else panes.second
}

private fun separatingPanes(width: Int, height: Int, fold: FoldGeometry?): Pair<PaneBounds, PaneBounds>? {
    if (fold == null || (!fold.separating && !fold.halfOpened)) return null
    val b = fold.bounds
    return when (fold.orientation) {
        FoldOrientation.HORIZONTAL -> {
            if (b.top <= 0 || b.bottom >= height || b.right <= 0 || b.left >= width) null
            else PaneBounds(0, 0, width, b.top) to PaneBounds(0, b.bottom, width, height)
        }
        FoldOrientation.VERTICAL -> {
            if (b.left <= 0 || b.right >= width || b.bottom <= 0 || b.top >= height) null
            else PaneBounds(0, 0, b.left, height) to PaneBounds(b.right, 0, width, height)
        }
    }
}
