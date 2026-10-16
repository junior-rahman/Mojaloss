package com.mojaloss.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import org.slf4j.spi.SLF4JServiceProvider
import java.util.ServiceLoader

/** Checks the Android runtime provider and service resource, including secret-safe SDK logging. */
@RunWith(AndroidJUnit4::class)
class SdkLoggingPolicyTest {
    @Test
    fun packagedProviderDiscardsSdkRequestAndResponseLogs() {
        val providers = ServiceLoader.load(
            SLF4JServiceProvider::class.java,
            LoggerFactory::class.java.classLoader,
        ).map { it.javaClass.name }

        // A NOP fallback with a missing provider resource is insufficient: verify discovery too.
        assertEquals(listOf("org.slf4j.nop.NOPServiceProvider"), providers)
        assertEquals("org.slf4j.helpers.NOPLoggerFactory", LoggerFactory.getILoggerFactory().javaClass.name)

        listOf(
            "org.jellyfin.sdk.api.okhttp.OkHttpClient",
            "org.jellyfin.sdk.api.client.RawResponse",
        ).forEach { name ->
            val logger = LoggerFactory.getLogger(name)
            assertFalse(logger.isTraceEnabled)
            assertFalse(logger.isDebugEnabled)
            assertFalse(logger.isInfoEnabled)
            assertFalse(logger.isWarnEnabled)
            assertFalse(logger.isErrorEnabled)
        }
    }
}
