package com.mojaloss.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlsTest {
    @Test fun serverBasePathIsPreservedForRelativeResources() {
        assertEquals("https://example.com/jellyfin/", ServerUrls.normalize("https://example.com/jellyfin"))
        assertEquals("https://example.com/jellyfin/Videos/one/master.m3u8", ServerUrls.sameOriginResource("https://example.com/jellyfin/", "/Videos/one/master.m3u8"))
        assertEquals("https://example.com/jellyfin/Videos/one/master.m3u8", ServerUrls.sameOriginResource("https://example.com/jellyfin/", "/jellyfin/Videos/one/master.m3u8"))
    }
    @Test fun externalCredentialBearingResourcesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.sameOriginResource("https://example.com/", "https://other.example/video") }
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.sameOriginResource("https://example.com/", "https://user@example.com/video") }
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("http://example.com/") }
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("https://example.com/?api_key=secret") }
    }
}
