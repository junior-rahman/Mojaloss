package com.mojaloss.app.feature.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.*
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.motionArrival
import com.mojaloss.core.ui.motion.motionPressFeedback
import com.mojaloss.app.adaptive.LocalAdaptiveWindowInfo
import java.security.MessageDigest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay

/** The coordinator supplies one Account/Settings entry for connected catalog pages. */
val LocalAccountAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The coordinator owns refresh and the sanitized diagnostic-copy action. */
val LocalCatalogRefresh = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalCatalogLoadMore = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalErrorReportCopy = staticCompositionLocalOf<(() -> Unit)?> { null }

/** All service state and navigation come from the single app coordinator. */
@Composable
fun HomeScreen(
    snapshot: CatalogSnapshot, onTitle: (MediaTitle) -> Unit, onPlay: (MediaTitle) -> Unit,
    featuredScopeKey: Any? = null, onKindChange: ((MediaKind?) -> Unit)? = null,
    resumeState: HomeResumeState = HomeResumeState(ContentStatus.READY),
    platformState: PlatformCollectionsState = PlatformCollectionsState(ContentStatus.READY),
    onResumeRetry: (() -> Unit)? = null, onRequestPlatforms: (() -> Unit)? = null,
    onPlatformSelected: ((PlatformCollection) -> Unit)? = null,
    onSeeAll: (() -> Unit)? = null, onMyList: (() -> Unit)? = null, preparingTitleId: String? = null,
    feedActive: Boolean = true,
    shelfStates: Map<HomeShelfKind, HomeShelfState> = emptyMap(),
    onRequestShelf: ((HomeShelfKind) -> Unit)? = null, onShelfRetry: ((HomeShelfKind) -> Unit)? = null,
    onShelfLoadMore: ((HomeShelfKind) -> Unit)? = null,
    onShelfSourceSelected: ((HomeShelfKind, PlatformCollection) -> Unit)? = null,
    onShelfSeeAll: ((HomeShelfKind, PlatformCollection) -> Unit)? = null,
    onDownloads: (() -> Unit)? = null,
    recentUploadsState: HomeResumeState? = null,
    onRecentUploadsSeeAll: (() -> Unit)? = null,
    onRequestRecentUploads: (() -> Unit)? = null,
) {
    val titles = snapshot.titles.distinctBy { it.id }
    val motion = LocalMotionPolicy.current
    val resumeTitles = resumeState.titles.distinctBy { it.id }
    var selectedFeaturedId by rememberSaveable(featuredScopeKey) { mutableStateOf<String?>(null) }
    val retainedFeatured = titles.firstOrNull { it.id == selectedFeaturedId }
    val featuredTitles = titles.take(8).toMutableList().also { featured ->
        if (retainedFeatured != null && featured.none { it.id == retainedFeatured.id }) {
            if (featured.size == 8) featured.removeAt(featured.lastIndex)
            featured += retainedFeatured
        }
    }
    val listState = rememberLazyListState()
    val platformVisible by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "platforms" } } }
    val recentUploadsVisible by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "recent-uploads-bottom" } } }
    val visibleShelves by remember(listState) { derivedStateOf {
        val keys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        HomeShelfKind.entries.filter { "shelf:${it.name}" in keys }
    } }
    val requestPlatforms by rememberUpdatedState(onRequestPlatforms)
    val requestShelf by rememberUpdatedState(onRequestShelf)
    val requestRecentUploads by rememberUpdatedState(onRequestRecentUploads)
    LaunchedEffect(platformVisible, snapshot.isConnectionVerified, snapshot.isDemo, feedActive) {
        if (feedActive && platformVisible && snapshot.isConnectionVerified && !snapshot.isDemo) requestPlatforms?.invoke()
    }
    LaunchedEffect(visibleShelves, feedActive, snapshot.isConnectionVerified, snapshot.isDemo) {
        if (feedActive && snapshot.isConnectionVerified && !snapshot.isDemo) visibleShelves.forEach { requestShelf?.invoke(it) }
    }
    LaunchedEffect(recentUploadsVisible, feedActive, snapshot.isConnectionVerified, snapshot.isDemo) {
        if (feedActive && recentUploadsVisible && snapshot.isConnectionVerified && !snapshot.isDemo) requestRecentUploads?.invoke()
    }
    val shelfSourceIds = shelfStates.values.flatMap { it.sources }.map { it.id }.toSet()
    CatalogRefreshContainer(snapshot) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().motionArrival("home", motion).testTag("home-catalog"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "brand") { CompactCatalogHeader("MojaLoss", trailing = {
                onMyList?.let { action -> IconButton(onClick = action, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.BookmarkBorder, "My List", Modifier.size(22.dp), tint = MojaLossColors.Forest) } }
                onDownloads?.let { action -> IconButton(onClick = action, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Download, "Downloads", Modifier.size(22.dp), tint = MojaLossColors.Forest) } }
            }) }
            if (needsCatalogNotice(snapshot)) item(key = "catalog-state") { CatalogNotice(snapshot) }
            if (titles.isNotEmpty()) item(key = "featured") {
                key(featuredScopeKey) { FeaturedCarousel(featuredTitles, snapshot.canUseLibrary, onTitle, onPlay, preparingTitleId, onSeeAll,
                    selectedId = selectedFeaturedId, onSelectedIdChange = { selectedFeaturedId = it }, active = feedActive) }
            } else if (snapshot.status == ContentStatus.LOADING) item(key = "recent-skeletons") { RecentStackSkeleton() }
            else if (confirmedCatalog(snapshot)) item {
                EmptyLibraryPanel()
                onSeeAll?.let { action -> TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text("Explore library") } }
            }
            if (resumeTitles.isNotEmpty() || resumeState.status != ContentStatus.READY) item(key = "continue") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading("Continue watching")
                    if (resumeState.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(resumeState.message ?: "Couldn’t update progress", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                        onResumeRetry?.let { retry -> TextButton(onClick = retry) { Text(if (snapshot.isConnectionVerified) "Retry" else "Reconnect") } }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listItems(resumeTitles, key = { it.id }) { title -> ResumeCard(title, snapshot.canUseLibrary, preparingTitleId == title.id, { onTitle(title) }, { onPlay(title) }) } }
                    if (resumeTitles.isEmpty() && resumeState.status == ContentStatus.LOADING) Row(Modifier.testTag("resume-skeletons").clearAndSetSemantics { contentDescription = "Loading your progress" }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(2) { ResumeSkeleton() }
                    }
                }
            }
            item(key = "platforms") { PlatformSection(platformState, onRequestPlatforms, onPlatformSelected, snapshot.isConnectionVerified) }
            HomeShelfKind.entries.forEach { kind ->
                if (kind in shelfStates || onRequestShelf != null && !snapshot.isDemo) item(key = "shelf:${kind.name}") {
                    HomeCatalogShelf(kind, shelfStates[kind] ?: HomeShelfState(), snapshot.isConnectionVerified, feedActive, onTitle,
                        onShelfRetry?.let { callback -> { callback(kind) } }, onShelfLoadMore?.let { callback -> { callback(kind) } },
                        onShelfSeeAll?.let { callback -> { source -> callback(kind, source) } })
                }
            }
            item(key = "collections-bottom") { CollectionSection(platformState, shelfSourceIds, onPlatformSelected) }
            if (!snapshot.isDemo && (recentUploadsState != null || onRequestRecentUploads != null)) item(key = "recent-uploads-bottom") {
                RecentUploadsSection(recentUploadsState ?: HomeResumeState(), snapshot.isConnectionVerified,
                    onTitle, onRecentUploadsSeeAll, onRequestRecentUploads)
            }
        }
    }
}

