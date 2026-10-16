package com.mojaloss.core.data

import android.content.Context
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Actual SDK/OkHttp code, with every response supplied before DNS or transport. */
internal class InterceptedSdkTransport(
    private val responseFor: (Request) -> FixtureResponse,
) : Closeable {
    data class FixtureResponse(val status: Int = 200, val body: String)

    val requestedPaths = CopyOnWriteArrayList<String>()
    val requestedMethods = CopyOnWriteArrayList<String>()
    val dnsAttempts = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(Dns {
            dnsAttempts.incrementAndGet()
            throw AssertionError("Connection regression tests must never resolve a host")
        })
        .addInterceptor { chain ->
            // Do not call chain.proceed: even the real provider hostname remains entirely local.
            val request = chain.request()
            check(request.url.scheme == "https")
            check(request.url.host == "www.mojaloss.stream")
            requestedPaths += request.url.encodedPath
            requestedMethods += request.method
            val fixture = responseFor(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(fixture.status)
                .message("Local test fixture")
                .body(fixture.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    fun createApi(context: Context, inputUrl: String, accessToken: String? = null): ApiClient {
        val factory = OkHttpFactory(client)
        val jellyfin = createJellyfin {
            this.context = context.applicationContext
            clientInfo = ClientInfo("MojaLoss connection regression", "test")
            deviceInfo = DeviceInfo("local-regression-device", "Local test fixture")
            apiClientFactory = factory
            socketConnectionFactory = factory
        }
        return jellyfin.createApi(
            baseUrl = ServerUrls.normalize(inputUrl),
            accessToken = accessToken,
            httpClientOptions = HttpClientOptions(
                followRedirects = false,
                requestTimeout = 15.seconds,
                connectTimeout = 6.seconds,
                socketTimeout = 15.seconds,
            ),
        )
    }

    override fun close() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
