package com.mojaloss.core.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

interface DownloadRuntimeProvider { val offlineDownloads: OfflineDownloads }

/** One process singleton. Durable state uses a private, non-evicting, no-backup cache. */
@UnstableApi
class OfflineDownloads(
    context: Context,
    appScope: CoroutineScope,
    private val authorization: suspend (String) -> DownloadAuthorization?,
) {
    private val appScope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]) +
        CoroutineExceptionHandler { _, failure ->
            if (failure !is CancellationException) {
                initializationFailure = "Downloads paused unexpectedly. Reopen Downloads to retry."
                _library.value = _library.value.copy(message = initializationFailure)
            }
        })
    private val context = context.applicationContext
    private val directory = File(this.context.noBackupFilesDir, "offline-media")
    private val store = DownloadStore(directory)
    private val lock = Any()
    private val rows = linkedMapOf<String, StoredDownload>()
    private val mutationMutex = Mutex()
    private val blockedOwners = mutableSetOf<String>()
    @Volatile private var owner: SessionScope? = null
    @Volatile private var transferScope: SessionScope? = null
    @Volatile private var settings = DownloadSettings()
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var manager: DownloadManager? = null
    @Volatile private var index: DownloadIndex? = null
    @Volatile private var initialized = false
    @Volatile private var initializationFailure: String? = null
    private var database: StandaloneDatabaseProvider? = null
    private var visible = false
    private val refreshGeneration = java.util.concurrent.atomic.AtomicLong()
    private val lastObservationSave = mutableMapOf<String, Long>()
    private var pollJob: Job? = null
    private var ownerAuthorizationJob: Job? = null
    private val _library = MutableStateFlow(DownloadLibrary())
    val library: StateFlow<DownloadLibrary> = _library
    private val connectivity = this.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }

    suspend fun initialize() {
        try {
            withContext(Dispatchers.IO) { prepareStorage() }
            withContext(Dispatchers.Main.immediate) { createManager(); initializationFailure = null; refresh() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            initializationFailure = "Downloads could not open. Free some space and try again."
            _library.value = DownloadLibrary(initialized = true, ownerKey = owner?.let(::downloadOwnerKey),
                settings = settings, message = initializationFailure)
        }
    }
    @Synchronized private fun prepareStorage() {
        if (cache != null) return
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Storage unavailable")
        val (saved, savedSettings) = store.read()
        val accepted = saved.filter { it.owner == downloadOwnerKey(SessionScope(it.server, it.account, 0)) &&
            DownloadPolicy.isSafeOriginal(it.server, it.url, it.title.id) &&
            it.fingerprint == DownloadPolicy.fingerprint(it.url, it.sourceId, it.expectedBytes) }
        synchronized(lock) { rows.clear(); accepted.forEach { rows[it.id] = it }; blockedOwners.clear(); blockedOwners += store.readBlockedOwners() }
        settings = savedSettings
        val provider = StandaloneDatabaseProvider(context)
        val mediaCache = SimpleCache(File(directory, "media"), NoOpCacheEvictor(), provider)
        database = provider
        cache = mediaCache
        val known = synchronized(lock) { rows.keys.toSet() }
        mediaCache.keys.filter { it !in known }.forEach(mediaCache::removeResource)
    }
    private fun createManager(): DownloadManager {
        manager?.let { return it }
        val mediaCache = cache ?: throw IOException("Download storage unavailable")
        val provider = database ?: throw IOException("Download index unavailable")
        val created = DownloadManager(context, provider, mediaCache,
            DataSource.Factory { GuardedDownloadDataSource(this) }, Executor(Runnable::run))
        manager = created
        index = created.downloadIndex
        created.maxParallelDownloads = 1
        created.minRetryCount = 1
        created.requirements = Requirements(Requirements.NETWORK)
        created.pauseDownloads() // Account restoration must finish before any network transfer.
        created.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                appScope.launch(Dispatchers.IO) {
                    val orphaned = mutableListOf<String>()
                    index?.getDownloads()?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.download.request.id
                            if (synchronized(lock) { id !in rows }) orphaned += id
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        orphaned.forEach(downloadManager::removeDownload); applyPolicy(); refresh()
                        synchronized(lock) { blockedOwners.toList() }.forEach { key -> appScope.launch { clearOwner(key) } }
                    }
                }
            }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.state == Download.STATE_FAILED) {
                    val blocked = generateSequence(finalException as Throwable?) { it.cause }
                        .filterIsInstance<TransferBlocked>().firstOrNull()?.message.orEmpty()
                    val problem = when {
                        !hasUsableNetwork() -> NETWORK_WAIT
                        blocked.contains("storage", true) -> "Not enough space. Remove a download or increase the storage limit, then retry."
                        blocked.contains("original", true) || blocked.contains("resume", true) -> "This original changed or could not be verified. Remove this copy and download it again."
                        blocked.contains("account", true) || blocked.contains("Sign in", true) -> "Sign in to this account, then tap Resume."
                        else -> "Download interrupted. Retry when connected; your library is still available."
                    }
                    synchronized(lock) { rows[download.request.id]?.let { rows[it.id] = it.copy(problem = problem) } }
                    persistAsync()
                }
                refresh(); startPolling()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) { refresh() }
            override fun onIdle(downloadManager: DownloadManager) { refresh(); startPolling() }
        })
        if (!initialized) {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
        }
        initialized = true
        applyPolicy()
        startPolling()
        return created
    }
    internal fun managerForService(): DownloadManager {
        // DownloadService may be cold-started. Disk initialization is contained and runs on IO.
        runBlocking(Dispatchers.IO) { prepareStorage() }
        return createManager()
    }
    @Synchronized fun setOwner(scope: SessionScope?) {
        if (owner == scope && (scope == null || transferScope == scope || ownerAuthorizationJob?.isActive == true)) return
        if (owner != scope) {
            ownerAuthorizationJob?.cancel()
            owner = scope // Invalidate transfer reads immediately, before asynchronous policy changes.
            transferScope = null
        }
        ownerAuthorizationJob = appScope.launch {
            withContext(Dispatchers.Main.immediate) { manager?.let { applyPolicy(); refresh() } }
            val auth = scope?.let { authorization(downloadOwnerKey(it)) }
            if (owner == scope && auth?.scope == scope) transferScope = scope
            withContext(Dispatchers.Main.immediate) { manager?.let { applyPolicy(); refresh(); startService(); resumeNetworkWaits() } }
        }
    }
    fun setVisible(value: Boolean) {
        visible = value
        appScope.launch(Dispatchers.Main.immediate) { if (value) refresh(); startPolling() }
    }
    suspend fun enqueue(source: DownloadSource): OperationResult<Unit> = mutationMutex.withLock { enqueueLocked(source) }
    private suspend fun enqueueLocked(source: DownloadSource): OperationResult<Unit> {
        if (synchronized(lock) { source.ownerKey in blockedOwners })
            return OperationResult.Failure("Finishing removal of this account's downloads. Try again shortly")
        if (source.title.kind == MediaKind.SERIES || !source.title.isPlayable)
            return OperationResult.Failure("Choose an episode to download")
        if (owner != source.sessionScope || !DownloadPolicy.isSafeOriginal(source.sessionScope.serverUrl, source.url, source.title.id))
            return OperationResult.Failure("Connect to this account before downloading", denied = true)
        initialize()
        val active = manager ?: return OperationResult.Failure(initializationFailure ?: "Downloads are unavailable")
        val auth = authorization(source.ownerKey)
        if (auth?.scope != source.sessionScope || owner != source.sessionScope)
            return OperationResult.Failure("Sign in again to download this title", denied = true)
        transferScope = auth.scope
        val sourceId = source.mediaSourceId ?: source.title.id
        val id = DownloadPolicy.id(source.ownerKey, source.title.id, sourceId)
        val item = StoredDownload(id, source.ownerKey, source.sessionScope.serverUrl, source.sessionScope.accountId,
            source.title.copy(artworkHeaders = emptyMap(), posterUrl = null, backdropUrl = null, cast = emptyList(),
                synopsis = "", ratings = emptyList(), trailerVideoId = null),
            source.url, sourceId, source.mimeType, source.expectedBytes,
            DownloadPolicy.fingerprint(source.url, sourceId, source.expectedBytes), System.currentTimeMillis())
        val previous = synchronized(lock) { rows[id] }
        if (previous != null && (previous.url != item.url || previous.sourceId != item.sourceId ||
            (source.expectedBytes != null && previous.expectedBytes != source.expectedBytes)))
            return OperationResult.Failure("The original changed. Remove the old download before downloading again")
        try {
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    val next = previous?.copy(userPaused = false, problem = null) ?: item
                    checkReservedCapacity(next)
                    rows[id] = next
                    persistLocked()
                }
                discardUnvalidatedPartial(id)
            }
            withContext(Dispatchers.Main.immediate) {
                if (owner != source.sessionScope) throw TransferBlocked("The account changed")
                val request = DownloadRequest.Builder(id, android.net.Uri.parse(item.url))
                    .setCustomCacheKey(id).setMimeType(item.mimeType).build()
                active.addDownload(request, stopReason(item))
                applyPolicy(); refresh(); startService()
            }
            return OperationResult.Success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return OperationResult.Failure("Could not start the download. Check storage and connection, then retry")
        }
    }
    fun pause(id: String) = updateRow(id) { it.copy(userPaused = true, problem = null) }
    fun resume(id: String) { resumeOrRetry(id) }
    fun retry(id: String) { resumeOrRetry(id) }
    private fun resumeOrRetry(id: String) {
        appScope.launch { mutationMutex.withLock {
            val row = synchronized(lock) { rows[id] } ?: return@launch
            if (row.owner != owner?.let(::downloadOwnerKey) || synchronized(lock) { row.owner in blockedOwners }) return@launch
            val auth = authorization(row.owner) ?: return@launch
            if (!authorizationMatches(row, auth)) return@launch
            transferScope = auth.scope
            withContext(Dispatchers.IO) {
                discardUnvalidatedPartial(id)
                synchronized(lock) { rows[id]?.let { rows[id] = it.copy(userPaused = false, problem = null); persistLocked() } }
            }
            withContext(Dispatchers.Main.immediate) {
                manager?.addDownload(DownloadRequest.Builder(id, android.net.Uri.parse(row.url))
                    .setCustomCacheKey(id).setMimeType(row.mimeType).build(), stopReason(row.copy(userPaused = false)))
                applyPolicy(); refresh(); startService()
            }
        } }
    }
    fun remove(id: String) {
        appScope.launch(Dispatchers.Main.immediate) {
            val row = synchronized(lock) { rows[id] } ?: return@launch
            if (row.owner != owner?.let(::downloadOwnerKey)) return@launch
            manager?.removeDownload(id)
            withContext(Dispatchers.IO) { synchronized(lock) { rows.remove(id); persistLocked() } }
            refresh()
        }
    }
    suspend fun clearOwner(ownerKey: String) = mutationMutex.withLock {
        // A late sign-out cleanup cannot erase a newly reconnected account's fresh queue.
        if (owner?.let(::downloadOwnerKey) == ownerKey && synchronized(lock) { ownerKey !in blockedOwners }) return@withLock
        synchronized(lock) { blockedOwners += ownerKey }
        try {
            initialize()
            val ids = withContext(Dispatchers.IO) { synchronized(lock) {
                blockedOwners += ownerKey
                persistLocked() // Tombstone prevents a cold restart from reopening this namespace.
                rows.values.filter { it.owner == ownerKey }.map { it.id }
            } }
            withContext(Dispatchers.Main.immediate) {
                ids.forEach { manager?.setStopReason(it, ACCOUNT_PAUSE); manager?.removeDownload(it) }
            }
            val removed = withContext(Dispatchers.IO) {
                var attempts = 0
                while (attempts++ < 60 && ids.any { index?.getDownload(it) != null }) delay(100)
                if (cache == null || index == null || ids.any { index?.getDownload(it) != null }) false
                else { ids.forEach { cache?.removeResource(it) }; true }
            }
            withContext(Dispatchers.IO) { synchronized(lock) {
                if (removed) { ids.forEach(rows::remove); blockedOwners -= ownerKey }
                else rows.replaceAll { _, row -> if (row.owner == ownerKey) row.copy(
                    problem = "Download removal is still finishing. Reopen the app to retry.") else row }
                persistLocked()
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Keep the tombstone on failures. New writes are refused instead of racing pending removal.
            initializationFailure = "Download removal could not finish. Reopen the app to retry."
            withContext(Dispatchers.IO) { runCatching { synchronized(lock) { persistLocked() } } }
        }
        withContext(Dispatchers.Main.immediate) { applyPolicy(); refresh() }
    }
    fun updateSettings(value: DownloadSettings) {
        settings = value.copy(storageLimitBytes = value.storageLimitBytes.coerceIn(256L * 1024 * 1024, DownloadPolicy.MAX_QUOTA_BYTES))
        appScope.launch(Dispatchers.IO) { runCatching { synchronized(lock) { persistLocked() } }
            withContext(Dispatchers.Main.immediate) { applyPolicy(); refresh(); startService() } }
    }
    suspend fun prepareOfflinePlayback(id: String, scope: SessionScope): OperationResult<PlaybackDescriptor> {
        initialize()
        return withContext(Dispatchers.IO) {
            val row = synchronized(lock) { rows[id] }
                ?: return@withContext OperationResult.Failure("This download is no longer available")
            if (owner != scope || row.owner != downloadOwnerKey(scope) || synchronized(lock) { row.owner in blockedOwners })
                return@withContext OperationResult.Failure("Open the account that downloaded this title", denied = true)
            val download = index?.getDownload(id)
            if (!isReady(row, download)) return@withContext OperationResult.Failure("Finish downloading this title before watching offline")
            OperationResult.Success(PlaybackDescriptor(title = row.title.copy(confirmedResumeMs = row.positionMs),
                streamUrl = row.url, sessionScope = scope, mediaSourceId = row.sourceId,
                resumeMs = row.positionMs, offlineCacheKey = row.id))
        }
    }
    fun cacheOnlyDataSourceFactory(): DataSource.Factory {
        val mediaCache = cache ?: throw IllegalStateException("Downloads are not initialized")
        val cacheFactory = cacheOnlyFactory(mediaCache)
        return DataSource.Factory { OwnerBoundOfflineDataSource(this, cacheFactory.createDataSource()) }
    }
    fun recordObservation(id: String, observation: PlaybackObservation) {
        if (!observation.observed || observation.positionMs <= 0) return
        if (!observation.advancing && !observation.paused && !observation.stopped && !observation.ended) return
        val currentOwner = owner ?: return
        if (observation.scope != currentOwner) return
        appScope.launch(Dispatchers.IO) {
            synchronized(lock) {
                val row = rows[id] ?: return@synchronized
                if (row.owner != downloadOwnerKey(currentOwner) || row.title.id != observation.mediaId || owner != currentOwner) return@synchronized
                val now = android.os.SystemClock.elapsedRealtime()
                val terminal = observation.paused || observation.stopped || observation.ended
                if (!terminal && now - (lastObservationSave[id] ?: 0) < 10_000) return@synchronized
                lastObservationSave[id] = now
                rows[id] = row.copy(positionMs = (if (observation.ended) 0 else observation.positionMs).coerceAtMost(row.title.runtimeMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
                runCatching { persistLocked() }
            }
        }
    }
    internal suspend fun release() {
        pollJob?.cancel()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        withContext(Dispatchers.Main.immediate) { manager?.release(); manager = null; index = null }
        withContext(Dispatchers.IO) { cache?.release(); cache = null; database?.close(); database = null }
        initialized = false
    }
    internal suspend fun authorize(key: String) = authorization(key)
    internal fun authorizationHeaders(value: DownloadAuthorization) = value.headers
    internal fun authorizationMatches(row: StoredDownload, value: DownloadAuthorization) =
        owner == value.scope && row.owner == downloadOwnerKey(value.scope)
    internal fun rowForTransfer(key: String?, uri: String) = synchronized(lock) {
        rows[key]?.takeIf { it.url == uri && DownloadPolicy.isSafeOriginal(it.server, uri, it.title.id) }
    }
    internal fun ownerMatches(scope: SessionScope) = owner == scope
    internal fun canReadOffline(id: String, key: String) = owner?.let(::downloadOwnerKey) == key &&
        synchronized(lock) { key !in blockedOwners && rows[id]?.owner == key }
    internal fun checkTransferAllowed(row: StoredDownload) {
        if (owner?.let(::downloadOwnerKey) != row.owner || synchronized(lock) { row.owner in blockedOwners || rows[row.id]?.userPaused != false })
            throw TransferBlocked("This download is paused")
        if (!hasUsableNetwork()) throw TransferBlocked(if (settings.wifiOnly) "Waiting for Wi-Fi" else "Waiting for connection")
    }
    internal fun checkCapacity(bytes: Long, next: Long) {
        if (!DownloadPolicy.canWrite(bytes, directory.usableSpace, next, settings.storageLimitBytes))
            throw TransferBlocked("Not enough download storage")
    }
    /** Predictable queue storage is reserved before accepting another title or body bytes. */
    private fun checkReservedCapacity(replacement: StoredDownload) {
        val candidates = rows.values.filter { it.id != replacement.id } + replacement
        var remaining = 0L
        for (candidate in candidates) {
            val expected = candidate.expectedBytes ?: continue
            val downloaded = cache?.getCachedBytes(candidate.id, 0, expected) ?: 0
            val required = (expected - downloaded).coerceAtLeast(0)
            if (required > settings.storageLimitBytes - remaining) throw TransferBlocked("Not enough download storage")
            remaining += required
        }
        checkCapacity(cachedBytes(), remaining)
    }
    internal fun cachedBytes() = cache?.cacheSpace ?: 0L
    internal fun rememberResponse(id: String, validator: String?, total: Long?) {
        synchronized(lock) {
            val row = rows[id] ?: throw TransferBlocked("Download removed")
            val size = row.expectedBytes ?: total
            if (size == null || size <= 0) throw TransferBlocked("The server did not provide a complete original size")
            val updated = row.copy(validator = validator, expectedBytes = size,
                fingerprint = DownloadPolicy.fingerprint(row.url, row.sourceId, size))
            checkReservedCapacity(updated)
            rows[id] = updated
            persistLocked()
        }
    }
    internal fun pauseForServiceTimeout() {
        val activeIds = manager?.currentDownloads?.map { it.request.id }?.toSet().orEmpty()
        synchronized(lock) { rows.replaceAll { id, row -> if (id in activeIds && !row.userPaused) row.copy(userPaused = true,
            problem = "Android paused background downloads. Tap Resume to continue.") else row } }
        runBlocking(Dispatchers.IO) { runCatching { synchronized(lock) { persistLocked() } } }
        manager?.pauseDownloads()
        applyPolicy(); refresh()
    }
    private fun updateRow(id: String, transform: (StoredDownload) -> StoredDownload) {
        appScope.launch(Dispatchers.IO) {
            synchronized(lock) {
                val row = rows[id] ?: return@synchronized
                if (row.owner == owner?.let(::downloadOwnerKey)) { rows[id] = transform(row); runCatching { persistLocked() } }
            }
            withContext(Dispatchers.Main.immediate) { applyPolicy(); refresh() }
        }
    }
    private fun persistLocked() = store.write(rows.values, settings, blockedOwners)
    private fun persistAsync() { appScope.launch(Dispatchers.IO) { runCatching { synchronized(lock) { persistLocked() } } } }
    private fun discardUnvalidatedPartial(id: String) {
        val row = synchronized(lock) { rows[id] } ?: return
        val download = index?.getDownload(id)
        if (download?.state != Download.STATE_COMPLETED && row.validator == null) cache?.removeResource(id)
    }
    private fun stopReason(row: StoredDownload) = when {
        synchronized(lock) { row.owner in blockedOwners } -> ACCOUNT_PAUSE
        row.userPaused -> USER_PAUSE
        row.owner != owner?.let(::downloadOwnerKey) || transferScope != owner -> ACCOUNT_PAUSE
        !hasUsableNetwork() && frameworkRequirements().checkRequirements(context) -> NETWORK_PAUSE
        else -> Download.STOP_REASON_NONE
    }
    private fun hasUsableNetwork(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return !settings.wifiOnly || (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    private fun applyPolicy() {
        val active = manager ?: return
        active.requirements = frameworkRequirements()
        synchronized(lock) { rows.values.toList() }.forEach { active.setStopReason(it.id, stopReason(it)) }
        if (owner == null || transferScope != owner) active.pauseDownloads() else active.resumeDownloads()
        val networkWaiting = synchronized(lock) { rows.values.any {
            !it.userPaused && it.owner == owner?.let(::downloadOwnerKey) && it.owner !in blockedOwners &&
                (it.expectedBytes == null || cache?.isCached(it.id, 0, it.expectedBytes) != true)
        } } && !hasUsableNetwork()
        if (networkWaiting) runCatching { scheduler().schedule(frameworkRequirements(), context.packageName,
            "androidx.media3.exoplayer.downloadService.action.RESTART") }
        else runCatching { scheduler().cancel() }
    }
    private fun frameworkRequirements() = Requirements(Requirements.NETWORK or
        if (settings.wifiOnly) Requirements.NETWORK_UNMETERED else 0)
    internal fun scheduler(): Scheduler = WifiDownloadScheduler(context, settings.wifiOnly)
    private fun networkChanged() { appScope.launch(Dispatchers.Main.immediate) { applyPolicy(); refresh(); startService(); resumeNetworkWaits() } }
    private fun resumeNetworkWaits() {
        if (!hasUsableNetwork() || owner == null || transferScope != owner) return
        appScope.launch { mutationMutex.withLock {
            val selected = owner?.let(::downloadOwnerKey)
            val waiting = synchronized(lock) { rows.values.filter {
                it.owner == selected && it.problem == NETWORK_WAIT && !it.userPaused && it.owner !in blockedOwners
            } }
            withContext(Dispatchers.IO) {
                waiting.forEach { discardUnvalidatedPartial(it.id) }
                synchronized(lock) { waiting.forEach { old -> rows[old.id]?.let { rows[it.id] = it.copy(problem = null) } }; persistLocked() }
            }
            withContext(Dispatchers.Main.immediate) {
                if (owner?.let(::downloadOwnerKey) != selected || !hasUsableNetwork()) return@withContext
                waiting.forEach { row -> manager?.addDownload(DownloadRequest.Builder(row.id, android.net.Uri.parse(row.url))
                    .setCustomCacheKey(row.id).setMimeType(row.mimeType).build(), stopReason(row)) }
                applyPolicy(); refresh(); startService()
            }
        } }
    }
    private fun startService() {
        if (manager?.currentDownloads?.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED } != true) return
        runCatching { androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(
            context, OfflineDownloadService::class.java, false) }.onFailure {
            manager?.pauseDownloads()
            synchronized(lock) { rows.replaceAll { _, row -> if (row.owner == owner?.let(::downloadOwnerKey))
                row.copy(userPaused = true, problem = "Open the app and tap Resume to continue downloading") else row } }
            persistAsync(); refresh()
        }
    }
    private fun startPolling() {
        pollJob?.cancel()
        if (!visible && manager?.currentDownloads?.any { it.state == Download.STATE_DOWNLOADING } != true) return
        pollJob = appScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(1_000); refresh()
                if (!visible && manager?.currentDownloads?.none { it.state == Download.STATE_DOWNLOADING } != false) break
            }
        }
    }
    private fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        val selected = owner?.let(::downloadOwnerKey)
        val copy = synchronized(lock) { rows.values.filter { it.owner == selected }.sortedByDescending { it.addedAt } }
        appScope.launch(Dispatchers.IO) {
            val items = copy.map { row ->
                val download = runCatching { index?.getDownload(row.id) }.getOrNull()
                val ready = isReady(row, download)
                val status = when {
                    ready -> DownloadStatus.READY
                    row.userPaused -> DownloadStatus.PAUSED
                    !hasUsableNetwork() -> DownloadStatus.WAITING_FOR_WIFI
                    download?.state == Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
                    download?.state == Download.STATE_REMOVING -> DownloadStatus.REMOVING
                    row.problem != null || download?.state == Download.STATE_FAILED || download?.state == Download.STATE_COMPLETED -> DownloadStatus.ERROR
                    else -> DownloadStatus.QUEUED
                }
                DownloadItem(row.id, row.owner, row.title, status,
                    bytesDownloaded = if (ready) row.expectedBytes ?: download?.contentLength ?: 0 else download?.bytesDownloaded ?: 0, totalBytes = row.expectedBytes ?: download?.contentLength?.takeIf { it > 0 },
                    message = when (status) {
                        DownloadStatus.WAITING_FOR_WIFI -> if (settings.wifiOnly) "Waiting for Wi-Fi" else "Waiting for connection"
                        DownloadStatus.ERROR -> row.problem ?: "This copy is incomplete. Retry to download it again."
                        else -> row.problem?.takeUnless { it == NETWORK_WAIT }
                    }, offlineResumeMs = row.positionMs)
            }
            if (refreshGeneration.get() == generation && owner?.let(::downloadOwnerKey) == selected) _library.value = DownloadLibrary(items, settings,
                initialized = initialized, ownerKey = selected, usedBytes = cachedBytes(), freeBytes = directory.usableSpace,
                message = initializationFailure)
        }
    }
    private fun isReady(row: StoredDownload, download: Download?): Boolean =
        cache?.let { isFullyCached(row, download, it) } ?: false
    companion object { private const val NETWORK_WAIT = "Waiting for connection"; private const val USER_PAUSE = 1001; private const val NETWORK_PAUSE = 1002; private const val ACCOUNT_PAUSE = 1003 }
}