@Composable
private fun HomeCatalogShelf(
    kind: HomeShelfKind, state: HomeShelfState, verified: Boolean, active: Boolean,
    onTitle: (MediaTitle) -> Unit, onRetry: (() -> Unit)?, onLoadMore: (() -> Unit)?,
    onSeeAll: ((PlatformCollection) -> Unit)?,
) {
    val rowState = rememberSaveable(kind.name, state.selectedSource?.id, saver = LazyListState.Saver) { LazyListState() }
    val motion = LocalMotionPolicy.current
    val posterWidth = posterMinimumWidth(140.dp)
    val titles = state.titles.distinctBy { it.id }
    if (active) ObserveSectionDemand(rowState, "${kind.name}:${state.selectedSource?.id}", verified && state.status == ContentStatus.READY && state.hasMore && !state.isLoadingMore && !state.isRefreshing, onLoadMore)
    // The repository resolves a real server group automatically. Home presents its
    // titles directly rather than turning each language row into a setup question.
    val emptyReady = state.status == ContentStatus.READY && titles.isEmpty() && !state.hasMore
    if (emptyReady || !verified && titles.isEmpty() && state.sources.isEmpty()) { Box(Modifier.fillMaxWidth().height(1.dp).testTag("shelf-anchor-${kind.name}")); return }
    Column(Modifier.testTag("shelf-${kind.name}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(kind.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            state.selectedSource?.let { source -> if (onSeeAll != null) TextButton(onClick = { onSeeAll(source) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("See all") } }
        }
        if (state.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.message ?: "Titles could not load", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
            onRetry?.let { retry -> TextButton(onClick = retry) { Text(if (verified) "Retry" else "Reconnect") } }
        }
        if (state.status == ContentStatus.LOADING && titles.isEmpty()) {
            // Reserve normal poster space so one loading row cannot expose every later row.
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading ${kind.displayName}" }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) { SkeletonCard(Modifier.width(posterWidth)) }
            }
        } else if (titles.isNotEmpty()) LazyRow(state = rowState, modifier = Modifier.testTag("shelf-row-${kind.name}").semantics {
            if (active && verified && state.hasMore && !state.isLoadingMore && !state.isRefreshing && onLoadMore != null) customActions = listOf(CustomAccessibilityAction("Load next titles") { onLoadMore(); true })
            if (state.isLoadingMore) stateDescription = "Loading more ${kind.displayName}"
        }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listItems(titles, key = { it.id }) { title -> PosterCard(title, { onTitle(title) }, Modifier.width(posterWidth).animateItem(fadeInSpec = if (motion.animationsEnabled) tween(180) else null, placementSpec = null, fadeOutSpec = null)) }
            if (state.isLoadingMore) items(2, key = { "append-skeleton-$it" }) { SkeletonCard(Modifier.width(posterWidth).testTag("shelf-skeleton-${kind.name}-$it")) }
        }
    }
}

/** Upload date is deliberately separated from the release-date-led discovery feed. */
@Composable
private fun RecentUploadsSection(state: HomeResumeState, verified: Boolean, onTitle: (MediaTitle) -> Unit,
    onSeeAll: (() -> Unit)?, onRetry: (() -> Unit)?) {
    val titles = state.titles.distinctBy { it.id }.take(12)
    if (titles.isEmpty() && state.status == ContentStatus.READY) {
        Box(Modifier.fillMaxWidth().height(1.dp).testTag("recent-uploads-anchor"))
        return
    }
    val width = posterMinimumWidth(140.dp)
    Column(Modifier.testTag("home-recent-uploads"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recently uploaded", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            onSeeAll?.let { action -> TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text("See all") } }
        }
        if (titles.isEmpty() && state.status == ContentStatus.LOADING) Row(Modifier.clearAndSetSemantics {
            contentDescription = "Loading recently uploaded titles"
        }, horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SkeletonCard(Modifier.width(width)) } }
        else if (titles.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listItems(titles, key = { it.id }) { title -> PosterCard(title, { onTitle(title) }, Modifier.width(width)) }
        }
        if (state.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.message ?: if (titles.isEmpty()) "Recently uploaded titles couldn’t load." else "Couldn’t update. Available titles are still here.", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
            if (verified) onRetry?.let { retry -> TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp).testTag("recent-uploads-retry")) { Text("Retry") } }
        }
    }
}

@Composable
private fun CollectionSection(state: PlatformCollectionsState, shelfSourceIds: Set<String>, onSelected: ((PlatformCollection) -> Unit)?) {
    val platformIds = remember(state.collections) { recognizedPlatforms(state.collections).flatMap { it.targets }.map { it.id }.toSet() }
    val collections = state.collections.filterNot { it.id in platformIds || it.id in shelfSourceIds }.distinctBy { it.id }
    if (collections.isEmpty()) return
    Column(Modifier.testTag("home-collections"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Collections")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listItems(collections, key = { it.id }) { collection ->
            OutlinedButton(onClick = { onSelected?.invoke(collection) }, enabled = onSelected != null, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 220.dp), shape = RoundedCornerShape(14.dp)) { Text(collection.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } }
    }
}

