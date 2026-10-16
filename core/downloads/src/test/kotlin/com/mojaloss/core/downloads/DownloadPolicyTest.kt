package com.mojaloss.core.downloads

import com.mojaloss.core.model.SessionScope
import com.mojaloss.core.model.downloadOwnerKey
import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun ownerUsesCanonicalAccountWithoutTransientGeneration() {
        assertEquals(downloadOwnerKey(SessionScope("https://media.example.test/jelly", "a", 999)),
            DownloadPolicy.ownerKey("https://media.example.test/jelly/", "a"))
        assertNotEquals(DownloadPolicy.ownerKey("https://media.example.test/jelly", "a"),
            DownloadPolicy.ownerKey("https://media.example.test/jelly", "b"))
    }
    @Test fun originalMustMatchHttpsOriginExactBasePathAndItem() {
        val server = "https://media.example.test/jelly"
        assertTrue(DownloadPolicy.isSafeOriginal(server, "$server/Items/abc/Download?mediaSourceId=source1", "abc"))
        listOf("http://media.example.test/jelly/Items/abc/Download",
            "https://other.example.test/jelly/Items/abc/Download", "$server/Items/other/Download",
            "https://media.example.test/Items/abc/Download", "$server/Items/abc/Download?api_key=secret",
            "$server/Items/abc/Download?mediaSourceId=s&redirect=https://other.test", "$server/Items/abc/Download#token",
            "https://user:secret@media.example.test/jelly/Items/abc/Download",
            "$server/Items/abc%2fother/Download").forEach { assertFalse(it, DownloadPolicy.isSafeOriginal(server, it, "abc")) }
    }
    @Test fun originalResponseRejectsLoginPagesAndAdaptiveManifests() {
        assertTrue(DownloadPolicy.isProgressiveContentType("video/mp4; charset=binary"))
        assertTrue(DownloadPolicy.isProgressiveContentType("application/octet-stream"))
        assertFalse(DownloadPolicy.isProgressiveContentType("text/html"))
        assertFalse(DownloadPolicy.isProgressiveContentType("application/json"))
        assertFalse(DownloadPolicy.isProgressiveContentType("application/vnd.apple.mpegurl"))
    }
    @Test fun knownLargeFilesCannotTruncateToIntAndExceedQuota() {
        assertFalse(DownloadPolicy.canWrite(0, Long.MAX_VALUE, 6L * 1024 * 1024 * 1024))
        assertTrue(DownloadPolicy.canWrite(0, Long.MAX_VALUE, 6L * 1024 * 1024 * 1024, DownloadPolicy.MAX_QUOTA_BYTES))
    }
    @Test fun validatorsOnlyAcceptStrongEtagOrModifiedDate() {
        assertEquals("\"one\"", DownloadPolicy.validator("\"one\"", "date"))
        assertEquals("date", DownloadPolicy.validator("W/\"weak\"", "date"))
        assertNull(DownloadPolicy.validator("W/\"weak\"", null))
        assertFalse(DownloadPolicy.sameValidator(null, null, null))
        assertFalse(DownloadPolicy.sameValidator("\"one\"", "\"two\"", "date"))
    }
    @Test fun quotaIncludesNextChunkAndPhysicalReserve() {
        val quota = 1024L
        val reserve = DownloadPolicy.DISK_RESERVE_BYTES
        assertTrue(DownloadPolicy.canWrite(1000, reserve + 24, 24, quota))
        assertFalse(DownloadPolicy.canWrite(1000, reserve + 24, 25, quota))
        assertFalse(DownloadPolicy.canWrite(0, reserve + 23, 24, quota))
        assertFalse(DownloadPolicy.canWrite(Long.MAX_VALUE, Long.MAX_VALUE, 24, quota))
    }
    @Test fun sourceFingerprintCannotSpliceChangedOriginalOrOwners() {
        assertNotEquals(DownloadPolicy.fingerprint("url", "source", 123), DownloadPolicy.fingerprint("url", "source", 124))
        assertNotEquals(DownloadPolicy.fingerprint("url", "source", 123), DownloadPolicy.fingerprint("url", "new", 123))
        assertNotEquals(DownloadPolicy.id("ownerA", "item", "source"), DownloadPolicy.id("ownerB", "item", "source"))
    }
}
