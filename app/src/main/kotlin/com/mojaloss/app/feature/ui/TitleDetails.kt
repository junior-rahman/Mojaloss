package com.mojaloss.app.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mojaloss.app.adaptive.LocalAdaptiveWindowInfo
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.*
import com.mojaloss.core.ui.motion.LocalMotionPolicy
import com.mojaloss.core.ui.motion.motionArrival
import com.mojaloss.core.ui.motion.motionPressFeedback
import kotlinx.coroutines.flow.collect

/** The photographic action panel always has a dark backing, independently of system theme. */
private val PhotoScrim = Color(0xFF071B13)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TitleScreen(
    title: MediaTitle, favorite: FavoriteState, episodes: List<MediaTitle>, onBack: () -> Unit,
    onSave: (Boolean) -> Unit, onPlay: (Boolean) -> Unit, onEpisodePlay: (MediaTitle) -> Unit, onTrailer: () -> Unit,
    detailStatus: ContentStatus = ContentStatus.READY, canSave: Boolean = true,
    episodeState: EpisodeSectionState? = null, onSelectSeason: (SeasonOption) -> Unit = {},
    onEpisodeRetry: (() -> Unit)? = null, onEpisodeLoadMore: (() -> Unit)? = null,
    onSeasonLoadMore: (() -> Unit)? = null, onSeasonRetry: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null, isPreparing: Boolean = false, resumeEpisode: MediaTitle? = null,
    preparingEpisodeId: String? = null, primaryEpisode: MediaTitle? = null,
    seriesStartState: SeriesStartState? = null, onSeriesStartRetry: (() -> Unit)? = null,
    downloads: Map<String, DownloadItem> = emptyMap(), downloadPreparing: Set<String> = emptySet(),
    onDownload: ((MediaTitle) -> Unit)? = null, onDownloadSeason: ((SeasonOption) -> Unit)? = null,
    isDownloadingSeason: Boolean = false,
) {
    val listState = rememberLazyListState()
    val seasonRowState = rememberLazyListState()
    val adaptive = LocalAdaptiveWindowInfo.current
    val section = episodeState ?: EpisodeSectionState(status = detailStatus, titles = episodes,
        seasons = episodes.mapNotNull { it.seasonNumber }.distinct().sorted().map { SeasonOption("$it", it, "Season $it") }, seasonStatus = detailStatus)
    var previewSeason by rememberSaveable(title.id) { mutableStateOf<String?>(null) }
    var selectedEpisodeId by rememberSaveable(title.id) { mutableStateOf<String?>(null) }
    val selectedSeason = section.selectedSeason ?: section.seasons.firstOrNull { it.id == previewSeason } ?: section.seasons.firstOrNull()
    val visibleEpisodes = if (episodeState != null) section.titles else section.titles.filter { selectedSeason == null || it.seasonNumber == selectedSeason.number }
    fun scopedEpisode(candidate: MediaTitle?): MediaTitle? = candidate?.takeIf {
        it.kind == MediaKind.EPISODE && (it.seriesId == null || it.seriesId == title.id)
    }
    // A live start lookup may find progress in another season. Never replace that pending
    // or failed lookup with the first row of an unrelated loaded season.
    val selectedEpisode = if (title.kind == MediaKind.SERIES) { scopedEpisode(primaryEpisode)
        ?: visibleEpisodes.firstOrNull { it.id == selectedEpisodeId }?.let(::scopedEpisode)
        ?: if (seriesStartState != null) {
            if (seriesStartState.status == ContentStatus.READY) scopedEpisode(seriesStartState.episode) else null
        } else scopedEpisode(resumeEpisode)?.takeIf { it.confirmedResumeMs > 0 } ?: scopedEpisode(visibleEpisodes.firstOrNull())
    } else null
    val target = if (title.kind == MediaKind.SERIES) selectedEpisode else title
    val preparing = isPreparing || preparingEpisodeId != null
    val startPending = title.kind == MediaKind.SERIES && target == null &&
        (seriesStartState?.status == ContentStatus.LOADING || seriesStartState == null && section.status == ContentStatus.LOADING)
    val startFailed = title.kind == MediaKind.SERIES && target == null && seriesStartState?.status == ContentStatus.ERROR
    val primaryLabel = when {
        preparing -> "Preparing…"
        detailStatus == ContentStatus.OFFLINE -> "Reconnect to play"
        title.isUpcoming -> "Coming soon"
        startPending -> "Play"
        startFailed -> "Retry"
        target == null -> "No episodes"
        !target.isPlayable -> if (detailStatus == ContentStatus.LOADING) "Play" else "Unavailable"
        target.confirmedResumeMs > 0 -> "Resume"
        else -> "Play"
    }
    val primaryEnabled = !preparing && when {
        detailStatus == ContentStatus.OFFLINE -> onReconnect != null
        title.isUpcoming || startPending -> false
        startFailed -> onSeriesStartRetry != null
        else -> target?.isPlayable == true
    }
    val activatePrimary: () -> Unit = {
        when {
            detailStatus == ContentStatus.OFFLINE -> onReconnect?.invoke()
            startFailed -> onSeriesStartRetry?.invoke()
            title.kind == MediaKind.SERIES -> target?.let { selectedEpisodeId = it.id; onEpisodePlay(it) }
            else -> onPlay(false)
        }
    }
    fun activateEpisode(episode: MediaTitle) { selectedEpisodeId = episode.id; onEpisodePlay(episode) }
    val canDemandEpisodes = title.kind == MediaKind.SERIES && detailStatus != ContentStatus.OFFLINE &&
        section.status == ContentStatus.READY && section.hasMore && !section.isLoadingMore
    ObserveEpisodeDemand(listState, "${title.id}:${selectedSeason?.id}", visibleEpisodes,
        canDemandEpisodes, onEpisodeLoadMore)
    ObserveSectionDemand(seasonRowState, "${title.id}:seasons", detailStatus != ContentStatus.OFFLINE &&
        section.seasonStatus == ContentStatus.READY && section.hasMoreSeasons && !section.isLoadingMoreSeasons, onSeasonLoadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("title-details").semantics {
        customActions = buildList {
            if (canDemandEpisodes && onEpisodeLoadMore != null) add(CustomAccessibilityAction("Load next episodes") { onEpisodeLoadMore(); true })
            if (section.hasMoreSeasons && !section.isLoadingMoreSeasons && section.seasonStatus == ContentStatus.READY && onSeasonLoadMore != null)
                add(CustomAccessibilityAction("Load next seasons") { onSeasonLoadMore(); true })
        }
    }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "back") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back", tint = MojaLossColors.Forest) }
            Text(if (title.kind == MediaKind.SERIES) "Series" else "Title details", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
        } }
        item(key = "artwork") {
            DetailArtworkCard(title, selectedEpisode, favorite, primaryLabel, primaryEnabled,
                preparing || startPending, activatePrimary, { onSave(!favorite.confirmed) }, canSave,
                onTrailer, { onPlay(true) }, adaptive.heroArtworkMaxHeightDp,
                seriesStartState?.message?.takeIf { startFailed },
                target?.takeIf { it.kind != MediaKind.SERIES }, downloads, downloadPreparing, onDownload)
        }
        item(key = "synopsis") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Synopsis")
            if (title.synopsis.isBlank() && detailStatus == ContentStatus.LOADING) SynopsisSkeleton()
            else DetailArrival("synopsis:${title.id}:${title.synopsis.isNotBlank()}") {
                Text(title.synopsis.ifBlank { when (detailStatus) {
                    ContentStatus.ERROR, ContentStatus.OFFLINE -> "Synopsis could not load. Reconnect or retry this title to update it."
                    ContentStatus.LOADING, ContentStatus.READY -> "A synopsis is not available for this title yet."
                } }, style = MaterialTheme.typography.bodyLarge, color = MojaLossColors.SecondaryInk)
            }
        } }
        if (title.kind == MediaKind.SERIES) {
            item(key = "season-picker") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeading("Episodes")
                    // A series container is never a downloadable media file. This queues only
                    // the actual selected server season and is absent during season lookup.
                    section.selectedSeason?.takeIf { section.seasonStatus == ContentStatus.READY &&
                        detailStatus != ContentStatus.OFFLINE && it.id == selectedSeason?.id }?.let { season ->
                        onDownloadSeason?.let { downloadSeason -> TextButton(onClick = { downloadSeason(season) }, enabled = !isDownloadingSeason,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("details-download-season")) {
                            if (isDownloadingSeason) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.FileDownload, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp)); Text(if (isDownloadingSeason) "Adding season…" else "Download season")
                        } }
                    }
                }
                when (section.seasonStatus) {
                    ContentStatus.LOADING -> if (section.seasons.isEmpty()) SeasonSkeletons()
                    ContentStatus.ERROR, ContentStatus.OFFLINE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section.seasonMessage ?: "Seasons could not load", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
                        val retry = if (section.seasonStatus == ContentStatus.OFFLINE) onReconnect else onSeasonRetry
                        retry?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (section.seasonStatus == ContentStatus.OFFLINE) "Reconnect" else "Retry seasons") } }
                    }
                    ContentStatus.READY -> Unit
                }
                if (section.seasons.isNotEmpty()) LazyRow(state = seasonRowState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listItems(section.seasons, key = { it.id }) { option -> DetailArrival("season:${option.id}", Modifier) {
                        SelectionChip(option.name.ifBlank { option.number?.let { "Season $it" } ?: "Episodes" }, option.id == selectedSeason?.id) {
                            previewSeason = option.id; selectedEpisodeId = null; onSelectSeason(option)
                        }
                    } }
                    if (section.isLoadingMoreSeasons) items(2, key = { "season-append-skeleton:$it" }) {
                        DetailSkeleton(Modifier.width(110.dp).height(48.dp).testTag("details-season-append-skeleton-$it"))
                    }
                }
            } }
            if (section.status == ContentStatus.LOADING && visibleEpisodes.isEmpty() && section.seasonStatus !in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) item(key = "episode-pending") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("details-episode-skeletons")) {
                    repeat(3) { EpisodeSkeleton() }
                }
            }
            if (section.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE) && section.seasonStatus !in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) item(key = "episode-error") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(section.message ?: "Episodes could not load", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
                    val retry = if (section.status == ContentStatus.OFFLINE) onReconnect else onEpisodeRetry
                    retry?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (section.status == ContentStatus.OFFLINE) "Reconnect" else "Retry episodes") } }
                }
            }
            if (section.status == ContentStatus.READY && section.seasonStatus == ContentStatus.READY && visibleEpisodes.isEmpty() && !section.hasMore) item(key = "episodes-empty") {
                CompactEmpty("No episodes available", "This season has no available episodes yet.")
            }
            listItems(visibleEpisodes, key = { "episode:${it.id}" }) { episode -> DetailArrival("episode:${episode.id}") {
                EpisodeRow(episode, selected = selectedEpisode?.id == episode.id, preparing = preparingEpisodeId == episode.id,
                    enabled = !preparing && detailStatus != ContentStatus.OFFLINE, offline = detailStatus == ContentStatus.OFFLINE,
                    item = downloads[episode.id], downloadPreparing = episode.id in downloadPreparing,
                    onDownload = onDownload?.let { { it(episode) } }) { activateEpisode(episode) }
            } }
            if (section.isLoadingMore) items(2, key = { "episode-append-skeleton:$it" }) { EpisodeSkeleton(Modifier.testTag("details-episode-append-skeleton-$it")) }
        }
        if (title.cast.isNotEmpty() || detailStatus != ContentStatus.READY) item(key = "cast") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Cast")
                if (title.cast.isNotEmpty()) DetailArrival("cast:${title.id}") { CastCards(title.cast) }
                else if (detailStatus == ContentStatus.LOADING) CastSkeletons()
                else Text("Cast could not load. Retry this title to update it.",
                    style = MaterialTheme.typography.bodyMedium, color = MojaLossColors.SecondaryInk)
            }
        }
        if (title.ratings.isNotEmpty()) item(key = "ratings") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Ratings")
            title.ratings.forEach { rating -> GlassSurface(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(rating.source, style = MaterialTheme.typography.titleMedium); Text(rating.label, color = MojaLossColors.SecondaryInk) }
                    Text(rating.value, style = MaterialTheme.typography.titleLarge, color = MojaLossColors.Forest)
                }
            } }
        } }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DetailArtworkCard(
    title: MediaTitle, episode: MediaTitle?, favorite: FavoriteState, primaryLabel: String,
    primaryEnabled: Boolean, busy: Boolean, onPrimary: () -> Unit, onSave: () -> Unit, canSave: Boolean,
    onTrailer: () -> Unit, onFromStart: () -> Unit, artworkMaxHeightDp: Float, startError: String?,
    downloadTarget: MediaTitle?, downloads: Map<String, DownloadItem>, downloadPreparing: Set<String>,
    onDownload: ((MediaTitle) -> Unit)?,
) {
    val current = episode ?: title
    val art = episode?.takeIf { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() } ?: title
    val primaryDescription = "$primaryLabel ${current.title}"
    val hasTrailer = title.trailerVideoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true
    val verticalActions = LocalDensity.current.fontScale >= 1.5f
    GlassSurface(Modifier.fillMaxWidth().testTag("details-artwork"), 22.dp, functionalBorder = true) {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PhotoScrim)) {
            val artworkWidth = maxWidth
            // Only the visible photo opening and actual title/actions size this card.
            // The separately measured background keeps its crop as text grows, without
            // reserving an unused portrait-shaped canvas underneath the final action.
            Layout(modifier = Modifier.fillMaxWidth(), content = {
                Box(Modifier.fillMaxWidth().height(artworkWidth * 4f / 3f)) {
                    TitleArtwork(art, Modifier.matchParentSize().testTag("details-artwork-image-${art.id}"), backdrop = true)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .28f to PhotoScrim.copy(alpha = .12f),
                        .43f to PhotoScrim.copy(alpha = .78f), .78f to PhotoScrim.copy(alpha = .94f), 1f to PhotoScrim)))
                }
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(minOf(artworkWidth * 9f / 16f, artworkMaxHeightDp.dp).coerceAtLeast(48.dp))
                        .testTag("details-artwork-play").clickable(enabled = primaryEnabled,
                            onClickLabel = primaryDescription, onClick = onPrimary)
                        .semantics { contentDescription = "Artwork for ${current.title}" })
                    Column(Modifier.fillMaxWidth().padding(14.dp).testTag("details-action-overlay"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title.title, style = MaterialTheme.typography.headlineMedium, color = Color.White,
                            modifier = Modifier.testTag("details-title").semantics { heading() })
                        Text(listOfNotNull(if (title.isUpcoming) "Upcoming" else "Released", title.year?.toString(),
                            title.genre.takeIf(String::isNotBlank), durationLabel(title.runtimeMs).takeIf(String::isNotBlank)).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .9f))
                        episode?.let { Text(listOfNotNull(it.seasonNumber?.let { n -> "Season $n" },
                            it.episodeNumber?.let { n -> "Episode $n" }, it.title).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .92f),
                            modifier = Modifier.testTag("details-episode-caption")) }
                        if (current.confirmedResumeMs > 0 && current.runtimeMs > 0) {
                            LinearProgressIndicator(progress = { (current.confirmedResumeMs.toFloat() / current.runtimeMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp).testTag("details-confirmed-progress"),
                                color = Color(0xFFB4D2BD), trackColor = Color.White.copy(alpha = .2f))
                            Text("${((current.runtimeMs - current.confirmedResumeMs).coerceAtLeast(0) / 60_000)} min left",
                                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .9f))
                        }
                        if (verticalActions) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhotoPlayButton(primaryLabel, primaryDescription, primaryEnabled, busy, onPrimary, Modifier.fillMaxWidth())
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PhotoSaveButton(favorite, canSave, onSave, title.title)
                                downloadTarget?.let { target -> onDownload?.let { download -> DownloadControl(target,
                                    downloads[target.id], target.id in downloadPreparing, { download(target) }, onArtwork = true) } }
                                if (hasTrailer) PhotoTrailerButton(title.title, onTrailer)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                PhotoPlayButton(primaryLabel, primaryDescription, primaryEnabled, busy, onPrimary, Modifier.weight(1f))
                                PhotoSaveButton(favorite, canSave, onSave, title.title)
                                downloadTarget?.let { target -> onDownload?.let { download -> DownloadControl(target,
                                    downloads[target.id], target.id in downloadPreparing, { download(target) }, onArtwork = true) } }
                            }
                            if (hasTrailer) PhotoTrailerButton(title.title, onTrailer)
                        }
                        if (current.confirmedResumeMs > 0 && title.kind != MediaKind.SERIES && title.isPlayable) TextButton(onClick = onFromStart,
                            enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Play from start") }
                        startError?.let { Text(it, color = Color(0xFFFFDAD5), style = MaterialTheme.typography.bodyMedium) }
                        favorite.error?.let { Text(it, color = Color(0xFFFFDAD5), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("details-save-error")) }
                    }
                }
            }) { layers, constraints ->
                val foreground = layers[1].measure(constraints)
                val photo = layers[0].measure(Constraints.fixed(
                    foreground.width, (artworkWidth * 4f / 3f).roundToPx().coerceAtLeast(1)))
                layout(foreground.width, foreground.height) {
                    photo.placeRelative(0, 0)
                    foreground.placeRelative(0, 0)
                }
            }
        }
    }
}