@Composable
private fun PlatformSection(state: PlatformCollectionsState, onRetry: (() -> Unit)?, onSelected: ((PlatformCollection) -> Unit)?, verified: Boolean) {
    val platforms = remember(state.collections) { recognizedPlatforms(state.collections) }
    // A real viewport anchor starts bounded metadata discovery without branded placeholders.
    // Empty/unrecognized sources do not consume a visible shelf or imply platform availability.
    if (platforms.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(1.dp).testTag("home-platform-anchor"))
        if (verified && state.status == ContentStatus.ERROR && onRetry != null) TextButton(onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = "Platforms could not load" }) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry platforms")
        }
        return
    }
    Column(Modifier.testTag("home-platform-anchor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Streaming platforms")
        LazyRow(modifier = Modifier.testTag("home-platforms"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listItems(platforms, key = { it.brand.name }) { platform -> PlatformButton(platform, onSelected) }
        }
        if (state.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE) && onRetry != null) TextButton(onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (verified) "Retry platforms" else "Reconnect") }
    }
}

@Composable
private fun PlatformButton(platform: PlatformGroup, onSelected: ((PlatformCollection) -> Unit)?) {
    var expanded by remember(platform.brand) { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val motion = LocalMotionPolicy.current
    val label = platform.brand.displayName
    Box {
        OutlinedButton(onClick = {
            if (platform.targets.size == 1) onSelected?.invoke(platform.targets.single()) else expanded = true
        }, enabled = onSelected != null, interactionSource = interactions, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 88.dp).motionPressFeedback(interactions, motion).testTag("platform-${platform.brand.name}").semantics {
                stateDescription = when { expanded -> "Choices expanded"; platform.targets.size > 1 -> "${platform.targets.size} choices"; else -> "Browse $label" }
                onClick(label = if (platform.targets.size > 1) "Choose what to browse on $label" else "Browse $label", action = null)
            }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MojaLossColors.Forest, containerColor = MojaLossColors.ControlGlass)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (platform.targets.size > 1) { Spacer(Modifier.width(5.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            platform.targets.forEach { target -> DropdownMenuItem(text = { Text(target.name) }, onClick = { expanded = false; onSelected?.invoke(target) }) }
        }
    }
}

@Composable
private fun ResumeCard(title: MediaTitle, canUseLibrary: Boolean, preparing: Boolean, onTitle: () -> Unit, onPlay: () -> Unit) {
    val width = 184.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.35f)
    val interactions = remember { MutableInteractionSource() }
    val motion = LocalMotionPolicy.current
    GlassSurface(Modifier.width(width).motionPressFeedback(interactions, motion).clickable(interactionSource = interactions, indication = ripple(), enabled = canUseLibrary && title.isPlayable && !preparing,
        onClickLabel = "Resume ${title.title}", onClick = onPlay).semantics(mergeDescendants = true) {
        contentDescription = "Resume ${title.title}"
        if (preparing) stateDescription = "Preparing playback"
    }, 15.dp) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))) {
                TitleArtwork(title, Modifier.fillMaxSize(), backdrop = true)
                Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .62f)), contentAlignment = Alignment.Center) {
                    if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Outlined.PlayArrow, null, Modifier.size(27.dp), tint = Color.White)
                }
            }
            if (title.runtimeMs > 0) LinearProgressIndicator(progress = { (title.confirmedResumeMs.toFloat() / title.runtimeMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = MojaLossColors.Forest, trackColor = MojaLossColors.Sage)
            Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    title.year?.takeIf { it in 1888..2100 }?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MojaLossColors.SecondaryInk) }
                    Text("Resume at ${positionLabel(title.confirmedResumeMs)}", style = MaterialTheme.typography.labelSmall, color = MojaLossColors.SecondaryInk)
                }
                IconButton(onClick = onTitle, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Info, "Details for ${title.title}", tint = MojaLossColors.Forest)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    snapshot: CatalogSnapshot, filter: BrowseFilter, onFilter: (BrowseFilter) -> Unit, onTitle: (MediaTitle) -> Unit,
    genreOptions: List<String> = emptyList(), genreStatus: ContentStatus = ContentStatus.READY,
    genreError: String? = null, onRequestGenres: ((MediaKind?, String) -> Unit)? = null,
    heading: String = "Browse", onClose: (() -> Unit)? = null,
) {
    val titles = if (snapshot.isDemo) filteredTitles(snapshot.titles, filter) else snapshot.titles
    val motion = LocalMotionPolicy.current
    // Facets are independently fetched on sheet-open, never inferred from a filtered page.
    val genres = if (snapshot.isDemo) snapshot.titles.flatMap { it.genre.split(" / ") }.distinct().sorted() else genreOptions
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var draftKind by rememberSaveable { mutableStateOf<String?>(null) }
    var draftGenre by rememberSaveable { mutableStateOf("") }
    var draftYear by rememberSaveable { mutableStateOf("") }
    var draftSort by rememberSaveable { mutableStateOf("Release newest") }
    val activeCount = listOf(filter.kind != null, filter.genre != null, filter.year != null, filter.sort != "Release newest").count { it }
    LaunchedEffect(showFilters, draftKind, draftGenre) {
        if (showFilters) {
            if (draftGenre.isNotBlank()) delay(300)
            onRequestGenres?.invoke(draftKind?.let(MediaKind::valueOf), draftGenre.trim())
        }
    }
    val gridState = rememberSaveable(catalogStateKey(snapshot.query), saver = LazyGridState.Saver) { LazyGridState() }
    ObserveCatalogDemand(snapshot, gridState)
    Column(Modifier.fillMaxSize()) {
        Surface(color = MojaLossColors.Porcelain.copy(alpha = .96f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                CompactCatalogHeader(heading, onClose = onClose, trailing = {
                    TextButton(onClick = {
                        draftKind = filter.kind?.name; draftGenre = filter.genre.orEmpty(); draftYear = filter.year?.toString().orEmpty(); draftSort = filter.sort
                        showFilters = true
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp))
                        Text(if (activeCount == 0) "Filters" else "Filters · $activeCount")
                    }
                })
                if (activeCount > 0) Text(listOfNotNull(filter.kind?.let { if (it == MediaKind.MOVIE) "Movies" else "Series" }, filter.genre, filter.year?.toString(), filter.sort.takeIf { it != "Release newest" }?.let(::catalogSortLabel)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        CatalogRefreshContainer(snapshot, Modifier.weight(1f)) {
            if (snapshot.titles.isEmpty() && snapshot.status == ContentStatus.LOADING) {
                // Do not compose an empty grid over a restored deep scroll anchor.
                CatalogSkeletons()
            } else LazyVerticalGrid(
                state = gridState, columns = GridCells.Adaptive(posterMinimumWidth()), modifier = Modifier.fillMaxSize().testTag("browse-catalog").catalogPageActions(snapshot),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (needsCatalogNotice(snapshot)) item(span = { GridItemSpan(maxLineSpan) }) { CatalogNotice(snapshot) }
                if (titles.isEmpty() && confirmedCatalog(snapshot)) item(span = { GridItemSpan(maxLineSpan) }) {
                    if (activeCount == 0) EmptyLibraryPanel()
                    else {
                        CompactEmpty("No titles match", "Try another filter.")
                        TextButton(onClick = { onFilter(BrowseFilter()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear filters") }
                    }
                }
                gridItems(titles, key = { it.id }) { title -> PosterCard(title, { onTitle(title) }, Modifier.animateItem(fadeInSpec = if (motion.animationsEnabled) tween(180) else null, placementSpec = null, fadeOutSpec = null)) }
                if (snapshot.isLoadingMore) items(2, key = { "append-skeleton-$it" }) { SkeletonCard(Modifier.testTag("catalog-append-skeleton-$it")) }
                item(span = { GridItemSpan(maxLineSpan) }) { CatalogPagingFooter(snapshot) }
            }
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false },
        // A long form must open at its usable height. A partial sheet measures the list
        // at the expanded size while concealing its lower controls outside the window.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().imePadding().testTag("browse-filter-options"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Filter titles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { draftKind = null; draftGenre = ""; draftYear = ""; draftSort = "Release newest" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
                }
            }
            item { CompactKinds(when (draftKind) { MediaKind.MOVIE.name -> "Movies"; MediaKind.SERIES.name -> "Series"; else -> "All" }) {
                draftKind = when (it) { "Movies" -> MediaKind.MOVIE.name; "Series" -> MediaKind.SERIES.name; else -> null }
            } }
            item {
                OutlinedTextField(draftGenre, { draftGenre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Genre") },
                    supportingText = { Text("Choose a genre below, or enter its name.") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                when (genreStatus) {
                    ContentStatus.LOADING -> Text("Loading genres…", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                    ContentStatus.ERROR, ContentStatus.OFFLINE -> {
                        Text(genreError ?: "Genres could not load. You can still enter a genre.", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                        onRequestGenres?.let { retry -> TextButton(onClick = { retry(draftKind?.let(MediaKind::valueOf), draftGenre.trim()) }) { Text("Retry genres") } }
                    }
                    ContentStatus.READY -> Unit
                }
                if (genres.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listItems(genres) { genre -> TextButton(onClick = { draftGenre = genre }, modifier = Modifier.heightIn(min = 48.dp)) { Text(genre) } }
                }
            }
            item {
                val yearInvalid = draftYear.isNotBlank() && draftYear.toIntOrNull()?.let { it !in 1888..2100 } != false
                OutlinedTextField(draftYear, { draftYear = it.filter(Char::isDigit).take(4) }, modifier = Modifier.fillMaxWidth(), label = { Text("Year") },
                    supportingText = if (yearInvalid) { { Text("Enter a year from 1888 to 2100.") } } else null, isError = yearInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = RoundedCornerShape(14.dp))
            }
            item { MenuSelector(catalogSortLabel(draftSort), listOf("Newest releases", "Title A–Z", "Recently uploaded"), Modifier.fillMaxWidth(), settingTitle = "Sort") { label ->
                draftSort = when (label) { "Recently uploaded" -> "Recently added"; "Title A–Z" -> "Title"; else -> "Release newest" }
            } }
            item { ForestButton("Apply filters", onClick = {
                onFilter(BrowseFilter(kind = draftKind?.let(MediaKind::valueOf), genre = draftGenre.trim().takeIf { it.isNotBlank() }, year = draftYear.toIntOrNull(), sort = draftSort))
                showFilters = false
            }, modifier = Modifier.fillMaxWidth(), enabled = draftYear.isBlank() || draftYear.toIntOrNull()?.let { it in 1888..2100 } == true) }
        }
    }
}

@Composable
fun SearchScreen(snapshot: CatalogSnapshot, query: String, onQuery: (String) -> Unit, onTitle: (MediaTitle) -> Unit, onClose: (() -> Unit)? = null) {
    val motion = LocalMotionPolicy.current
    val trimmed = query.trim().take(512)
    val queryMatches = snapshot.isDemo || (snapshot.query.destination == Destination.SEARCH && snapshot.query.search.trim() == trimmed)
    val results = if (trimmed.isEmpty() || !queryMatches) emptyList() else if (!snapshot.isDemo) snapshot.titles else snapshot.titles.filter { it.title.contains(trimmed, ignoreCase = true) }
    val gridState = rememberSaveable(trimmed, saver = LazyGridState.Saver) { LazyGridState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    if (trimmed.isNotEmpty() && queryMatches) ObserveCatalogDemand(snapshot, gridState)
    Column(Modifier.fillMaxSize()) {
        Surface(color = MojaLossColors.Porcelain.copy(alpha = .96f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("Search movies and series") }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
                    trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(20.dp)) } } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }), shape = RoundedCornerShape(16.dp))
                if (onClose != null) CatalogCloseAction(onClose) else CompactAccountAction()
            }
        }
        CatalogRefreshContainer(snapshot, Modifier.weight(1f), enabled = trimmed.isNotEmpty() && queryMatches) {
            if (trimmed.isNotEmpty() && (!queryMatches || snapshot.titles.isEmpty() && snapshot.status == ContentStatus.LOADING)) {
                CatalogSkeletons()
            } else LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(posterMinimumWidth()), modifier = Modifier.fillMaxSize().testTag("search-catalog").catalogPageActions(snapshot),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (trimmed.isNotEmpty() && queryMatches && needsCatalogNotice(snapshot)) item(span = { GridItemSpan(maxLineSpan) }) { CatalogNotice(snapshot) }
                if (trimmed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Find a title", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk, modifier = Modifier.padding(top = 8.dp)) }
                else if (queryMatches && results.isEmpty() && confirmedCatalog(snapshot)) item(span = { GridItemSpan(maxLineSpan) }) { CompactEmpty("No results for “$trimmed”", "Try another title or spelling.") }
                else gridItems(results, key = { it.id }) { title -> PosterCard(title, { focus.clearFocus(); keyboard?.hide(); onTitle(title) }, Modifier.animateItem(fadeInSpec = if (motion.animationsEnabled) tween(180) else null, placementSpec = null, fadeOutSpec = null)) }
                if (trimmed.isNotEmpty() && queryMatches && snapshot.isLoadingMore) items(2, key = { "append-skeleton-$it" }) { SkeletonCard(Modifier.testTag("catalog-append-skeleton-$it")) }
                if (trimmed.isNotEmpty() && queryMatches) item(span = { GridItemSpan(maxLineSpan) }) { CatalogPagingFooter(snapshot) }
            }
        }
    }
}

@Composable
fun MyListScreen(snapshot: CatalogSnapshot, favorites: Map<String, FavoriteState>, onTitle: (MediaTitle) -> Unit, onRemove: (MediaTitle) -> Unit, onClose: (() -> Unit)? = null) {
    val motion = LocalMotionPolicy.current
    val saved = snapshot.titles.filter { favorites[it.id]?.let { favorite -> favorite.confirmed || favorite.pending || favorite.error != null }
        ?: (snapshot.query.destination == Destination.MY_LIST || it.favorite) }
    val listState = rememberLazyListState()
    ObserveCatalogDemand(snapshot, listState)
    Column(Modifier.fillMaxSize()) {
        Surface(color = MojaLossColors.Porcelain.copy(alpha = .96f)) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { CompactCatalogHeader("My List", subtitle = if (saved.isNotEmpty()) "${saved.size} shown" else null, onClose = onClose) }
        }
        CatalogRefreshContainer(snapshot, Modifier.weight(1f)) {
        if (snapshot.titles.isEmpty() && snapshot.status == ContentStatus.LOADING) LazyColumn(Modifier.fillMaxSize().testTag("catalog-skeletons").clearAndSetSemantics { contentDescription = "Loading saved titles" }, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(5) { SavedTitleSkeleton() } }
        else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("saved-catalog").catalogPageActions(snapshot), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (needsCatalogNotice(snapshot)) item { CatalogNotice(snapshot) }
        if (saved.isEmpty() && confirmedCatalog(snapshot)) {
            item { CompactEmpty("No saved titles yet", "Save a title from its details page.") }
        } else if (saved.isNotEmpty()) {
            listItems(saved, key = { it.id }) { title ->
                val favorite = favorites[title.id] ?: FavoriteState(confirmed = title.favorite || snapshot.query.destination == Destination.MY_LIST, desired = title.favorite)
                GlassSurface(Modifier.fillMaxWidth().animateItem(fadeInSpec = if (motion.animationsEnabled) tween(180) else null, placementSpec = null, fadeOutSpec = null), 15.dp) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.weight(1f).clickable { onTitle(title) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TitleArtwork(title, Modifier.width(68.dp).height(88.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(titleMetadata(title), style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (favorite.pending) Text(if (favorite.desired) "Saving…" else "Removing…", color = MojaLossColors.Forest, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            IconButton(onClick = { onRemove(title) }, enabled = !favorite.pending && snapshot.canUseLibrary, modifier = Modifier.size(48.dp)) {
                                if (favorite.pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.BookmarkRemove, "Remove ${title.title} from My List", Modifier.size(21.dp))
                            }
                        }
                        favorite.error?.let { ErrorCopy(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    }
                }
            }
        }
        if (snapshot.isLoadingMore) items(2, key = { "append-skeleton-$it" }) { SavedTitleSkeleton(Modifier.testTag("catalog-append-skeleton-$it")) }
        item { CatalogPagingFooter(snapshot) }
    }
        }
    }
}

@Composable
fun ConnectScreen(session: SessionState, onQuickConnect: (String) -> Unit, onPassword: (String, String, String) -> Unit, onCancel: () -> Unit, onPreview: (() -> Unit)? = null, initialServerUrl: String = "", onCopyErrorReport: (() -> Unit)? = null, onCheckUpdates: (() -> Unit)? = null) {
    var server by rememberSaveable { mutableStateOf(initialServerUrl) }
    var providerCredentials by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishEntry(action: () -> Unit) {
        focusManager.clearFocus()
        keyboard?.hide()
        action()
    }
    var codeRemainingSeconds by remember(session) { mutableStateOf<Long?>(null) }
    LaunchedEffect(session) {
        val waiting = session as? SessionState.Waiting ?: return@LaunchedEffect
        val expiry = waiting.expiresAtEpochMs ?: return@LaunchedEffect
        while (true) {
            codeRemainingSeconds = ((expiry - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            if (codeRemainingSeconds == 0L) break
            delay(1000)
        }
    }
    val busy = session == SessionState.Connecting || session is SessionState.Waiting || session is SessionState.Restoring
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { BrandHeader() }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Your stories.\nOne beautiful home.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))
            Text("Connect the server you already use to bring your movies, series and saved titles together.", color = MojaLossColors.SecondaryInk, style = MaterialTheme.typography.bodyLarge)
        }
        item {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Connect to MojaLoss", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = server, onValueChange = { server = it }, enabled = !busy, label = { Text("Server URL") }, placeholder = { Text("https://your-server") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, shape = RoundedCornerShape(13.dp))
                    Text("Use the exact server address from your working Jellyfin app.", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
                    when (session) {
                        SessionState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Connecting…") }
                        is SessionState.Restoring -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Checking your saved account…") }
                        is SessionState.Waiting -> {
                            Text("Approve this connection", style = MaterialTheme.typography.titleMedium)
                            Text(session.code, style = MaterialTheme.typography.headlineLarge, color = MojaLossColors.Forest)
                            Text("On a device already signed in to your server, open Settings → Quick Connect and enter this code.", style = MaterialTheme.typography.bodyMedium)
                            codeRemainingSeconds?.let { remaining -> Text(if (remaining > 0) "Code expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}" else "Code expired. Request a new code.", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk) }
                        }
                        is SessionState.Failed -> ErrorCopy(session.message)
                        is SessionState.Connected -> { Text("Connected as ${session.account.displayName}", color = MojaLossColors.Forest, style = MaterialTheme.typography.titleMedium) }
                        SessionState.SignedOut -> Unit
                    }
                    if (session !is SessionState.Connected) {
                        ForestButton("Use Quick Connect", { finishEntry { onQuickConnect(server.trim()) } }, enabled = server.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Link)
                        if (busy) TextButton(onClick = onCancel) { Text("Cancel connection") }
                        else {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = providerCredentials, role = Role.Checkbox, onValueChange = { providerCredentials = it })) {
                                Checkbox(checked = providerCredentials, onCheckedChange = null)
                                Text("My provider issued a username and password", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                            if (providerCredentials) {
                                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                                ForestButton("Connect with credentials", { finishEntry { onPassword(server.trim(), username.trim(), password) } }, modifier = Modifier.fillMaxWidth(), enabled = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty())
                            }
                        }
                    }
                }
            }
        }
        if (!busy && onCopyErrorReport != null) {
            item { TextButton(onClick = onCopyErrorReport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy error report") } }
        }
        if (onCheckUpdates != null) {
            item { TextButton(onClick = onCheckUpdates, modifier = Modifier.heightIn(min = 48.dp).testTag("connect-app-updates")) { Text("Check for updates") } }
        }
        if (!busy && session !is SessionState.Connected && onPreview != null) {
            item {
                TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Preview the design") }
                Text("Sample titles only. Playback and account changes stay disabled.", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
            }
        }
    }
}

@Composable
fun SettingsScreen(preferences: AppPreferences, accountName: String?, onPreferences: (AppPreferences) -> Unit, onSignOut: () -> Unit, onBack: () -> Unit, accountVerified: Boolean = true, onReconnect: (() -> Unit)? = null, onCheckUpdates: (() -> Unit)? = null, appVersion: String? = null, onCopyErrorReport: (() -> Unit)? = null) {
    var showSignOut by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeader("Settings", onBack) }
        item {
            GlassSurface(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, null, Modifier.size(40.dp), tint = MojaLossColors.Forest)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(accountName ?: "Not connected", style = MaterialTheme.typography.titleMedium); Text(when { accountName == null -> "Connect to access your library"; accountVerified -> "Your connected account"; else -> "Saved account" }, style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk) }
                }
            }
        }
        if (accountName != null && !accountVerified && onReconnect != null) item { ForestButton("Reconnect account", onReconnect, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Link) }
        item { SectionHeading("Playback") }
        item {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    PreferenceSelector("Audio language", "Used when this language is available.", preferences.audioLanguage, listOf("System", "English", "Bengali", "Hindi", "Spanish")) { onPreferences(preferences.copy(audioLanguage = it)) }
                    HorizontalDivider(color = MojaLossColors.NavigationGlass)
                    PreferenceSelector("Subtitles", "Choose a preference; each title’s available tracks may differ.", preferences.subtitleLanguage, listOf("Off", "System", "English", "Bengali", "Hindi", "Spanish")) { onPreferences(preferences.copy(subtitleLanguage = it)) }
                }
            }
        }
        item { SectionHeading("Comfort & interaction") }
        item {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    PreferenceSwitch("Reduce motion", "Keep transitions gentle and minimize movement.", preferences.reducedMotion) { onPreferences(preferences.copy(reducedMotion = it)) }
                    HorizontalDivider(color = MojaLossColors.NavigationGlass)
                    PreferenceSwitch("Touch feedback", "Subtle haptics for meaningful actions, when allowed by your device.", preferences.haptics) { onPreferences(preferences.copy(haptics = it)) }
                }
            }
        }
        item {
            Text("Forest & Porcelain / Sage", style = MaterialTheme.typography.labelLarge, color = MojaLossColors.Forest)
            Spacer(Modifier.height(5.dp))
            Text("Your display and playback preferences stay on this device.", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
        }
        if (accountName != null) {
            // Keep update access independent of server/account availability.
            item { OutlinedButton(onClick = { showSignOut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Outlined.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") } }
        }
        if (onCheckUpdates != null) item {
            GlassSurface(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App updates", style = MaterialTheme.typography.titleSmall)
                        appVersion?.let { Text("Version $it", style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk) }
                    }
                    TextButton(onClick = onCheckUpdates, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-app-updates")) { Text("Check for updates") }
                }
            }
        }
        if (onCopyErrorReport != null) item {
            TextButton(onClick = onCopyErrorReport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy error report") }
        }
    }
    if (showSignOut) AlertDialog(
        onDismissRequest = { showSignOut = false }, title = { Text("Sign out?") },
        text = { Text("This removes the connection, cached account data, and offline downloads from this device. Your server library and viewing history stay on the server.") },
        confirmButton = { TextButton(onClick = { showSignOut = false; onSignOut() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { showSignOut = false }) { Text("Keep connected") } }
    )
}

@Composable
private fun BrandHeader() {
    val accountAction = LocalAccountAction.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MojaLossColors.Forest), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Apps, null, tint = MojaLossColors.OnForest, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("MojaLoss", style = MaterialTheme.typography.titleLarge, color = MojaLossColors.Ink, modifier = Modifier.weight(1f))
        if (accountAction != null) {
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = accountAction, shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 10.dp)) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Account")
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(MojaLossColors.Forest), colors = IconButtonDefaults.iconButtonColors(contentColor = MojaLossColors.OnForest)) { Icon(Icons.Outlined.ArrowBack, "Back") }
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SectionHeading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, color = MojaLossColors.Ink, modifier = Modifier.semantics { heading() }) }

