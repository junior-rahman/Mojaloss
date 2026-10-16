package com.mojaloss.app.feature.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mojaloss.app.adaptive.LocalAdaptiveWindowInfo
import com.mojaloss.core.model.MediaKind
import com.mojaloss.core.model.MediaTitle
import com.mojaloss.core.ui.ForestButton
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/** A depth cycle: the front card returns underneath while the centered rear card rises to the front. */
@Composable
internal fun FeaturedCarousel(
    titles: List<MediaTitle>,
    canUseLibrary: Boolean,
    onTitle: (MediaTitle) -> Unit,
    onPlay: (MediaTitle) -> Unit,
    preparingTitleId: String? = null,
    onSeeAll: (() -> Unit)? = null,
    selectedId: String? = null,
    onSelectedIdChange: (String) -> Unit = {},
    active: Boolean = true,
) {
    val featured = titles.distinctBy { it.id }
    if (featured.isEmpty()) return
    var frontId by rememberSaveable { mutableStateOf(selectedId ?: featured.first().id) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var pendingTargetId by remember { mutableStateOf<String?>(null) }
    var restingUnderId by remember { mutableStateOf<String?>(null) }
    var transitionDirection by remember { mutableIntStateOf(-1) }
    var animation by remember { mutableStateOf(Animatable(0f)) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    // One navigation per visible Home session; rearmed only after leaving and returning.
    var terminalDispatched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motion = LocalMotionPolicy.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val adaptive = LocalAdaptiveWindowInfo.current
    val liftPx = with(density) { 24.dp.toPx() }
    val flingThreshold = with(density) { 850.dp.toPx() }
    val latestFeatured by rememberUpdatedState(featured)
    val latestSelection by rememberUpdatedState(onSelectedIdChange)
    val latestSeeAll by rememberUpdatedState(onSeeAll)
    val latestActive by rememberUpdatedState(active)
    val visibleTitle = featured.firstOrNull { it.id == frontId } ?: featured.first()
    val visiblePage = featured.indexOfFirst { it.id == visibleTitle.id }
    val direction = if (dragX > 0) -1 else 1
    val underTitle = featured.firstOrNull { it.id == pendingTargetId }
        ?: if (dragging || dragX != 0f) {
            if (onSeeAll != null && visiblePage == featured.lastIndex) null else featured.getOrNull(visiblePage + direction)
        } else
            featured.firstOrNull { it.id == restingUnderId && it.id != visibleTitle.id }
                ?: featured.getOrNull(visiblePage + 1) ?: featured.getOrNull(visiblePage - 1)
    val progress = if (settling) animation.value else featuredDragProgress(dragX, widthPx)
    val incoming = featuredIncomingTransform(progress, liftPx, motion.animationsEnabled)
    val outgoing = featuredOutgoingTransform(progress, widthPx, liftPx,
        if (settling) transitionDirection else if (dragX > 0f) 1 else -1, motion.animationsEnabled)
    val interactive = active && !terminalDispatched && !dragging && !settling

    fun exploreReleases(terminalOnly: Boolean = false): Boolean {
        val action = latestSeeAll ?: return false
        if (!latestActive || terminalDispatched || settling) return false
        // A refresh during the gesture must not turn an old terminal card into navigation.
        if (terminalOnly && latestFeatured.lastOrNull()?.id != visibleTitle.id) return false
        terminalDispatched = true
        dragging = false
        dragX = 0f
        pendingTargetId = null
        action()
        return true
    }

    fun settle(delta: Int?, startOffset: Float = dragX): Boolean {
        if (!latestActive || terminalDispatched || settling) return false
        val target = delta?.let { featured.getOrNull(visiblePage + it) }
        if (delta != null && target == null) return false
        dragging = false
        if (!motion.animationsEnabled) {
            target?.let { restingUnderId = visibleTitle.id; frontId = it.id; latestSelection(it.id) }
            dragX = 0f
            return true
        }
        pendingTargetId = target?.id
        transitionDirection = if ((delta ?: if (startOffset < 0f) 1 else -1) == 1) -1 else 1
        val nextAnimation = Animatable(featuredDragProgress(startOffset, widthPx))
        animation = nextAnimation
        settling = true
        transitionJob = scope.launch {
            try {
                val destination = if (target == null) 0f else 1f
                nextAnimation.animateTo(destination, tween(motion.transitionDurationMillis, easing = FastOutSlowInEasing))
                // A concurrent catalog update may remove the target; never publish a stale selection.
                if (target != null && latestFeatured.any { it.id == target.id }) {
                    restingUnderId = visibleTitle.id
                    frontId = target.id
                    latestSelection(target.id)
                }
            } finally {
                dragX = 0f
                pendingTargetId = null
                settling = false
            }
        }
        return true
    }

    LaunchedEffect(featured.map { it.id }, selectedId) {
        val ids = featured.map { it.id }
        if (dragging || frontId !in ids || pendingTargetId?.let { it !in ids } == true) {
            transitionJob?.cancel()
            dragX = 0f; dragging = false; settling = false; pendingTargetId = null
            frontId = selectedId?.takeIf { it in ids } ?: frontId.takeIf { it in ids } ?: featured.first().id
        } else if (!settling && !dragging && selectedId != null && selectedId in ids && selectedId != frontId) {
            frontId = selectedId
            dragX = 0f
        }
    }
    LaunchedEffect(visibleTitle.id) { latestSelection(visibleTitle.id) }
    LaunchedEffect(active) {
        if (active) terminalDispatched = false
        else {
            transitionJob?.cancel()
            dragX = 0f; dragging = false; settling = false; pendingTargetId = null
        }
    }
    LaunchedEffect(motion.animationsEnabled) {
        if (!motion.animationsEnabled && settling) {
            val target = pendingTargetId
            transitionJob?.cancel()
            if (target != null && latestFeatured.any { it.id == target }) {
                restingUnderId = visibleTitle.id; frontId = target; latestSelection(target)
            }
            dragX = 0f; settling = false; pendingTargetId = null
        }
    }

    Column {
        // Horizontal dragging waits for horizontal touch slop. Vertical drags remain with Home's list.
        Box(Modifier.fillMaxWidth().clipToBounds().testTag("featured-pager")
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                stateDescription = "New releases. Featured card ${visiblePage + 1} of ${featured.size}"
                customActions = buildList {
                    if (!active || terminalDispatched) return@buildList
                    if (visiblePage > 0) add(CustomAccessibilityAction("Previous featured card") { settle(-1, 0f) })
                    if (visiblePage < featured.lastIndex) add(CustomAccessibilityAction("Next featured card") { settle(1, 0f) })
                    if (onSeeAll != null) add(CustomAccessibilityAction("Explore all new releases") { exploreReleases() })
                }
            }
            .draggable(orientation = Orientation.Horizontal,
                enabled = active && !terminalDispatched && !settling && (featured.size > 1 || onSeeAll != null),
                state = rememberDraggableState { amount ->
                    val raw = dragX + amount
                    val candidate = if (raw > 0f) visiblePage - 1 else visiblePage + 1
                    // Both deliberate terminal directions open the same release page; earlier
                    // cards retain ordinary next/previous swipes and first-edge resistance.
                    dragX = if (candidate in featured.indices || onSeeAll != null && visiblePage == featured.lastIndex) raw.coerceIn(-widthPx, widthPx)
                        else raw.coerceIn(-widthPx * .08f, widthPx * .08f)
                },
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    val delta = featuredSwipeDelta(dragX, widthPx, velocity, flingThreshold)
                    when {
                        featuredSwipeOpensReleases(visiblePage, featured.size, delta, onSeeAll != null) -> {
                            if (!exploreReleases(terminalOnly = true)) settle(null)
                        }
                        delta == null || featured.getOrNull(visiblePage + delta) == null -> settle(null)
                        else -> settle(delta)
                    }
                })) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 24.dp)) {
                // Measure all eight possible captions once for this width/font. The deck
                // stays the same height while short names no longer reserve a blank line.
                val textWidth = with(density) { (maxWidth - 32.dp).roundToPx().coerceAtLeast(1) }
                val titleStyle = MaterialTheme.typography.headlineSmall
                val metadataStyle = MaterialTheme.typography.labelMedium
                val largestCaption = remember(featured, textWidth, density.density, density.fontScale, titleStyle, metadataStyle) {
                    featured.maxOf { title ->
                        textMeasurer.measure(title.title, titleStyle, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = textWidth)).size.height +
                            textMeasurer.measure(heroMetadata(title), metadataStyle, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = textWidth)).size.height
                    }
                }
                val captionHeight = with(density) { largestCaption.toDp() } +
                    maxOf(52.dp, with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 28.dp) + 48.dp
                val cardHeight = maxOf(minOf(maxWidth * .92f, adaptive.heroArtworkMaxHeightDp.dp), captionHeight + 104.dp)
                if (underTitle != null && motion.animationsEnabled) Box(Modifier.matchParentSize()
                    .zIndex(if (outgoing.behindIncoming) 1f else 0f)
                    .graphicsLayer {
                        // This invariant intentionally differs from a pager: incoming never comes from a side.
                        translationX = 0f
                        translationY = incoming.translationY
                        scaleX = incoming.scale; scaleY = incoming.scale
                    }.testTag("featured-under-layer").clearAndSetSemantics {
                        hideFromAccessibility(); stateDescription = "Underneath ${underTitle.title}"
                    }) {
                    HeroArtworkCard(underTitle, cardHeight, Modifier.fillMaxSize(), false, {}, {}, false, false)
                }
                Box(Modifier.fillMaxWidth().graphicsLayer {
                    translationX = outgoing.translationX
                    translationY = outgoing.translationY
                    scaleX = outgoing.scale; scaleY = outgoing.scale
                    rotationZ = outgoing.rotationZ
                }.zIndex(if (outgoing.behindIncoming) 0f else 1f).testTag("featured-front-layer")) {
                    HeroArtworkCard(visibleTitle, cardHeight, Modifier.testTag("featured-card-${visibleTitle.id}"), interactive,
                        { onTitle(visibleTitle) }, { onPlay(visibleTitle) }, canUseLibrary, preparingTitleId == visibleTitle.id)
                }
            }
        }
    }
}

