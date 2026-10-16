package com.mojaloss.app.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

data class AdaptiveWindowInfo(
    val widthDp: Float = 0f,
    val heightDp: Float = 0f,
    val fold: FoldGeometry? = null,
) {
    val expanded: Boolean get() = widthDp >= 840f
    /** Feature layouts use their usable content viewport, never a device model/screen width. */
    val supportsSupportingPane: Boolean get() = widthDp >= 600f && heightDp >= 480f
    /** Artwork may shrink in short windows; captions/actions must remain outside this limit. */
    val heroArtworkMaxHeightDp: Float get() = if (heightDp > 0f) (heightDp * .46f).coerceAtMost(360f) else 360f
    val readingContentMaxWidthDp: Float get() = 760f
    val tabletop: Boolean get() = fold?.orientation == FoldOrientation.HORIZONTAL && fold.halfOpened
}

val LocalAdaptiveWindowInfo = staticCompositionLocalOf { AdaptiveWindowInfo() }

/** Lifecycle-scoped observation. A normal phone or preview safely has no reported hinge. */
@Composable
fun rememberWindowFold(): FoldGeometry? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val owner = LocalLifecycleOwner.current
    var fold by remember(activity) { mutableStateOf<FoldGeometry?>(null) }
    LaunchedEffect(activity, owner) {
        if (activity != null) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                fold = feature?.let {
                    FoldGeometry(
                        bounds = PaneBounds(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom),
                        orientation = if (it.orientation == FoldingFeature.Orientation.HORIZONTAL)
                            FoldOrientation.HORIZONTAL else FoldOrientation.VERTICAL,
                        separating = it.isSeparating,
                        halfOpened = it.state == FoldingFeature.State.HALF_OPENED,
                    )
                }
            }
        }
    }
    return fold
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
