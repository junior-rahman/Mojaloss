package com.mojaloss.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojaloss.core.model.CatalogQuery
import com.mojaloss.core.model.ContentStatus
import com.mojaloss.core.model.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production adapter; all SDK HTTP is intercepted before DNS or transport. */
@RunWith(AndroidJUnit4::class)
class JellyfinRepositoryConnectionTest {
    @Test
    fun actualSdkHttpDenialBecomesFailureAtServerInfo() = runBlocking(Dispatchers.IO) {
        Fixture { InterceptedSdkTransport.FixtureResponse(403, "{}") }.use { fixture ->
            val repository = fixture.repository()
            repository.connectQuickConnect(INPUT)
            val failed = repository.session.value as SessionState.Failed
            assertTrue(failed.message.contains("not allowed"))
            assertEquals("server_info", fixture.failures.single().first)
            assertTrue(fixture.failures.single().second is InvalidStatusException)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test
    fun actualSdkNonJsonBecomesFailureWithoutProcessTermination() = runBlocking(Dispatchers.IO) {
        Fixture { InterceptedSdkTransport.FixtureResponse(body = "<html>Local fixture</html>") }.use { fixture ->
            val repository = fixture.repository()
            repository.connectQuickConnect(INPUT)
            assertTrue(repository.session.value is SessionState.Failed)
            assertEquals("server_info", fixture.failures.single().first)
            assertTrue(fixture.failures.single().second is InvalidContentException)
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test
    fun initializationLinkageErrorIsContainedAndReportsActualStage() = runBlocking(Dispatchers.IO) {
        Fixture { throw AssertionError("No HTTP expected") }.use { fixture ->
            val failure = NoClassDefFoundError("Local missing component fixture")
            val repository = fixture.repository { _, _ -> throw failure }
            repository.connectQuickConnect(INPUT)
            assertTrue(repository.session.value is SessionState.Failed)
            assertEquals("initialize_client", fixture.failures.single().first)
            assertSame(failure, fixture.failures.single().second)
            assertTrue(fixture.transport.requestedPaths.isEmpty())
        }
    }

    @Test
    fun requestLinkageErrorIsContainedBeforeAnyTransport() = runBlocking(Dispatchers.IO) {
        Fixture { throw AssertionError("No HTTP expected") }.use { fixture ->
            val failure = NoSuchMethodError("Local incompatible component fixture")
            val repository = fixture.repository { base, token ->
                RequestOverride(fixture.transport.createApi(fixture.context, base, token)) { throw failure }
            }
            repository.connectQuickConnect(INPUT)
            assertTrue(repository.session.value is SessionState.Failed)
            assertEquals("server_info", fixture.failures.single().first)
            assertSame(failure, fixture.failures.single().second)
            assertTrue(fixture.transport.requestedPaths.isEmpty())
        }
    }

    @Test
    fun ordinaryFailureCanRetryToWaitingAndUserCancelWithoutLateFailure() = runBlocking(Dispatchers.IO) {
        val publicRequests = AtomicInteger()
        Fixture { request ->
            when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> if (publicRequests.incrementAndGet() == 1)
                    InterceptedSdkTransport.FixtureResponse(403, "{}")
                else InterceptedSdkTransport.FixtureResponse(body = SERVER_JSON)
                "/jelly/QuickConnect/Enabled" -> InterceptedSdkTransport.FixtureResponse(body = "true")
                "/jelly/QuickConnect/Initiate" -> InterceptedSdkTransport.FixtureResponse(body = QUICK_CONNECT_JSON)
                else -> throw AssertionError("Unexpected local fixture endpoint")
            }
        }.use { fixture ->
            val repository = fixture.repository()
            repository.connectQuickConnect(INPUT)
            assertTrue(repository.session.value is SessionState.Failed)
            val retry = async { repository.connectQuickConnect(INPUT) }
            val waiting = withTimeout(15_000) { repository.session.filterIsInstance<SessionState.Waiting>().first() }
            assertEquals("TEST01", waiting.code)
            repository.cancelConnection()
            withTimeout(15_000) { retry.await() }
            assertEquals(SessionState.SignedOut, repository.session.value)
            assertEquals(1, fixture.failures.size)
            assertTrue(fixture.stages.contains("connection_cancelled"))
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test
    fun cancelDuringFirstSdkRequestDoesNotPublishLateResponse() = runBlocking(Dispatchers.IO) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Local fixture release timed out" }
            InterceptedSdkTransport.FixtureResponse(body = SERVER_JSON)
        }.use { fixture ->
            val repository = fixture.repository()
            val connection = async { repository.connectQuickConnect(INPUT) }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                repository.cancelConnection()
                assertEquals(SessionState.SignedOut, repository.session.value)
            } finally { release.countDown() }
            withTimeout(15_000) { connection.await() }
            assertEquals(SessionState.SignedOut, repository.session.value)
            assertTrue(fixture.failures.isEmpty())
            assertEquals(0, fixture.transport.dnsAttempts.get())
        }
    }

    @Test
    fun callerCancellationIsPreservedAndClearsPendingConnection() = runBlocking(Dispatchers.IO) {
        Fixture { throw AssertionError("No HTTP expected") }.use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val never = CompletableDeferred<RawResponse>()
            val repository = fixture.repository { base, token ->
                RequestOverride(fixture.transport.createApi(fixture.context, base, token)) {
                    entered.complete(Unit)
                    never.await()
                }
            }
            val connection = async { repository.connectQuickConnect(INPUT) }
            withTimeout(15_000) { entered.await() }
            connection.cancel()
            try { connection.await(); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            assertEquals(SessionState.SignedOut, repository.session.value)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun restoredSessionLinkageErrorRemainsRecoverable() = runBlocking(Dispatchers.IO) {
        Fixture { throw AssertionError("No HTTP expected") }.use { fixture ->
            PrivateStorage(fixture.context).saveSession(StoredSession(
                "https://www.mojaloss.stream/jelly/", UUID.randomUUID().toString(), "local-fixture-token",
            ))
            val failure = NoClassDefFoundError("Local restore component fixture")
            val repository = fixture.repository { _, _ -> throw failure }
            repository.refresh()
            assertTrue(repository.session.value is SessionState.Failed)
            assertEquals("initialize_client", fixture.failures.single().first)
            assertSame(failure, fixture.failures.single().second)
        }
    }

    @Test
    fun catalogLinkageFailureOnVisibleDemandRetainsVerifiedSessionAndCanRetry() = runBlocking(Dispatchers.IO) {
        Fixture { request ->
            when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> InterceptedSdkTransport.FixtureResponse(body = SERVER_JSON)
                "/jelly/QuickConnect/Enabled" -> InterceptedSdkTransport.FixtureResponse(body = "true")
                "/jelly/QuickConnect/Initiate" -> InterceptedSdkTransport.FixtureResponse(body = QUICK_CONNECT_JSON.replace("\"Authenticated\":false", "\"Authenticated\":true"))
                "/jelly/Users/AuthenticateWithQuickConnect" -> InterceptedSdkTransport.FixtureResponse(body = """{"User":$USER_JSON,"AccessToken":"local-fixture-token"}""")
                "/jelly/Users/Me" -> InterceptedSdkTransport.FixtureResponse(body = USER_JSON)
                else -> throw AssertionError("Unexpected local catalog fixture endpoint")
            }
        }.use { fixture ->
            val storage = PrivateStorage(fixture.context)
            val failure = NoSuchMethodError("Local catalog component fixture")
            var reachedSavedCatalog = false
            val repository = fixture.repository { base, token ->
                CatalogFailureClient(fixture.transport.createApi(fixture.context, base, token), failure) {
                    assertNotNull(storage.loadSession())
                    reachedSavedCatalog = true
                }
            }
            repository.connectQuickConnect(INPUT)
            // Authentication alone must never start a hidden/default HOME request.
            assertFalse(reachedSavedCatalog)
            assertTrue(repository.session.value is SessionState.Connected)
            assertNotNull(storage.loadSession())
            assertTrue(fixture.failures.isEmpty())
            repository.showCatalog(CatalogQuery())
            assertTrue(reachedSavedCatalog)
            assertTrue(repository.session.value is SessionState.Connected)
            assertNotNull(storage.loadSession())
            assertEquals(ContentStatus.ERROR, repository.catalog.value.status)
            assertFalse(repository.catalog.value.isRefreshing)
            assertTrue(repository.catalog.value.message!!.contains("connection components"))
            assertTrue(repository.catalog.value.titles.isEmpty())
            assertEquals("catalog_refresh", fixture.failures.single().first)
            assertSame(failure, fixture.failures.single().second)
            assertEquals(0, fixture.transport.dnsAttempts.get())
            repository.refresh()
            assertEquals(2, fixture.failures.size)
            assertTrue(repository.session.value is SessionState.Connected)
            assertNotNull(storage.loadSession())
            assertEquals(1, fixture.transport.requestedPaths.count { it == "/jelly/Users/AuthenticateWithQuickConnect" })
        }
    }

    @Test
    fun lateReplacedAttemptCannotOverwriteCurrentConnectionDiagnostics() = runBlocking(Dispatchers.IO) {
        Fixture { InterceptedSdkTransport.FixtureResponse(403, "{}") }.use { fixture ->
            val calls = AtomicInteger()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val repository = fixture.repository { base, token ->
                val sdk = fixture.transport.createApi(fixture.context, base, token)
                if (calls.incrementAndGet() == 1) RequestOverride(sdk) {
                    withContext(NonCancellable) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        throw NoSuchMethodError("Late old component fixture")
                    }
                } else sdk
            }
            val first = async { repository.connectQuickConnect(INPUT) }
            withTimeout(15_000) { firstEntered.await() }
            try {
                repository.connectQuickConnect(INPUT)
                assertTrue(repository.session.value is SessionState.Failed)
                assertTrue(fixture.failures.single().second is InvalidStatusException)
                val currentStages = fixture.stages.toList()
                val currentFailures = fixture.failures.toList()
                releaseFirst.complete(Unit)
                withTimeout(15_000) { first.await() }
                assertEquals(currentStages, fixture.stages.toList())
                assertEquals(currentFailures, fixture.failures.toList())
                assertTrue((repository.session.value as SessionState.Failed).message.contains("not allowed"))
                assertEquals(0, fixture.transport.dnsAttempts.get())
            } finally { releaseFirst.complete(Unit) }
        }
    }

    @Test
    fun otherFatalErrorsAreNotConvertedIntoConnectionFailures() = runBlocking(Dispatchers.IO) {
        Fixture { throw AssertionError("No HTTP expected") }.use { fixture ->
            val fatal = AssertionError("Local fatal error fixture")
            val repository = fixture.repository { _, _ -> throw fatal }
            supervisorScope {
                val connection = async { repository.connectQuickConnect(INPUT) }
                try { connection.await(); fail("Fatal errors must propagate") }
                catch (failure: AssertionError) { assertSame(fatal, failure) }
            }
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private class RequestOverride(
        delegate: ApiClient,
        private val action: suspend () -> RawResponse,
    ) : ForwardingApiClient(delegate) {
        override suspend fun request(method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, requestBody: Any?): RawResponse = action()
    }

    private class CatalogFailureClient(
        delegate: ApiClient,
        private val failure: LinkageError,
        private val beforeFailure: () -> Unit,
    ) : ForwardingApiClient(delegate) {
        override suspend fun request(method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, requestBody: Any?): RawResponse {
            if (pathTemplate == "/Items") { beforeFailure(); throw failure }
            return super.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
        }
    }

    private open class ForwardingApiClient(protected val delegate: ApiClient) : ApiClient() {
        override val baseUrl get() = delegate.baseUrl
        override val accessToken get() = delegate.accessToken
        override val clientInfo get() = delegate.clientInfo
        override val deviceInfo get() = delegate.deviceInfo
        override val httpClientOptions get() = delegate.httpClientOptions
        override val webSocket get() = delegate.webSocket
        override fun update(baseUrl: String?, accessToken: String?, clientInfo: org.jellyfin.sdk.model.ClientInfo, deviceInfo: org.jellyfin.sdk.model.DeviceInfo) =
            delegate.update(baseUrl, accessToken, clientInfo, deviceInfo)
        override suspend fun request(method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>, queryParameters: Map<String, Any?>, requestBody: Any?): RawResponse =
            delegate.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
    }

    private class Fixture(responseFor: (okhttp3.Request) -> InterceptedSdkTransport.FixtureResponse) : Closeable {
        private val rootContext: Context = ApplicationProvider.getApplicationContext()
        private val privateDirectory = File(rootContext.cacheDir, "connection-regression-${UUID.randomUUID()}").apply { mkdirs() }
        val context: Context = object : ContextWrapper(rootContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = privateDirectory
        }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = InterceptedSdkTransport(responseFor)
        val stages = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Pair<String, Throwable>>()
        fun repository(factory: ((String, String?) -> ApiClient)? = null) = JellyfinMediaRepository(
            context, scope,
            onConnectionDiagnostic = { stage, failure -> stages += stage; if (failure != null) failures += stage to failure },
            apiClientFactory = factory ?: { base, token -> transport.createApi(context, base, token) },
        )
        override fun close() {
            scope.cancel()
            transport.close()
            privateDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val INPUT = "https://www.mojaloss.stream/jelly"
        const val SERVER_JSON = """{"Version":"10.11.0","ServerName":"Local fixture","Id":"fixture-server"}"""
        const val USER_JSON = """{"Id":"00000000-0000-0000-0000-000000000001","Name":"Local fixture","HasPassword":false,"HasConfiguredPassword":false,"HasConfiguredEasyPassword":false}"""
        const val QUICK_CONNECT_JSON = """{"Authenticated":false,"Secret":"local-test-secret","Code":"TEST01","DeviceId":"fixture-device","DeviceName":"Local fixture","AppName":"MojaLoss connection regression","AppVersion":"test","DateAdded":"2026-10-13T13:00:00Z"}"""
    }
}
