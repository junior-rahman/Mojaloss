package com.mojaloss.core.model

enum class MediaKind { MOVIE, SERIES, EPISODE }
enum class Destination { HOME, BROWSE, SEARCH, MY_LIST }
enum class ContentStatus { LOADING, READY, OFFLINE, ERROR }
enum class PlaybackPhase { IDLE, PREPARING, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }

data class SessionScope(val serverUrl: String, val accountId: String, val generation: Long)
data class Account(val id: String, val displayName: String)
data class CatalogQuery(
    val destination: Destination = Destination.HOME,
    val filter: BrowseFilter = BrowseFilter(),
    val search: String = "",
    /** A verified server collection ID, never a guessed streaming-platform label. */
    val parentId: String? = null
)
sealed interface SessionState {
    data object SignedOut : SessionState
    data object Connecting : SessionState
    data class Waiting(val code: String, val expiresAtEpochMs: Long? = null) : SessionState
    /** Saved account identity while the server is being checked; service actions stay disabled. */
    data class Restoring(val account: Account, val scope: SessionScope) : SessionState
    data class Connected(val account: Account, val scope: SessionScope) : SessionState
    data class Failed(val message: String) : SessionState
}
data class CastMember(
    val name: String,
    val role: String = "",
    val imageUrl: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap()
)
data class Rating(val source: String, val label: String, val value: String)
data class MediaTitle(
    val id: String,
    val title: String,
    val kind: MediaKind = MediaKind.MOVIE,
    val synopsis: String = "",
    val year: Int? = null,
    val genre: String = "",
    val runtimeMs: Long = 0,
    val isPlayable: Boolean = false,
    val isUpcoming: Boolean = false,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap(),
    val trailerVideoId: String? = null,
    val cast: List<CastMember> = emptyList(),
    val ratings: List<Rating> = emptyList(),
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val confirmedResumeMs: Long = 0,
    val favorite: Boolean = false
)
data class CatalogSnapshot(
    val status: ContentStatus = ContentStatus.LOADING,
    val titles: List<MediaTitle> = emptyList(),
    val message: String? = null,
    val updatedAtEpochMs: Long? = null,
    val isDemo: Boolean = false,
    val isCached: Boolean = false,
    val isComplete: Boolean = status == ContentStatus.READY,
    val isRefreshing: Boolean = false,
    val isConnectionVerified: Boolean = true,
    val query: CatalogQuery = CatalogQuery(),
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
    val isLoadingMore: Boolean = false
) {
    /** Listing health is independent from verified account and per-title permissions. */
    val canUseLibrary: Boolean get() = !isDemo && isConnectionVerified
}

data class SeasonOption(val id: String, val number: Int?, val name: String)
data class SeasonPage(
    val items: List<SeasonOption>,
    val nextStart: Int? = null,
    val totalCount: Int? = null
)
data class EpisodePage(
    val items: List<MediaTitle>,
    val nextStart: Int? = null,
    val totalCount: Int? = null
)
data class EpisodeSectionState(
    val status: ContentStatus = ContentStatus.LOADING,
    val titles: List<MediaTitle> = emptyList(),
    val message: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val seasons: List<SeasonOption> = emptyList(),
    val selectedSeason: SeasonOption? = null,
    val seasonStatus: ContentStatus = ContentStatus.LOADING,
    val seasonMessage: String? = null,
    val hasMoreSeasons: Boolean = false,
    val isLoadingMoreSeasons: Boolean = false
)
data class HomeResumeState(
    val status: ContentStatus = ContentStatus.LOADING,
    val titles: List<MediaTitle> = emptyList(),
    val message: String? = null,
    val isRefreshing: Boolean = false
)
data class PlatformCollection(val id: String, val name: String)
data class PlatformCollectionsState(
    val status: ContentStatus = ContentStatus.LOADING,
    val collections: List<PlatformCollection> = emptyList(),
    val message: String? = null
)
data class GenreOptionsState(
    val status: ContentStatus = ContentStatus.LOADING,
    val genres: List<String> = emptyList(),
    val message: String? = null
)
data class FavoriteState(
    val confirmed: Boolean = false,
    val desired: Boolean = false,
    val pending: Boolean = false,
    val operation: Long = 0,
    val error: String? = null
)
data class TrackOption(
    val id: String,
    val label: String,
    val language: String? = null,
    val externalUri: String? = null,
    val mimeType: String? = null
)
data class PlaybackDescriptor(
    val title: MediaTitle,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val sessionScope: SessionScope,
    val playSessionId: String? = null,
    val mediaSourceId: String? = null,
    val resumeMs: Long = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val offlineCacheKey: String? = null
)
data class PlaybackSnapshot(
    val mediaId: String? = null,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val requestedSeekMs: Long? = null,
    val lastAdvancingPositionMs: Long = 0,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val error: String? = null,
    val playbackSpeed: Float = 1f
)
data class PlaybackObservation(
    val scope: SessionScope,
    val mediaId: String,
    val positionMs: Long,
    val advancing: Boolean,
    val paused: Boolean,
    val ended: Boolean = false,
    val playSessionId: String? = null,
    val mediaSourceId: String? = null,
    val stopped: Boolean = false,
    // False only for a terminal server-session cleanup before any movement was observed.
    // Such an event must not write progress or mark an item watched.
    val observed: Boolean = true
)
data class AppPreferences(
    val reducedMotion: Boolean = false,
    val haptics: Boolean = true,
    val audioLanguage: String = "System",
    val subtitleLanguage: String = "Off",
    val autoplayNext: Boolean = false
)
data class BrowseFilter(
    val kind: MediaKind? = null,
    val genre: String? = null,
    val year: Int? = null,
    val sort: String = "Release newest"
)
data class NavigationOrigin(
    val destination: Destination,
    val query: String = "",
    val filter: BrowseFilter = BrowseFilter(),
    val scrollIndex: Int = 0,
    val selectedSeason: Int? = null
)
enum class OperationRecovery { RETRY, RESET_SOURCE, RESTART_PAGING }

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>
    data class Failure(val message: String, val denied: Boolean = false,
        val recovery: OperationRecovery = OperationRecovery.RETRY) : OperationResult<Nothing>
}