@Composable
private fun PhotoTrailerButton(titleName: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("details-trailer")
        .semantics { contentDescription = "Watch $titleName trailer" },
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Trailer")
    }
}

@Composable
private fun PhotoPlayButton(label: String, description: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val policy = LocalMotionPolicy.current
    Button(onClick = onClick, enabled = enabled && !busy, interactionSource = interactions,
        modifier = modifier.heightIn(min = 52.dp).testTag("details-primary").motionPressFeedback(interactions, policy).semantics { contentDescription = description },
        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = MojaLossColors.Forest,
            contentColor = MojaLossColors.OnForest, disabledContainerColor = Color.White.copy(alpha = .12f), disabledContentColor = Color.White.copy(alpha = .75f)),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = .75f))
        else Icon(Icons.Outlined.PlayArrow, null, Modifier.size(21.dp))
        Spacer(Modifier.width(7.dp)); Text(label)
    }
}

@Composable
private fun PhotoSaveButton(favorite: FavoriteState, canSave: Boolean, onClick: () -> Unit, titleName: String) {
    val label = when { favorite.pending -> if (favorite.desired) "Saving…" else "Removing…"; favorite.confirmed -> "Saved"; else -> "Save" }
    OutlinedIconButton(onClick = onClick, enabled = canSave && !favorite.pending,
        modifier = Modifier.size(48.dp).testTag("details-save").semantics {
            contentDescription = "$label $titleName"; stateDescription = label
        }, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp,
            Color.White.copy(alpha = if (canSave) .65f else .28f)),
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color.White.copy(alpha = .1f),
            contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .6f))) {
        if (favorite.pending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White.copy(alpha = .6f), strokeWidth = 2.dp)
        else Icon(if (favorite.confirmed) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null, Modifier.size(22.dp))
    }
}