@Composable
private fun MenuSelector(label: String, options: List<String>, modifier: Modifier = Modifier, settingTitle: String? = null, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) "$label, options expanded" else label
            settingTitle?.let { onClick(label = "Change $it", action = null) }
        }, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
            if (settingTitle != null) Text("$settingTitle: ", style = MaterialTheme.typography.labelMedium)
            Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(option) }) }
        }
    }
}

private fun confirmedCatalog(snapshot: CatalogSnapshot) = snapshot.status == ContentStatus.READY && snapshot.isComplete && snapshot.isConnectionVerified

private fun needsCatalogNotice(snapshot: CatalogSnapshot) = snapshot.isDemo ||
    snapshot.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)

private fun catalogStateKey(query: CatalogQuery) = listOf(query.destination.name, query.parentId.orEmpty(), query.filter.kind?.name.orEmpty(), query.filter.genre.orEmpty(), query.filter.year?.toString().orEmpty(), query.filter.sort, query.search).joinToString("\u0001")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogRefreshContainer(snapshot: CatalogSnapshot, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val refresh = LocalCatalogRefresh.current
    if (!enabled || snapshot.isDemo || refresh == null) Box(modifier.fillMaxSize()) { content() }
    // Empty pages reserve their card geometry instead of a second overlay spinner.
    else PullToRefreshBox(isRefreshing = snapshot.isRefreshing && !snapshot.isLoadingMore && snapshot.titles.isNotEmpty(),
        onRefresh = { if (!snapshot.isRefreshing && !snapshot.isLoadingMore) refresh() },
        modifier = modifier.fillMaxSize().testTag("catalog-refresh-container").semantics {
            customActions = listOf(CustomAccessibilityAction("Refresh titles") { if (snapshot.isRefreshing || snapshot.isLoadingMore) false else { refresh(); true } })
        }) { content() }
}

