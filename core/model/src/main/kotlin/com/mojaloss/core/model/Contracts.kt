package com.mojaloss.core.model

import kotlinx.coroutines.flow.StateFlow

interface MediaRepository {
    val session: StateFlow<SessionState>
    val catalog: StateFlow<CatalogSnapshot>
    val favorites: StateFlow<Map<String, FavoriteState>>
    val preferences: StateFlow<AppPreferences>
    suspend fun connectQuickConnect(serverUrl: String)
    suspend fun connectPassword(serverUrl: String, username: String, password: String)
    fun cancelConnection()
    suspend fun signOut()
    suspend fun refresh()
    /** Startup keeps a fresh saved library; explicit Refresh/Retry always checks it. */
    suspend fun refreshIfStale() = refresh()
    /** Reopen the saved account/cache; the selected screen owns the first network page. */
    suspend fun initialize() = refreshIfStale()
    /** Select one visible server query; hidden destinations do not fetch. */
    suspend fun showCatalog(query: CatalogQuery) = Unit
    /** Demand exactly one additional page for the current query. */
    suspend fun loadMore() = Unit
    /** An account-scoped, bounded memory peek. No disk or network work. */
    fun cachedCatalog(query: CatalogQuery): CatalogSnapshot? = null
    /** Independent small Home request, only while Home is visible. */
    suspend fun homeResume(kind: MediaKind? = null, force: Boolean = false): OperationResult<List<MediaTitle>> =
        OperationResult.Success(emptyList())
    /** Fetch collection metadata only when its Home section is actually visible. */
    suspend fun platformCollections(force: Boolean = false): OperationResult<List<PlatformCollection>> =
        OperationResult.Success(emptyList())
    /** One visible, server-category-backed Home shelf; this never selects the main catalog. */
    suspend fun homeShelf(kind: HomeShelfKind, sourceId: String? = null, startIndex: Int = 0,
        force: Boolean = false): OperationResult<HomeShelfPage> =
        OperationResult.Success(HomeShelfPage(emptyList()))
    /** Resolve the actual resumable episode, otherwise the first regular-season episode. */
    suspend fun seriesStart(seriesId: String, force: Boolean = false): OperationResult<MediaTitle?> =
        OperationResult.Success(null)
    /** Account-scoped memory peek of service-read-back progress; null means not confirmed here. */
    fun confirmedProgress(id: String): Long? = null
    /** Fetch lightweight genre names only when the filter sheet is opened/searched. */
    suspend fun genres(kind: MediaKind? = null, search: String = ""): OperationResult<List<String>> =
        OperationResult.Success(emptyList())
    suspend fun seasonPage(seriesId: String, startIndex: Int = 0): OperationResult<SeasonPage> =
        OperationResult.Success(SeasonPage(emptyList()))
    suspend fun episodePage(seriesId: String, season: SeasonOption? = null, startIndex: Int = 0): OperationResult<EpisodePage> =
        when (val result = episodes(seriesId, season?.number)) {
            is OperationResult.Success -> {
                val items = result.value.drop(startIndex).take(50)
                val next = (startIndex + items.size).takeIf { it < result.value.size }
                OperationResult.Success(EpisodePage(items, next, result.value.size))
            }
            is OperationResult.Failure -> result
        }
    suspend fun details(id: String): OperationResult<MediaTitle>
    suspend fun episodes(seriesId: String, season: Int?): OperationResult<List<MediaTitle>>
    suspend fun setFavorite(id: String, desired: Boolean): OperationResult<Boolean>
    suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor>
    suspend fun reportPlayback(observation: PlaybackObservation)
    suspend fun prepareDownload(id: String): OperationResult<DownloadSource> =
        OperationResult.Failure("Downloads are not available here.", denied = true)
    suspend fun downloadAuthorization(ownerKey: String): DownloadAuthorization? = null
    /** Small viewport-triggered bottom row, sorted by server upload date only here. */
    suspend fun homeRecentUploads(force: Boolean = false): OperationResult<List<MediaTitle>> =
        OperationResult.Success(emptyList())
    suspend fun updatePreferences(preferences: AppPreferences)
}

interface MainPlayerController {
    val snapshot: StateFlow<PlaybackSnapshot>
    fun prepare(descriptor: PlaybackDescriptor, autoPlay: Boolean = true)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float) = Unit
    fun selectAudio(id: String?)
    fun selectSubtitle(id: String?)
    fun retry()
    fun release()
}