@Composable
private fun HeroArtworkCard(title: MediaTitle, height: Dp, modifier: Modifier, interactive: Boolean,
    onTitle: () -> Unit, onPlay: () -> Unit, canUseLibrary: Boolean, preparing: Boolean) {
    val playLabel = when { preparing -> "Preparing…"; title.kind == MediaKind.SERIES -> "View series"; title.confirmedResumeMs > 0 -> "Resume"; else -> "Play" }
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(20.dp))
        .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(20.dp))
        .clickable(enabled = interactive, onClickLabel = "Open ${title.title}", onClick = onTitle)
        .semantics { contentDescription = "Details for ${title.title}" }) {
        TitleArtwork(title, Modifier.fillMaxSize(), backdrop = true)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag("featured-caption-${title.id}")) {
            Spacer(Modifier.fillMaxWidth().height(38.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .76f)))))
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .76f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title.title, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(heroMetadata(title), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .95f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                ForestButton(playLabel, if (title.kind == MediaKind.SERIES) onTitle else onPlay,
                    modifier = Modifier.fillMaxWidth().testTag("featured-play-${title.id}").semantics { contentDescription = "$playLabel ${title.title}" },
                    enabled = interactive && canUseLibrary && !preparing && (title.kind == MediaKind.SERIES || title.isPlayable),
                    icon = if (title.kind == MediaKind.SERIES) Icons.Outlined.LiveTv else Icons.Outlined.PlayArrow, busy = preparing)
            }
        }
    }
}

