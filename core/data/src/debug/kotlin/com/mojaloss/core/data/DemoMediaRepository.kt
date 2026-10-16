package com.mojaloss.core.data

import com.mojaloss.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Debug source set only. Illustrative state preview; never contacts or impersonates a service. */
class DemoMediaRepository : MediaRepository {
    private val demoScope = SessionScope("demo://illustrative", "preview", 1)
    private val mutableSession = MutableStateFlow<SessionState>(SessionState.Connected(Account("preview", "Design preview"), demoScope))
    private val mutableCatalog = MutableStateFlow(CatalogSnapshot(ContentStatus.READY, fixtures, "Illustrative preview — no service connection", 0, true))
    private val mutableFavorites = MutableStateFlow(fixtures.associate { it.id to FavoriteState(it.favorite, it.favorite) })
    private val mutablePreferences = MutableStateFlow(AppPreferences())
    override val session: StateFlow<SessionState> = mutableSession.asStateFlow()
    override val catalog: StateFlow<CatalogSnapshot> = mutableCatalog.asStateFlow()
    override val favorites: StateFlow<Map<String, FavoriteState>> = mutableFavorites.asStateFlow()
    override val preferences: StateFlow<AppPreferences> = mutablePreferences.asStateFlow()
    override suspend fun connectQuickConnect(serverUrl: String) { mutableSession.value = SessionState.Failed("Leave design preview to connect a real account.") }
    override suspend fun connectPassword(serverUrl: String, username: String, password: String) { connectQuickConnect(serverUrl) }
    override fun cancelConnection() { mutableSession.value = SessionState.SignedOut }
    override suspend fun signOut() { mutableSession.value = SessionState.SignedOut; mutableFavorites.value = emptyMap(); mutableCatalog.value = CatalogSnapshot(ContentStatus.READY, isDemo = true, message = "Design preview signed out") }
    override suspend fun refresh() { mutableCatalog.update { it.copy(status = ContentStatus.READY, isDemo = true) } }
    override fun cachedCatalog(query: CatalogQuery): CatalogSnapshot = mutableCatalog.value
    override suspend fun homeResume(kind: MediaKind?, force: Boolean): OperationResult<List<MediaTitle>> =
        OperationResult.Success(fixtures.filter { it.confirmedResumeMs > 0 && (kind == null || it.kind == kind) })
    override suspend fun genres(kind: MediaKind?, search: String): OperationResult<List<String>> =
        OperationResult.Success(fixtures.filter { kind == null || it.kind == kind }.flatMap { it.genre.split(" / ") }
            .distinct().filter { it.contains(search.trim(), ignoreCase = true) }.sorted())
    override suspend fun seasonPage(seriesId: String, startIndex: Int): OperationResult<SeasonPage> {
        val values = demoEpisodes.filter { it.seriesId == seriesId }.mapNotNull { it.seasonNumber }.distinct().sorted()
            .map { SeasonOption("$seriesId-season-$it", it, "Season $it") }
        val page = values.drop(startIndex).take(50)
        return OperationResult.Success(SeasonPage(page, (startIndex + page.size).takeIf { it < values.size }, values.size))
    }
    override suspend fun details(id: String): OperationResult<MediaTitle> =
        (mutableCatalog.value.titles + demoEpisodes).firstOrNull { it.id == id }?.let { OperationResult.Success(it) }
            ?: OperationResult.Failure("This title is not part of the illustrative preview.")
    override suspend fun episodes(seriesId: String, season: Int?): OperationResult<List<MediaTitle>> =
        OperationResult.Success(demoEpisodes.filter { it.seriesId == seriesId && (season == null || it.seasonNumber == season) })
    override suspend fun setFavorite(id: String, desired: Boolean): OperationResult<Boolean> {
        if (mutableCatalog.value.titles.none { it.id == id }) return OperationResult.Failure("Preview title not found.")
        mutableFavorites.update { it + (id to FavoriteState(desired, desired)) }
        mutableCatalog.update { it.copy(titles = it.titles.map { title -> if (title.id == id) title.copy(favorite = desired) else title }, isDemo = true) }
        return OperationResult.Success(desired) // Root must label every view as illustrative.
    }
    override suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor> =
        OperationResult.Failure("Design preview contains no playable streams. Connect your account for playback.")
    override suspend fun reportPlayback(observation: PlaybackObservation) = Unit
    override suspend fun updatePreferences(preferences: AppPreferences) { mutablePreferences.value = preferences }

    companion object {
        private val fixtures = listOf(
            MediaTitle("demo-low-tide", "Low Tide", synopsis = "A quiet coastal story about two lives crossing paths.", year = 2025, genre = "Drama", runtimeMs = 88 * 60_000L, confirmedResumeMs = 46 * 60_000L, favorite = true, posterUrl = "file:///android_asset/low-tide.png", backdropUrl = "file:///android_asset/low-tide.png"),
            MediaTitle("demo-northbound", "Northbound", synopsis = "A journey through the mountains changes an unexpected friendship.", year = 2024, genre = "Adventure", runtimeMs = 102 * 60_000L, posterUrl = "file:///android_asset/northbound.png", backdropUrl = "file:///android_asset/northbound.png"),
            MediaTitle("demo-after-hours", "After Hours", synopsis = "One rainy night, three strangers follow the same mystery.", year = 2025, genre = "Thriller", runtimeMs = 96 * 60_000L, posterUrl = "file:///android_asset/after-hours.png", backdropUrl = "file:///android_asset/after-hours.png"),
            MediaTitle("demo-hollow-pines", "Hollow Pines", MediaKind.SERIES, "A small town keeps a secret beneath the trees.", 2025, "Mystery", favorite = true, posterUrl = "file:///android_asset/hollow-pines.png", backdropUrl = "file:///android_asset/hollow-pines.png")
        ).sortedByDescending { it.year ?: 0 }
        private val demoEpisodes = listOf(45, 46, 44).mapIndexed { index, minutes ->
            MediaTitle("demo-hollow-pines-e${index + 1}", listOf("The arrival", "Behind the trees", "A quiet warning")[index], MediaKind.EPISODE,
                year = 2025, genre = "Mystery", runtimeMs = minutes * 60_000L, seriesId = "demo-hollow-pines", seasonNumber = 1, episodeNumber = index + 1, posterUrl = "file:///android_asset/hollow-pines.png", backdropUrl = "file:///android_asset/hollow-pines.png")
        }
    }
}
