package com.mojaloss.core.data

import android.content.Context
import android.os.Build
import android.media.MediaCodecList
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.TimeoutException as SdkTimeoutException
import org.jellyfin.sdk.api.client.extensions.*
import org.jellyfin.sdk.api.operations.*
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.*
import java.net.URI
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Native SDK adapter. Construction never starts authentication or contacts a service. */
class JellyfinMediaRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val onConnectionDiagnostic: (stage: String, failure: Throwable?) -> Unit = { _, _ -> },
    private val apiClientFactory: ((baseUrl: String, accessToken: String?) -> ApiClient)? = null,
    private val catalogLoadOptions: CatalogLoadOptions = CatalogLoadOptions(),
    private val downloadCodecProbe: (container: String, streams: List<MediaStream>) -> Boolean = ::deviceSupportsDownload,
) : MediaRepository {
    private val applicationContext = context.applicationContext
    private val storage = PrivateStorage(applicationContext)
    private val catalogStorage = CatalogCacheStorage(applicationContext)
    private val generations = AtomicLong()
    private val sessionLock = Any()
    private val favoriteTransactions = FavoriteTransactions()
    private val mutableSession = MutableStateFlow<SessionState>(SessionState.SignedOut)
    private val mutableCatalog = MutableStateFlow(CatalogSnapshot(ContentStatus.READY))
    private val mutablePreferences = MutableStateFlow(AppPreferences())
    override val session = mutableSession.asStateFlow()
    override val catalog = mutableCatalog.asStateFlow()
    override val favorites = favoriteTransactions.states
    override val preferences = mutablePreferences.asStateFlow()
    @Volatile private var active: ActiveSession? = null
    @Volatile private var connectionJob: Job? = null
    private val restorationMutex = Mutex()
    private val preferencesMutex = Mutex()
    private val progressMutex = Mutex()
    private val records = ConcurrentHashMap<String, PlaybackRecord>()
    private val inFlight = ConcurrentHashMap<Job, Long>()
    private var refreshSequence = AtomicLong()
    @Volatile private var catalogJob: Job? = null
    // Only an accepted page in this exact verified session may supply a retry cursor.
    private var catalogCheckpoint: CatalogCheckpoint? = null
    private var activeQuery = CatalogQuery()
    // Immediate query peeks prevent a temporary empty frame from clamping saved scroll anchors.
    // This cache is scoped to this repository/session, bounded by both queries and total cards.
    private val rememberedCatalogs = linkedMapOf<CatalogQuery, CatalogSnapshot>()
    private val rememberedImageTags = linkedMapOf<String, Pair<String?, String?>>()
    private data class QueryMemoryIndex(
        val scope: SessionScope? = null,
        val catalogs: Map<CatalogQuery, CatalogSnapshot> = emptyMap(),
        val imageTags: Map<String, Pair<String?, String?>> = emptyMap(),
    )
    @Volatile private var queryMemoryIndex = QueryMemoryIndex()
    private val resumeMutex = Mutex()
    private val recentUploadsMutex = Mutex()
    @Volatile private var recentUploadsCache: TimedTitles? = null
    private val seriesStartMutex = Mutex()
    private val seriesStartCache = linkedMapOf<String, TimedSeriesStart>()
    private data class TimedSeriesStart(val scope: SessionScope, val at: Long, val title: MediaTitle?)
    private data class ProgressIndex(val scope: SessionScope? = null, val values: Map<String, Long> = emptyMap())
    @Volatile private var progressIndex = ProgressIndex()
    private val genreMutex = Mutex()
    private val collectionMutex = Mutex()
    private val homeShelfPermits = Semaphore(2)
    private val shelfMetadataMutex = Mutex()
    private val shelfQueryMutexes = ConcurrentHashMap<String, Mutex>()
    private val shelfMetadataCache = ConcurrentHashMap<String, TimedCollections>()
    @Volatile private var libraryViewsCache: TimedCollections? = null
    private val resumeCache = ConcurrentHashMap<String, TimedTitles>()
    private val genreCache = linkedMapOf<String, TimedGenres>()
    @Volatile private var collectionCache: TimedCollections? = null

    private data class TimedTitles(val scope: SessionScope, val at: Long, val titles: List<MediaTitle>)
    private data class TimedGenres(val scope: SessionScope, val at: Long, val values: List<String>)
    private data class TimedCollections(val scope: SessionScope, val at: Long, val values: List<PlatformCollection>)

    private data class ActiveSession(val api: ApiClient, val account: Account, val scope: SessionScope)
    private data class CatalogCheckpoint(
        val connected: ActiveSession,
        val operation: Long,
        val query: CatalogQuery,
        val values: List<MediaTitle>,
        val nextStart: Int,
        val total: Int,
        val recordedAtEpochMs: Long,
        val retryable: Boolean = false,
    )
    private data class PlaybackRecord(
        val descriptor: PlaybackDescriptor,
        val method: PlayMethod,
        val progress: PlaybackReportState = PlaybackReportState(),
        var started: Boolean = false,
    )

    init { scope.launch(Dispatchers.IO) { preferencesMutex.withLock { mutablePreferences.value = storage.preferences() } } }

    override suspend fun connectQuickConnect(serverUrl: String) = connect(serverUrl) { api, attempt, stage ->
        stage("quick_connect_enabled")
        if (!api.quickConnectApi.getQuickConnectEnabled().content)
            throw UserVisibleFailure("Quick Connect is unavailable on this server. Use issued account credentials.")
        stage("quick_connect_request")
        val requested = api.quickConnectApi.initiateQuickConnect().content
        // The provider returns no expiry field; impose a five-minute client waiting limit.
        publishAttempt(attempt) { mutableSession.value = SessionState.Waiting(requested.code, System.currentTimeMillis() + 300_000) }
        withTimeout(300_000) {
            var state = requested
            stage("quick_connect_wait")
            while (!state.authenticated) {
                delay(5_000)
                ensureAttempt(attempt)
                state = api.quickConnectApi.getQuickConnectState(requested.secret).content
            }
            stage("quick_connect_authenticate")
            api.userApi.authenticateWithQuickConnect(QuickConnectDto(requested.secret)).content
        }
    }

    override suspend fun connectPassword(serverUrl: String, username: String, password: String) = connect(serverUrl) { api, _, stage ->
        stage("password_authenticate")
        require(username.isNotBlank()) { "Enter the username issued by your provider." }
        api.userApi.authenticateUserByName(AuthenticateUserByName(username.trim(), password)).content
    }

    private suspend fun connect(
        serverUrl: String,
        authenticate: suspend (ApiClient, Long, (String) -> Unit) -> AuthenticationResult,
    ) = coroutineScope {
        val attempt = synchronized(sessionLock) {
            connectionJob?.cancel()
            inFlight.keys.forEach { it.cancel() }
            active = null
            clearAuxiliaryCaches()
            catalogCheckpoint = null
            activeQuery = CatalogQuery()
            favoriteTransactions.clear()
            records.clear()
            mutableCatalog.value = CatalogSnapshot(ContentStatus.READY)
            mutableSession.value = SessionState.Connecting
            generations.incrementAndGet()
        }
        val worker = launch(Dispatchers.IO) {
            var currentStage = "idle"
            fun stage(value: String) { currentStage = value; connectionDiagnostic(attempt, value) }
            try {
                stage("idle")
                synchronized(sessionLock) { ensureAttempt(attempt); storage.clearSession(); catalogStorage.clear() }
                stage("validate_server")
                val base = ServerUrls.normalize(serverUrl)
                stage("initialize_client")
                val api = api(base)
                stage("server_info")
                checkServerVersion(api)
                ensureAttempt(attempt)
                val result = authenticate(api, attempt, ::stage)
                ensureAttempt(attempt)
                val token = result.accessToken?.takeIf { it.isNotBlank() }
                    ?: throw UserVisibleFailure("The service did not issue an access token.")
                api.update(accessToken = token)
                stage("verify_account")
                val verified = api.userApi.getCurrentUser().content
                ensureAttempt(attempt)
                if (result.user?.id != null && result.user?.id != verified.id)
                    throw UserVisibleFailure("The account could not be verified. Connect again.")
                val account = Account(verified.id.toString(), verified.name.orEmpty())
                val connected = ActiveSession(api, account, SessionScope(base, account.id, attempt))
                stage("save_session")
                synchronized(sessionLock) {
                    ensureAttempt(attempt)
                    storage.saveSession(StoredSession(base, account.id, token))
                    active = connected
                    mutableCatalog.value = CatalogSnapshot(ContentStatus.LOADING)
                    mutableSession.value = SessionState.Connected(account, connected.scope)
                }
                stage("idle")
            } catch (e: TimeoutCancellationException) {
                connectionDiagnostic(attempt, currentStage, e)
                publishAttempt(attempt) { mutableSession.value = SessionState.Failed("The connection code expired. Request a new code.") }
            } catch (e: CancellationException) {
                publishAttempt(attempt) {
                    if (mutableSession.value is SessionState.Connecting || mutableSession.value is SessionState.Waiting) {
                        mutableSession.value = SessionState.SignedOut
                        connectionDiagnostic(attempt, "connection_cancelled")
                    }
                }
                throw e
            }
            catch (e: LinkageError) {
                connectionDiagnostic(attempt, currentStage, e)
                publishAttempt(attempt) {
                    active = null
                    clearAuxiliaryCaches()
                    catalogCheckpoint = null
                    storage.clearSession()
                    catalogStorage.clear()
                    records.clear()
                    favoriteTransactions.clear()
                    mutableCatalog.value = CatalogSnapshot(ContentStatus.READY)
                    mutableSession.value = SessionState.Failed(CONNECTION_COMPONENT_FAILURE)
                }
            }
            catch (e: Exception) {
                connectionDiagnostic(attempt, currentStage, e)
                publishAttempt(attempt) { mutableSession.value = SessionState.Failed(message(e, "Could not connect. Check the server and try again.")) }
            }
        }
        connectionJob = worker
        worker.join()
        if (connectionJob === worker) connectionJob = null
    }

    override fun cancelConnection() {
        if (session.value is SessionState.Waiting || session.value is SessionState.Connecting) {
            val canceledGeneration = synchronized(sessionLock) {
                connectionJob?.cancel()
                connectionJob = null
                active = null
                clearAuxiliaryCaches()
                catalogCheckpoint = null
                mutableSession.value = SessionState.SignedOut
                connectionDiagnostic(generations.get(), "connection_cancelled")
                generations.incrementAndGet()
            }
            scope.launch(Dispatchers.IO) { synchronized(sessionLock) { if (generations.get() == canceledGeneration && active == null) { storage.clearSession(); catalogStorage.clear() } } }
        }
    }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            generations.incrementAndGet()
            connectionJob?.cancel()
            inFlight.keys.forEach { it.cancel() }
            connectionJob = null
            active = null
            clearAuxiliaryCaches()
            catalogCheckpoint = null
            records.clear()
            favoriteTransactions.clear()
            mutableCatalog.value = CatalogSnapshot(ContentStatus.READY)
            mutableSession.value = SessionState.SignedOut
            storage.clearSession()
            catalogStorage.clear()
        }
        // No service history deletion and no silent replacement of a provider session.
    }

    override suspend fun initialize() {
        sessionIo { if (active == null) restore() }
    }

    override fun cachedCatalog(query: CatalogQuery): CatalogSnapshot? {
        val normalized = runCatching { normalizeQuery(query) }.getOrNull() ?: return null
        val owner = displayScope() ?: return null
        if (owner.generation != generations.get()) return null
        val connected = active?.takeIf { it.scope == owner }
        // Immutable volatile indexes keep UI peeks independent from disk work under sessionLock.
        val index = queryMemoryIndex.takeIf { it.scope == owner }
        val current = mutableCatalog.value
        val candidate = if (current.query == normalized && (current.titles.isNotEmpty() || current.status == ContentStatus.READY)) current
            else index?.catalogs?.get(normalized) ?: return null
        if (candidate.isConnectionVerified == (connected != null))
            return candidate.takeIf { owner == displayScope() && owner.generation == generations.get() && active === connected }
        val rebound = candidate.copy(isConnectionVerified = connected != null, titles = candidate.titles.map { item ->
            if (connected == null) item.copy(posterUrl = null, backdropUrl = null, artworkHeaders = emptyMap())
            else {
                val tags = index?.imageTags?.get(item.id)
                item.copy(
                    posterUrl = item.posterUrl ?: tags?.first?.let { connected.api.imageApi.getItemImageUrl(UUID.fromString(item.id), ImageType.PRIMARY, maxWidth = 720, tag = it) },
                    backdropUrl = item.backdropUrl ?: tags?.second?.let { connected.api.imageApi.getItemImageUrl(UUID.fromString(item.id), ImageType.BACKDROP, maxWidth = 1440, tag = it, imageIndex = 0) },
                    artworkHeaders = mapOf("X-Emby-Token" to requireNotNull(connected.api.accessToken)),
                )
            }
        })
        return rebound.takeIf { owner == displayScope() && owner.generation == generations.get() && active === connected }
    }

    private fun displayScope(): SessionScope? = when (val value = session.value) {
        is SessionState.Connected -> value.scope
        is SessionState.Restoring -> value.scope
        else -> null
    }

    private fun publishQueryMemoryIndex(owner: SessionScope? = active?.scope ?: displayScope()) {
        queryMemoryIndex = QueryMemoryIndex(owner, rememberedCatalogs.toMap(), rememberedImageTags.toMap())
    }

    /** Called with sessionLock held; never retains transient spinners on an inactive query. */
    private fun rememberCatalog(snapshot: CatalogSnapshot) {
        if (snapshot.titles.isEmpty() && snapshot.status != ContentStatus.READY) return
        if (snapshot.titles.size > MAX_MEMORY_CARDS) return
        rememberedCatalogs.remove(snapshot.query)
        rememberedCatalogs[snapshot.query] = snapshot.copy(isRefreshing = false, isLoadingMore = false)
        while (rememberedCatalogs.size > MAX_MEMORY_QUERIES || rememberedCatalogs.values.sumOf { it.titles.size } > MAX_MEMORY_CARDS) {
            rememberedCatalogs.remove(rememberedCatalogs.keys.first())
        }
        publishQueryMemoryIndex()
    }

    private fun clearAuxiliaryCaches() {
        rememberedCatalogs.clear()
        rememberedImageTags.clear()
        resumeCache.clear()
        recentUploadsCache = null
        genreCache.clear()
        collectionCache = null
        shelfMetadataCache.clear()
        libraryViewsCache = null
        shelfQueryMutexes.clear()
        seriesStartCache.clear()
        progressIndex = ProgressIndex()
        queryMemoryIndex = QueryMemoryIndex()
    }

    override suspend fun homeResume(kind: MediaKind?, force: Boolean): OperationResult<List<MediaTitle>> = sessionIo {
        val pending = session.value as? SessionState.Restoring
        if (active == null && pending != null) {
            val key = "home-resume-v1|${kind?.name.orEmpty()}"
            val disk = catalogStorage.load(pending.scope.serverUrl, pending.account.id, key)
            return@sessionIo if (disk != null && generations.get() == pending.scope.generation && session.value == pending)
                OperationResult.Success(cachedSnapshot(disk, null, CatalogQuery(), verified = false).titles.take(RESUME_LIMIT).map { it.copy(isPlayable = false) })
            else OperationResult.Failure("Reconnect your account to load Continue watching.")
        }
        request("Could not update Continue watching.") { connected -> resumeMutex.withLock {
            val key = "home-resume-v1|${kind?.name.orEmpty()}"
            val now = System.currentTimeMillis()
            val remembered = resumeCache[key]?.takeIf { it.scope == connected.scope }
            if (!force && remembered != null && now - remembered.at in 0 until RESUME_FRESHNESS_MILLIS)
                return@withLock remembered.titles
            val disk = if (force) null else catalogStorage.load(connected.scope.serverUrl, connected.account.id, key)
            if (disk != null && now - disk.savedAtEpochMs in 0 until RESUME_FRESHNESS_MILLIS) {
                val values = cachedSnapshot(disk, connected, CatalogQuery(), verified = true).titles.take(RESUME_LIMIT)
                publishCurrent(connected) { resumeCache[key] = TimedTitles(connected.scope, disk.savedAtEpochMs, values) }
                return@withLock values
            }
            val result = boundedRead(connected) {
                connected.api.itemsApi.getResumeItems(
                    userId = UUID.fromString(connected.account.id), startIndex = 0, limit = RESUME_LIMIT,
                    includeItemTypes = when (kind) {
                        MediaKind.MOVIE -> listOf(BaseItemKind.MOVIE)
                        MediaKind.SERIES, MediaKind.EPISODE -> listOf(BaseItemKind.EPISODE)
                        null -> listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE)
                    },
                    fields = catalogFields, enableUserData = true, enableImages = true, imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP), enableTotalRecordCount = false,
                ).content
            }
            require(result.items.size <= RESUME_LIMIT) { "The service exceeded the Continue watching limit." }
            // This endpoint has no sort parameter in the pinned SDK. Reorder only the small
            // authoritative resume response; never infer progress from release order.
            val values = result.items.sortedWith(compareByDescending<BaseItemDto> { it.premiereDate }.thenBy { it.sortName ?: it.name })
                .map { title(it, connected) }.distinctBy { it.id }
                .filter { it.confirmedResumeMs > 0 && it.kind != MediaKind.SERIES }
            val recordedAt = System.currentTimeMillis()
            publishCurrent(connected) {
                resumeCache[key] = TimedTitles(connected.scope, recordedAt, values)
                catalogStorage.save(CachedCatalog(
                    connected.scope.serverUrl, connected.account.id, connected.account.displayName.ifBlank { "Account" },
                    recordedAt, complete = true, titles = cacheEntries(values), queryKey = key,
                    totalCount = values.size, nextStart = values.size,
                ))
            }
            values
        } }
    }

    /** Deliberately separate from release-first discovery; only its visible bottom row requests it. */
    override suspend fun homeRecentUploads(force: Boolean): OperationResult<List<MediaTitle>> = sessionIo {
        val pending = session.value as? SessionState.Restoring
        if (active == null && pending != null) {
            val disk = catalogStorage.load(pending.scope.serverUrl, pending.account.id, "home-recent-uploads-v1")
            return@sessionIo if (disk != null && disk.titles.size <= RECENT_UPLOADS_LIMIT &&
                generations.get() == pending.scope.generation && session.value == pending)
                OperationResult.Success(cachedSnapshot(disk, null, CatalogQuery(), verified = false).titles.map { it.copy(isPlayable = false) })
            else OperationResult.Failure("Reconnect your account to update Recently added.")
        }
        request("Could not update Recently added. Try again.", diagnosticStage = "home_recent_uploads") { connected -> recentUploadsMutex.withLock {
            val now = System.currentTimeMillis()
            val memory = recentUploadsCache?.takeIf { it.scope == connected.scope }
            if (!force && memory != null && now - memory.at in 0 until CATALOG_FRESHNESS_MILLIS)
                return@withLock memory.titles
            val key = "home-recent-uploads-v1"
            val disk = if (force) null else catalogStorage.load(connected.scope.serverUrl, connected.account.id, key)
            if (disk != null && disk.titles.size <= RECENT_UPLOADS_LIMIT && now - disk.savedAtEpochMs in 0 until CATALOG_FRESHNESS_MILLIS) {
                val values = cachedSnapshot(disk, connected, CatalogQuery(), verified = true).titles
                publishCurrent(connected) { recentUploadsCache = TimedTitles(connected.scope, disk.savedAtEpochMs, values) }
                return@withLock values
            }
            val result = boundedRead(connected) {
                connected.api.itemsApi.getItems(userId = UUID.fromString(connected.account.id), recursive = true,
                    startIndex = 0, limit = RECENT_UPLOADS_LIMIT, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    // Otherwise a server with collection grouping enabled can substitute BoxSets
                    // after its type filter, breaking an intentionally Movie/Series-only row.
                    collapseBoxSetItems = false,
                    fields = catalogFields, enableUserData = true, enableImages = true, imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP), enableTotalRecordCount = false,
                    sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)).content
            }
            require(result.startIndex == 0 && result.items.size <= RECENT_UPLOADS_LIMIT &&
                result.items.all { it.type in setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES) })
            // This is a fixed preview, not a paged cursor. Repeated identical rows are harmless;
            // contradictory identities still fail rather than silently choosing wrong metadata.
            require(result.items.groupBy { it.id }.values.all { versions ->
                versions.map { Triple(it.type, it.name, it.productionYear) }.distinct().size == 1
            })
            val values = result.items.distinctBy { it.id }.map { title(it, connected) }
            publishCurrent(connected) {
                recentUploadsCache = TimedTitles(connected.scope, now, values)
                catalogStorage.save(CachedCatalog(connected.scope.serverUrl, connected.account.id,
                    connected.account.displayName.ifBlank { "Account" }, now, true, cacheEntries(values), key,
                    values.size, values.size))
            }
            values
        } }
    }

    override suspend fun platformCollections(force: Boolean): OperationResult<List<PlatformCollection>> =
        request("Could not load collections.") { connected -> collectionMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = collectionCache?.takeIf { it.scope == connected.scope }
            if (!force && cached != null && now - cached.at in 0 until CATALOG_FRESHNESS_MILLIS)
                return@withLock cached.values
            // Server names and IDs only. Studios/provider IDs do not establish platform membership.
            val result = boundedRead(connected) {
                connected.api.itemsApi.getItems(
                    userId = UUID.fromString(connected.account.id), recursive = true, startIndex = 0, limit = METADATA_LIMIT,
                    includeItemTypes = listOf(BaseItemKind.BOX_SET, BaseItemKind.COLLECTION_FOLDER),
                    fields = emptyList(), enableImages = false, enableUserData = false, enableTotalRecordCount = false,
                    sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
                ).content
            }
            require(result.items.size <= METADATA_LIMIT) { "The service exceeded the collection limit." }
            val values = validCollectionTargets(result.items, includeFolders = false)
            publishCurrent(connected) { collectionCache = TimedCollections(connected.scope, System.currentTimeMillis(), values) }
            values
        } }

    /** Visible Home rows are independent queries; they never replace the recent catalog. */
    override suspend fun homeShelf(kind: HomeShelfKind, sourceId: String?, startIndex: Int, force: Boolean): OperationResult<HomeShelfPage> =
        request("Could not load ${kind.displayName.lowercase()}. Try again.", diagnosticStage = "home_language_shelf") { connected -> homeShelfPermits.withPermit {
            require(startIndex >= 0)
            shelfQueryMutexes.getOrPut(kind.name) { Mutex() }.withLock {
                val sources = shelfSources(connected, kind, force && startIndex == 0)
                val requestedSource = sourceId?.let { value -> runCatching { UUID.fromString(value).toString() }.getOrNull()
                    ?: throw UserVisibleFailure("This section changed. Retry to update it.", OperationRecovery.RESET_SOURCE) }
                val selected = if (requestedSource != null) sources.singleOrNull { it.id == requestedSource }
                    ?: throw UserVisibleFailure("This section changed. Retry to update it.", OperationRecovery.RESET_SOURCE)
                else automaticShelfSource(kind, sources)
                if (selected == null) {
                    if (startIndex != 0) throw UserVisibleFailure("This row changed. Refresh it to continue.", OperationRecovery.RESET_SOURCE)
                    return@withLock HomeShelfPage(emptyList(), sources = sources)
                }
                val query = normalizeQuery(CatalogQuery(Destination.BROWSE,
                    BrowseFilter(kind = kind.mediaKind, sort = "Release newest"), parentId = selected.id))
                val now = System.currentTimeMillis()
                val cache = catalogStorage.load(connected.scope.serverUrl, connected.account.id, queryKey(query))
                val fresh = cache?.takeIf { now - it.savedAtEpochMs in 0 until CATALOG_FRESHNESS_MILLIS &&
                    it.totalCount != null && it.nextStart == it.titles.size && it.titles.size <= it.totalCount &&
                    (kind.mediaKind == null || it.titles.all { title -> title.kind == kind.mediaKind }) }
                if (!force && fresh != null && (startIndex < fresh.titles.size || fresh.complete && startIndex == fresh.titles.size)) {
                    val snapshot = cachedSnapshot(fresh, connected, query, verified = true)
                    publishCurrent(connected) { rememberCatalog(snapshot) }
                    val items = snapshot.titles.drop(startIndex).take(HOME_SHELF_PAGE_SIZE)
                    val next = startIndex + items.size
                    return@withLock HomeShelfPage(items, sources, selected, next.takeIf { it < requireNotNull(fresh.totalCount) }, fresh.totalCount)
                }
                // A later page may only extend this exact, still-fresh source and order snapshot.
                if (startIndex > 0 && (fresh == null || fresh.nextStart != startIndex))
                    throw UserVisibleFailure("This category changed. Refresh it before loading more.", OperationRecovery.RESTART_PAGING)
                val result = boundedRead(connected) {
                    connected.api.itemsApi.getItems(
                        userId = UUID.fromString(connected.account.id), parentId = UUID.fromString(selected.id), recursive = true,
                        includeItemTypes = when (kind.mediaKind) {
                            MediaKind.MOVIE -> listOf(BaseItemKind.MOVIE)
                            MediaKind.SERIES -> listOf(BaseItemKind.SERIES)
                            else -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
                        }, startIndex = startIndex, limit = HOME_SHELF_PAGE_SIZE, collapseBoxSetItems = false,
                        fields = catalogFields, enableUserData = true, enableImages = true, imageTypeLimit = 1,
                        enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP), enableTotalRecordCount = true,
                        // Trending membership remains server-owned; all title shelves now open release-first.
                        sortBy = releaseSort,
                        sortOrder = releaseSortOrder,
                    ).content
                }
                val next = sectionNext(result, startIndex, HOME_SHELF_PAGE_SIZE, OperationRecovery.RESTART_PAGING)
                val allowedTypes = when (kind.mediaKind) {
                    MediaKind.MOVIE -> setOf(BaseItemKind.MOVIE)
                    MediaKind.SERIES -> setOf(BaseItemKind.SERIES)
                    else -> setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
                }
                if (result.items.any { it.type !in allowedTypes }) throw UserVisibleFailure("The service returned another category. Try again.", OperationRecovery.RESTART_PAGING)
                val previous = if (startIndex == 0) emptyList() else cachedSnapshot(requireNotNull(fresh), connected, query, true).titles
                if (startIndex > 0 && (fresh?.totalCount != result.totalRecordCount || result.items.any { item -> previous.any { it.id == item.id.toString() } }))
                    throw UserVisibleFailure("This category changed while loading. Refresh it to continue.", OperationRecovery.RESTART_PAGING)
                val items = releaseOrderedItems(result.items).map { title(it, connected) }
                val snapshot = CatalogSnapshot(ContentStatus.READY, titles = previous + items, query = query,
                    updatedAtEpochMs = now, isComplete = next == null, hasMore = next != null, totalCount = result.totalRecordCount)
                publishCurrent(connected) {
                    rememberCatalog(snapshot)
                    catalogStorage.save(CachedCatalog(connected.scope.serverUrl, connected.account.id, connected.account.displayName.ifBlank { "Account" },
                        now, next == null, cacheEntries(snapshot.titles), queryKey(query), result.totalRecordCount, startIndex + items.size))
                }
                HomeShelfPage(items, sources, selected, next, result.totalRecordCount)
            }
        } }

    /** No discovery runs at authentication. A missing language folder gets one library lookup,
     * shared across all language rows, without walking any movie/episode catalog. */
    private suspend fun shelfSources(connected: ActiveSession, kind: HomeShelfKind, force: Boolean): List<PlatformCollection> = shelfMetadataMutex.withLock {
        var categoryFailure: Exception? = null
        val categories = try {
            shelfCategorySources(connected, kind, force)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (kind == HomeShelfKind.TRENDING || !canUseLibraryViewDiscovery(e)) throw e
            categoryFailure = e
            emptyList()
        }
        val matches = categories.filter { matchesShelfSource(kind, it.name) }
        if (matches.isNotEmpty() || kind == HomeShelfKind.TRENDING) return@withLock matches
        val libraries = languageLibraryViews(connected, force).filter { matchesShelfSource(kind, it.name) }
        // A failed search plus no authoritative library match is still a failure, never a
        // fabricated empty language row or a guess from dubbed audio/production country.
        if (libraries.isEmpty() && categoryFailure != null) throw categoryFailure
        libraries
    }

    private suspend fun shelfCategorySources(connected: ActiveSession, kind: HomeShelfKind, force: Boolean): List<PlatformCollection> {
        val term = shelfMetadataTerm(kind)
        val key = "home-shelf-sources-v1|$term"
        val now = System.currentTimeMillis()
        val remembered = shelfMetadataCache[term]?.takeIf { it.scope == connected.scope }
        val values = if (!force && remembered != null && now - remembered.at in 0 until CATALOG_FRESHNESS_MILLIS) remembered.values else {
            val disk = if (force) null else catalogStorage.load(connected.scope.serverUrl, connected.account.id, key)
            if (disk != null && disk.titles.size <= SHELF_METADATA_LIMIT && now - disk.savedAtEpochMs in 0 until CATALOG_FRESHNESS_MILLIS) {
                disk.titles.map { PlatformCollection(it.id, it.title) }.also { stored ->
                    publishCurrent(connected) { shelfMetadataCache[term] = TimedCollections(connected.scope, disk.savedAtEpochMs, stored) }
                }
            } else {
                val result = boundedRead(connected) {
                    connected.api.itemsApi.getItems(userId = UUID.fromString(connected.account.id), recursive = true,
                        startIndex = 0, limit = SHELF_METADATA_LIMIT, searchTerm = term,
                        includeItemTypes = listOf(BaseItemKind.BOX_SET, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.FOLDER),
                        fields = emptyList(), enableImages = false, enableUserData = false, enableTotalRecordCount = false,
                        sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING)).content
                }
                require(result.items.size <= SHELF_METADATA_LIMIT && result.startIndex == 0) { "The service returned an unexpected category page." }
                validCollectionTargets(result.items, includeFolders = true).also { accepted ->
                    publishCurrent(connected) {
                        shelfMetadataCache[term] = TimedCollections(connected.scope, now, accepted)
                        // This separate namespace stores collection identity only. These entries are
                        // never presented as playable media; the shared encrypted store binds account/query.
                        catalogStorage.save(CachedCatalog(connected.scope.serverUrl, connected.account.id, connected.account.displayName.ifBlank { "Account" },
                            now, true, accepted.map { CachedCatalogTitle(it.id, it.name) }, key, accepted.size, accepted.size))
                    }
                }
            }
        }
        return values
    }

    /** /UserViews returns directory metadata, not playable titles. Some visible virtual library
     * views are not returned by a recursive /Items BoxSet/Folder text search. */
    private suspend fun languageLibraryViews(connected: ActiveSession, force: Boolean): List<PlatformCollection> {
        val key = "home-language-library-views-v1"
        val now = System.currentTimeMillis()
        val remembered = libraryViewsCache?.takeIf { it.scope == connected.scope }
        if (!force && remembered != null && now - remembered.at in 0 until CATALOG_FRESHNESS_MILLIS)
            return remembered.values
        val disk = if (force) null else catalogStorage.load(connected.scope.serverUrl, connected.account.id, key)
        if (disk != null && disk.titles.size <= METADATA_LIMIT && now - disk.savedAtEpochMs in 0 until CATALOG_FRESHNESS_MILLIS) {
            val values = disk.titles.map { PlatformCollection(it.id, it.title) }
            publishCurrent(connected) { libraryViewsCache = TimedCollections(connected.scope, disk.savedAtEpochMs, values) }
            return values
        }
        val result = boundedRead(connected) {
            connected.api.userViewsApi.getUserViews(userId = UUID.fromString(connected.account.id),
                includeExternalContent = false, includeHidden = false).content
        }
        // The library-directory API has no paging/limit parameter. Accept only a small complete
        // directory; never follow it into every library, and never truncate a mislabeled result.
        require(result.startIndex == 0 && result.items.size <= METADATA_LIMIT && result.totalRecordCount == result.items.size) {
            "The service returned an unexpected library directory."
        }
        val values = validCollectionTargets(result.items, includeFolders = true, includeLibraryViews = true)
        publishCurrent(connected) {
            libraryViewsCache = TimedCollections(connected.scope, now, values)
            catalogStorage.save(CachedCatalog(connected.scope.serverUrl, connected.account.id,
                connected.account.displayName.ifBlank { "Account" }, now, true,
                values.map { CachedCatalogTitle(it.id, it.name) }, key, values.size, values.size))
        }
        return values
    }

    override fun confirmedProgress(id: String): Long? {
        val connected = active ?: return null
        val index = progressIndex.takeIf { it.scope == connected.scope } ?: return null
        return index.values[id].takeIf { isCurrent(connected) && progressIndex === index }
    }

    /** A series container is never prepared as media. Only a confirmed episode becomes its action. */
    override suspend fun seriesStart(seriesId: String, force: Boolean): OperationResult<MediaTitle?> =
        request("Could not find where to start this series. Try again.") { connected -> seriesStartMutex.withLock {
            val series = UUID.fromString(seriesId)
            val normalizedId = series.toString()
            val now = System.currentTimeMillis()
            val cached = synchronized(sessionLock) { seriesStartCache[normalizedId]?.takeIf { it.scope == connected.scope } }
            if (!force && cached != null && now - cached.at in 0 until RESUME_FRESHNESS_MILLIS) return@withLock cached.title
            val resume = boundedRead(connected) {
                connected.api.itemsApi.getResumeItems(userId = UUID.fromString(connected.account.id), parentId = series,
                    startIndex = 0, limit = 1, includeItemTypes = listOf(BaseItemKind.EPISODE), fields = catalogFields,
                    enableUserData = true, enableImages = true, imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP), enableTotalRecordCount = false).content
            }
            if (resume.startIndex != 0 || resume.items.size > 1 || resume.items.any { it.type != BaseItemKind.EPISODE || it.seriesId != series || (it.userData?.playbackPositionTicks ?: 0) <= 0 })
                throw UserVisibleFailure("The service returned an unexpected resume episode. Try again.")
            val target = resume.items.singleOrNull()?.let { title(it, connected) } ?: run {
                val seasons = boundedRead(connected) {
                    connected.api.itemsApi.getItems(userId = UUID.fromString(connected.account.id), parentId = series, recursive = false,
                        includeItemTypes = listOf(BaseItemKind.SEASON), startIndex = 0, limit = SECTION_PAGE_SIZE,
                        fields = emptyList(), enableImages = false, enableUserData = false, enableTotalRecordCount = true,
                        sortBy = listOf(ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING)).content
                }
                val nextSeason = sectionNext(seasons, 0)
                if (seasons.items.any { it.type != BaseItemKind.SEASON || it.seriesId != null && it.seriesId != series })
                    throw UserVisibleFailure("The service returned unexpected seasons. Try again.")
                val first = seasons.items.filter { (it.indexNumber ?: 0) > 0 }.minByOrNull { it.indexNumber!! }
                if (first == null && nextSeason != null) throw UserVisibleFailure("The first regular season could not be verified. Try again.")
                if (first == null) null else {
                    val episode = boundedRead(connected) {
                        connected.api.itemsApi.getItems(userId = UUID.fromString(connected.account.id), parentId = first.id, recursive = false,
                            includeItemTypes = listOf(BaseItemKind.EPISODE), startIndex = 0, limit = 1, fields = catalogFields,
                            enableUserData = true, enableImages = true, imageTypeLimit = 1,
                            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP), enableTotalRecordCount = true,
                            sortBy = listOf(ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING)).content
                    }
                    sectionNext(episode, 0, 1)
                    if (episode.items.any { it.type != BaseItemKind.EPISODE || it.seriesId != series || it.parentIndexNumber != first.indexNumber || (it.indexNumber ?: 0) <= 0 })
                        throw UserVisibleFailure("The service returned another season's episode. Try again.")
                    episode.items.singleOrNull()?.let { title(it, connected) }
                }
            }
            publishCurrent(connected) {
                seriesStartCache.remove(normalizedId)
                seriesStartCache[normalizedId] = TimedSeriesStart(connected.scope, now, target)
                while (seriesStartCache.size > MAX_SERIES_START_ITEMS) seriesStartCache.remove(seriesStartCache.keys.first())
            }
            target
        } }

    override suspend fun genres(kind: MediaKind?, search: String): OperationResult<List<String>> =
        request("Could not load genres. Try again.") { connected -> genreMutex.withLock {
            val term = search.trim().take(128)
            val key = "${kind?.name.orEmpty()}|$term"
            val now = System.currentTimeMillis()
            val cached = synchronized(sessionLock) { genreCache[key]?.takeIf { it.scope == connected.scope } }
            if (cached != null && now - cached.at in 0 until GENRE_FRESHNESS_MILLIS) return@withLock cached.values
            val result = boundedRead(connected) {
                connected.api.genresApi.getGenres(
                    userId = UUID.fromString(connected.account.id), startIndex = 0, limit = METADATA_LIMIT,
                    searchTerm = term.takeIf { it.isNotEmpty() }, includeItemTypes = when (kind) {
                        MediaKind.MOVIE -> listOf(BaseItemKind.MOVIE)
                        MediaKind.SERIES -> listOf(BaseItemKind.SERIES)
                        MediaKind.EPISODE -> listOf(BaseItemKind.EPISODE)
                        null -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
                    },
                    fields = emptyList(), enableImages = false, enableTotalRecordCount = false,
                    sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
                ).content
            }
            require(result.items.size <= METADATA_LIMIT) { "The service exceeded the genre limit." }
            val values = result.items.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }.distinct().sorted()
            publishCurrent(connected) {
                genreCache.remove(key)
                genreCache[key] = TimedGenres(connected.scope, System.currentTimeMillis(), values)
                while (genreCache.size > MAX_MEMORY_QUERIES) genreCache.remove(genreCache.keys.first())
            }
            values
        } }

    override suspend fun seasonPage(seriesId: String, startIndex: Int): OperationResult<SeasonPage> =
        request("Could not load seasons. Try again.") { connected ->
            require(startIndex >= 0)
            val result = boundedRead(connected) {
                connected.api.itemsApi.getItems(
                    userId = UUID.fromString(connected.account.id), parentId = UUID.fromString(seriesId), recursive = false,
                    includeItemTypes = listOf(BaseItemKind.SEASON), startIndex = startIndex, limit = SECTION_PAGE_SIZE,
                    fields = emptyList(), enableImages = false, enableUserData = false, enableTotalRecordCount = true,
                    sortBy = listOf(ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.ASCENDING),
                ).content
            }
            val next = sectionNext(result, startIndex)
            if (result.items.any { it.type != BaseItemKind.SEASON }) throw UserVisibleFailure("The service returned unexpected seasons. Retry to update them.")
            SeasonPage(result.items.map { SeasonOption(it.id.toString(), it.indexNumber, it.name?.takeIf(String::isNotBlank) ?: "Season ${it.indexNumber ?: ""}".trim()) }, next, result.totalRecordCount)
        }

    override suspend fun episodePage(seriesId: String, season: SeasonOption?, startIndex: Int): OperationResult<EpisodePage> =
        request("Could not load episodes. Try again.") { connected ->
            require(startIndex >= 0)
            val result = boundedRead(connected) {
                connected.api.tvShowsApi.getEpisodes(
                    UUID.fromString(seriesId), userId = UUID.fromString(connected.account.id),
                    season = season?.number, seasonId = season?.id?.takeIf(String::isNotBlank)?.let(UUID::fromString),
                    startIndex = startIndex, limit = EPISODE_PAGE_SIZE, fields = catalogFields,
                    enableUserData = true, enableImages = true, imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY), sortBy = ItemSortBy.INDEX_NUMBER,
                ).content
            }
            val next = sectionNext(result, startIndex, EPISODE_PAGE_SIZE)
            if (result.items.any { it.type != BaseItemKind.EPISODE || it.seriesId?.toString()?.let { id -> id != seriesId } == true })
                throw UserVisibleFailure("The service returned unexpected episodes. Retry to update them.")
            if (season?.number != null && result.items.any { it.parentIndexNumber != null && it.parentIndexNumber != season.number })
                throw UserVisibleFailure("The service returned another season. Retry to update episodes.")
            EpisodePage(result.items.map { title(it, connected) }, next, result.totalRecordCount)
        }

    private fun sectionNext(result: BaseItemDtoQueryResult, requestedStart: Int, limit: Int = SECTION_PAGE_SIZE, recovery: OperationRecovery = OperationRecovery.RETRY): Int? {
        val count = result.items.size
        if (result.startIndex != requestedStart || result.totalRecordCount < 0 || count > limit || result.items.map { it.id }.distinct().size != count)
            throw UserVisibleFailure("The service returned an unexpected page. Retry to update it.", recovery)
        val next = try { Math.addExact(requestedStart, count) } catch (_: ArithmeticException) {
            throw UserVisibleFailure("The service returned an unexpected page. Retry to update it.", recovery)
        }
        if (next > result.totalRecordCount || count == 0 && requestedStart != result.totalRecordCount)
            throw UserVisibleFailure("The service did not return the remaining items. Retry to update them.", recovery)
        return next.takeIf { it < result.totalRecordCount }
    }

    /** One small read with the same bounded deadlines/cancellation discipline as catalog pages. */
    private suspend fun <T> boundedRead(connected: ActiveSession, block: suspend () -> T): T {
        for (attempt in 0..catalogLoadOptions.transientRetries) {
            val worker = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                if (!isCurrent(connected)) throw CancellationException("Account changed")
                block()
            }
            inFlight[worker] = connected.scope.generation
            try {
                worker.start()
                val result = withTimeout(catalogLoadOptions.pageTimeoutMillis) { worker.await() }
                if (!isCurrent(connected)) throw CancellationException("Account changed")
                return result
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (attempt == catalogLoadOptions.transientRetries) throw CatalogPageTimeout(e)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (catalogNetworkFailure(e) == null || attempt == catalogLoadOptions.transientRetries) throw e
            } finally { worker.cancel(); inFlight.remove(worker) }
            delay(catalogLoadOptions.retryBackoffMillis * (attempt + 1))
        }
        error("Bounded read did not settle")
    }

    override suspend fun showCatalog(query: CatalogQuery) = sessionIo {
        val normalized = normalizeQuery(query)
        synchronized(sessionLock) {
            if (activeQuery != normalized) {
                rememberCatalog(mutableCatalog.value)
                activeQuery = normalized
                refreshSequence.incrementAndGet()
                catalogJob?.cancel()
                catalogJob = null
                catalogCheckpoint = null
                val connected = active
                val pending = mutableSession.value as? SessionState.Restoring
                val owner = connected?.scope ?: pending?.scope
                val cache = owner?.let { catalogStorage.load(it.serverUrl, it.accountId, queryKey(normalized)) }
                mutableCatalog.value = if (cache != null)
                    cachedSnapshot(cache, connected, normalized, verified = connected != null)
                else CatalogSnapshot(ContentStatus.LOADING, query = normalized, isConnectionVerified = connected != null)
                if (connected != null && cache != null) restoreCheckpoint(cache, connected, normalized)
            }
        }
        refreshIfStale()
    }

    override suspend fun refreshIfStale() = sessionIo {
        val connected = active ?: restore() ?: return@sessionIo
        if (synchronized(sessionLock) { catalogJob?.isActive == true }) return@sessionIo
        if (hasFreshSnapshot(connected)) return@sessionIo
        fetchCatalogPage(connected, demandMore = false, allowRetryResume = true)
    }

    private fun hasFreshSnapshot(connected: ActiveSession): Boolean {
        val snapshot = mutableCatalog.value
        val age = snapshot.updatedAtEpochMs?.let { System.currentTimeMillis() - it }
        val cursorKnown = snapshot.isComplete || synchronized(sessionLock) { catalogCheckpoint?.connected === connected }
        return snapshot.status == ContentStatus.READY && !snapshot.isRefreshing && snapshot.isConnectionVerified &&
            cursorKnown && age != null && age in 0..CATALOG_FRESHNESS_MILLIS
    }

    override suspend fun refresh() = sessionIo {
        val reconnecting = mutableSession.value is SessionState.Restoring
        val connected = active ?: restore() ?: return@sessionIo
        // Retry of account verification finishes reconnecting without restarting a fresh page.
        if (reconnecting && hasFreshSnapshot(connected)) return@sessionIo
        fetchCatalogPage(connected, demandMore = false, allowRetryResume = true)
    }

    override suspend fun loadMore() = sessionIo {
        val connected = active ?: return@sessionIo
        fetchCatalogPage(connected, demandMore = true, allowRetryResume = false)
    }

    /** Exactly one accepted page per visible demand; never traverses a library in the background. */
    private suspend fun fetchCatalogPage(connected: ActiveSession, demandMore: Boolean, allowRetryResume: Boolean) {
        val job = requireNotNull(currentCoroutineContext()[Job])
        val beginning = synchronized(sessionLock) {
            if (!isCurrent(connected)) return
            if (demandMore && (catalogJob?.isActive == true || !mutableCatalog.value.hasMore)) return
            val query = activeQuery
            if (query.destination == Destination.SEARCH && query.search.isBlank()) {
                catalogCheckpoint = null
                mutableCatalog.value = CatalogSnapshot(ContentStatus.READY, query = query, totalCount = 0)
                return
            }
            val next = refreshSequence.incrementAndGet()
            catalogJob?.takeUnless { it === job }?.cancel()
            catalogJob = job
            val now = System.currentTimeMillis()
            val checkpoint = catalogCheckpoint?.takeIf {
                it.connected === connected && it.query == query && now - it.recordedAtEpochMs in 0..CATALOG_FRESHNESS_MILLIS &&
                    (demandMore || allowRetryResume && it.retryable)
            }
            catalogCheckpoint = checkpoint?.copy(operation = next, retryable = false)
            Triple(next, checkpoint, mutableCatalog.value)
        }
        val (operation, checkpoint, previous) = beginning
        val query = previous.query
        if (!publishRefresh(connected, operation) { mutableCatalog.update { it.copy(
            status = if (it.titles.isEmpty()) ContentStatus.LOADING else ContentStatus.READY,
            message = null, isRefreshing = !demandMore, isLoadingMore = demandMore,
        ) } }) return
        try {
            val values = linkedMapOf<String, MediaTitle>()
            val previousById = previous.titles.associateBy { it.id }
            checkpoint?.values?.forEach { old -> values[old.id] = previousById[old.id] ?: old }
            val start = checkpoint?.nextStart ?: 0
            val favoriteVersion = favoriteTransactions.version
            val page = catalogPage(connected, query, start)
            if (!isCurrent(connected) || operation != refreshSequence.get()) return
            if (page.start != start || page.total < 0)
                throw CatalogPaginationFailure("The server returned an unexpected library page. Retry to update it.")
            if (checkpoint != null && checkpoint.total != page.total)
                throw CatalogPaginationFailure("The server did not return the rest of your library because it changed while loading. Retry to update it.")
            val size = page.titles.size
            val nextStart = Math.addExact(start, size)
            if (nextStart > page.total || size == 0 && (start != page.total || values.size != page.total))
                throw CatalogPaginationFailure("The server did not return the rest of your library. Retry to load it.")
            if (page.titles.map { it.id }.distinct().size != size || page.titles.any { it.id in values })
                throw CatalogPaginationFailure("The server repeated a library page. Retry to update it.")
            page.titles.forEach { values[it.id] = it }
            val complete = nextStart == page.total && values.size == page.total
            publishRefresh(connected, operation) {
                catalogCheckpoint = if (complete) null else CatalogCheckpoint(
                    connected, operation, query, values.values.toList(), nextStart, page.total, System.currentTimeMillis(),
                )
            }
            publishCatalogPage(connected, operation, query, values.values.toList(), page.titles, favoriteVersion, complete, page.total)
        } catch (e: CancellationException) {
            publishRefresh(connected, operation) {
                catalogCheckpoint = null
                mutableCatalog.update { it.copy(
                    status = ContentStatus.ERROR, isRefreshing = false, isLoadingMore = false,
                    message = catalogFailureCopy(it.titles.size, "Library loading was interrupted. Retry to finish loading."),
                ) }
            }
            throw e
        } catch (e: LinkageError) {
            // An incompatible SDK component can fail when the first catalog request starts.
            // Catch that narrow category at the awaited worker boundary without discarding a
            // successfully verified account or converting other fatal VM errors into retries.
            publishRefresh(connected, operation) {
                connectionDiagnostic(connected.scope.generation, "catalog_refresh", e)
                catalogCheckpoint = null
                mutableCatalog.update { it.copy(
                    status = ContentStatus.ERROR, isRefreshing = false, isLoadingMore = false,
                    message = catalogFailureCopy(it.titles.size, CONNECTION_COMPONENT_FAILURE),
                ) }
            }
        } catch (e: Exception) {
            if (!isCurrent(connected) || operation != refreshSequence.get()) return
            publishRefresh(connected, operation) { connectionDiagnostic(connected.scope.generation, "catalog_refresh", e) }
            if (expireIfNeeded(e, connected)) return
            publishRefresh(connected, operation) {
                catalogCheckpoint = if (catalogNetworkFailure(e) != null)
                    catalogCheckpoint?.takeIf { it.operation == operation }?.copy(retryable = true) else null
                mutableCatalog.update { it.copy(
                    status = ContentStatus.ERROR, isRefreshing = false, isLoadingMore = false,
                    message = catalogFailureCopy(it.titles.size, catalogFailureMessage(e)),
                ) }
            }
        } finally {
            synchronized(sessionLock) { if (catalogJob === job) catalogJob = null }
        }
    }

    private data class CatalogPage(val start: Int, val total: Int, val titles: List<MediaTitle>)

    private suspend fun catalogPage(connected: ActiveSession, query: CatalogQuery, start: Int): CatalogPage {
        for (attempt in 0..catalogLoadOptions.transientRetries) {
            // Owned by the app scope so a non-cooperative SDK decode cannot keep the UI deadline waiting.
            // This worker never publishes state; its result is accepted only by the current refresh.
            val worker = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(connected)) throw CancellationException("Account changed")
                val page = connected.api.itemsApi.getItems(
                    userId = UUID.fromString(connected.account.id), recursive = true,
                    parentId = query.parentId?.let(UUID::fromString),
                    includeItemTypes = when (query.filter.kind) {
                        MediaKind.MOVIE -> listOf(BaseItemKind.MOVIE)
                        MediaKind.SERIES -> listOf(BaseItemKind.SERIES)
                        MediaKind.EPISODE -> listOf(BaseItemKind.EPISODE)
                        null -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
                    },
                    searchTerm = query.search.takeIf { it.isNotBlank() },
                    isFavorite = if (query.destination == Destination.MY_LIST) true else null,
                    genres = query.filter.genre?.let { listOf(it) }, years = query.filter.year?.let { listOf(it) },
                    collapseBoxSetItems = false,
                    fields = catalogFields, enableUserData = true, enableImages = true,
                    enableTotalRecordCount = true, imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP),
                    sortBy = when (query.filter.sort) {
                        "Title" -> listOf(ItemSortBy.SORT_NAME)
                        "Recently added" -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)
                        else -> releaseSort
                    },
                    sortOrder = when (query.filter.sort) {
                        "Title" -> listOf(SortOrder.ASCENDING)
                        "Recently added" -> listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
                        else -> releaseSortOrder
                    },
                    startIndex = start, limit = catalogLoadOptions.pageSize,
                ).content
                currentCoroutineContext().ensureActive()
                val ordered = if (query.filter.sort == "Release newest") releaseOrderedItems(page.items) else page.items
                CatalogPage(page.startIndex, page.totalRecordCount, ordered.map {
                    currentCoroutineContext().ensureActive()
                    title(it, connected)
                })
            }
            synchronized(sessionLock) {
                if (!isCurrent(connected)) { worker.cancel(); throw CancellationException("Account changed") }
                inFlight[worker] = connected.scope.generation
                worker.start()
            }
            try {
                return withTimeout(catalogLoadOptions.pageTimeoutMillis) { worker.await() }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (attempt == catalogLoadOptions.transientRetries) throw CatalogPageTimeout(e)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == catalogLoadOptions.transientRetries || catalogNetworkFailure(e) == null) throw e
            } finally {
                worker.cancel()
                inFlight.remove(worker)
            }
            delay(catalogLoadOptions.retryBackoffMillis * (attempt + 1))
        }
        error("Catalog retry loop did not settle")
    }

    private fun publishCatalogPage(
        connected: ActiveSession,
        operation: Long,
        query: CatalogQuery,
        values: List<MediaTitle>,
        observed: List<MediaTitle>,
        favoriteVersion: Long,
        complete: Boolean,
        total: Int,
    ) {
        publishRefresh(connected, operation) {
            // Re-seeding old checkpoint values would undo a later service-confirmed favorite.
            observed.forEach { favoriteTransactions.seed(it.id, it.favorite, favoriteVersion) }
            // Old cards survive while a request is pending, then an accepted first page replaces
            // them. Appending is based only on this exact query's verified checkpoint.
            val reconciled = values.map { value ->
                favoriteTransactions.states.value[value.id]?.let { value.copy(favorite = it.confirmed) } ?: value
            }
            mutableCatalog.value = CatalogSnapshot(
                status = ContentStatus.READY,
                titles = reconciled,
                message = null,
                updatedAtEpochMs = System.currentTimeMillis(),
                isCached = false,
                isComplete = complete,
                isRefreshing = false, query = query, hasMore = !complete, totalCount = total,
            )
            saveCatalog(connected)
            rememberCatalog(mutableCatalog.value)
        }
    }

    private fun publishRefresh(connected: ActiveSession, operation: Long, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (!isCurrent(connected) || operation != refreshSequence.get()) false else { action(); true }
    }

    private suspend fun restore(): ActiveSession? = restorationMutex.withLock {
        active?.let { return@withLock it }
        if (connectionJob?.isActive == true || session.value is SessionState.Connecting || session.value is SessionState.Waiting) return@withLock null
        val attempt = generations.get()
        var currentStage = "idle"
        var savedCatalog: CachedCatalog? = null
        val requestedQuery = synchronized(sessionLock) { activeQuery }
        fun stage(value: String) { currentStage = value; connectionDiagnostic(attempt, value) }
        try {
            stage("idle")
            val saved = storage.loadSession() ?: return@withLock null
            stage("validate_server")
            val base = ServerUrls.normalize(saved.serverUrl)
            stage("initialize_client")
            val api = api(base, saved.token)
            savedCatalog = catalogStorage.load(base, saved.accountId, queryKey(requestedQuery))
            // Encrypted, normalized saved ownership is enough to expose local Downloads even
            // when this account has never cached a catalog. It grants no network action.
            val savedId = runCatching { UUID.fromString(saved.accountId).toString() }.getOrNull()
                ?: throw SavedAccountMismatch()
            if (savedId != saved.accountId || saved.token.isBlank()) throw SavedAccountMismatch()
            val pendingAccount = Account(savedId, savedCatalog?.displayName ?: "Account")
            val pending = ActiveSession(api, pendingAccount, SessionScope(base, savedId, attempt))
            publishAttempt(attempt) {
                if (activeQuery == requestedQuery) mutableCatalog.value = savedCatalog?.let { cached ->
                    cachedSnapshot(cached, pending, requestedQuery, verified = false)
                } ?: CatalogSnapshot(ContentStatus.LOADING, query = requestedQuery,
                    isCached = false, isConnectionVerified = false)
                mutableSession.value = SessionState.Restoring(pendingAccount, pending.scope)
            }
            ensureAttempt(attempt)
            stage("verify_account")
            val user = api.userApi.getCurrentUser().content
            ensureAttempt(attempt)
            if (user.id.toString() != saved.accountId) throw SavedAccountMismatch()
            val account = Account(user.id.toString(), user.name.orEmpty())
            val restored = ActiveSession(api, account, SessionScope(base, account.id, attempt))
            synchronized(sessionLock) {
                ensureAttempt(attempt)
                active = restored
                catalogCheckpoint = null
                val matchingCache = if (activeQuery == requestedQuery) savedCatalog
                    else catalogStorage.load(base, saved.accountId, queryKey(activeQuery))
                mutableCatalog.value = matchingCache?.let { cached ->
                    val restoredSnapshot = cachedSnapshot(cached, restored, activeQuery, verified = true)
                    restoredSnapshot.titles.forEach { favoriteTransactions.seed(it.id, it.favorite) }
                    restoredSnapshot
                } ?: CatalogSnapshot(ContentStatus.LOADING, query = activeQuery)
                matchingCache?.let { restoreCheckpoint(it, restored, activeQuery) }
                mutableSession.value = SessionState.Connected(account, restored.scope)
            }
            stage("idle")
            restored
        } catch (e: CancellationException) {
            publishAttempt(attempt) {
                if (mutableSession.value is SessionState.Restoring) mutableCatalog.update { it.copy(
                    status = ContentStatus.ERROR, isRefreshing = false, isLoadingMore = false,
                    message = "Account verification was interrupted. Retry to reconnect.",
                ) }
            }
            throw e
        } catch (e: LinkageError) {
            connectionDiagnostic(attempt, currentStage, e)
            publishAttempt(attempt) {
                active = null
                mutableSession.value = SessionState.Failed(CONNECTION_COMPONENT_FAILURE)
            }
            null
        } catch (e: Exception) {
            connectionDiagnostic(attempt, currentStage, e)
            publishAttempt(attempt) {
                if (isDenied(e) || e is SavedAccountMismatch) {
                    clearAuxiliaryCaches()
                    catalogCheckpoint = null
                    storage.clearSession()
                    catalogStorage.clear()
                    mutableCatalog.value = CatalogSnapshot(ContentStatus.ERROR, message = "Sign in again to continue.", isConnectionVerified = false)
                    mutableSession.value = SessionState.Failed("Your saved account could not be verified. Connect again.")
                } else if (mutableSession.value is SessionState.Restoring) {
                    mutableCatalog.update { it.copy(
                        status = ContentStatus.ERROR, isRefreshing = false, isConnectionVerified = false,
                        message = if (it.titles.isNotEmpty()) "Showing this account’s saved library. " + catalogFailureMessage(e)
                            else "Reconnect your account to load this page. " + catalogFailureMessage(e),
                    ) }
                } else mutableSession.value = SessionState.Failed(message(e, "Could not reopen your account. Try again."))
            }
            null
        }
    }

    private fun cachedSnapshot(cached: CachedCatalog, connected: ActiveSession?, query: CatalogQuery, verified: Boolean): CatalogSnapshot {
        synchronized(sessionLock) {
            val owner = active?.scope ?: (session.value as? SessionState.Restoring)?.scope ?: connected?.scope
            if (owner?.generation == generations.get() && owner.accountId == cached.accountId && owner.serverUrl == cached.serverUrl) {
                cached.titles.forEach { item ->
                    rememberedImageTags.remove(item.id)
                    rememberedImageTags[item.id] = item.posterTag to item.backdropTag
                }
                while (rememberedImageTags.size > MAX_MEMORY_CARDS) rememberedImageTags.remove(rememberedImageTags.keys.first())
                publishQueryMemoryIndex(owner)
            }
        }
        return CatalogSnapshot(
        status = ContentStatus.READY,
        titles = cached.titles.map { item ->
            val id = UUID.fromString(item.id)
            MediaTitle(
                id = item.id, title = item.title, kind = item.kind, year = item.year, genre = item.genre,
                runtimeMs = item.runtimeMs, isPlayable = item.isPlayable, isUpcoming = item.isUpcoming,
                posterUrl = if (verified) item.posterTag?.let { requireNotNull(connected).api.imageApi.getItemImageUrl(id, ImageType.PRIMARY, maxWidth = 720, tag = it) } else null,
                backdropUrl = if (verified) item.backdropTag?.let { requireNotNull(connected).api.imageApi.getItemImageUrl(id, ImageType.BACKDROP, maxWidth = 1440, tag = it, imageIndex = 0) } else null,
                artworkHeaders = if (verified) mapOf("X-Emby-Token" to requireNotNull(requireNotNull(connected).api.accessToken)) else emptyMap(),
                seriesId = item.seriesId, seasonNumber = item.seasonNumber, episodeNumber = item.episodeNumber,
                confirmedResumeMs = item.confirmedResumeMs, favorite = item.favorite,
            )
        },
        updatedAtEpochMs = cached.savedAtEpochMs,
        isCached = true, isComplete = cached.complete, isConnectionVerified = verified,
        query = query, hasMore = !cached.complete, totalCount = cached.totalCount,
    )
    }

    private fun restoreCheckpoint(cached: CachedCatalog, connected: ActiveSession, query: CatalogQuery) {
        val age = System.currentTimeMillis() - cached.savedAtEpochMs
        val cursor = cached.nextStart ?: return
        val total = cached.totalCount ?: return
        if (!cached.complete && age in 0..CATALOG_FRESHNESS_MILLIS && cursor == cached.titles.size && cursor < total) {
            catalogCheckpoint = CatalogCheckpoint(
                connected, refreshSequence.get(), query, mutableCatalog.value.titles, cursor, total, cached.savedAtEpochMs,
            )
        }
    }

    /** Persist only account-owned metadata and confirmed service state, never credentials or URLs. */
    private fun saveCatalog(connected: ActiveSession) {
        if (!isCurrent(connected)) return
        val snapshot = mutableCatalog.value
        val entries = cacheEntries(snapshot.titles)
        val saved = catalogStorage.save(CachedCatalog(
            serverUrl = connected.scope.serverUrl, accountId = connected.account.id,
            displayName = connected.account.displayName.ifBlank { "Account" },
            savedAtEpochMs = snapshot.updatedAtEpochMs ?: System.currentTimeMillis(),
            complete = snapshot.isComplete, titles = entries, queryKey = queryKey(snapshot.query), totalCount = snapshot.totalCount,
            nextStart = catalogCheckpoint?.takeIf {
                it.connected === connected && it.query == snapshot.query && it.values.size == entries.size
            }?.nextStart ?: snapshot.totalCount.takeIf { snapshot.isComplete },
        ))
        if (saved && snapshot.query == CatalogQuery()) {
            catalogStorage.deleteQuery("home-v1")
            catalogStorage.deleteQuery("home-v2-recent")
            catalogStorage.deleteQuery("home-v3-release")
        }
    }

    private fun cacheEntries(titles: List<MediaTitle>) = titles.map { item ->
            CachedCatalogTitle(
                id = item.id, title = item.title, kind = item.kind, year = item.year, genre = item.genre,
                runtimeMs = item.runtimeMs, isPlayable = item.isPlayable, isUpcoming = item.isUpcoming,
                posterTag = imageTag(item.posterUrl), backdropTag = imageTag(item.backdropUrl),
                seriesId = item.seriesId, seasonNumber = item.seasonNumber, episodeNumber = item.episodeNumber,
                confirmedResumeMs = item.confirmedResumeMs, favorite = item.favorite,
            )
        }

    override suspend fun details(id: String): OperationResult<MediaTitle> = request("Could not load this title.") { connected ->
        val favoriteVersion = favoriteTransactions.version
        val item = connected.api.userLibraryApi.getItem(UUID.fromString(id), UUID.fromString(connected.account.id)).content
        var mapped = title(item, connected)
        publishCurrent(connected) {
            favoriteTransactions.seed(id, mapped.favorite, favoriteVersion)
            mapped = mapped.copy(favorite = favoriteTransactions.states.value[id]?.confirmed ?: mapped.favorite)
            mutableCatalog.update { it.copy(titles = it.titles.map { old -> if (old.id == id) mapped else old }) }
            updateRememberedTitles { if (it.id == id) mapped else it }
            saveCatalog(connected)
        }
        mapped
    }

    /** Compatibility call is also bounded; production UI uses explicit seasonal pages. */
    override suspend fun episodes(seriesId: String, season: Int?): OperationResult<List<MediaTitle>> =
        when (val result = episodePage(seriesId, season?.let { SeasonOption("", it, "Season $it") }, 0)) {
            is OperationResult.Success -> OperationResult.Success(result.value.items)
            is OperationResult.Failure -> result
        }

    private fun updateRememberedTitles(transform: (MediaTitle) -> MediaTitle) {
        rememberedCatalogs.replaceAll { _, snapshot -> snapshot.copy(titles = snapshot.titles.map(transform)) }
        resumeCache.replaceAll { _, snapshot -> snapshot.copy(titles = snapshot.titles.map(transform)) }
        publishQueryMemoryIndex()
    }

    private fun invalidateResumeCache(connected: ActiveSession) {
        resumeCache.clear()
        listOf(null, MediaKind.MOVIE, MediaKind.SERIES, MediaKind.EPISODE).forEach { kind ->
            catalogStorage.deleteQuery("home-resume-v1|${kind?.name.orEmpty()}")
        }
    }

    override suspend fun setFavorite(id: String, desired: Boolean): OperationResult<Boolean> = sessionIo {
        val connected = active ?: return@sessionIo OperationResult.Failure("Connect your account to save titles.")
        val item = try { UUID.fromString(id) } catch (_: Exception) { return@sessionIo OperationResult.Failure("This title is unavailable.") }
        val account = UUID.fromString(connected.account.id)
        val result = favoriteTransactions.set(id, desired, { isCurrent(connected) }, write = {
            try {
                if (it) connected.api.userLibraryApi.markFavoriteItem(item, account)
                else connected.api.userLibraryApi.unmarkFavoriteItem(item, account)
            } catch (e: Exception) { expireIfNeeded(e, connected); throw e }
        }, read = {
            try { connected.api.userLibraryApi.getItem(item, account).content.userData?.isFavorite
                ?: throw UserVisibleFailure("The service did not return saved status.") }
            catch (e: Exception) { expireIfNeeded(e, connected); throw e }
        }, publish = { action -> publishCurrent(connected, action) })
        publishCurrent(connected) {
            val confirmed = favorites.value[id]?.confirmed
            if (confirmed != null) {
                val changed = mutableCatalog.value.titles.firstOrNull { it.id == id }?.favorite != confirmed
                mutableCatalog.update { it.copy(titles = it.titles.map { title -> if (title.id == id) title.copy(favorite = confirmed) else title }) }
                updateRememberedTitles { if (it.id == id) it.copy(favorite = confirmed) else it }
                if (changed) {
                    // A membership change shifts every following raw offset in server favorites.
                    // Home's order/cursor stay valid; preserve its cold-open cache and update
                    // only the favorite read back from the service.
                    val home = catalogStorage.load(connected.scope.serverUrl, connected.account.id, queryKey(CatalogQuery()))
                    catalogStorage.clear()
                    home?.let { catalogStorage.save(it.copy(titles = it.titles.map { title ->
                        if (title.id == id) title.copy(favorite = confirmed) else title
                    })) }
                    if (activeQuery.destination == Destination.MY_LIST) {
                        refreshSequence.incrementAndGet()
                        catalogJob?.cancel()
                        catalogJob = null
                        catalogCheckpoint = null
                        mutableCatalog.update { it.copy(
                            titles = it.titles.filter { title -> title.favorite }, totalCount = null,
                            isComplete = false, hasMore = true, updatedAtEpochMs = null,
                            isLoadingMore = false, isRefreshing = false,
                        ) }
                    }
                }
                saveCatalog(connected)
            }
        }
        result
    }

    /** Fresh admission only. Downloading never opens a playback session or reads a file into RAM. */
    override suspend fun prepareDownload(id: String): OperationResult<DownloadSource> =
        request("Could not prepare this download. Try again.") { connected ->
            val itemId = runCatching { UUID.fromString(id) }.getOrNull()
                ?: throw UserVisibleFailure("This title is unavailable to download.")
            val currentUser = boundedRead(connected) { connected.api.userApi.getCurrentUser().content }
            if (currentUser.id.toString() != connected.account.id) throw SavedAccountMismatch()
            if (currentUser.policy?.isDisabled != false || currentUser.policy?.enableContentDownloading != true || currentUser.policy?.enableMediaPlayback != true)
                throw UserVisibleFailure("Your account does not allow downloads.", denied = true)
            val result = boundedRead(connected) {
                connected.api.itemsApi.getItems(userId = UUID.fromString(connected.account.id), ids = listOf(itemId),
                    startIndex = 0, limit = 1, recursive = false,
                    fields = listOf(ItemFields.CAN_DOWNLOAD, ItemFields.PATH, ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS,
                        ItemFields.PLAY_ACCESS, ItemFields.GENRES),
                    enableUserData = true, enableImages = false, enableTotalRecordCount = false).content
            }
            val item = result.items.singleOrNull()?.takeIf { result.startIndex == 0 && it.id == itemId }
                ?: throw UserVisibleFailure("This title changed. Open it again to download.")
            if (item.type !in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE) || item.canDownload != true ||
                item.playAccess != PlayAccess.FULL || item.isPlaceHolder == true ||
                item.locationType in setOf(LocationType.VIRTUAL, LocationType.OFFLINE) ||
                item.premiereDate?.isAfter(LocalDateTime.now()) == true)
                throw UserVisibleFailure("This title is not available to download.")
            // /Items/{id}/Download serves the item's original file, not an arbitrary alternate
            // source. Bind all three authoritative identities before assuming codec or size.
            val path = item.path?.takeIf { it.isNotBlank() }
                ?: throw UserVisibleFailure("The original file could not be verified for download.")
            val source = item.mediaSources.orEmpty().singleOrNull { candidate ->
                candidate.type == MediaSourceType.DEFAULT && candidate.path == path &&
                    candidate.id?.replace("-", "")?.equals(itemId.toString().replace("-", ""), ignoreCase = true) == true
            } ?: throw UserVisibleFailure("The original file could not be verified for download.")
            val container = source.container?.trim()?.lowercase().orEmpty()
            val mime = downloadContainerMime(container)
                ?: throw UserVisibleFailure("This file needs streaming on this device. Download is unavailable.")
            if (source.protocol != MediaProtocol.FILE || source.isRemote || source.requiresOpening || source.requiresClosing ||
                source.isInfiniteStream || !source.requiredHttpHeaders.isNullOrEmpty() ||
                !downloadCodecProbe(container, source.mediaStreams.orEmpty()))
                throw UserVisibleFailure("This file needs streaming on this device. Download is unavailable.")
            val resource = connected.api.libraryApi.getDownloadUrl(itemId)
            val safe = ServerUrls.sameOriginResource(connected.scope.serverUrl, resource)
            val uri = URI(safe)
            val expectedPath = URI(connected.scope.serverUrl).path + "Items/$itemId/Download"
            if (uri.rawQuery != null || uri.rawFragment != null || uri.path != expectedPath)
                throw UserVisibleFailure("The server returned an unsupported download address.")
            val mapped = title(item, connected).copy(posterUrl = null, backdropUrl = null,
                artworkHeaders = emptyMap(), cast = emptyList())
            DownloadSource(mapped, safe, connected.scope, source.size?.takeIf { it > 0 }, mime, source.id)
        }

    /** The worker obtains credentials just-in-time; no token or credential URL enters its index. */
    override suspend fun downloadAuthorization(ownerKey: String): DownloadAuthorization? = sessionIo {
        synchronized(sessionLock) {
            val owner = displayScope()?.takeIf { it.generation == generations.get() } ?: return@synchronized null
            if (downloadOwnerKey(owner) != ownerKey) return@synchronized null
            val saved = storage.loadSession() ?: return@synchronized null
            val base = runCatching { ServerUrls.normalize(saved.serverUrl) }.getOrNull() ?: return@synchronized null
            if (base != owner.serverUrl || saved.accountId != owner.accountId || saved.token.isBlank() ||
                runCatching { UUID.fromString(saved.accountId).toString() }.getOrNull() != owner.accountId)
                return@synchronized null
            DownloadAuthorization(owner, mapOf("X-Emby-Token" to saved.token))
        }
    }

    override suspend fun preparePlayback(id: String, fromStart: Boolean): OperationResult<PlaybackDescriptor> = request("Could not prepare this video.") { connected ->
        // An already-dispatched old stop must complete before a fresh same-title session is prepared.
        progressMutex.withLock {
            val itemId = UUID.fromString(id)
            val item = connected.api.userLibraryApi.getItem(itemId, UUID.fromString(connected.account.id)).content
            val mapped = title(item, connected)
            if (!mapped.isPlayable) throw UserVisibleFailure("This title is not available to play.")
            if (!isCurrent(connected)) throw CancellationException("Account changed")
            val resume = if (fromStart) 0L else mapped.confirmedResumeMs
            val info = connected.api.mediaInfoApi.getPostedPlaybackInfo(itemId, PlaybackInfoDto(
                userId = UUID.fromString(connected.account.id), deviceProfile = profile,
                startTimeTicks = ticks(resume), enableDirectPlay = true, enableDirectStream = false,
                enableTranscoding = true, autoOpenLiveStream = false, maxAudioChannels = 2
            )).content
            if (!isCurrent(connected)) throw CancellationException("Account changed")
            if (info.errorCode != null) throw UserVisibleFailure("The service could not provide a compatible stream.")
            if (info.playSessionId.isNullOrBlank()) throw UserVisibleFailure("The service did not identify the playback session.")
            val source = info.mediaSources.firstOrNull {
                !it.requiresOpening && (it.supportsDirectPlay || !it.transcodingUrl.isNullOrBlank())
            } ?: throw UserVisibleFailure("No supported movie stream is available.")
            require(!source.id.isNullOrBlank()) { "The service did not identify the media source." }
            val direct = source.supportsDirectPlay
            val url = if (direct) connected.api.videosApi.getVideoStreamUrl(
                itemId, static = true, mediaSourceId = source.id,
                playSessionId = info.playSessionId, deviceId = connected.api.deviceInfo.id
            ) else source.transcodingUrl ?: throw UserVisibleFailure("The service did not return a stream URL.")
            val safeUrl = ServerUrls.sameOriginResource(connected.scope.serverUrl, url)
            val headers = source.requiredHttpHeaders.orEmpty().mapNotNull { (key, value) -> value?.let { key to it } }.toMap() +
                mapOf("X-Emby-Token" to requireNotNull(connected.api.accessToken))
            val streams = source.mediaStreams.orEmpty()
            val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }.mapNotNull { stream ->
                val mime = subtitleMime(stream.codec)
                if (stream.isExternal) {
                    val resource = stream.deliveryUrl?.takeIf { mime != null } ?: return@mapNotNull null
                    val safe = runCatching { ServerUrls.sameOriginResource(connected.scope.serverUrl, resource) }.getOrNull()
                        ?: return@mapNotNull null
                    TrackOption(stream.index.toString(), stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}", stream.language, safe, mime)
                } else TrackOption(stream.index.toString(), stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}", stream.language)
            }
            val descriptor = PlaybackDescriptor(mapped, safeUrl, headers, connected.scope, info.playSessionId, source.id, resume,
                streams.filter { it.type == MediaStreamType.AUDIO }.map { TrackOption(it.index.toString(), it.displayTitle ?: it.language ?: "Audio ${it.index}", it.language) }, subtitles)
            publishCurrent(connected) { records[id] = PlaybackRecord(descriptor, if (direct) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE) }
            descriptor
        }
    }

    override suspend fun reportPlayback(observation: PlaybackObservation) = sessionIo {
        progressMutex.withLock {
            val connected = active ?: return@withLock
            val record = records[observation.mediaId] ?: return@withLock
            fun currentRecord() = isCurrent(connected) && observation.scope == connected.scope &&
                isCurrentPlaybackObservation(observation, record.descriptor, records[observation.mediaId]?.descriptor)
            if (!currentRecord()) return@withLock
            val terminal = observation.stopped || observation.ended
            val cleanup = record.progress.unobservedStopInfo(observation, record.descriptor)
            if (cleanup != null) {
                try {
                    currentCoroutineContext().ensureActive()
                    connected.api.playStateApi.reportPlaybackStopped(cleanup)
                    if (!currentRecord()) return@withLock
                    // No Start/Progress, read-back, or catalog/resume mutation for unobserved cleanup.
                    // Jellyfin 10.11 skips user-data updates for Failed=true; a normal Stop or
                    // omitted position may otherwise mark an unstarted item watched.
                    records.remove(observation.mediaId, record)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { expireIfNeeded(e, connected) /* retain record for cleanup retry */ }
                return@withLock
            }
            if (!observation.observed) return@withLock
            // Scope/IDs above bind this to the main player's prepared descriptor. Final samples
            // carry its latest observed movement even when advancing=false; no seek target enters.
            val position = record.progress.observe(observation) ?: return@withLock
            val now = System.nanoTime()
            if (record.started && !observation.paused && !terminal && now - record.progress.reportedAtNs < 15_000_000_000L) return@withLock
            try {
                val item = UUID.fromString(observation.mediaId)
                if (!record.started) {
                    connected.api.playStateApi.reportPlaybackStart(PlaybackStartInfo(canSeek = true, itemId = item,
                        mediaSourceId = observation.mediaSourceId, isPaused = observation.paused, isMuted = false,
                        positionTicks = ticks(position), playMethod = record.method, playSessionId = observation.playSessionId,
                        repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT))
                    if (!currentRecord()) return@withLock
                    record.started = true
                }
                if (!currentRecord()) return@withLock
                currentCoroutineContext().ensureActive()
                if (terminal) connected.api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = item,
                    mediaSourceId = observation.mediaSourceId, positionTicks = ticks(position), playSessionId = observation.playSessionId, failed = false))
                else connected.api.playStateApi.reportPlaybackProgress(PlaybackProgressInfo(canSeek = true, itemId = item,
                    mediaSourceId = observation.mediaSourceId, isPaused = observation.paused, isMuted = false,
                    positionTicks = ticks(position), playMethod = record.method, playSessionId = observation.playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT))
                if (!currentRecord()) return@withLock
                record.progress.postAcknowledged(now)
                // A successful POST is not sufficient: only server read-back changes confirmed resume.
                val readBack = connected.api.userLibraryApi.getItem(item, UUID.fromString(connected.account.id)).content
                val userData = readBack.userData?.takeIf { readBack.id == item && it.itemId == item && it.playbackPositionTicks >= 0 }
                if (!currentRecord()) return@withLock
                if (userData != null) {
                    val confirmed = millis(userData.playbackPositionTicks)
                    val settled = record.progress.readBack(confirmed, observation.ended && userData.played)
                    publishCurrent(connected) {
                        if (currentRecord()) {
                            mutableCatalog.update { snapshot -> snapshot.copy(titles = snapshot.titles.map {
                                if (it.id == observation.mediaId) it.copy(confirmedResumeMs = confirmed) else it
                            }) }
                            updateRememberedTitles { if (it.id == observation.mediaId) it.copy(confirmedResumeMs = confirmed) else it }
                            val updatedProgress = if (progressIndex.scope == connected.scope) progressIndex.values.toMutableMap() else linkedMapOf()
                            updatedProgress.remove(observation.mediaId)
                            updatedProgress[observation.mediaId] = confirmed
                            while (updatedProgress.size > MAX_PROGRESS_ITEMS) updatedProgress.remove(updatedProgress.keys.first())
                            progressIndex = ProgressIndex(connected.scope, updatedProgress.toMap())
                            seriesStartCache.clear()
                            invalidateResumeCache(connected)
                            saveCatalog(connected)
                        }
                    }
                    if (terminal && settled) records.remove(observation.mediaId, record)
                } else record.progress.acknowledgementFailed()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                record.progress.acknowledgementFailed()
                expireIfNeeded(e, connected)
            }
        }
    }

    override suspend fun updatePreferences(preferences: AppPreferences) = withContext(Dispatchers.IO) {
        preferencesMutex.withLock { storage.savePreferences(preferences); mutablePreferences.value = preferences }
    }

    private suspend fun <T> request(fallback: String, diagnosticStage: String? = null,
        block: suspend (ActiveSession) -> T): OperationResult<T> = sessionIo {
        val connected = active ?: return@sessionIo OperationResult.Failure("Connect your account first.")
        if (!isCurrent(connected)) return@sessionIo OperationResult.Failure("Your account changed. Try again.")
        try {
            val value = block(connected)
            if (isCurrent(connected)) OperationResult.Success(value) else OperationResult.Failure("Your account changed. Try again.")
        } catch (e: CancellationException) { throw e }
        catch (e: LinkageError) {
            connectionDiagnostic(connected.scope.generation, diagnosticStage ?: "content_read", e)
            OperationResult.Failure(CONNECTION_COMPONENT_FAILURE)
        }
        catch (e: Exception) {
            if (diagnosticStage != null) connectionDiagnostic(connected.scope.generation, diagnosticStage, e)
            // Expiration cancels sibling work, while this awaited request must return its explicit
            // failure instead of cancelling its own caller before the account error can be shown.
            currentCoroutineContext()[Job]?.let { inFlight.remove(it) }
            expireIfNeeded(e, connected)
            val copy = if (diagnosticStage in setOf("home_recent_uploads", "home_language_shelf") &&
                (e is IllegalArgumentException || e is InvalidContentException))
                "The server returned section data this app could not read. Retry, or copy the error report."
            else message(e, fallback)
            OperationResult.Failure(copy, isDenied(e) || (e as? UserVisibleFailure)?.denied == true,
                (e as? UserVisibleFailure)?.recovery ?: OperationRecovery.RETRY)
        }
    }

    private suspend fun <T> sessionIo(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        val job = requireNotNull(currentCoroutineContext()[Job])
        inFlight[job] = generations.get()
        try { currentCoroutineContext().ensureActive(); action() } finally { inFlight.remove(job) }
    }

    private fun connectionDiagnostic(attempt: Long, stage: String, failure: Throwable? = null) = synchronized(sessionLock) {
        // Diagnostics are optional and must not prevent ordinary connection failure handling.
        // The adapter never logs messages, credentials, codes, tokens or server responses.
        // Keep both the generation check and callback atomic with respect to new connections.
        if (generations.get() == attempt) {
            try { onConnectionDiagnostic(stage, failure) } catch (_: Exception) { }
        }
    }

    private fun api(base: String, token: String? = null): ApiClient = apiClientFactory?.invoke(base, token) ?: createJellyfin {
        context = applicationContext
        clientInfo = ClientInfo("MojaLoss", "0.1.8")
        deviceInfo = DeviceInfo(storage.deviceId(), "MojaLoss on ${Build.MODEL}")
    }.createApi(baseUrl = base, accessToken = token,
        httpClientOptions = HttpClientOptions(followRedirects = false, requestTimeout = 15.seconds, connectTimeout = 6.seconds, socketTimeout = 15.seconds))

    private suspend fun checkServerVersion(api: ApiClient) {
        val version = api.systemApi.getPublicSystemInfo().content.version ?: return
        val values = version.split('.').take(3).map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        if (values.size < 2 || values[0] < 10 || values[0] == 10 && values[1] < 11)
            throw UserVisibleFailure("This build requires Jellyfin 10.11 or newer. Your working client stays unchanged.")
    }
    private fun isCurrent(value: ActiveSession) = active === value && generations.get() == value.scope.generation
    private fun publishCurrent(value: ActiveSession, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (!isCurrent(value)) false else { action(); true }
    }
    private fun publishAttempt(attempt: Long, action: () -> Unit): Boolean = synchronized(sessionLock) {
        if (generations.get() != attempt) false else { action(); true }
    }
    private fun ensureAttempt(attempt: Long) { if (generations.get() != attempt) throw CancellationException("Connection replaced") }
    private fun expireIfNeeded(error: Exception, value: ActiveSession): Boolean = synchronized(sessionLock) {
        if ((error is InvalidStatusException && error.status == 401 || error is SavedAccountMismatch) && isCurrent(value)) {
            generations.incrementAndGet(); active = null; clearAuxiliaryCaches(); catalogCheckpoint = null; records.clear(); favoriteTransactions.clear()
            inFlight.keys.forEach { it.cancel() }
            storage.clearSession(); catalogStorage.clear(); mutableCatalog.value = CatalogSnapshot(ContentStatus.ERROR, message = "Sign in again to continue.", isConnectionVerified = false)
            mutableSession.value = SessionState.Failed("Your sign-in expired. Connect again.")
            return@synchronized true
        }
        false
    }
    private fun title(item: BaseItemDto, connected: ActiveSession): MediaTitle {
        val kind = when (item.type) { BaseItemKind.SERIES -> MediaKind.SERIES; BaseItemKind.EPISODE -> MediaKind.EPISODE; else -> MediaKind.MOVIE }
        val upcoming = item.premiereDate?.isAfter(LocalDateTime.now()) == true
        return MediaTitle(id = item.id.toString(), title = item.name ?: "Untitled", kind = kind,
            synopsis = item.overview.orEmpty(), year = item.premiereDate?.year ?: item.productionYear, genre = item.genres.orEmpty().joinToString(" / "),
            runtimeMs = millis(item.runTimeTicks ?: 0),
            isPlayable = kind != MediaKind.SERIES && item.playAccess == PlayAccess.FULL && item.isPlaceHolder != true &&
                item.locationType !in setOf(LocationType.VIRTUAL, LocationType.OFFLINE) && !upcoming,
            isUpcoming = upcoming,
            posterUrl = item.imageTags?.get(ImageType.PRIMARY)?.let { connected.api.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY, maxWidth = 720, tag = it) },
            backdropUrl = item.backdropImageTags?.firstOrNull()?.let { connected.api.imageApi.getItemImageUrl(item.id, ImageType.BACKDROP, maxWidth = 1440, tag = it, imageIndex = 0) },
            trailerVideoId = item.remoteTrailers.orEmpty().mapNotNull { youtubeId(it.url) }.firstOrNull(),
            cast = item.people.orEmpty().filter { it.type == PersonKind.ACTOR }.map {
                CastMember(it.name.orEmpty(), it.role.orEmpty(), it.primaryImageTag?.let { tag ->
                    connected.api.imageApi.getItemImageUrl(it.id, ImageType.PRIMARY, maxWidth = 240, tag = tag)
                }, mapOf("X-Emby-Token" to requireNotNull(connected.api.accessToken)))
            },
            // Jellyfin critic/community numbers do not prove Rotten Tomatoes source/scale.
            ratings = emptyList(), seriesId = item.seriesId?.toString(), seasonNumber = item.parentIndexNumber, episodeNumber = item.indexNumber,
            confirmedResumeMs = millis(item.userData?.playbackPositionTicks ?: 0), favorite = item.userData?.isFavorite == true,
            artworkHeaders = mapOf("X-Emby-Token" to requireNotNull(connected.api.accessToken)))
    }

    companion object {
        private fun normalizeQuery(query: CatalogQuery) = query.copy(
            search = query.search.trim().take(512),
            parentId = query.parentId?.trim()?.takeIf { it.isNotEmpty() }?.let { UUID.fromString(it).toString() },
            filter = query.filter.copy(genre = query.filter.genre?.trim()?.takeIf { it.isNotBlank() },
                // Explicit alphabetical/upload views remain choices. Unknown or legacy default
                // labels migrate to actual release dates, never silently to alphabetical order.
                sort = query.filter.sort.takeIf { it in setOf("Title", "Release newest", "Recently added") } ?: "Release newest"),
        )
        private fun queryKey(query: CatalogQuery): String {
            if (query == CatalogQuery()) return "home-v4-release-year"
            val parts = listOf(query.destination.name, query.filter.kind?.name.orEmpty(), query.filter.genre.orEmpty(),
                query.filter.year?.toString().orEmpty(), query.filter.sort, query.search)
            // A changed release order must never reuse the old cursor or cards. Explicit
            // alphabetical/upload views retain their authenticated fingerprints.
            val version = if (query.filter.sort == "Release newest") "catalog-query-v3-release-year|"
                else if (query.destination == Destination.HOME || query.parentId != null) "catalog-query-v2|" else "catalog-query-v1|"
            val identity = if (version == "catalog-query-v1|") parts else parts + query.parentId.orEmpty()
            return version + identity.joinToString("|") { "${it.length}:$it" }
        }
        private const val CATALOG_FRESHNESS_MILLIS = 10 * 60 * 1_000L
        private const val RESUME_FRESHNESS_MILLIS = 60_000L
        private const val GENRE_FRESHNESS_MILLIS = 60 * 60_000L
        private const val RESUME_LIMIT = 12
        private const val RECENT_UPLOADS_LIMIT = 12
        private const val METADATA_LIMIT = 60
        private const val SECTION_PAGE_SIZE = 50
        private const val EPISODE_PAGE_SIZE = 20
        private const val MAX_SERIES_START_ITEMS = 32
        private const val MAX_PROGRESS_ITEMS = 64
        private const val HOME_SHELF_PAGE_SIZE = 12
        private const val SHELF_METADATA_LIMIT = 20
        private const val MAX_MEMORY_QUERIES = 12
        private const val MAX_MEMORY_CARDS = 4096
        private const val CONNECTION_COMPONENT_FAILURE = "The connection components could not start. Copy the error report and try again."
        private val fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PLAY_ACCESS, ItemFields.MEDIA_SOURCES, ItemFields.PEOPLE, ItemFields.REMOTE_TRAILERS)
        private val catalogFields = listOf(ItemFields.GENRES, ItemFields.PLAY_ACCESS)
        private val releaseSort = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME)
        private val releaseSortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING)
        private fun canUseLibraryViewDiscovery(error: Exception): Boolean = error is InvalidContentException ||
            error is InvalidStatusException && error.status in setOf(400, 404, 405, 500, 501, 502, 503, 504)
        private fun catalogFailureCopy(count: Int, failure: String) = if (count == 0) failure else "$count loaded titles remain available. $failure"
        private fun catalogFailureMessage(error: Exception) = when {
            error is CatalogPaginationFailure -> requireNotNull(error.message)
            catalogNetworkFailure(error) == CatalogNetworkFailure.DNS -> "Your device could not find the server address. Check your connection, then retry to continue loading."
            catalogNetworkFailure(error) == CatalogNetworkFailure.CONNECTION -> "The connection to your server was interrupted. Retry to continue loading."
            catalogNetworkFailure(error) == CatalogNetworkFailure.TIMEOUT -> "The library took too long to respond. Retry to finish loading."
            error is InvalidContentException || error is IllegalArgumentException -> "The server returned library data this app could not read. Retry, or copy the error report."
            error is InvalidStatusException && error.status == 403 -> "The server did not allow this library request. Check library access, or copy the error report."
            error is InvalidStatusException -> "The server could not finish the library request. Retry, or copy the error report."
            else -> "Your library could not finish loading. Retry, or copy the error report."
        }
        private val profile = DeviceProfile(name = "MojaLoss conservative Media3", directPlayProfiles = listOf(
            DirectPlayProfile("mp4,m4v", "aac,mp3", "h264", DlnaProfileType.VIDEO)),
            transcodingProfiles = listOf(TranscodingProfile("ts", DlnaProfileType.VIDEO, "h264", "aac", MediaStreamProtocol.HLS,
                maxAudioChannels = "2", conditions = emptyList())), containerProfiles = emptyList(), codecProfiles = emptyList(),
            subtitleProfiles = listOf("srt", "vtt", "ttml").map { SubtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL) })
        private fun imageTag(url: String?): String? = runCatching {
            URI(url ?: return null).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("tag=") }
                ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
        }.getOrNull()
        private fun millis(ticks: Long) = (ticks / 10_000).coerceAtLeast(0)
        private fun ticks(ms: Long) = ms.coerceIn(0, Long.MAX_VALUE / 10_000) * 10_000
        private fun subtitleMime(codec: String?) = when (codec?.lowercase()) {
            "srt", "subrip" -> "application/x-subrip"; "vtt", "webvtt" -> "text/vtt"; "ttml" -> "application/ttml+xml"; else -> null
        }
        private fun youtubeId(value: String?): String? = runCatching {
            val url = URI(value ?: return null)
            val host = url.host?.lowercase()?.removePrefix("www.")
            val id = if (host == "youtu.be") url.path.trim('/') else if (host in setOf("youtube.com", "m.youtube.com")) {
                if (url.path.startsWith("/embed/")) url.path.removePrefix("/embed/").substringBefore('/')
                else url.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            } else null
            id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        }.getOrNull()
        private fun isDenied(error: Exception) = error is InvalidStatusException && error.status in setOf(401, 403)
        private fun message(error: Exception, fallback: String) = when {
            error is UserVisibleFailure -> error.message ?: fallback
            error is IllegalArgumentException -> "Check the server address and account details."
            error is InvalidStatusException && error.status == 401 -> "The sign-in was not accepted. Check your account or connection code."
            error is InvalidStatusException && error.status == 403 -> "This action is not allowed by the service."
            error is InvalidStatusException && error.status == 404 -> "This item or connection code is unavailable or expired."
            else -> fallback
        }
    }
}

