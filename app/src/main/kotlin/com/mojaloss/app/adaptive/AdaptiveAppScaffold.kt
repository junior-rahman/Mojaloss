package com.mojaloss.app.adaptive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mojaloss.core.model.Destination
import com.mojaloss.core.ui.SageNavigation
import kotlin.math.roundToInt

/**
 * Hosts existing content, not a second navigation host. Optional visual slots allow the shared
 * UI owner to replace rail/bar presentation without changing navigation ownership.
 */
@Composable
fun AdaptiveAppScaffold(
    selected: Destination,
    onDestination: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    bottomNavigation: (@Composable () -> Unit)? = null,
    railNavigation: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val fold = rememberWindowFold()
    val density = LocalDensity.current
    val latestContent = rememberUpdatedState(content)
    val stableContent = remember { movableContentOf { latestContent.value() } }
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
    ) {
        HingeSafePane(fold, Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Classify the usable window/pane, not a hardcoded device's screen dimensions.
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(112.dp).fillMaxHeight()) {
                            if (railNavigation != null) railNavigation()
                            else SageRail(selected, onDestination)
                        }
                        ContentViewport(Modifier.weight(1f).fillMaxHeight(), fold) { stableContent() }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        ContentViewport(Modifier.weight(1f).fillMaxWidth(), fold) { stableContent() }
                        if (!keyboardVisible) {
                            if (bottomNavigation != null) bottomNavigation()
                            else SageNavigation(selected = selected, onSelected = onDestination)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A content-first screen uses one unobstructed pane and a comfortable centered width.
 * Callers own system/IME insets; this also works inside a sheet whose origin differs from
 * the app window. Local dimensions describe the actual inner content, after hinge and width
 * limits, so artwork sizing never depends on an unrelated outer viewport.
 */
@Composable
fun AdaptiveContentPane(
    modifier: Modifier = Modifier,
    fold: FoldGeometry? = rememberWindowFold(),
    maxContentWidth: Dp = 760.dp,
    content: @Composable () -> Unit,
) {
    require(maxContentWidth > 0.dp)
    HingeSafePane(fold, modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            ContentViewport(
                Modifier.widthIn(max = maxContentWidth).fillMaxWidth().fillMaxHeight(),
                fold,
                content,
            )
        }
    }
}

@Composable
private fun ContentViewport(modifier: Modifier, fold: FoldGeometry?, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val info = AdaptiveWindowInfo(maxWidth.value, maxHeight.value, fold)
        CompositionLocalProvider(LocalAdaptiveWindowInfo provides info, content = content)
    }
}

@Composable
private fun HingeSafePane(fold: FoldGeometry?, modifier: Modifier, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        modifier = modifier.onGloballyPositioned { origin = it.positionInWindow() },
        content = { Box(Modifier.fillMaxSize()) { content() } },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val pane = unobstructedPane(width, height, fold?.relativeTo(origin.x.roundToInt(), origin.y.roundToInt()))
        val child = measurables.single().measure(Constraints.fixed(pane.width, pane.height))
        layout(width, height) { child.place(pane.left, pane.top) }
    }
}

@Composable
private fun SageRail(selected: Destination, onDestination: (Destination) -> Unit) {
    val ink = Color(0xFF172B25)
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = Color(0xFFDDE8E2),
        contentColor = ink,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Destination.entries.forEach { destination ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = { if (destination != selected) onDestination(destination) },
                icon = { NavigationSymbol(destination, ink) },
                label = { Text(destination.label()) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Color(0xFF173B2D),
                    selectedTextColor = Color(0xFF173B2D),
                    indicatorColor = Color(0xFFA8C7AE),
                    unselectedIconColor = ink,
                    unselectedTextColor = ink,
                ),
            )
        }
    }
}

private fun Destination.label(): String = when (this) {
    Destination.HOME -> "Home"
    Destination.BROWSE -> "Browse"
    Destination.SEARCH -> "Search"
    Destination.MY_LIST -> "My List"
}

@Composable
private fun NavigationSymbol(destination: Destination, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height.coerceAtMost(w)
        val s = w / 24f
        val stroke = Stroke(1.8f * s)
        when (destination) {
            Destination.HOME -> {
                val path = Path().apply {
                    moveTo(w * .15f, h * .44f); lineTo(w * .5f, h * .14f); lineTo(w * .85f, h * .44f)
                    lineTo(w * .85f, h * .88f); lineTo(w * .6f, h * .88f); lineTo(w * .6f, h * .6f)
                    lineTo(w * .4f, h * .6f); lineTo(w * .4f, h * .88f); lineTo(w * .15f, h * .88f); close()
                }
                drawPath(path, color, style = stroke)
            }
            Destination.BROWSE -> listOf(.15f to .15f, .58f to .15f, .15f to .58f, .58f to .58f).forEach { (x, y) ->
                drawRect(color, Offset(w * x, h * y), androidx.compose.ui.geometry.Size(w * .27f, h * .27f), style = stroke)
            }
            Destination.SEARCH -> {
                drawCircle(color, w * .27f, Offset(w * .42f, h * .42f), style = stroke)
                drawLine(color, Offset(w * .62f, h * .62f), Offset(w * .9f, h * .9f), strokeWidth = stroke.width)
            }
            Destination.MY_LIST -> {
                val path = Path().apply {
                    moveTo(w * .24f, h * .12f); lineTo(w * .76f, h * .12f); lineTo(w * .76f, h * .9f)
                    lineTo(w * .5f, h * .68f); lineTo(w * .24f, h * .9f); close()
                }
                drawPath(path, color, style = stroke)
            }
        }
    }
}
