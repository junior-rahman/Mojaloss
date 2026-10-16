package com.mojaloss.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import com.mojaloss.core.model.*
import com.mojaloss.core.player.Media3MainPlayerController
import com.mojaloss.core.downloads.OfflineDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
class AppViewModel(
    val repository: MediaRepository,
    private val applicationContext: Context,
    private val offlineDownloads: OfflineDownloads? = null,
) : ViewModel() {
    val downloadLibrary: StateFlow<DownloadLibrary> = offlineDownloads?.library
        ?: MutableStateFlow(DownloadLibrary(initialized = true, message = "Downloads are unavailable in this preview."))
    private val _downloadPreparing = MutableStateFlow<Set<String>>(emptySet())
    val downloadPreparing = _downloadPreparing.asStateFlow()
    private val _downloadRequest = MutableStateFlow<DownloadSource?>(null)
    val downloadRequest = _downloadRequest.asStateFlow()
    private val _downloadingSeason = MutableStateFlow<Set<String>>(emptySet())
    val downloadingSeason = _downloadingSeason.asStateFlow()
    private var downloadInitialization: Job? = null
    private var downloadAdmission: Job? = null
    private var downloadAdmissionVersion = 0L
    private var admissionTitleId: String? = null
    private val enqueueJobs = mutableMapOf<String, Job>()
    private val seasonDownloadJobs = mutableMapOf<String, Job>()
    private data class SeasonDownloadProgress(val scope: SessionScope, val season: SeasonOption,
        var nextStart: Int = 0, val seen: MutableSet<String> = linkedSetOf(),
        var pending: List<MediaTitle> = emptyList(), var pendingNext: Int? = null, var complete: Boolean = false)
    private val seasonDownloadProgress = linkedMapOf<String, SeasonDownloadProgress>()
    private var downloadsVisible = false
    private var activeDownloadId: String? = null
    private var activePlaybackId: String? = null
    private val _homeRecentUploads = MutableStateFlow(HomeResumeState())
    val homeRecentUploads = _homeRecentUploads.asStateFlow()
    private var recentUploadsJob: Job? = null
    private var recentUploadsVersion = 0L
    private var recentUploadsLoadedAt: Long? = null
    private var recentUploadsVerified = false
    private val failedSeasonAppends = mutableSetOf<String>()
    private val _details = MutableStateFlow<Map<String, MediaTitle>>(emptyMap())
    val details = _details.asStateFlow()
    private val _detailErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val detailErrors = _detailErrors.asStateFlow()
    private val _episodes = MutableStateFlow<Map<String, List<MediaTitle>>>(emptyMap())
    val episodes = _episodes.asStateFlow()
    private val _episodeSections = MutableStateFlow<Map<String, EpisodeSectionState>>(emptyMap())
    val episodeSections = _episodeSections.asStateFlow()
    private val episodeJobs = mutableMapOf<String, Job>()
    private val seasonJobs = mutableMapOf<String, Job>()
    private val episodeCursors = mutableMapOf<String, Int?>()
    private val seasonCursors = mutableMapOf<String, Int?>()
    private val episodeVersions = mutableMapOf<String, Long>()
    private val _homeResume = MutableStateFlow(HomeResumeState())
    val homeResume = _homeResume.asStateFlow()
    private val _platforms = MutableStateFlow(PlatformCollectionsState())
    val platforms = _platforms.asStateFlow()
    private val _homeShelves = MutableStateFlow<Map<HomeShelfKind, HomeShelfState>>(emptyMap())
    val homeShelves = _homeShelves.asStateFlow()
    private val shelfJobs = mutableMapOf<HomeShelfKind, Job>()
    private val shelfVersions = mutableMapOf<HomeShelfKind, Long>()
    private val shelfCursors = mutableMapOf<HomeShelfKind, Int?>()
    private val shelfLoadedAt = mutableMapOf<HomeShelfKind, Long>()
    private val failedShelfAppends = mutableSetOf<HomeShelfKind>()
    private var homeFeedActive = false
    private val _seriesStarts = MutableStateFlow<Map<String, SeriesStartState>>(emptyMap())
    val seriesStarts = _seriesStarts.asStateFlow()
    private val seriesStartJobs = mutableMapOf<String, Job>()
    private val seriesStartVersions = mutableMapOf<String, Long>()
    private val seriesStartLoadedAt = mutableMapOf<String, Long>()
    private val manualSeriesSeasons = mutableSetOf<String>()
    private val _genres = MutableStateFlow(GenreOptionsState())
    val genres = _genres.asStateFlow()
    private var resumeJob: Job? = null
    private var resumeKind: MediaKind? = null
    private var resumeVerified = false
    private var resumeVersion = 0L
    private var platformJob: Job? = null
    private var genreJob: Job? = null
    private var genreVersion = 0L
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    data class Notice(val id: Long, val text: String, val canUndo: Boolean = false)
    private var noticeSequence = 0L
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice = _notice.asStateFlow()
    private data class Removal(val id: String, val scope: SessionScope, val sequence: Long)
    private var removal: Removal? = null
    private val favoriteSequences = mutableMapOf<String, Long>()
    private var favoriteSequenceCounter = 0L
    private val _canUndoRemoval = MutableStateFlow(false)
    val canUndoRemoval = _canUndoRemoval.asStateFlow()
    private val _preparing = MutableStateFlow(false)
    val preparing = _preparing.asStateFlow()
    private val _preparingTitleId = MutableStateFlow<String?>(null)
    val preparingTitleId = _preparingTitleId.asStateFlow()
    private val _player = MutableStateFlow<Media3MainPlayerController?>(null)
    val player = _player.asStateFlow()
    private var preparation: Job? = null
    private var playbackGeneration = 0L
    private var appActive = false
    private val detailJobs = mutableMapOf<String, Job>()
    private val detailLoadedAt = mutableMapOf<String, Long>()
    private val detailVisits = linkedSetOf<String>()
    private val knownTitles = linkedMapOf<String, MediaTitle>()
    private var catalogSelection: Job? = null
    private var selectedQuery: CatalogQuery? = null
    private var pageDemand: Job? = null
    private var refreshDemand: Job? = null

    init {
        viewModelScope.launch { repository.initialize() }
        initializeDownloads()
        viewModelScope.launch {
            var previous: SessionScope? = null
            repository.session.collect { session ->
                val current = when (session) {
                    is SessionState.Connected -> session.scope
                    is SessionState.Restoring -> session.scope
                    else -> null
                }
                // Identity is invalidated synchronously; download transport rechecks it on reads.
                offlineDownloads?.setOwner(current)
                if (previous != current) {
                    cancelDownloadRequests()
                    recentUploadsJob?.cancel(); recentUploadsVersion++; recentUploadsLoadedAt = null
                    _homeRecentUploads.value = HomeResumeState()
                    detailJobs.values.forEach { it.cancel() }
                    detailJobs.clear()
                    detailLoadedAt.clear()
                    detailVisits.clear()
                    knownTitles.clear()
                    episodeJobs.values.forEach { it.cancel() }; episodeJobs.clear()
                    seasonJobs.values.forEach { it.cancel() }; seasonJobs.clear()
                    episodeCursors.clear(); seasonCursors.clear(); episodeVersions.clear(); failedSeasonAppends.clear()
                    resumeJob?.cancel(); resumeVersion++
                    platformJob?.cancel(); genreJob?.cancel(); genreVersion++
                    refreshDemand?.cancel()
                    catalogSelection?.cancel()
                    catalogSelection = null
                    selectedQuery = null
                    pageDemand?.cancel()
                    pageDemand = null
                    closePlayer()
                    _details.value = emptyMap()
                    _detailErrors.value = emptyMap()
                    _episodes.value = emptyMap()
                    _episodeSections.value = emptyMap()
                    _homeResume.value = HomeResumeState()
                    _platforms.value = PlatformCollectionsState()
                    cancelHomeShelfRequests()
                    _homeShelves.value = emptyMap()
                    shelfCursors.clear(); shelfLoadedAt.clear(); failedShelfAppends.clear()
                    seriesStartJobs.values.forEach { it.cancel() }; seriesStartJobs.clear()
                    seriesStartVersions.clear(); seriesStartLoadedAt.clear(); manualSeriesSeasons.clear()
                    _seriesStarts.value = emptyMap()
                    _genres.value = GenreOptionsState()
                    _message.value = null
                    _notice.value = null
                    removal = null
                    _canUndoRemoval.value = false
                    favoriteSequences.clear()
                    favoriteSequenceCounter = 0L
                    previous = current
                }
            }
        }
    }

    fun loadTitle(id: String) {
        if (repository.session.value !is SessionState.Connected || !repository.catalog.value.isConnectionVerified) {
            return
        }
        detailVisits.remove(id)
        detailVisits.add(id)
        trimDetails()
        if (detailJobs[id]?.isActive == true) return
        val loadedAt = detailLoadedAt[id]
        if (_details.value.containsKey(id) && loadedAt != null &&
            System.currentTimeMillis() - loadedAt in 0 until DETAIL_TTL_MS) {
            if (_details.value[id]?.kind == MediaKind.SERIES) loadSeriesStart(id)
            return
        }
        val initialScope = (repository.session.value as? SessionState.Connected)?.scope
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
            _detailErrors.value = _detailErrors.value - id
            when (val result = repository.details(id)) {
                is OperationResult.Success -> {
                    if (!request.isActive || detailJobs[id] !== request ||
                        initialScope != (repository.session.value as? SessionState.Connected)?.scope) return@launch
                    _details.value = _details.value + (id to result.value)
                    detailLoadedAt[id] = System.currentTimeMillis()
                    if (result.value.kind == MediaKind.SERIES) loadSeriesStart(id)
                    val section = _episodeSections.value[id]
                    if (result.value.kind == MediaKind.SERIES && (section == null ||
                        section.seasons.isEmpty() && section.seasonStatus == ContentStatus.ERROR)) loadSeasons(id)
                }
                is OperationResult.Failure -> if (request.isActive && detailJobs[id] === request &&
                    initialScope == (repository.session.value as? SessionState.Connected)?.scope) {
                    _detailErrors.value = _detailErrors.value + (id to result.message)
                    if (knownTitle(id)?.kind == MediaKind.SERIES && !_episodeSections.value.containsKey(id)) {
                        _episodeSections.value = _episodeSections.value + (id to EpisodeSectionState(
                            status = ContentStatus.ERROR, message = result.message,
                            seasonStatus = ContentStatus.ERROR, seasonMessage = result.message))
                    }
                }
            }
            } finally { if (detailJobs[id] === request) detailJobs.remove(id) }
        }
        detailJobs[id] = request
        request.start()
    }

    private fun trimDetails() {
        while (detailVisits.size > MAX_DETAIL_ENTRIES) {
            val evicted = detailVisits.first()
            detailVisits.remove(evicted)
            detailJobs.remove(evicted)?.cancel()
            detailLoadedAt.remove(evicted)
            _details.value = _details.value - evicted
            _detailErrors.value = _detailErrors.value - evicted
            _episodes.value = _episodes.value - evicted
            _episodeSections.value = _episodeSections.value - evicted
            episodeJobs.remove(evicted)?.cancel(); seasonJobs.remove(evicted)?.cancel()
            episodeCursors.remove(evicted); seasonCursors.remove(evicted); episodeVersions.remove(evicted); failedSeasonAppends.remove(evicted)
        }
    }

    fun setFavorite(id: String, desired: Boolean) {
        if (repository.catalog.value.isDemo) { postNotice("Sample preview does not change your account."); return }
        if (!repository.catalog.value.canUseLibrary) return
        val initialScope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        val sequence = ++favoriteSequenceCounter
        favoriteSequences[id] = sequence
        if (favoriteSequences.size > 64) favoriteSequences.keys.firstOrNull { it != id }?.let { favoriteSequences.remove(it) }
        removal = null
        _canUndoRemoval.value = false
        if (_notice.value?.canUndo == true) { _notice.value = null; _message.value = null }
        viewModelScope.launch {
            val result = repository.setFavorite(id, desired)
            if (initialScope != (repository.session.value as? SessionState.Connected)?.scope || favoriteSequences[id] != sequence) return@launch
            when (result) {
                is OperationResult.Success -> {
                    if (!result.value) {
                        removal = Removal(id, initialScope, sequence)
                        _canUndoRemoval.value = true
                    }
                    postNotice(if (result.value) "Saved to My List" else "Removed from My List", canUndo = !result.value)
                }
                is OperationResult.Failure -> Unit // The account-scoped favorite state keeps the local error.
            }
        }
    }

    fun play(id: String, fromStart: Boolean = false) {
        if (_preparing.value) return
        if (repository.session.value !is SessionState.Connected || !repository.catalog.value.canUseLibrary) {
            postNotice("Connect to stream this title."); return
        }
        val initialScope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        val known = knownTitle(id)
        if (known == null || known.kind == MediaKind.SERIES || !known.isPlayable) {
            postNotice(if (known?.kind == MediaKind.SERIES) "Choose an episode to play." else "This title is not available to play.")
            return
        }
        // Release the previous session before negotiating another stream for the same item.
        _player.value?.release()
        _player.value = null
        activeDownloadId = null; activePlaybackId = id
        val operation = ++playbackGeneration
        _preparing.value = true
        _preparingTitleId.value = id
        preparation = viewModelScope.launch {
            try {
                when (val result = repository.preparePlayback(id, fromStart)) {
                    is OperationResult.Success -> {
                        if (operation != playbackGeneration || initialScope !=
                            (repository.session.value as? SessionState.Connected)?.scope) return@launch
                        val controller = Media3MainPlayerController(applicationContext, viewModelScope) { observation ->
                            viewModelScope.launch {
                                repository.reportPlayback(observation)
                                if (currentScope() == observation.scope) repository.confirmedProgress(observation.mediaId)
                                    ?.let { updateConfirmedProgress(observation.mediaId, it) }
                            }
                        }
                        val preferences = repository.preferences.value
                        controller.exoPlayer.trackSelectionParameters = controller.exoPlayer.trackSelectionParameters.buildUpon()
                            .setPreferredAudioLanguage(languageTag(preferences.audioLanguage))
                            .setPreferredTextLanguage(languageTag(preferences.subtitleLanguage))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, preferences.subtitleLanguage == "Off")
                            .build()
                        _player.value = controller
                        controller.setAppActive(appActive)
                        controller.prepare(result.value, autoPlay = appActive)
                    }
                    is OperationResult.Failure -> postNotice(result.message)
                }
            } finally { if (operation == playbackGeneration) { _preparing.value = false; _preparingTitleId.value = null } }
        }
    }

    fun cancelPreparation() {
        playbackGeneration++
        preparation?.cancel()
        _preparing.value = false
        _preparingTitleId.value = null
    }
    fun closePlayer() {
        cancelPreparation()
        _player.value?.release()
        _player.value = null
        activeDownloadId = null; activePlaybackId = null
    }
    fun undoRemoval(noticeId: Long? = null) {
        if (noticeId != null && _notice.value?.id != noticeId) return
        val candidate = removal ?: run { dismissMessage(noticeId); return }
        if (candidate.scope == (repository.session.value as? SessionState.Connected)?.scope && favoriteSequences[candidate.id] == candidate.sequence) {
            _notice.value = null; _message.value = null
            setFavorite(candidate.id, true)
        }
        else dismissMessage()
    }
    fun dismissMessage(noticeId: Long? = null) {
        if (noticeId != null && _notice.value?.id != noticeId) return
        _message.value = null; _notice.value = null; removal = null; _canUndoRemoval.value = false
    }
    private fun postNotice(text: String, canUndo: Boolean = false) {
        if (!canUndo) { removal = null; _canUndoRemoval.value = false }
        _message.value = text
        _notice.value = Notice(++noticeSequence, text, canUndo)
    }
    fun refresh() {
        if (refreshDemand?.isActive == true || catalogSelection?.isActive == true) return
        refreshDemand = viewModelScope.launch { repository.refresh() }
    }
    fun reconnect() {
        if (refreshDemand?.isActive == true) return
        refreshDemand = viewModelScope.launch { repository.refresh() }
    }
    fun refreshVisibleIfStale(query: CatalogQuery) {
        if (selectedQuery != query || refreshDemand?.isActive == true) return
        val scope = currentScope()
        refreshDemand = viewModelScope.launch {
            catalogSelection?.join()
            if (selectedQuery == query && currentScope() == scope) repository.refreshIfStale()
        }
    }
    fun showCatalog(query: CatalogQuery) {
        if (selectedQuery == query && catalogSelection?.isActive == true) return
        catalogSelection?.cancel()
        pageDemand?.cancel()
        refreshDemand?.cancel()
        selectedQuery = query
        catalogSelection = viewModelScope.launch { repository.showCatalog(query) }
    }
    fun loadMore() {
        if (pageDemand?.isActive == true || catalogSelection?.isActive == true) return
        pageDemand = viewModelScope.launch { repository.loadMore() }
    }
    private fun currentScope(): SessionScope? = when (val session = repository.session.value) {
        is SessionState.Connected -> session.scope
        is SessionState.Restoring -> session.scope
        else -> null
    }
    fun rememberTitle(title: MediaTitle) {
        knownTitles.remove(title.id); knownTitles[title.id] = title
        while (knownTitles.size > 64) knownTitles.remove(knownTitles.keys.first())
    }
    fun knownTitle(id: String): MediaTitle? = _seriesStarts.value.values.firstNotNullOfOrNull { state -> state.episode?.takeIf { it.id == id } }
        ?: _details.value[id]
        ?: repository.catalog.value.titles.firstOrNull { it.id == id }
        ?: _homeResume.value.titles.firstOrNull { it.id == id }
        ?: _homeRecentUploads.value.titles.firstOrNull { it.id == id }
        ?: _homeShelves.value.values.firstNotNullOfOrNull { state -> state.titles.firstOrNull { it.id == id } }
        ?: _episodes.value.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }
        ?: knownTitles[id]

    /** The upload-date row is independent of the release-date Home hero and fetches only on visibility. */
    fun loadHomeRecentUploads(force: Boolean = false) {
        val scope = currentScope() ?: return
        if (!homeFeedActive) return
        val verified = repository.session.value is SessionState.Connected
        if (recentUploadsJob?.isActive == true && recentUploadsVerified == verified && !force) return
        if (!force && recentUploadsVerified == verified && _homeRecentUploads.value.status == ContentStatus.READY &&
            recentUploadsLoadedAt?.let { System.currentTimeMillis() - it in 0 until HOME_SHELF_TTL_MS } == true) return
        recentUploadsJob?.cancel(); recentUploadsVerified = verified
        val version = ++recentUploadsVersion
        val retained = _homeRecentUploads.value.titles
        _homeRecentUploads.value = HomeResumeState(ContentStatus.LOADING, retained, isRefreshing = retained.isNotEmpty())
        recentUploadsJob = viewModelScope.launch {
            val result = repository.homeRecentUploads(force)
            if (currentScope() != scope || version != recentUploadsVersion || !homeFeedActive) return@launch
            _homeRecentUploads.value = when (result) {
                is OperationResult.Success -> {
                    recentUploadsLoadedAt = System.currentTimeMillis()
                    HomeResumeState(if (repository.session.value is SessionState.Connected) ContentStatus.READY else ContentStatus.OFFLINE,
                        result.value.take(12), if (verified) null else "Saved titles · reconnect to update")
                }
                is OperationResult.Failure -> HomeResumeState(ContentStatus.ERROR, retained, result.message)
            }
        }
    }

    fun loadHomeResume(kind: MediaKind? = null, force: Boolean = false) {
        val scope = currentScope() ?: return
        val verified = repository.session.value is SessionState.Connected
        if (resumeJob?.isActive == true && resumeKind == kind && resumeVerified == verified && !force) return
        val retained = if (resumeKind == kind) _homeResume.value.titles else emptyList()
        resumeJob?.cancel(); resumeKind = kind; resumeVerified = verified
        val operation = ++resumeVersion
        _homeResume.value = HomeResumeState(ContentStatus.LOADING, retained, isRefreshing = retained.isNotEmpty())
        resumeJob = viewModelScope.launch {
            val result = repository.homeResume(kind, force)
            if (currentScope() != scope || operation != resumeVersion) return@launch
            _homeResume.value = when (result) {
                is OperationResult.Success -> HomeResumeState(
                    if (repository.session.value is SessionState.Connected) ContentStatus.READY else ContentStatus.OFFLINE,
                    result.value, if (repository.session.value is SessionState.Connected) null else "Saved progress · reconnect to update")
                is OperationResult.Failure -> HomeResumeState(ContentStatus.ERROR, retained, result.message)
            }
        }
    }
    fun loadPlatforms(force: Boolean = false) {
        val scope = currentScope() ?: return
        if (platformJob?.isActive == true) return
        platformJob = viewModelScope.launch {
            _platforms.value = _platforms.value.copy(status = ContentStatus.LOADING, message = null)
            val result = repository.platformCollections(force)
            if (currentScope() != scope) return@launch
            _platforms.value = when (result) {
                is OperationResult.Success -> PlatformCollectionsState(ContentStatus.READY, result.value)
                is OperationResult.Failure -> _platforms.value.copy(status = ContentStatus.ERROR, message = result.message)
            }
        }
    }
    fun setHomeFeedActive(active: Boolean) {
        if (homeFeedActive == active) return
        homeFeedActive = active
        if (!active) {
            cancelHomeShelfRequests()
            recentUploadsJob?.cancel(); recentUploadsVersion++
            _homeRecentUploads.value = _homeRecentUploads.value.let { state -> state.copy(
                status = if (state.titles.isNotEmpty()) ContentStatus.READY else ContentStatus.LOADING, isRefreshing = false) }
        }
    }
    private fun cancelHomeShelfRequests() {
        shelfJobs.forEach { (kind, job) ->
            shelfVersions[kind] = (shelfVersions[kind] ?: 0L) + 1
            job.cancel()
        }
        shelfJobs.clear()
        _homeShelves.value = _homeShelves.value.mapValues { (_, state) ->
            if (state.isLoadingMore || state.isRefreshing || state.status == ContentStatus.LOADING)
                state.copy(status = if (state.titles.isNotEmpty() || state.sources.isNotEmpty()) ContentStatus.READY else ContentStatus.LOADING,
                    isLoadingMore = false, isRefreshing = false)
            else state
        }
    }
    fun loadHomeShelf(kind: HomeShelfKind, force: Boolean = false, more: Boolean = false,
        source: PlatformCollection? = null) {
        val scope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        if (!homeFeedActive) return
        val previous = _homeShelves.value[kind] ?: HomeShelfState()
        if (more && previous.previewLimitReached) return
        val changedSource = source != null && source.id != previous.selectedSource?.id
        if (source != null && previous.sources.none { it.id == source.id && it.name == source.name }) return
        // A visibility change after a failed row is not a deliberate retry.
        if (!force && !changedSource && previous.status in setOf(ContentStatus.ERROR, ContentStatus.OFFLINE)) return
        if (shelfJobs[kind]?.isActive == true && !changedSource && !force) return
        if (!force && !more && !changedSource && previous.status == ContentStatus.READY &&
            shelfLoadedAt[kind]?.let { System.currentTimeMillis() - it in 0 until HOME_SHELF_TTL_MS } == true) return
        if (more && previous.status == ContentStatus.ERROR && !force) return
        val append = !changedSource && (more || force && kind in failedShelfAppends)
        val start = if (append) shelfCursors[kind] ?: return else 0
        if (changedSource) { shelfCursors.remove(kind); failedShelfAppends.remove(kind); shelfLoadedAt.remove(kind) }
        val retained = if (changedSource) previous.copy(titles = emptyList(), hasMore = false,
            selectedSource = source) else previous
        shelfJobs[kind]?.cancel()
        val operation = (shelfVersions[kind] ?: 0L) + 1
        shelfVersions[kind] = operation
        _homeShelves.value = _homeShelves.value + (kind to retained.copy(
            status = if (append && retained.titles.isNotEmpty()) ContentStatus.READY else ContentStatus.LOADING,
            message = null, isLoadingMore = append, isRefreshing = !append && retained.titles.isNotEmpty()))
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.homeShelf(kind, source?.id ?: retained.selectedSource?.id, start, force)
                if (!request.isActive || shelfJobs[kind] !== request || shelfVersions[kind] != operation ||
                    !homeFeedActive || currentScope() != scope) return@launch
                val state = _homeShelves.value[kind] ?: return@launch
                when (result) {
                    is OperationResult.Success -> {
                        val page = result.value
                        val invalid = page.items.size > HOME_SHELF_PAGE_SIZE ||
                            page.items.map { it.id }.distinct().size != page.items.size ||
                            page.nextStart?.let { it <= start } == true ||
                            append && page.items.any { title -> state.titles.any { it.id == title.id } }
                        if (invalid) {
                            shelfCursors.remove(kind)
                            failedShelfAppends.remove(kind)
                            shelfLoadedAt.remove(kind)
                            _homeShelves.value = _homeShelves.value + (kind to state.copy(
                                status = ContentStatus.ERROR, message = "This section changed. Retry to update it.",
                                hasMore = false, isLoadingMore = false, isRefreshing = false))
                            return@launch
                        }
                        shelfCursors[kind] = page.nextStart
                        failedShelfAppends.remove(kind)
                        shelfLoadedAt[kind] = System.currentTimeMillis()
                        val combined = (if (append) state.titles else emptyList()) + page.items
                        val previewLimitReached = combined.size > MAX_HOME_SHELF_CARDS ||
                            combined.size >= MAX_HOME_SHELF_CARDS && page.nextStart != null
                        _homeShelves.value = _homeShelves.value + (kind to HomeShelfState(
                            status = ContentStatus.READY,
                            titles = combined.take(MAX_HOME_SHELF_CARDS),
                            sources = page.sources, selectedSource = page.selectedSource,
                            hasMore = page.nextStart != null && !previewLimitReached,
                            previewLimitReached = previewLimitReached))
                    }
                    is OperationResult.Failure -> {
                        val resetSource = result.recovery == OperationRecovery.RESET_SOURCE
                        val restart = resetSource || result.recovery == OperationRecovery.RESTART_PAGING
                        if (restart) { shelfCursors.remove(kind); failedShelfAppends.remove(kind); shelfLoadedAt.remove(kind) }
                        else if (append) failedShelfAppends.add(kind)
                        _homeShelves.value = _homeShelves.value + (kind to state.copy(
                            status = ContentStatus.ERROR, message = result.message,
                            sources = if (resetSource) emptyList() else state.sources,
                            selectedSource = if (resetSource) null else state.selectedSource,
                            hasMore = if (restart) false else state.hasMore,
                            isLoadingMore = false, isRefreshing = false))
                    }
                }
            } finally { if (shelfJobs[kind] === request) shelfJobs.remove(kind) }
        }
        shelfJobs[kind] = request
        request.start()
    }
    fun loadSeriesStart(seriesId: String, force: Boolean = false) {
        val scope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        if (seriesStartJobs[seriesId]?.isActive == true && !force) return
        val previous = _seriesStarts.value[seriesId] ?: SeriesStartState()
        if (!force && previous.status == ContentStatus.READY &&
            seriesStartLoadedAt[seriesId]?.let { System.currentTimeMillis() - it in 0 until SERIES_START_TTL_MS } == true) return
        seriesStartJobs[seriesId]?.cancel()
        val operation = (seriesStartVersions[seriesId] ?: 0L) + 1
        seriesStartVersions[seriesId] = operation
        _seriesStarts.value = _seriesStarts.value + (seriesId to previous.copy(status = ContentStatus.LOADING, message = null))
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.seriesStart(seriesId, force)
                if (!request.isActive || seriesStartJobs[seriesId] !== request ||
                    seriesStartVersions[seriesId] != operation || currentScope() != scope) return@launch
                when (result) {
                    is OperationResult.Success -> {
                        val episode = result.value
                        if (episode != null && (episode.kind != MediaKind.EPISODE || episode.seriesId != seriesId)) {
                            _seriesStarts.value = _seriesStarts.value + (seriesId to previous.copy(
                                status = ContentStatus.ERROR, message = "Couldn’t identify this series’ episode. Retry."))
                            return@launch
                        }
                        _seriesStarts.value = _seriesStarts.value + (seriesId to SeriesStartState(ContentStatus.READY, episode))
                        seriesStartLoadedAt[seriesId] = System.currentTimeMillis()
                        val section = _episodeSections.value[seriesId]
                        if (episode != null && seriesId !in manualSeriesSeasons) {
                            val season = section?.seasons?.firstOrNull { it.number == episode.seasonNumber }
                            if (season != null && section.selectedSeason?.id != season.id) loadEpisodes(seriesId, season)
                        }
                    }
                    is OperationResult.Failure -> _seriesStarts.value = _seriesStarts.value + (seriesId to previous.copy(
                        status = ContentStatus.ERROR, message = result.message))
                }
            } finally { if (seriesStartJobs[seriesId] === request) seriesStartJobs.remove(seriesId) }
        }
        seriesStartJobs[seriesId] = request
        while (_seriesStarts.value.size > MAX_DETAIL_ENTRIES) {
            val evicted = _seriesStarts.value.keys.first { it != seriesId }
            seriesStartJobs.remove(evicted)?.cancel()
            seriesStartVersions.remove(evicted)
            seriesStartLoadedAt.remove(evicted); manualSeriesSeasons.remove(evicted)
            _seriesStarts.value = _seriesStarts.value - evicted
        }
        request.start()
    }
    fun selectSeriesEpisode(seriesId: String, episode: MediaTitle): Boolean {
        if (repository.session.value !is SessionState.Connected || !repository.catalog.value.canUseLibrary ||
            episode.kind != MediaKind.EPISODE || episode.seriesId != seriesId || !episode.isPlayable) return false
        val known = _seriesStarts.value[seriesId]?.episode?.takeIf { it.id == episode.id }
            ?: _episodeSections.value[seriesId]?.titles?.firstOrNull { it.id == episode.id }
            ?: return false
        if (known.kind != MediaKind.EPISODE || known.seriesId != seriesId || !known.isPlayable) return false
        seriesStartJobs.remove(seriesId)?.cancel()
        seriesStartVersions[seriesId] = (seriesStartVersions[seriesId] ?: 0L) + 1
        seriesStartLoadedAt[seriesId] = System.currentTimeMillis()
        _seriesStarts.value = _seriesStarts.value + (seriesId to SeriesStartState(ContentStatus.READY, known))
        rememberTitle(known)
        return true
    }
    private fun updateConfirmedProgress(id: String, positionMs: Long) {
        if (positionMs < 0) return
        fun updated(title: MediaTitle) = if (title.id == id) title.copy(confirmedResumeMs = positionMs) else title
        knownTitles[id]?.let { knownTitles[id] = updated(it) }
        _details.value = _details.value.mapValues { (_, title) -> updated(title) }
        _homeResume.value = _homeResume.value.copy(titles = _homeResume.value.titles.map(::updated))
        _homeRecentUploads.value = _homeRecentUploads.value.copy(titles = _homeRecentUploads.value.titles.map(::updated))
        _homeShelves.value = _homeShelves.value.mapValues { (_, state) -> state.copy(titles = state.titles.map(::updated)) }
        _episodes.value = _episodes.value.mapValues { (_, titles) -> titles.map(::updated) }
        _episodeSections.value = _episodeSections.value.mapValues { (_, state) -> state.copy(titles = state.titles.map(::updated)) }
        _seriesStarts.value = _seriesStarts.value.mapValues { (_, state) -> state.copy(episode = state.episode?.let(::updated)) }
    }
    fun loadGenres(kind: MediaKind? = null, search: String = "") {
        val scope = currentScope() ?: return
        genreJob?.cancel()
        val operation = ++genreVersion
        _genres.value = _genres.value.copy(status = ContentStatus.LOADING, message = null)
        genreJob = viewModelScope.launch {
            val result = repository.genres(kind, search.trim())
            if (currentScope() != scope || genreVersion != operation) return@launch
            _genres.value = when (result) {
                is OperationResult.Success -> GenreOptionsState(ContentStatus.READY, result.value)
                is OperationResult.Failure -> _genres.value.copy(status = ContentStatus.ERROR, message = result.message)
            }
        }
    }
    fun loadSeasons(seriesId: String, more: Boolean = false) {
        val scope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        if (seasonJobs[seriesId]?.isActive == true) return
        val previous = _episodeSections.value[seriesId] ?: EpisodeSectionState()
        val start = if (more) seasonCursors[seriesId] ?: return else 0
        _episodeSections.value = _episodeSections.value + (seriesId to previous.copy(
            seasonStatus = if (more) previous.seasonStatus else ContentStatus.LOADING,
            seasonMessage = null, isLoadingMoreSeasons = more))
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.seasonPage(seriesId, start)
                if (!request.isActive || seasonJobs[seriesId] !== request || currentScope() != scope) return@launch
                val state = _episodeSections.value[seriesId] ?: return@launch
                when (result) {
                    is OperationResult.Success -> {
                        val repeated = more && result.value.items.isNotEmpty() &&
                            result.value.items.all { item -> state.seasons.any { it.id == item.id } }
                        if (repeated || result.value.nextStart?.let { it <= start } == true) {
                            seasonCursors[seriesId] = null
                            failedSeasonAppends.remove(seriesId)
                            _episodeSections.value = _episodeSections.value + (seriesId to state.copy(
                                seasonStatus = ContentStatus.ERROR, seasonMessage = "Seasons changed. Retry to update them.",
                                hasMoreSeasons = false, isLoadingMoreSeasons = false))
                            return@launch
                        }
                        val options = ((if (more) state.seasons else emptyList()) + result.value.items).distinctBy { it.id }
                        seasonCursors[seriesId] = result.value.nextStart
                        failedSeasonAppends.remove(seriesId)
                        val resume = _seriesStarts.value[seriesId]?.episode
                            ?: _homeResume.value.titles.firstOrNull { it.seriesId == seriesId && it.confirmedResumeMs > 0 }
                        val selected = state.selectedSeason?.takeIf { old -> options.any { it.id == old.id } }
                            ?: options.firstOrNull { resume != null && it.number == resume.seasonNumber }
                            ?: options.firstOrNull { (it.number ?: 0) > 0 } ?: options.firstOrNull()
                        _episodeSections.value = _episodeSections.value + (seriesId to state.copy(
                            seasons = options, selectedSeason = selected, seasonStatus = ContentStatus.READY,
                            seasonMessage = null, hasMoreSeasons = result.value.nextStart != null, isLoadingMoreSeasons = false))
                        if (!more || state.selectedSeason?.id != selected?.id) loadEpisodes(seriesId, selected)
                    }
                    is OperationResult.Failure -> {
                        if (more) failedSeasonAppends.add(seriesId)
                        _episodeSections.value = _episodeSections.value + (seriesId to state.copy(
                            seasonStatus = ContentStatus.ERROR, seasonMessage = result.message, isLoadingMoreSeasons = false,
                            status = if (state.titles.isEmpty()) ContentStatus.ERROR else state.status,
                            message = if (state.titles.isEmpty()) result.message else state.message))
                    }
                }
            } finally { if (seasonJobs[seriesId] === request) seasonJobs.remove(seriesId) }
        }
        seasonJobs[seriesId] = request; request.start()
    }
    fun retrySeasons(seriesId: String) {
        loadSeasons(seriesId, more = seriesId in failedSeasonAppends && seasonCursors[seriesId] != null)
    }
    fun selectSeason(seriesId: String, season: SeasonOption) {
        manualSeriesSeasons.add(seriesId)
        if (_episodeSections.value[seriesId]?.selectedSeason?.id == season.id) return
        loadEpisodes(seriesId, season)
    }
    fun retryEpisodes(seriesId: String) {
        val state = _episodeSections.value[seriesId]
        if (state == null || state.seasonStatus == ContentStatus.ERROR && state.seasons.isEmpty()) loadSeasons(seriesId)
        else loadEpisodes(seriesId, state.selectedSeason,
            more = state.titles.isNotEmpty() && state.hasMore && episodeCursors[seriesId] != null)
    }
    fun loadMoreEpisodes(seriesId: String) {
        val state = _episodeSections.value[seriesId] ?: return
        if (state.hasMore) loadEpisodes(seriesId, state.selectedSeason, more = true)
    }
    private fun loadEpisodes(seriesId: String, season: SeasonOption?, more: Boolean = false) {
        val scope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        val previous = _episodeSections.value[seriesId] ?: EpisodeSectionState()
        val sameSeason = previous.selectedSeason?.id == season?.id
        if (episodeJobs[seriesId]?.isActive == true && sameSeason) return
        episodeJobs.remove(seriesId)?.cancel()
        val start = if (more && sameSeason) episodeCursors[seriesId] ?: return else 0
        val retained = if (sameSeason) previous.titles else emptyList()
        val operation = (episodeVersions[seriesId] ?: 0) + 1
        episodeVersions[seriesId] = operation
        _episodeSections.value = _episodeSections.value + (seriesId to previous.copy(
            selectedSeason = season, status = if (more) ContentStatus.READY else ContentStatus.LOADING,
            titles = retained, message = null, hasMore = if (more) previous.hasMore else false, isLoadingMore = more))
        if (!sameSeason) _episodes.value = _episodes.value - seriesId
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.episodePage(seriesId, season, start)
                if (!request.isActive || episodeJobs[seriesId] !== request ||
                    currentScope() != scope || episodeVersions[seriesId] != operation) return@launch
                val state = _episodeSections.value[seriesId] ?: return@launch
                when (result) {
                    is OperationResult.Success -> {
                        val repeated = more && result.value.items.isNotEmpty() &&
                            result.value.items.all { item -> state.titles.any { it.id == item.id } }
                        if (repeated || result.value.nextStart?.let { it <= start } == true) {
                            episodeCursors[seriesId] = null
                            _episodeSections.value = _episodeSections.value + (seriesId to state.copy(
                                status = ContentStatus.ERROR, message = "Episodes changed. Retry to update this season.",
                                hasMore = false, isLoadingMore = false))
                            return@launch
                        }
                        val titles = ((if (more) state.titles else emptyList()) + result.value.items).distinctBy { it.id }
                        episodeCursors[seriesId] = result.value.nextStart
                        _episodes.value = _episodes.value + (seriesId to titles)
                        _episodeSections.value = _episodeSections.value + (seriesId to state.copy(
                            status = ContentStatus.READY, titles = titles, message = null,
                            hasMore = result.value.nextStart != null, isLoadingMore = false))
                    }
                    is OperationResult.Failure -> _episodeSections.value = _episodeSections.value + (seriesId to state.copy(
                        status = ContentStatus.ERROR, message = result.message, isLoadingMore = false))
                }
            } finally { if (episodeJobs[seriesId] === request) episodeJobs.remove(seriesId) }
        }
        episodeJobs[seriesId] = request; request.start()
    }
    fun initializeDownloads() {
        val engine = offlineDownloads ?: return
        if (downloadInitialization?.isActive == true) return
        downloadInitialization = viewModelScope.launch {
            try { engine.initialize() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { postNotice("Downloads could not open. Free some space and try again.") }
        }
    }
    fun setDownloadsVisible(visible: Boolean) {
        downloadsVisible = visible
        offlineDownloads?.setVisible(visible && appActive)
    }
    fun downloadSettings(settings: DownloadSettings) { offlineDownloads?.updateSettings(settings) }
    fun pauseDownload(id: String) { offlineDownloads?.pause(id) }
    fun resumeDownload(id: String) { offlineDownloads?.resume(id) }
    fun retryDownload(id: String) { offlineDownloads?.retry(id) }
    fun removeDownload(id: String) { offlineDownloads?.remove(id) }

    fun requestDownload(title: MediaTitle) {
        val engine = offlineDownloads ?: run { postNotice("Downloads are not available in this preview."); return }
        val scope = (repository.session.value as? SessionState.Connected)?.scope
        if (!canRequestDownload(title, scope, repository.catalog.value.canUseLibrary)) {
            postNotice(if (title.kind == MediaKind.SERIES) "Choose an episode to download." else "Connect to download this title."); return
        }
        if (engine.library.value.items.any { it.title.id == title.id }) { postNotice("Open Downloads to manage this title."); return }
        if (admissionTitleId == title.id && downloadAdmission?.isActive == true) return
        dismissDownloadRequest()
        val operation = ++downloadAdmissionVersion
        admissionTitleId = title.id
        _downloadPreparing.value += title.id
        downloadAdmission = viewModelScope.launch {
            try {
                val result = repository.prepareDownload(title.id)
                if (operation != downloadAdmissionVersion || currentScope() != scope) return@launch
                when (result) {
                    is OperationResult.Success -> if (result.value.sessionScope == scope && result.value.title.kind != MediaKind.SERIES)
                        _downloadRequest.value = result.value
                    else postNotice("This account changed. Reopen the title to download it.")
                    is OperationResult.Failure -> postNotice(result.message)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (operation == downloadAdmissionVersion) postNotice("Could not prepare this download. Try again when connected.") }
            finally { if (operation == downloadAdmissionVersion) {
                _downloadPreparing.value -= title.id; admissionTitleId = null
            } }
        }
    }
    fun dismissDownloadRequest() {
        downloadAdmissionVersion++
        downloadAdmission?.cancel(); downloadAdmission = null
        admissionTitleId?.let { _downloadPreparing.value -= it }; admissionTitleId = null
        _downloadRequest.value = null
    }
    fun confirmDownload() {
        val engine = offlineDownloads ?: return
        val source = _downloadRequest.value ?: return
        _downloadRequest.value = null
        val scope = (repository.session.value as? SessionState.Connected)?.scope
        if (scope != source.sessionScope || !repository.catalog.value.canUseLibrary) {
            postNotice("Connect to this account before downloading."); return
        }
        if (enqueueJobs[source.title.id]?.isActive == true) return
        _downloadPreparing.value += source.title.id
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = engine.enqueue(source)
                if (!request.isActive || currentScope() != scope) return@launch
                when (result) {
                    is OperationResult.Success -> postNotice("Added to Downloads")
                    is OperationResult.Failure -> postNotice(result.message)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (currentScope() == scope) postNotice("Could not start the download. Check storage and try again.") }
            finally { if (enqueueJobs[source.title.id] === request) {
                enqueueJobs.remove(source.title.id); _downloadPreparing.value -= source.title.id
            } }
        }
        enqueueJobs[source.title.id] = request; request.start()
    }

    /** A deliberate season action queues bounded pages, with one transfer at a time in Media3. */
    fun downloadSeason(seriesId: String, season: SeasonOption) {
        val engine = offlineDownloads ?: return
        val scope = (repository.session.value as? SessionState.Connected)?.scope ?: return
        val section = _episodeSections.value[seriesId] ?: return
        if (!repository.catalog.value.canUseLibrary || section.selectedSeason?.id != season.id ||
            section.seasons.none { it.id == season.id }) return
        val key = "$seriesId:${season.id}"
        if (seasonDownloadJobs[key]?.isActive == true) return
        val progress = seasonDownloadProgress[key]?.takeIf { it.scope == scope && it.season == season }
            ?: SeasonDownloadProgress(scope, season).also { seasonDownloadProgress[key] = it }
        if (progress.complete) { postNotice("This season has already been added to Downloads."); return }
        while (seasonDownloadProgress.size > 8) seasonDownloadProgress.keys.firstOrNull {
            it != key && seasonDownloadJobs[it]?.isActive != true
        }?.let(seasonDownloadProgress::remove) ?: break
        _downloadingSeason.value += key
        lateinit var request: Job
        request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var newlyQueued = 0
            try {
                while (request.isActive && currentScope() == scope && !progress.complete) {
                    if (progress.pending.isEmpty()) {
                        if (progress.seen.size >= 100) {
                            progress.complete = true
                            postNotice("This season action covers the first 100 episodes. Download any remaining episodes individually."); break
                        }
                        val page = when (val result = repository.episodePage(seriesId, season, progress.nextStart)) {
                            is OperationResult.Success -> result.value
                            is OperationResult.Failure -> { postNotice(result.message); return@launch }
                        }
                        if (!request.isActive || currentScope() != scope) return@launch
                        when (val valid = validateSeasonDownloadPage(seriesId, season, progress.nextStart, progress.seen, page)) {
                            is OperationResult.Failure -> { seasonDownloadProgress.remove(key); postNotice(valid.message); return@launch }
                            is OperationResult.Success -> {
                                progress.seen += valid.value.map { it.id }
                                progress.pending = valid.value
                                progress.pendingNext = page.nextStart
                            }
                        }
                        if (progress.pending.isEmpty()) { progress.complete = true; break }
                    }
                    while (progress.pending.isNotEmpty()) {
                        ensureActive()
                        if (currentScope() != scope) return@launch
                        val title = progress.pending.first()
                        if (engine.library.value.items.none { it.title.id == title.id }) {
                            val source = when (val admitted = repository.prepareDownload(title.id)) {
                                is OperationResult.Success -> admitted.value
                                is OperationResult.Failure -> { postNotice(admitted.message); return@launch }
                            }
                            if (!request.isActive || currentScope() != scope || source.sessionScope != scope) return@launch
                            when (val queued = engine.enqueue(source)) {
                                is OperationResult.Success -> newlyQueued++
                                is OperationResult.Failure -> { postNotice(queued.message); return@launch }
                            }
                        }
                        progress.pending = progress.pending.drop(1)
                    }
                    val next = progress.pendingNext
                    if (next == null) progress.complete = true else progress.nextStart = next
                }
                if (request.isActive && currentScope() == scope && progress.complete &&
                    (progress.seen.size < 100 || progress.pendingNext == null)) postNotice(when {
                    progress.seen.isEmpty() -> "This season has no episodes to download."
                    newlyQueued == 1 -> "Added 1 episode to Downloads"
                    newlyQueued > 1 -> "Added $newlyQueued episodes to Downloads"
                    else -> "These episodes are already in Downloads."
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (currentScope() == scope) postNotice("Season download paused. Tap Download season to continue.") }
            finally { if (seasonDownloadJobs[key] === request) {
                seasonDownloadJobs.remove(key); _downloadingSeason.value -= key
                // Completed membership is not durable: the user can remove an episode later.
                // A new explicit action rechecks bounded pages and skips only actual library items.
                // Failed work retains its pending episode and page cursor for an exact retry.
                if (progress.complete && seasonDownloadProgress[key] === progress) seasonDownloadProgress.remove(key)
            } }
        }
        seasonDownloadJobs[key] = request; request.start()
    }
    private fun cancelDownloadRequests() {
        dismissDownloadRequest()
        enqueueJobs.values.forEach { it.cancel() }; enqueueJobs.clear()
        seasonDownloadJobs.values.forEach { it.cancel() }; seasonDownloadJobs.clear()
        seasonDownloadProgress.clear(); _downloadingSeason.value = emptySet(); _downloadPreparing.value = emptySet()
    }

    fun playDownload(id: String, fromStart: Boolean = false) {
        val engine = offlineDownloads ?: return
        val scope = currentScope() ?: run { postNotice("Open the account that downloaded this title."); return }
        if (_preparing.value) return
        val item = engine.library.value.items.firstOrNull { it.id == id && it.ready && it.ownerKey == downloadOwnerKey(scope) }
            ?: run { postNotice("Finish downloading this title before watching offline."); return }
        _player.value?.release(); _player.value = null
        activeDownloadId = id; activePlaybackId = item.title.id
        val operation = ++playbackGeneration
        _preparing.value = true; _preparingTitleId.value = item.title.id
        preparation = viewModelScope.launch {
            try {
                when (val result = engine.prepareOfflinePlayback(id, scope)) {
                    is OperationResult.Success -> {
                        if (operation != playbackGeneration || currentScope() != scope) return@launch
                        val controller = Media3MainPlayerController(applicationContext, viewModelScope,
                            offlineDataSourceFactory = engine.cacheOnlyDataSourceFactory()) { observation -> engine.recordObservation(id, observation) }
                        _player.value = controller
                        controller.setAppActive(appActive)
                        val descriptor = if (fromStart) result.value.copy(resumeMs = 0,
                            title = result.value.title.copy(confirmedResumeMs = 0)) else result.value
                        controller.prepare(descriptor, autoPlay = appActive)
                    }
                    is OperationResult.Failure -> if (operation == playbackGeneration) postNotice(result.message)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (operation == playbackGeneration) postNotice("This download could not open. Remove it and download it again.") }
            finally { if (operation == playbackGeneration) { _preparing.value = false; _preparingTitleId.value = null } }
        }
    }
    fun replayActive() {
        val download = activeDownloadId
        if (download != null) playDownload(download, fromStart = true)
        else activePlaybackId?.let { play(it, fromStart = true) }
    }

    fun connectQuick(url: String) { viewModelScope.launch { repository.connectQuickConnect(url) } }
    fun connectPassword(url: String, user: String, password: String) {
        viewModelScope.launch { repository.connectPassword(url, user, password) }
    }
    fun cancelConnection() { repository.cancelConnection() }
    fun setAppActive(active: Boolean) {
        if (appActive == active) return
        appActive = active
        _player.value?.setAppActive(active)
        offlineDownloads?.setVisible(active && downloadsVisible)
        if (!active) setHomeFeedActive(false)
    }
    fun preferences(value: AppPreferences) { viewModelScope.launch { repository.updatePreferences(value) } }
    fun signOut() {
        closePlayer()
        _details.value = emptyMap()
        _episodes.value = emptyMap()
        val owner = currentScope()?.let(::downloadOwnerKey)
        cancelDownloadRequests()
        offlineDownloads?.setOwner(null)
        viewModelScope.launch {
            repository.signOut() // Revoke credentials before touching asynchronous private-cache cleanup.
            if (owner != null && currentScope() == null) withTimeoutOrNull(8_000) {
                runCatching { offlineDownloads?.clearOwner(owner) }
            }
        }
    }
    override fun onCleared() { _player.value?.release() }

    private fun languageTag(label: String): String? = when (label) {
        "English" -> "en"
        "Bengali" -> "bn"
        "Hindi" -> "hi"
        "Spanish" -> "es"
        "System" -> Locale.getDefault().toLanguageTag()
        else -> null
    }
    private companion object {
        const val MAX_DETAIL_ENTRIES = 32
        const val DETAIL_TTL_MS = 10 * 60_000L
        const val HOME_SHELF_TTL_MS = 10 * 60_000L
        const val HOME_SHELF_PAGE_SIZE = 12
        const val MAX_HOME_SHELF_CARDS = 72
        const val SERIES_START_TTL_MS = 60_000L
    }
}