@Composable
private fun CatalogNotice(snapshot: CatalogSnapshot) {
    if (snapshot.isDemo) {
        Text("Design preview · Sample titles", style = MaterialTheme.typography.labelSmall, color = MojaLossColors.SecondaryInk)
        return
    }
    if (snapshot.status !in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) return
    val refresh = LocalCatalogRefresh.current
    val loadMore = LocalCatalogLoadMore.current
    val copyReport = LocalErrorReportCopy.current
    val retry = if (snapshot.isConnectionVerified && snapshot.hasMore && snapshot.titles.isNotEmpty()) loadMore ?: refresh else refresh
    var expanded by remember(snapshot.query, snapshot.message) { mutableStateOf(false) }
    val failureDetail = snapshot.message?.trim()?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.contains("Showing this account's cached titles", ignoreCase = true) }
        ?.replaceFirst(Regex("^[0-9]+ loaded titles remain available\\.\\s*"), "")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (snapshot.status == ContentStatus.OFFLINE) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
                null, Modifier.size(18.dp), tint = MojaLossColors.Error)
            Spacer(Modifier.width(7.dp))
            Text(when {
                snapshot.titles.isEmpty() -> "Couldn’t load titles"
                snapshot.hasMore -> "Couldn’t load more"
                else -> "Couldn’t update titles"
            }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
            if (retry != null) TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz, if (expanded) "Hide library error details" else "Show library error details", Modifier.size(21.dp))
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 25.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (!failureDetail.isNullOrBlank()) Text(failureDetail, style = MaterialTheme.typography.bodySmall, color = MojaLossColors.SecondaryInk)
                if (snapshot.titles.isNotEmpty()) Text("${snapshot.titles.size} titles remain available", style = MaterialTheme.typography.labelSmall, color = MojaLossColors.SecondaryInk)
                if (copyReport != null) TextButton(onClick = copyReport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy error report") }
            }
        }
    }
}