@Composable
private fun SelectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MojaLossColors.Forest, selectedLabelColor = MojaLossColors.OnForest,
            containerColor = MojaLossColors.ControlGlass, labelColor = MojaLossColors.Ink))
}

@Composable
private fun EpisodeRow(
    episode: MediaTitle, selected: Boolean, preparing: Boolean = false, enabled: Boolean = true, offline: Boolean = false,
    item: DownloadItem? = null, downloadPreparing: Boolean = false, onDownload: (() -> Unit)? = null, onPlay: () -> Unit,
) {
    val actionable = enabled && episode.isPlayable && !preparing
    val action = when {
        preparing -> "Preparing"
        offline -> "Reconnect to play"
        !episode.isPlayable -> "Unavailable"
        !enabled -> "Please wait"
        episode.confirmedResumeMs > 0 -> "Resume"
        else -> "Play"
    }
    GlassSurface(Modifier.fillMaxWidth().testTag("episode-row-${episode.id}")
        .selectable(selected = selected, enabled = actionable, role = Role.Button, onClick = onPlay)
        .semantics { contentDescription = "$action ${episode.title}"; if (selected) stateDescription = "Selected episode" },
        15.dp, functionalBorder = true) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            TitleArtwork(episode, Modifier.width(82.dp).height(62.dp).clip(RoundedCornerShape(10.dp)), true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(listOfNotNull(episode.episodeNumber?.let { "$it." }, episode.title).joinToString(" "), style = MaterialTheme.typography.labelLarge)
                if (episode.runtimeMs > 0) Text(durationLabel(episode.runtimeMs), style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                if (!enabled || !episode.isPlayable) Text(action, style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                else if (episode.confirmedResumeMs > 0) Text("Resume at ${positionLabel(episode.confirmedResumeMs)}", style = MaterialTheme.typography.labelMedium, color = MojaLossColors.Forest)
            }
            Spacer(Modifier.width(8.dp))
            // The row itself remains the play action. Its independent download button consumes
            // its own touch, so saving an episode cannot also start playback.
            if (onDownload != null) DownloadControl(episode, item, downloadPreparing, { onDownload() })
            else if (preparing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(when { !actionable -> Icons.Outlined.Lock; selected -> Icons.Outlined.CheckCircleOutline; else -> Icons.Outlined.PlayCircleOutline },
                null, Modifier.size(24.dp), tint = MojaLossColors.Forest)
        }
    }
}

@Composable
private fun CastCards(cast: List<CastMember>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("details-cast-cards")) {
        listItems(cast.distinctBy { it.name to it.role }, key = { "${it.name}:${it.role}" }) { person ->
            GlassSurface(Modifier.width(116.dp).semantics(mergeDescendants = true) {}, 15.dp) {
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(MojaLossColors.NavigationGlass), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, null, tint = MojaLossColors.Forest, modifier = Modifier.size(36.dp))
                        person.imageUrl?.let { AsyncImage(model = artworkRequest(it, person.artworkHeaders), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(person.name, style = MaterialTheme.typography.labelLarge)
                        if (person.role.isNotBlank()) Text(person.role, style = MaterialTheme.typography.labelMedium, color = MojaLossColors.SecondaryInk)
                    }
                }
            }
        }
    }
}