private fun heroMetadata(title: MediaTitle) = listOf(titleMetadata(title), durationLabel(title.runtimeMs)).filter(String::isNotBlank).joinToString(" · ")

/** Right is explicitly supported at the final real title; forward/left works there too. */
internal fun featuredSwipeOpensReleases(page: Int, total: Int, delta: Int?, canExplore: Boolean): Boolean =
    canExplore && total > 0 && page == total - 1 && (delta == -1 || delta == 1)

internal data class FeaturedIncomingTransform(val translationX: Float = 0f, val translationY: Float, val scale: Float)

/** Holding a long drag previews the change; the depth handoff happens only after a committed swipe. */
internal fun featuredDragProgress(displacement: Float, width: Float): Float =
    (abs(displacement) / width.coerceAtLeast(1f)).coerceIn(0f, .45f)

internal fun featuredIncomingTransform(progress: Float, liftPx: Float, animationsEnabled: Boolean): FeaturedIncomingTransform {
    val bounded = progress.coerceIn(0f, 1f)
    return if (animationsEnabled) FeaturedIncomingTransform(translationY = liftPx * (1f - bounded), scale = .94f + .06f * bounded)
    else FeaturedIncomingTransform(translationY = 0f, scale = 1f)
}

internal data class FeaturedOutgoingTransform(val translationX: Float = 0f, val translationY: Float = 0f,
    val scale: Float = 1f, val rotationZ: Float = 0f, val behindIncoming: Boolean = false)

internal fun featuredOutgoingTransform(progress: Float, width: Float, liftPx: Float, direction: Int,
    animationsEnabled: Boolean): FeaturedOutgoingTransform {
    if (!animationsEnabled) return FeaturedOutgoingTransform()
    val bounded = progress.coerceIn(0f, 1f)
    // A small directional arc communicates the gesture, then returns fully to the centered rear slot.
    // Neither card ever travels offscreen. The incoming layer covers this card after the depth handoff.
    val arc = if (bounded == 0f || bounded == 1f) 0f else sin(Math.PI * bounded).toFloat()
    val signedArc = if (arc == 0f) 0f else arc * if (direction > 0) 1f else -1f
    return FeaturedOutgoingTransform(translationX = width.coerceAtLeast(1f) * .14f * signedArc,
        translationY = liftPx * bounded, scale = 1f - .06f * bounded,
        rotationZ = 1.8f * signedArc, behindIncoming = bounded >= .5f)
}

internal fun featuredSwipeDelta(displacement: Float, width: Float, velocity: Float, flingThreshold: Float): Int? {
    val distance = abs(displacement)
    if (distance < width.coerceAtLeast(1f) * .24f && !(distance > width * .06f && abs(velocity) >= flingThreshold)) return null
    return if (displacement < 0f) 1 else if (displacement > 0f) -1 else null
}
