package com.mojaloss.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/** Native SDK runtime checks. None of these tests contacts the MojaLoss service. */
@RunWith(AndroidJUnit4::class)
class JellyfinSdkConnectionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultAndroidSdkCreatesApiForExactProviderUrlWithoutMakingARequest() = runBlocking(Dispatchers.IO) {
        val jellyfin = createJellyfin {
            this.context = this@JellyfinSdkConnectionTest.context
            clientInfo = ClientInfo("MojaLoss", "0.1.0")
            deviceInfo = DeviceInfo("local-init-regression-device", "Local test fixture")
        }
        val api = jellyfin.createApi(
            baseUrl = ServerUrls.normalize(PROVIDER_INPUT),
            httpClientOptions = HttpClientOptions(
                followRedirects = false,
                requestTimeout = 15.seconds,
                connectTimeout = 6.seconds,
                socketTimeout = 15.seconds,
            ),
        )
        assertEquals("https://www.mojaloss.stream/jelly/", api.baseUrl)
        assertEquals("https://www.mojaloss.stream/jelly/System/Info/Public", api.createUrl("/System/Info/Public"))
        assertFalse(api.httpClientOptions.followRedirects)
        // No SDK request, discovery, socket or authentication operation is invoked here.
    }

    @Test
    fun firstPublicInfoAndQuickConnectCallsUseJellyPrefixAndDecodeOnAndroid() = runBlocking(Dispatchers.IO) {
        InterceptedSdkTransport { request ->
            when (request.url.encodedPath) {
                "/jelly/System/Info/Public" -> InterceptedSdkTransport.FixtureResponse(body = """{"Version":"10.11.0","ServerName":"Local fixture","Id":"fixture-server"}""")
                "/jelly/QuickConnect/Enabled" -> InterceptedSdkTransport.FixtureResponse(body = "true")
                "/jelly/QuickConnect/Initiate" -> InterceptedSdkTransport.FixtureResponse(body = QUICK_CONNECT_JSON)
                else -> throw AssertionError("Unexpected SDK endpoint in local regression")
            }
        }.use { transport ->
            val api = transport.createApi(context, PROVIDER_INPUT)
            assertEquals("10.11.0", api.systemApi.getPublicSystemInfo().content.version)
            assertTrue(api.quickConnectApi.getQuickConnectEnabled().content)
            val connection = api.quickConnectApi.initiateQuickConnect().content
            assertEquals("TEST01", connection.code)
            assertFalse(connection.authenticated)
            assertEquals(listOf("/jelly/System/Info/Public", "/jelly/QuickConnect/Enabled", "/jelly/QuickConnect/Initiate"), transport.requestedPaths.toList())
            assertEquals(listOf("GET", "GET", "POST"), transport.requestedMethods.toList())
            assertEquals(0, transport.dnsAttempts.get())
        }
    }

    @Test
    fun denialIsAnOrdinarySdkExceptionWithNoDnsOrTransport() = runBlocking(Dispatchers.IO) {
        InterceptedSdkTransport { InterceptedSdkTransport.FixtureResponse(403, "{}") }.use { transport ->
            val api = transport.createApi(context, PROVIDER_INPUT)
            val failure = expectFailure<InvalidStatusException> { api.systemApi.getPublicSystemInfo() }
            assertEquals(403, failure.status)
            assertEquals(listOf("/jelly/System/Info/Public"), transport.requestedPaths.toList())
            assertEquals(0, transport.dnsAttempts.get())
        }
    }

    @Test
    fun nonJsonResponseIsAnOrdinarySdkExceptionWithNoDnsOrTransport() = runBlocking(Dispatchers.IO) {
        InterceptedSdkTransport { InterceptedSdkTransport.FixtureResponse(body = "<html>Local denial fixture</html>") }.use { transport ->
            val api = transport.createApi(context, PROVIDER_INPUT)
            expectFailure<InvalidContentException> { api.systemApi.getPublicSystemInfo() }
            assertEquals(0, transport.dnsAttempts.get())
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private companion object {
        const val PROVIDER_INPUT = "https://www.mojaloss.stream/jelly"
        const val QUICK_CONNECT_JSON = """{"Authenticated":false,"Secret":"local-test-secret","Code":"TEST01","DeviceId":"fixture-device","DeviceName":"Local fixture","AppName":"MojaLoss connection regression","AppVersion":"test","DateAdded":"2026-10-13T13:00:00Z"}"""
    }
}
