package com.mojaloss.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.*
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Production SDK/encrypted session, intercepted before DNS. No media transfer or live credentials. */
@RunWith(AndroidJUnit4::class)
class DownloadAdmissionDataTest {
    @Test fun signedOutCannotPrepareOrAuthorizeAndDoesNotStartNetworkWork() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            assertNull(f.repository.downloadAuthorization(downloadOwnerKey(SCOPE)))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun admissionReadsFreshPolicyAndExactlyOneOriginalMetadataRecordWithoutPlaybackOrBytes() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); val before = f.requests.size
            val source = success(f.repository.prepareDownload(ITEM))
            assertEquals(listOf("/jelly/Users/Me", "/jelly/Items"), f.requests.drop(before).map { it.url.encodedPath })
            val request = f.requests.last()
            assertEquals(ITEM, request.url.queryParameter("ids"))
            assertEquals("1", request.url.queryParameter("limit")); assertEquals("0", request.url.queryParameter("startIndex"))
            assertTrue(request.url.queryParameterValues("fields").containsAll(listOf("CanDownload", "Path", "MediaSources", "MediaStreams")))
            assertEquals("https://www.mojaloss.stream/jelly/Items/$ITEM/Download", source.url)
            assertEquals(123456L, source.expectedBytes); assertEquals("video/mp4", source.mimeType)
            assertEquals(ITEM, source.mediaSourceId); assertEquals(1, f.codecChecks.get())
            assertTrue(source.title.artworkHeaders.isEmpty()); assertNull(source.title.posterUrl)
            assertEquals(0, f.transport.dnsAttempts.get())
        }
    }

    @Test fun changedDownloadPolicyIsCheckedAgainAndDeniesBeforeItemMetadata() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); f.user = user(download = false)
            val failure = f.repository.prepareDownload(ITEM) as OperationResult.Failure
            assertTrue(failure.denied); assertEquals(0, f.itemRequests().size)
            assertEquals(0, f.codecChecks.get())
        }
    }

    @Test fun playbackPermissionIsRequiredEvenWhenDownloadPolicyAllowsIt() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); f.user = user(playback = false)
            assertTrue((f.repository.prepareDownload(ITEM) as OperationResult.Failure).denied)
            assertTrue(f.itemRequests().isEmpty())
        }
    }

    @Test fun itemDownloadPermissionCannotBeInferredFromPlayableCatalogData() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); f.item = item().put("CanDownload", false)
            assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            assertEquals(0, f.codecChecks.get())
        }
    }

    @Test fun wrongDuplicateOrUnexpectedMetadataPagesCannotAdmitAnotherTitle() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect()
            for (response in listOf(page(item().put("Id", OTHER)), page(item(), item()), page(item(), start = 1))) {
                f.itemResponse = response
                assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            }
            assertEquals(0, f.codecChecks.get())
        }
    }

    @Test fun upcomingPlaceholderVirtualAndDeniedPlayItemsAreNotDownloadable() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect()
            for (invalid in listOf(item().put("PremiereDate", "2999-01-01T00:00:00Z"),
                item().put("IsPlaceHolder", true), item().put("LocationType", "Virtual"), item().put("PlayAccess", "None"))) {
                f.item = invalid
                assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            }
            assertEquals(0, f.codecChecks.get())
        }
    }

    @Test fun seriesContainersAreDeniedButActualEpisodeOriginalsCanBeAdmitted() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); f.item = item().put("Type", "Series")
            assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            f.item = item().put("Type", "Episode").put("SeriesId", OTHER).put("ParentIndexNumber", 1).put("IndexNumber", 2)
            assertEquals(MediaKind.EPISODE, success(f.repository.prepareDownload(ITEM)).title.kind)
        }
    }

    @Test fun originalSourceMustMatchItemPathUuidAndDefaultTypeWithoutAmbiguity() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect()
            for (sources in listOf(JSONArray().put(source().put("Id", OTHER)),
                JSONArray().put(source().put("Path", "/media/alternate.mp4")),
                JSONArray().put(source().put("Type", "Grouping")), JSONArray().put(source()).put(source()))) {
                f.item = item().put("MediaSources", sources)
                assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            }
            assertEquals(0, f.codecChecks.get())
        }
    }

    @Test fun unknownContainerRemoteOpeningSourcesAndUnsupportedDeviceCodecsStayStreamingOnly() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect()
            for (invalid in listOf(source().put("Container", "avi"), source().put("IsRemote", true),
                source().put("RequiresOpening", true), source().put("RequiredHttpHeaders", JSONObject().put("Authorization", "fixture-only")))) {
                f.item = item().put("MediaSources", JSONArray().put(invalid))
                assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            }
            f.item = item(); f.supported = false
            assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            assertEquals(1, f.codecChecks.get())
        }
    }

    @Test fun authorizationIsEphemeralExactSavedOwnerOnlyAndInvalidAfterSignOut() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); val connected = f.repository.session.value as SessionState.Connected
            val key = downloadOwnerKey(connected.scope)
            assertEquals(mapOf("X-Emby-Token" to TOKEN), f.repository.downloadAuthorization(key)!!.headers)
            assertNull(f.repository.downloadAuthorization(downloadOwnerKey(SCOPE.copy(accountId = OTHER))))
            PrivateStorage(f.context).saveSession(StoredSession(ServerUrls.normalize(INPUT), OTHER, TOKEN))
            assertNull(f.repository.downloadAuthorization(key))
            f.repository.signOut(); assertNull(f.repository.downloadAuthorization(key))
        }
    }

    @Test fun noCatalogCacheDnsRestoreStillExposesOnlySavedOfflineOwnerWithoutFalseCachedTitles() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            f.connect(); val old = f.repository.session.value as SessionState.Connected
            assertNull(CatalogCacheStorage(f.context).load(INPUT, USER, "home-v4-release-year"))
            f.offline = true
            val reopened = f.reopen(); reopened.initialize()
            val pending = reopened.session.value as SessionState.Restoring
            assertEquals(USER, pending.account.id); assertEquals(downloadOwnerKey(old.scope), downloadOwnerKey(pending.scope))
            assertFalse(reopened.catalog.value.isCached); assertTrue(reopened.catalog.value.titles.isEmpty())
            assertFalse(reopened.catalog.value.isConnectionVerified)
            assertFalse(reopened.catalog.value.message.orEmpty().contains("saved library"))
            assertEquals(TOKEN, reopened.downloadAuthorization(downloadOwnerKey(pending.scope))!!.headers["X-Emby-Token"])
            val before = f.requests.size
            assertTrue(reopened.prepareDownload(ITEM) is OperationResult.Failure)
            assertEquals(before, f.requests.size); assertEquals(0, f.transport.dnsAttempts.get())
        }
    }

    @Test fun expiredOrMismatchedFreshAccountClearsSavedAuthorizationInsteadOfAdmittingMedia() = runBlocking(Dispatchers.IO) {
        for (wrongUser in listOf(false, true)) Fixture().use { f ->
            f.connect(); val connected = f.repository.session.value as SessionState.Connected
            if (wrongUser) f.user = user().put("Id", OTHER) else f.userStatus = 401
            assertTrue(f.repository.prepareDownload(ITEM) is OperationResult.Failure)
            assertTrue(f.repository.session.value is SessionState.Failed)
            assertNull(PrivateStorage(f.context).loadSession())
            assertNull(f.repository.downloadAuthorization(downloadOwnerKey(connected.scope)))
            assertTrue(f.itemRequests().isEmpty())
        }
    }

    private class Fixture : Closeable {
        private val root: Context = ApplicationProvider.getApplicationContext()
        private val directory = File(root.cacheDir, "download-admission-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(root) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val scopes = CopyOnWriteArrayList<CoroutineScope>()
        val requests = CopyOnWriteArrayList<Request>()
        val codecChecks = AtomicInteger()
        var user = user(); var item = item(); var userStatus = 200; var itemResponse: String? = null
        var offline = false; var supported = true
        val transport = InterceptedSdkTransport { request ->
            requests += request
            when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> response("""{"Version":"10.11.0","Id":"offline-fixture"}""")
                "/jelly/Users/AuthenticateByName" -> response(JSONObject().put("User", user).put("AccessToken", TOKEN).toString())
                "/jelly/Users/Me" -> {
                    if (offline) throw UnknownHostException("Local offline fixture")
                    InterceptedSdkTransport.FixtureResponse(userStatus, user.toString())
                }
                "/jelly/Items" -> response(itemResponse ?: page(item))
                else -> throw IOException("Unexpected download fixture endpoint; transfers and playback are forbidden")
            }
        }
        val repository = reopen()
        fun reopen(): JellyfinMediaRepository {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            return JellyfinMediaRepository(context, scope, apiClientFactory = { base, token -> transport.createApi(context, base, token) },
                catalogLoadOptions = CatalogLoadOptions(24, 20_000, 0, 0),
                downloadCodecProbe = { container, streams ->
                    codecChecks.incrementAndGet(); assertEquals("mp4", container); assertTrue(streams.isNotEmpty()); supported
                })
        }
        suspend fun connect() { repository.connectPassword(INPUT, "fixture", "fixture-password"); assertTrue(repository.session.value is SessionState.Connected) }
        fun itemRequests() = requests.filter { it.url.encodedPath == "/jelly/Items" }
        override fun close() { scopes.forEach { it.cancel() }; transport.close(); directory.deleteRecursively() }
    }

    private companion object {
        const val INPUT = "https://www.mojaloss.stream/jelly"
        const val USER = "00000000-0000-0000-0000-000000000042"
        const val ITEM = "00000000-0000-0000-0000-000000000001"
        const val OTHER = "00000000-0000-0000-0000-000000000002"
        const val TOKEN = "download-admission-fixture-token"
        val SCOPE = SessionScope(ServerUrls.normalize(INPUT), USER, 1)
        fun response(body: String) = InterceptedSdkTransport.FixtureResponse(200, body)
        fun page(vararg items: JSONObject, start: Int = 0) = JSONObject().put("Items", JSONArray(items.toList()))
            .put("StartIndex", start).put("TotalRecordCount", items.size).toString()
        fun user(download: Boolean = true, playback: Boolean = true): JSONObject {
            val policy = JSONObject()
            "IsAdministrator IsHidden IsDisabled EnableUserPreferenceAccess EnableRemoteControlOfOtherUsers EnableSharedDeviceControl EnableRemoteAccess EnableLiveTvManagement EnableLiveTvAccess EnableMediaPlayback EnableAudioPlaybackTranscoding EnableVideoPlaybackTranscoding EnablePlaybackRemuxing ForceRemoteSourceTranscoding EnableContentDeletion EnableContentDownloading EnableSyncTranscoding EnableMediaConversion EnableAllDevices EnableAllChannels EnableAllFolders EnablePublicSharing"
                .split(' ').forEach { policy.put(it, false) }
            "InvalidLoginAttemptCount LoginAttemptsBeforeLockout MaxActiveSessions RemoteClientBitrateLimit".split(' ').forEach { policy.put(it, 0) }
            policy.put("EnableContentDownloading", download).put("EnableMediaPlayback", playback)
                .put("AuthenticationProviderId", "fixture").put("PasswordResetProviderId", "fixture").put("SyncPlayAccess", "None")
            return JSONObject().put("Id", USER).put("Name", "Fixture").put("HasPassword", false)
                .put("HasConfiguredPassword", false).put("HasConfiguredEasyPassword", false).put("Policy", policy)
        }
        fun stream() = JSONObject().put("Type", "Video").put("Codec", "h264").put("Index", 0)
            .put("Width", 1280).put("Height", 720).put("Profile", "High")
            .put("IsInterlaced", false).put("IsDefault", true).put("IsForced", false).put("IsHearingImpaired", false).put("IsExternal", false)
            .put("IsTextSubtitleStream", false).put("SupportsExternalStream", false)
        fun source(): JSONObject {
            val source = JSONObject().put("Id", ITEM).put("Path", "/media/original.mp4").put("Type", "Default")
                .put("Protocol", "File").put("Container", "mp4").put("Size", 123456)
                .put("MediaStreams", JSONArray().put(stream())).put("TranscodingSubProtocol", "hls")
            "IsRemote ReadAtNativeFramerate IgnoreDts IgnoreIndex GenPtsInput SupportsTranscoding SupportsDirectStream SupportsDirectPlay IsInfiniteStream RequiresOpening RequiresClosing RequiresLooping SupportsProbing HasSegments"
                .split(' ').forEach { source.put(it, false) }
            return source
        }
        fun item() = JSONObject().put("Id", ITEM).put("Name", "Original fixture").put("Type", "Movie")
            .put("CanDownload", true).put("PlayAccess", "Full").put("LocationType", "FileSystem")
            .put("Path", "/media/original.mp4").put("MediaSources", JSONArray().put(source()))
        fun <T> success(result: OperationResult<T>): T = when (result) {
            is OperationResult.Success -> result.value
            is OperationResult.Failure -> throw AssertionError(result.message)
        }
    }
}