/** One explicit user drag arms one page, including the fling after finger release. */
@Composable
private fun ObserveEpisodeDemand(state: LazyListState, scopeKey: String, episodes: List<MediaTitle>, enabled: Boolean, onMore: (() -> Unit)?) {
    val lastIds = remember(episodes.map { it.id }) { episodes.takeLast(2).map { "episode:${it.id}" }.toSet() }
    val nearEnd by remember(state, lastIds) { derivedStateOf { lastIds.isNotEmpty() && state.layoutInfo.visibleItemsInfo.any { it.key in lastIds } } }
    val latestMore by rememberUpdatedState(onMore)
    var gestureToken by remember(scopeKey) { mutableStateOf(0) }
    var consumedToken by remember(scopeKey) { mutableStateOf(0) }
    LaunchedEffect(state.interactionSource, scopeKey) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) gestureToken++
            // Stop/Cancel does not erase demand: inertial scrolling may reach the page edge.
        }
    }
    LaunchedEffect(gestureToken, consumedToken, nearEnd, enabled) {
        if (gestureToken > consumedToken && nearEnd && enabled && latestMore != null) {
            consumedToken = gestureToken
            latestMore?.invoke()
        }
    }
}

@Composable
private fun DetailArrival(key: String, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable () -> Unit) {
    val motion = LocalMotionPolicy.current
    Box(modifier.motionArrival(key, motion)) { content() }
}