@Composable
private fun CompactCatalogHeader(title: String, subtitle: String? = null, onClose: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MojaLossColors.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MojaLossColors.SecondaryInk)
        }
        trailing?.invoke()
        if (onClose != null) CatalogCloseAction(onClose) else CompactAccountAction()
    }
}

@Composable
private fun CatalogCloseAction(onClose: () -> Unit) {
    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Back to Home", Modifier.size(22.dp), tint = MojaLossColors.Forest) }
}

@Composable
private fun CompactAccountAction() {
    LocalAccountAction.current?.let { action ->
        IconButton(onClick = action, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.AccountCircle, "Account", Modifier.size(23.dp), tint = MojaLossColors.Forest) }
    }
}

@Composable
private fun CompactKinds(selected: String, onSelected: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listItems(listOf("All", "Movies", "Series")) { label ->
            Column(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                .selectable(selected = selected == label, role = Role.Tab, onClick = { onSelected(label) })
                .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected == label) MojaLossColors.Forest else MojaLossColors.SecondaryInk)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.height(2.dp).width(16.dp).clip(RoundedCornerShape(1.dp))
                    .background(if (selected == label) MojaLossColors.Forest else Color.Transparent))
            }
        }
    }
}

@Composable
private fun EmptyLibraryPanel() { CompactEmpty("Your library is empty", "No movies or series are available yet.") }

