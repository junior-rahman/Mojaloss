package com.mojaloss.core.model

/** Home rows follow explicit server categories, not inferred original/audio language. */
enum class HomeShelfKind(val displayName: String, val mediaKind: MediaKind?) {
    TRENDING("Trending", null),
    HINDI_MOVIES("Hindi movies", MediaKind.MOVIE),
    ENGLISH_MOVIES("English movies", MediaKind.MOVIE),
    HINDI_SHOWS("Hindi shows", MediaKind.SERIES),
    ENGLISH_SHOWS("English shows", MediaKind.SERIES),
    INTERNATIONAL_MOVIES("International movies", MediaKind.MOVIE),
    INTERNATIONAL_SHOWS("International series", MediaKind.SERIES),
}

data class HomeShelfPage(
    val items: List<MediaTitle>,
    val sources: List<PlatformCollection> = emptyList(),
    val selectedSource: PlatformCollection? = null,
    val nextStart: Int? = null,
    val totalCount: Int? = null,
)

data class HomeShelfState(
    val status: ContentStatus = ContentStatus.LOADING,
    val titles: List<MediaTitle> = emptyList(),
    val message: String? = null,
    val sources: List<PlatformCollection> = emptyList(),
    val selectedSource: PlatformCollection? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val previewLimitReached: Boolean = false,
)

/** A separately verified resume/first-episode target for the series artwork action. */
data class SeriesStartState(
    val status: ContentStatus = ContentStatus.LOADING,
    val episode: MediaTitle? = null,
    val message: String? = null,
)
