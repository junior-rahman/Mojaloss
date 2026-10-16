package com.mojaloss.app

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import coil3.imageLoader
import com.mojaloss.app.adaptive.AdaptiveContentPane
import com.mojaloss.app.feature.player.MainPlayerScreen
import com.mojaloss.app.feature.player.TrailerScreen
import com.mojaloss.app.feature.ui.*
import com.mojaloss.app.update.AppUpdateScreen
import com.mojaloss.core.model.*
import com.mojaloss.core.ui.*
import com.mojaloss.core.ui.motion.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable private data object ShellRoute : NavKey
@Serializable private data class TitleRoute(val id: String) : NavKey
@Serializable private data object SettingsRoute : NavKey
@Serializable private data object DownloadsRoute : NavKey
@Serializable private data object PlayerRoute : NavKey
@Serializable private data class TrailerRoute(val videoId: String) : NavKey
private const val PLAYBACK_MOTION_KEY = "MojaLoss.playback"

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MojaLossTheme { MojaLossRoot(application as MojaLossApplication) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun MojaLossRoot(application: MojaLossApplication) {
    var preview by rememberSaveable { mutableStateOf(false) }
    var showAppUpdates by rememberSaveable { mutableStateOf(false) }
    val appUpdate by application.appUpdater.state.collectAsStateWithLifecycle()
    val activityContext = LocalContext.current
    fun openUpdates() { showAppUpdates = true; application.appUpdater.check() }
    val demo = remember(application) { PreviewProvider.create(application.appScope) }
    val repository: MediaRepository = if (preview && demo != null) demo else application.repository
    val factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(repository, application.applicationContext, if (preview) null else application.offlineDownloads) as T
        }
    }
    val model: AppViewModel = viewModel(key = if (preview) "preview" else "live", factory = factory)
    val lifecycleOwner = LocalLifecycleOwner.current
    var foregroundEpoch by remember { mutableIntStateOf(0) }
    var appForeground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(model, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { appForeground = true; model.setAppActive(true); foregroundEpoch++ }
                Lifecycle.Event.ON_STOP -> { appForeground = false; model.setAppActive(false) }
                Lifecycle.Event.ON_RESUME -> application.appUpdater.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        model.setAppActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); model.setAppActive(false) }
    }
    val session by repository.session.collectAsStateWithLifecycle()
    val diagnosticReport by application.diagnostics.report.collectAsStateWithLifecycle()
    val catalog by repository.catalog.collectAsStateWithLifecycle()
    val favorites by repository.favorites.collectAsStateWithLifecycle()
    val preferences by repository.preferences.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    val details by model.details.collectAsStateWithLifecycle()
    val detailErrors by model.detailErrors.collectAsStateWithLifecycle()
    val episodeMap by model.episodes.collectAsStateWithLifecycle()
    val episodeSections by model.episodeSections.collectAsStateWithLifecycle()
    val homeResume by model.homeResume.collectAsStateWithLifecycle()
    val platforms by model.platforms.collectAsStateWithLifecycle()
    val homeShelves by model.homeShelves.collectAsStateWithLifecycle()
    val seriesStarts by model.seriesStarts.collectAsStateWithLifecycle()
    val genres by model.genres.collectAsStateWithLifecycle()
    val preparingTitleId by model.preparingTitleId.collectAsStateWithLifecycle()
    val player by model.player.collectAsStateWithLifecycle()
    val downloadLibrary by model.downloadLibrary.collectAsStateWithLifecycle()
    val downloadRequest by model.downloadRequest.collectAsStateWithLifecycle()
    val downloadPreparing by model.downloadPreparing.collectAsStateWithLifecycle()
    val homeRecentUploads by model.homeRecentUploads.collectAsStateWithLifecycle()
    val downloadingSeason by model.downloadingSeason.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestDownloadNotification() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val motion = rememberMotionPolicy(preferences)
    val confirmation = rememberConfirmationFeedback(preferences)
    val connected = session as? SessionState.Connected
    SystemChrome()
    val restoring = session as? SessionState.Restoring
    val displayScope = connected?.scope ?: restoring?.scope
    // Restoring is admitted only after a matching encrypted saved account/cache is read.
    // Keep that read-only shell when another destination has no cached rows yet.
    val hasSavedLibrary = restoring != null
    val copyErrorReport: (() -> Unit)? = diagnosticReport?.let { report ->
        {
            application.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("MojaLoss error report", report))
            Toast.makeText(application, "Error report copied", Toast.LENGTH_SHORT).show()
        }
    }
    var lastAccountScope by remember { mutableStateOf<SessionScope?>(null) }

    LaunchedEffect(displayScope) {
        if (lastAccountScope != displayScope) {
            withContext(Dispatchers.IO) { application.updateArtworkAccount(displayScope) }
            if (lastAccountScope != null) model.closePlayer()
            lastAccountScope = displayScope
        }
    }
    LaunchedEffect(favorites, connected?.scope) {
        val generation = connected?.scope?.generation ?: return@LaunchedEffect
        favorites.forEach { (id, state) ->
            if (!state.pending && state.error == null && state.operation > 0)
                confirmation.confirm(ConfirmationEvent(generation, "favorite:$id", state.operation))
        }
    }

    CompositionLocalProvider(LocalMotionPolicy provides motion) {
        MojaLossBackdrop(Modifier.fillMaxSize()) {
            if (connected == null && !hasSavedLibrary && !preview) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().motionArrival("connect", motion)) {
                    ConnectScreen(
                        session, model::connectQuick, model::connectPassword, model::cancelConnection,
                        onPreview = if (BuildConfig.DEBUG && demo != null) ({ preview = true }) else null,
                        initialServerUrl = "https://www.mojaloss.stream/jelly",
                        onCopyErrorReport = copyErrorReport,
                        onCheckUpdates = ::openUpdates
                    )
                }
            } else {
                key(displayScope?.serverUrl, displayScope?.accountId, displayScope?.generation, preview) {
                    val stack = rememberNavBackStack(ShellRoute)
                    var overlayName by rememberSaveable { mutableStateOf<String?>(null) }
                    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
                    var collectionName by rememberSaveable { mutableStateOf("") }
                    var query by rememberSaveable { mutableStateOf("") }
                    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
                    var genre by rememberSaveable { mutableStateOf<String?>(null) }
                    var year by rememberSaveable { mutableStateOf<Int?>(null) }
                    var sort by rememberSaveable { mutableStateOf("Release newest") }
                    val filter = BrowseFilter(kindName?.let(MediaKind::valueOf), genre, year, sort)
                    val holder = rememberSaveableStateHolder()
                    val snackbar = remember { SnackbarHostState() }
                    val shellVisible = stack.lastOrNull() == ShellRoute
                    val sheetVisible = shellVisible && overlayName != null
                    val homeVisible = shellVisible && overlayName == null && appForeground
                    var seasonDownload by remember { mutableStateOf<Pair<String, SeasonOption>?>(null) }
                    LaunchedEffect(stack.lastOrNull(), appForeground) {
                        model.setDownloadsVisible(stack.lastOrNull() == DownloadsRoute && appForeground)
                    }
                    DisposableEffect(model) { onDispose { model.setDownloadsVisible(false) } }
                    SystemChrome(stack.lastOrNull() == PlayerRoute)
                    LaunchedEffect(homeVisible, connected?.scope, preview, foregroundEpoch) {
                        model.setHomeFeedActive(homeVisible && connected != null && !preview)
                    }
                    fun openGroup(group: PlatformCollection, selectedKind: MediaKind? = null, selectedSort: String = "Release newest") {
                        collectionId = group.id; collectionName = group.name
                        kindName = selectedKind?.name; genre = null; year = null; sort = selectedSort
                        overlayName = Destination.BROWSE.name
                    }
                    fun explore() {
                        collectionId = null; collectionName = ""
                        kindName = null; genre = null; year = null; sort = "Release newest"
                        overlayName = Destination.BROWSE.name
                    }
                    fun navigate(route: NavKey) { if (stack.lastOrNull() != route) stack.add(route) }
                    fun openTitle(title: MediaTitle) { model.rememberTitle(title); navigate(TitleRoute(title.id)) }
                    fun playTitle(title: MediaTitle) { model.rememberTitle(title); model.play(title.id) }
                    fun downloadTitle(title: MediaTitle) {
                        if (downloadLibrary.items.any { it.title.id == title.id }) navigate(DownloadsRoute)
                        else model.requestDownload(title)
                    }
                    fun back() {
                        model.cancelPreparation()
                        if (stack.lastOrNull() == PlayerRoute) model.closePlayer()
                        if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    }
                    LaunchedEffect(player) { if (player != null) navigate(PlayerRoute) }
                    LaunchedEffect(notice?.id) {
                        val event = notice ?: return@LaunchedEffect
                        val result = snackbar.showSnackbar(
                            message = event.text,
                            actionLabel = if (event.canUndo) "Undo" else null,
                            duration = if (event.canUndo) SnackbarDuration.Long else SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed && event.canUndo) model.undoRemoval(event.id)
                        else model.dismissMessage(event.id)
                    }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            if (preview && stack.lastOrNull() != PlayerRoute) Surface(color = MojaLossColors.Sage) {
                                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("Design preview · Sample titles", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                    TextButton(onClick = { model.closePlayer(); preview = false }) { Text("Exit preview") }
                                }
                            }
                            CompositionLocalProvider(
                                LocalAccountAction provides { navigate(SettingsRoute) },
                                LocalCatalogRefresh provides if (preview) null else model::refresh,
                                LocalCatalogLoadMore provides if (preview) null else model::loadMore,
                                LocalErrorReportCopy provides if (preview) null else copyErrorReport,
                            ) {
                                NavDisplay(
                                    modifier = Modifier.weight(1f), backStack = stack, onBack = { back() },
                                    // Do not retain an outgoing player/trailer surface for decoration.
                                    transitionSpec = { pageTransition(motion, (initialState.entries + targetState.entries)
                                        .any { it.metadata[PLAYBACK_MOTION_KEY] == true }) },
                                    popTransitionSpec = { pageTransition(motion, (initialState.entries + targetState.entries)
                                        .any { it.metadata[PLAYBACK_MOTION_KEY] == true }) },
                                    predictivePopTransitionSpec = { _ -> pageTransition(motion, (initialState.entries + targetState.entries)
                                        .any { it.metadata[PLAYBACK_MOTION_KEY] == true }) },
                                    entryProvider = entryProvider {
                                        entry<ShellRoute> {
                                            val homeQuery = CatalogQuery(Destination.HOME)
                                            val destination = overlayName?.let(Destination::valueOf) ?: Destination.HOME
                                            val requestedQuery = when (destination) {
                                                Destination.HOME -> homeQuery
                                                Destination.BROWSE -> CatalogQuery(destination, filter.copy(
                                                    genre = filter.genre?.trim()?.takeIf(String::isNotEmpty),
                                                    sort = filter.sort.takeIf { it in setOf("Title", "Release newest", "Recently added") } ?: "Release newest"), parentId = collectionId)
                                                Destination.SEARCH -> CatalogQuery(destination, search = query.trim().take(512))
                                                Destination.MY_LIST -> CatalogQuery(destination)
                                            }
                                            fun visibleSnapshot(wanted: CatalogQuery): CatalogSnapshot {
                                                val saved = if (preview || catalog.query == wanted) catalog else repository.cachedCatalog(wanted)
                                                return (saved ?: CatalogSnapshot(query = wanted, isConnectionVerified = catalog.isConnectionVerified))
                                                    .copy(isConnectionVerified = connected != null && catalog.isConnectionVerified, isDemo = preview)
                                            }
                                            val homeSnapshot = visibleSnapshot(homeQuery)
                                            val overlaySnapshot = visibleSnapshot(requestedQuery)
                                            LaunchedEffect(displayScope, connected != null, requestedQuery, shellVisible, preview) {
                                                if (shellVisible && !preview) {
                                                    if (destination == Destination.SEARCH && requestedQuery.search.isNotEmpty()) delay(300)
                                                    model.showCatalog(requestedQuery)
                                                    if (destination == Destination.HOME) model.loadHomeResume()
                                                }
                                            }
                                            LaunchedEffect(foregroundEpoch, requestedQuery, shellVisible, connected?.scope, preview) {
                                                if (shellVisible && connected != null && !preview) {
                                                    model.refreshVisibleIfStale(requestedQuery)
                                                    if (destination == Destination.HOME) model.loadHomeResume()
                                                }
                                            }
                                            AdaptiveContentPane(Modifier.safeDrawingPadding()) {
                                                Box(Modifier.fillMaxSize()) {
                                                    holder.SaveableStateProvider("home") {
                                                        CompositionLocalProvider(
                                                            LocalCatalogRefresh provides if (preview) null else ({
                                                                model.refresh(); model.loadHomeResume(force = true)
                                                            }),
                                                            LocalCatalogLoadMore provides if (preview || overlayName != null) null else model::loadMore,
                                                        ) {
                                                            HomeScreen(homeSnapshot, onTitle = ::openTitle, onPlay = ::playTitle,
                                                                featuredScopeKey = displayScope,
                                                                resumeState = if (preview) HomeResumeState(ContentStatus.READY,
                                                                    catalog.titles.filter { it.confirmedResumeMs > 0 }) else homeResume,
                                                                platformState = if (preview) PlatformCollectionsState(ContentStatus.READY)
                                                                    else if (connected == null) platforms.copy(status = ContentStatus.OFFLINE, message = "Reconnect to view streaming platforms") else platforms,
                                                                onResumeRetry = { if (!preview) { if (connected == null) model.reconnect() else model.loadHomeResume(force = true) } },
                                                                onRequestPlatforms = { if (shellVisible && overlayName == null && !preview) {
                                                                    if (connected == null) model.reconnect() else model.loadPlatforms()
                                                                } },
                                                                onPlatformSelected = { collection ->
                                                                    openGroup(collection)
                                                                },
                                                                shelfStates = if (preview) emptyMap() else homeShelves,
                                                                onRequestShelf = { kind -> if (homeVisible && connected != null && !preview) {
                                                                    model.setHomeFeedActive(true); model.loadHomeShelf(kind)
                                                                } },
                                                                onShelfRetry = { kind -> if (homeVisible && !preview) {
                                                                    if (connected == null) model.reconnect() else model.loadHomeShelf(kind, force = true)
                                                                } },
                                                                onShelfLoadMore = { kind -> if (homeVisible && connected != null && !preview) model.loadHomeShelf(kind, more = true) },
                                                                onShelfSourceSelected = { kind, source -> if (homeVisible && connected != null && !preview) model.loadHomeShelf(kind, source = source) },
                                                                onShelfSeeAll = { kind, source -> openGroup(source, kind.mediaKind,
                                                                    "Release newest") },
                                                                onSeeAll = ::explore,
                                                                onMyList = { overlayName = Destination.MY_LIST.name },
                                                                preparingTitleId = preparingTitleId, feedActive = homeVisible,
                                                                onDownloads = if (preview) null else ({ navigate(DownloadsRoute) }),
                                                                recentUploadsState = if (preview) null else homeRecentUploads,
                                                                onRequestRecentUploads = { if (homeVisible && connected != null && !preview) model.loadHomeRecentUploads() },
                                                                onRecentUploadsSeeAll = {
                                                                    collectionId = null; collectionName = "Recently uploaded"
                                                                    kindName = null; genre = null; year = null; sort = "Recently added"
                                                                    overlayName = Destination.BROWSE.name
                                                                })
                                                        }
                                                    }
                                                    if (shellVisible && overlayName == null) ExtendedFloatingActionButton(
                                                        onClick = { overlayName = Destination.SEARCH.name },
                                                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("home-search"),
                                                        containerColor = MojaLossColors.Forest, contentColor = MojaLossColors.OnForest,
                                                        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                                        text = { Text("Search") })
                                                }
                                            }
                                            if (sheetVisible) ModalBottomSheet(
                                                onDismissRequest = { overlayName = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                                containerColor = MojaLossColors.Porcelain,
                                            ) {
                                                AdaptiveContentPane {
                                                    Box(Modifier.fillMaxSize()) {
                                                        holder.SaveableStateProvider("sheet:$destination:${collectionId.orEmpty()}") {
                                                            when (destination) {
                                                                Destination.BROWSE -> BrowseScreen(overlaySnapshot, filter,
                                                                    onFilter = { kindName = it.kind?.name; genre = it.genre; year = it.year; sort = it.sort },
                                                                    onTitle = ::openTitle, genreOptions = genres.genres, genreStatus = genres.status,
                                                                    genreError = genres.message, onRequestGenres = { kind, search -> model.loadGenres(kind, search) },
                                                                    heading = collectionName.ifBlank { "Explore" }, onClose = { overlayName = null })
                                                                Destination.SEARCH -> SearchScreen(overlaySnapshot, query,
                                                                    onQuery = { query = it }, onTitle = ::openTitle, onClose = { overlayName = null })
                                                                Destination.MY_LIST -> MyListScreen(overlaySnapshot, favorites,
                                                                    onTitle = ::openTitle, onRemove = { model.setFavorite(it.id, false) }, onClose = { overlayName = null })
                                                                Destination.HOME -> Unit
                                                            }
                                                        }
                                                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(12.dp))
                                                    }
                                                }
                                            }
                                        }
                                        entry<TitleRoute> { route ->
                                            LaunchedEffect(route.id, connected?.scope) { if (connected != null) model.loadTitle(route.id) }
                                            val title = model.knownTitle(route.id)
                                            AdaptiveContentPane(Modifier.safeDrawingPadding()) {
                                                if (title != null) {
                                                    val actionsAllowed = connected != null && catalog.canUseLibrary && !preview
                                                    val display = title.copy(isPlayable = title.isPlayable && actionsAllowed)
                                                    val currentStartEpisode = seriesStarts[route.id]?.episode
                                                    fun displayedEpisode(episode: MediaTitle): MediaTitle {
                                                        val current = currentStartEpisode?.takeIf { it.id == episode.id } ?: episode
                                                        return current.copy(isPlayable = current.isPlayable && actionsAllowed)
                                                    }
                                                    val detailError = detailErrors[route.id]
                                                    val detailStatus = when {
                                                        connected == null && !preview -> ContentStatus.OFFLINE
                                                        detailError != null -> ContentStatus.ERROR
                                                        details[route.id] == null && !preview -> ContentStatus.LOADING
                                                        else -> ContentStatus.READY
                                                    }
                                                    Column(Modifier.fillMaxSize()) {
                                                        if (detailStatus in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                                                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                Text(detailError ?: "Saved title · reconnect to update", Modifier.weight(1f).padding(vertical = 8.dp))
                                                                TextButton(onClick = { if (connected == null) model.reconnect() else model.loadTitle(route.id) }) {
                                                                    Text(if (connected == null) "Reconnect" else "Retry")
                                                                }
                                                            }
                                                        }
                                                        Box(Modifier.weight(1f)) {
                                                            TitleScreen(display,
                                                                favorites[route.id] ?: FavoriteState(title.favorite, title.favorite),
                                                                episodeMap[route.id].orEmpty().map(::displayedEpisode),
                                                                onBack = { back() }, onSave = { model.setFavorite(route.id, it) },
                                                                onPlay = { model.play(route.id, it) },
                                                                onEpisodePlay = { if (model.selectSeriesEpisode(route.id, it)) model.play(it.id) },
                                                                onTrailer = { title.trailerVideoId?.let { navigate(TrailerRoute(it)) } },
                                                                detailStatus = detailStatus, canSave = actionsAllowed,
                                                                episodeState = if (preview) null else (episodeSections[route.id]?.let { state ->
                                                                    state.copy(titles = state.titles.map(::displayedEpisode))
                                                                } ?: EpisodeSectionState(status = if (connected == null) ContentStatus.OFFLINE else ContentStatus.LOADING,
                                                                    seasonStatus = if (connected == null) ContentStatus.OFFLINE else ContentStatus.LOADING)),
                                                                onSelectSeason = { model.selectSeason(route.id, it) },
                                                                onEpisodeRetry = { model.retryEpisodes(route.id) },
                                                                onEpisodeLoadMore = { model.loadMoreEpisodes(route.id) },
                                                                onSeasonLoadMore = { model.loadSeasons(route.id, more = true) },
                                                                onSeasonRetry = { model.retrySeasons(route.id) },
                                                                onReconnect = model::reconnect, isPreparing = preparing && preparingTitleId == route.id,
                                                                preparingEpisodeId = preparingTitleId?.takeIf { model.knownTitle(it)?.seriesId == route.id },
                                                                primaryEpisode = seriesStarts[route.id]?.episode?.let { it.copy(isPlayable = it.isPlayable && actionsAllowed) },
                                                                seriesStartState = if (preview || title.kind != MediaKind.SERIES) null else
                                                                    (seriesStarts[route.id] ?: SeriesStartState()).let { state -> state.copy(
                                                                        status = if (connected == null) ContentStatus.OFFLINE else state.status,
                                                                        episode = state.episode?.let { it.copy(isPlayable = it.isPlayable && actionsAllowed) }) },
                                                                onSeriesStartRetry = { if (connected == null) model.reconnect() else model.loadSeriesStart(route.id, force = true) },
                                                                resumeEpisode = homeResume.titles.firstOrNull { it.seriesId == route.id && it.confirmedResumeMs > 0 }
                                                                    ?.let { it.copy(isPlayable = it.isPlayable && actionsAllowed) },
                                                                downloads = downloadLibrary.items.associateBy { it.title.id },
                                                                downloadPreparing = downloadPreparing,
                                                                onDownload = if (preview || connected == null) null else ::downloadTitle,
                                                                onDownloadSeason = if (preview || connected == null) null else ({ season -> seasonDownload = route.id to season }),
                                                                isDownloadingSeason = downloadingSeason.any { it.startsWith("${route.id}:") })
                                                        }
                                                    }
                                                } else Column(Modifier.padding(20.dp)) {
                                                    Text(detailErrors[route.id] ?: "Loading title…")
                                                    TextButton(onClick = { if (connected == null) model.reconnect() else model.loadTitle(route.id) }) {
                                                        Text(if (connected == null) "Reconnect" else "Retry")
                                                    }
                                                    TextButton(onClick = { back() }) { Text("Back") }
                                                }
                                            }
                                        }
                                        entry<SettingsRoute> {
                                            AdaptiveContentPane(Modifier.safeDrawingPadding()) {
                                                SettingsScreen(preferences,
                                                    if (preview) "Design preview" else connected?.account?.displayName ?: restoring?.account?.displayName,
                                                    onPreferences = model::preferences,
                                                    onSignOut = { if (preview) model.closePlayer() else model.signOut(); preview = false },
                                                    onBack = { back() }, accountVerified = preview || connected != null && catalog.isConnectionVerified,
                                                    onReconnect = model::reconnect,
                                                    onCheckUpdates = ::openUpdates,
                                                    appVersion = BuildConfig.VERSION_NAME,
                                                    onCopyErrorReport = copyErrorReport)
                                            }
                                        }
                                        entry<DownloadsRoute> {
                                            AdaptiveContentPane(Modifier.safeDrawingPadding()) {
                                                DownloadsScreen(downloadLibrary, onClose = { back() },
                                                    onPlay = { model.playDownload(it.id) },
                                                    onPause = { model.pauseDownload(it.id) },
                                                    onResume = { model.resumeDownload(it.id) },
                                                    onRetry = { model.retryDownload(it.id) },
                                                    onRemove = { model.removeDownload(it.id) },
                                                    onSettings = model::downloadSettings,
                                                    onRetryLibrary = model::initializeDownloads)
                                            }
                                        }
                                        entry<PlayerRoute>(metadata = mapOf(PLAYBACK_MOTION_KEY to true)) {
                                            val activePlayer = player
                                            if (activePlayer != null) MainPlayerScreen(activePlayer, onBack = { back() },
                                                onReplay = model::replayActive)
                                            else Column(Modifier.padding(20.dp)) {
                                                Text(if (preparing) "Preparing video…" else "Playback ended.")
                                                TextButton(onClick = { back() }) { Text("Back to title") }
                                            }
                                        }
                                        entry<TrailerRoute>(metadata = mapOf(PLAYBACK_MOTION_KEY to true)) { route -> TrailerScreen(route.videoId, onBack = { back() }) }
                                    }
                                )
                            }
                        }
                        downloadRequest?.let { source ->
                            DownloadConfirmSheet(source, downloadLibrary.settings, downloadLibrary.usedBytes, downloadLibrary.freeBytes,
                                onSettings = model::downloadSettings,
                                onConfirm = { requestDownloadNotification(); model.confirmDownload() },
                                onDismiss = model::dismissDownloadRequest)
                        }
                        seasonDownload?.let { (seriesId, season) ->
                            AlertDialog(onDismissRequest = { seasonDownload = null }, title = { Text("Download ${season.name}?") },
                                text = { Text("Save episodes in their original quality for offline viewing. Downloads follow your Wi-Fi and storage settings. Large seasons may need extra space.") },
                                confirmButton = { TextButton(onClick = {
                                    seasonDownload = null; requestDownloadNotification(); model.downloadSeason(seriesId, season)
                                }) { Text("Download season") } },
                                dismissButton = { TextButton(onClick = { seasonDownload = null }) { Text("Cancel") } })
                        }
                        if (!sheetVisible && stack.lastOrNull() != PlayerRoute) SnackbarHost(snackbar,
                            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(horizontal = 12.dp,
                                vertical = if (shellVisible) 88.dp else 12.dp))
                    }
                }
            }
        }
        if (showAppUpdates) {
            ModalBottomSheet(onDismissRequest = { showAppUpdates = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AppUpdateScreen(appUpdate,
                        onCheck = application.appUpdater::check,
                        onDownload = application.appUpdater::download,
                        onCancel = application.appUpdater::cancel,
                        onInstall = {
                            var context = activityContext
                            while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
                            (context as? android.app.Activity)?.let(application.appUpdater::install)
                        },
                        onBack = { showAppUpdates = false })
                }
            }
        }
    }
}

@Composable
private fun SystemChrome(isPlayer: Boolean = false) {
    val view = LocalView.current
    val lightBackground = !isPlayer && MaterialTheme.colorScheme.background.luminance() > .5f
    SideEffect {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        (context as? android.app.Activity)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = lightBackground
            controller.isAppearanceLightNavigationBars = lightBackground
        }
    }
}