@Composable
internal fun CompactEmpty(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
    }
}

/** One explicit demand loads one page. Passive composition never walks the catalog. */
@Composable
private fun CatalogPagingFooter(snapshot: CatalogSnapshot) {
    val more = LocalCatalogLoadMore.current
    if (!snapshot.isDemo && snapshot.titles.isNotEmpty() && snapshot.hasMore && snapshot.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE) && more != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { TextButton(onClick = more, enabled = snapshot.isConnectionVerified) { Text("Retry titles") } }
    }
}

@Composable
private fun Modifier.catalogPageActions(snapshot: CatalogSnapshot): Modifier {
    val more = LocalCatalogLoadMore.current
    return semantics {
        if (snapshot.isLoadingMore) stateDescription = "Loading more titles"
        if (!snapshot.isDemo && snapshot.isConnectionVerified && snapshot.hasMore && !snapshot.isLoadingMore && !snapshot.isRefreshing && snapshot.status == ContentStatus.READY && more != null)
            customActions = listOf(CustomAccessibilityAction("Load next titles") { more(); true })
    }
}

@Composable
private fun SkeletonCard(modifier: Modifier = Modifier, ratio: Float = 3f / 4f) {
    Box(modifier.aspectRatio(ratio).clip(RoundedCornerShape(15.dp)).background(MojaLossColors.NavigationGlass.copy(alpha = .7f)).semantics { hideFromAccessibility() })
}

@Composable
private fun SavedTitleSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(15.dp)).background(MojaLossColors.NavigationGlass.copy(alpha = .7f)).semantics { hideFromAccessibility() })
}

@Composable
private fun CatalogSkeletons(ratio: Float = 3f / 4f) {
    LazyVerticalGrid(columns = GridCells.Adaptive(posterMinimumWidth()), modifier = Modifier.fillMaxSize().testTag("catalog-skeletons").clearAndSetSemantics { contentDescription = "Loading titles" }, contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(6) { SkeletonCard(ratio = ratio) } }
}

@Composable
private fun RecentStackSkeleton() {
    val adaptive = LocalAdaptiveWindowInfo.current
    val density = LocalDensity.current
    val captionHeight = with(density) { MaterialTheme.typography.headlineSmall.lineHeight.toDp() + MaterialTheme.typography.labelMedium.lineHeight.toDp() + 92.dp }
    Column(modifier = Modifier.testTag("recent-skeletons").clearAndSetSemantics { contentDescription = "Loading new releases" }) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { SectionHeading("New releases") }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 24.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MojaLossColors.NavigationGlass.copy(alpha = .7f))) {
                Spacer(Modifier.fillMaxWidth().height(maxOf(minOf(maxWidth * .92f, adaptive.heroArtworkMaxHeightDp.dp), captionHeight + 104.dp)))
            }
        }
    }
}

@Composable
private fun ResumeSkeleton() {
    val density = LocalDensity.current
    val caption = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() * 2 + MaterialTheme.typography.labelSmall.lineHeight.toDp() * 2 + 8.dp }
    val width = 184.dp * density.fontScale.coerceIn(1f, 1.35f)
    Box(Modifier.width(width).height(width * 9 / 16 + caption).clip(RoundedCornerShape(15.dp)).background(MojaLossColors.NavigationGlass.copy(alpha = .7f)).semantics { hideFromAccessibility() })
}

@Composable
private fun ObserveCatalogDemand(snapshot: CatalogSnapshot, state: LazyListState) {
    val nearEnd by remember(state) { derivedStateOf {
        val layout = state.layoutInfo
        layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 6
    } }
    ObserveCatalogDemand(snapshot, state.interactionSource, nearEnd)
}