@Composable
private fun DetailSkeleton(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MojaLossColors.NavigationGlass.copy(alpha = .7f)))
}

@Composable
private fun SynopsisSkeleton() {
    Column(Modifier.fillMaxWidth().testTag("details-synopsis-skeleton").semantics { contentDescription = "Loading synopsis" },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailSkeleton(Modifier.fillMaxWidth().height(14.dp))
        DetailSkeleton(Modifier.fillMaxWidth(.94f).height(14.dp))
        DetailSkeleton(Modifier.fillMaxWidth(.68f).height(14.dp))
    }
}

@Composable
private fun SeasonSkeletons() {
    Row(Modifier.testTag("details-season-skeletons").semantics { contentDescription = "Loading seasons" },
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) { DetailSkeleton(Modifier.width(110.dp).height(48.dp)) }
    }
}

@Composable
private fun EpisodeSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 88.dp).padding(11.dp).semantics { contentDescription = "Loading episode" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailSkeleton(Modifier.width(82.dp).height(62.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailSkeleton(Modifier.fillMaxWidth(.85f).height(16.dp))
            DetailSkeleton(Modifier.width(80.dp).height(12.dp))
        }
    }
}

@Composable
private fun CastSkeletons() {
    Row(Modifier.testTag("details-cast-skeletons").semantics { contentDescription = "Loading cast" },
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) { DetailSkeleton(Modifier.width(116.dp).height(156.dp)) }
    }
}