private class UserVisibleFailure(message: String, val recovery: OperationRecovery = OperationRecovery.RETRY, val denied: Boolean = false) : Exception(message)
internal class CatalogPageTimeout(cause: TimeoutCancellationException) : Exception("Catalog page timed out", cause)
private class CatalogPaginationFailure(message: String) : Exception(message)

private class SavedAccountMismatch : Exception("Saved account mismatch")

/** Media3's progressive extractors support these original-file containers; no offline transcode. */
private fun downloadContainerMime(container: String): String? = when (container) {
    "mp4", "m4v", "mov" -> "video/mp4"
    "mkv", "matroska" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> null
}

/** Conservative admission against the actual device decoders, rather than a fixed profile guess. */
private fun deviceSupportsDownload(container: String, streams: List<MediaStream>): Boolean = runCatching {
    if (downloadContainerMime(container) == null) return@runCatching false
    val playable = streams.filter { it.type == MediaStreamType.VIDEO || it.type == MediaStreamType.AUDIO }
    if (playable.none { it.type == MediaStreamType.VIDEO } || playable.any { it.isExternal || it.dvProfile != null })
        return@runCatching false
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    playable.all { stream ->
        val mime = when (stream.codec?.lowercase()) {
            "h264", "avc" -> "video/avc"
            "hevc", "h265" -> "video/hevc"
            "vp8" -> "video/x-vnd.on2.vp8"
            "vp9" -> "video/x-vnd.on2.vp9"
            "av1" -> "video/av01"
            "mpeg2video" -> "video/mpeg2"
            "mpeg4" -> "video/mp4v-es"
            "aac" -> "audio/mp4a-latm"
            "mp3" -> "audio/mpeg"
            "ac3" -> "audio/ac3"
            "eac3" -> "audio/eac3"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "vorbis" -> "audio/vorbis"
            else -> return@all false
        }
        if (mime.startsWith("video/") != (stream.type == MediaStreamType.VIDEO)) return@all false
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            if (stream.type == MediaStreamType.VIDEO) {
                if ((stream.width ?: 0) <= 0 || (stream.height ?: 0) <= 0) return@all false
                val profile = when (mime) {
                    "video/avc" -> when (stream.profile?.lowercase()?.replace(" ", "")) {
                        "baseline", "constrainedbaseline" -> CodecProfileLevel.AVCProfileBaseline
                        "main" -> CodecProfileLevel.AVCProfileMain
                        "extended" -> CodecProfileLevel.AVCProfileExtended
                        "high" -> CodecProfileLevel.AVCProfileHigh
                        "high10" -> CodecProfileLevel.AVCProfileHigh10
                        "high422" -> CodecProfileLevel.AVCProfileHigh422
                        "high444", "high444predictive" -> CodecProfileLevel.AVCProfileHigh444
                        else -> return@all false
                    }
                    "video/hevc" -> when (stream.profile?.lowercase()?.replace(" ", "")) {
                        "main" -> CodecProfileLevel.HEVCProfileMain
                        "main10" -> CodecProfileLevel.HEVCProfileMain10
                        else -> return@all false
                    }
                    "video/x-vnd.on2.vp9" -> when (stream.profile?.lowercase()?.replace(" ", "")) {
                        "profile0", "0" -> CodecProfileLevel.VP9Profile0
                        "profile1", "1" -> CodecProfileLevel.VP9Profile1
                        "profile2", "2" -> CodecProfileLevel.VP9Profile2
                        "profile3", "3" -> CodecProfileLevel.VP9Profile3
                        else -> return@all false
                    }
                    "video/av01" -> when {
                        stream.bitDepth == 10 -> CodecProfileLevel.AV1ProfileMain10
                        stream.bitDepth == 8 -> CodecProfileLevel.AV1ProfileMain8
                        else -> return@all false
                    }
                    else -> null
                }
                profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
                setInteger(MediaFormat.KEY_WIDTH, requireNotNull(stream.width))
                setInteger(MediaFormat.KEY_HEIGHT, requireNotNull(stream.height))
                (stream.averageFrameRate ?: stream.realFrameRate)?.takeIf { it > 0 }?.let { setFloat(MediaFormat.KEY_FRAME_RATE, it) }
            } else {
                if ((stream.channels ?: 0) <= 0 || (stream.sampleRate ?: 0) <= 0) return@all false
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, requireNotNull(stream.channels))
                setInteger(MediaFormat.KEY_SAMPLE_RATE, requireNotNull(stream.sampleRate))
            }
        }
        decoders.any { decoder ->
            decoder.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching { decoder.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
        }
    }
}.getOrDefault(false)