@Composable
private fun ObserveCatalogDemand(snapshot: CatalogSnapshot, state: LazyGridState) {
    val nearEnd by remember(state) { derivedStateOf {
        val layout = state.layoutInfo
        layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 6
    } }
    ObserveCatalogDemand(snapshot, state.interactionSource, nearEnd)
}

@Composable
internal fun ObserveSectionDemand(state: LazyListState, scopeKey: String, enabled: Boolean, onMore: (() -> Unit)?) {
    val nearEnd by remember(state) { derivedStateOf {
        val layout = state.layoutInfo
        layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 6
    } }
    val latestMore by rememberUpdatedState(onMore)
    var earnedDemand by remember(scopeKey) { mutableStateOf(false) }
    LaunchedEffect(state.interactionSource, scopeKey) {
        state.interactionSource.interactions.collect { interaction -> when (interaction) {
            is DragInteraction.Start -> earnedDemand = true
            // A released fling is still the user's demand. Keep its single token
            // until a page consumes it; cancellation is not an intentional fling.
            is DragInteraction.Cancel -> earnedDemand = false
            else -> Unit
        } }
    }
    LaunchedEffect(earnedDemand, nearEnd, enabled) {
        if (earnedDemand && nearEnd && enabled && latestMore != null) { earnedDemand = false; latestMore?.invoke() }
    }
}

@Composable
private fun ObserveCatalogDemand(snapshot: CatalogSnapshot, interactions: androidx.compose.foundation.interaction.InteractionSource, nearEnd: Boolean) {
    val loadMore by rememberUpdatedState(LocalCatalogLoadMore.current)
    var earnedDemand by remember(snapshot.query) { mutableStateOf(false) }
    LaunchedEffect(interactions, snapshot.query) {
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> earnedDemand = true
                is DragInteraction.Cancel -> earnedDemand = false
                else -> Unit
            }
        }
    }
    LaunchedEffect(earnedDemand, nearEnd, snapshot.hasMore, snapshot.isLoadingMore, snapshot.isRefreshing, snapshot.status, snapshot.isConnectionVerified) {
        if (earnedDemand && nearEnd && snapshot.hasMore && !snapshot.isLoadingMore && !snapshot.isRefreshing &&
            snapshot.isConnectionVerified && !snapshot.isDemo && snapshot.status == ContentStatus.READY && loadMore != null) {
            earnedDemand = false
            loadMore?.invoke()
        }
    }
}

@Composable
private fun PosterCard(title: MediaTitle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val motion = LocalMotionPolicy.current
    val metadata = listOfNotNull(title.year?.takeIf { it in 1888..2100 }?.toString(), title.genre.takeIf(String::isNotBlank)).joinToString(" · ")
    Box(modifier.fillMaxWidth().aspectRatio(3f / 4f).motionPressFeedback(interactions, motion).clip(RoundedCornerShape(15.dp))
        .clickable(interactionSource = interactions, indication = ripple(), onClickLabel = "Open ${title.title}", onClick = onClick)
        .semantics { contentDescription = listOf(title.title, metadata).filter(String::isNotBlank).joinToString(" · ") }) {
        TitleArtwork(title, Modifier.fillMaxSize().testTag("poster-artwork-${title.id}"))
        // Artwork remains the whole card. The measured caption has guaranteed contrast;
        // a fading scrim continues beneath it without a separate under-image tile.
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .76f)).padding(10.dp).testTag("poster-caption-${title.id}")) {
                Text(title.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .95f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.fillMaxWidth().height(42.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .76f), Color.Transparent))))
        }
    }
}

@Composable
internal fun TitleArtwork(title: MediaTitle, modifier: Modifier = Modifier, backdrop: Boolean = false) {
    val url = if (backdrop) title.backdropUrl ?: title.posterUrl else title.posterUrl ?: title.backdropUrl
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFFB7CEC0), Color(0xFF2A5A49))))) {
        Column(Modifier.align(Alignment.Center).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (title.kind == MediaKind.SERIES) Icons.Outlined.LiveTv else Icons.Outlined.Movie, null, Modifier.size(33.dp), tint = Color.White.copy(alpha = .85f))
            Spacer(Modifier.height(6.dp))
            Text(title.title.take(1), fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .78f))
        }
        if (!url.isNullOrBlank()) AsyncImage(model = artworkRequest(url, title.artworkHeaders), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun ErrorCopy(message: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(19.dp), tint = MojaLossColors.Error); Spacer(Modifier.width(8.dp)); Text(message, color = MojaLossColors.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) }
}

@Composable
private fun PreferenceSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk) }
        Spacer(Modifier.width(10.dp)); Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PreferenceSelector(title: String, description: String, value: String, options: List<String>, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
        MenuSelector(value, options, Modifier.fillMaxWidth(), settingTitle = title, onSelected = onSelected)
    }
}

/** Headers never enter the public URL, logs, or a plain-text cache key. */
@Composable
internal fun artworkRequest(url: String, headers: Map<String, String>): ImageRequest {
    val context = LocalContext.current
    val crossfadeMs = if (LocalMotionPolicy.current.animationsEnabled) 180 else 0
    return remember(context, url, headers, crossfadeMs) {
        val networkHeaders = NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build()
        val identity = headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cacheKey = "$url:headers:$digest"
        ImageRequest.Builder(context).data(url).httpHeaders(networkHeaders).memoryCacheKey(cacheKey).diskCacheKey(cacheKey).crossfade(crossfadeMs).build()
    }
}

private fun filteredTitles(titles: List<MediaTitle>, filter: BrowseFilter): List<MediaTitle> {
    val filtered = titles.filter { (filter.kind == null || it.kind == filter.kind) && (filter.genre == null || it.genre == filter.genre) && (filter.year == null || it.year == filter.year) }
    return when (filter.sort) { "Title" -> filtered.sortedBy { it.title.lowercase() }; "Recently added" -> filtered; else -> filtered.sortedByDescending { it.year ?: 0 } }
}

private fun catalogSortLabel(sort: String) = when (sort) { "Title" -> "Title A–Z"; "Recently added" -> "Recently uploaded"; else -> "Newest releases" }

@Composable
private fun posterMinimumWidth(base: Dp = 130.dp) = base * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)

internal fun titleMetadata(title: MediaTitle): String = listOfNotNull(
    if (title.kind == MediaKind.SERIES) "Series" else if (title.kind == MediaKind.EPISODE) "Episode" else "Movie",
    title.year?.takeIf { it in 1888..2100 }?.toString(),
    title.genre.takeIf { it.isNotBlank() },
).joinToString(" · ")
internal fun durationLabel(ms: Long): String { if (ms <= 0) return ""; val minutes = ms / 60_000; return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m" }
internal fun positionLabel(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) }
