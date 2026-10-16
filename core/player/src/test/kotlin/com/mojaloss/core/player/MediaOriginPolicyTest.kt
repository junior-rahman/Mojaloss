package com.mojaloss.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOriginPolicyTest {
    private val policy = MediaOriginPolicy("https://media.example.test/jellyfin")

    @Test fun allowsSameOriginStreamSubtitleSegmentAndKeyPaths() {
        listOf("/Videos/1/stream.mp4", "/Videos/1/Subtitles/0/stream.vtt",
            "/Videos/1/hls/segment01.ts", "/keys/key01").forEach {
            assertTrue(policy.allows("https://media.example.test$it"))
        }
    }

    @Test fun absoluteForeignManifestChildrenAreRejected() {
        listOf("https://cdn.example.test/segment.ts", "https://evil.test/key",
            "https://media.example.test.evil.test/subtitle.vtt").forEach {
            assertFalse(policy.allows(it))
        }
    }

    @Test fun rejectsSchemeChangesLocalUrisUserInfoAndMalformedOrigins() {
        listOf("http://media.example.test/segment.ts", "file:///etc/passwd", "data:text/plain,key",
            "https://user:secret@media.example.test/segment.ts", "//media.example.test/segment.ts",
            "https://media.example.test:99999/video", "https://media.example.test/video#fragment").forEach {
            assertFalse(policy.allows(it))
        }
    }

    @Test fun normalizesDefaultHttpsPortAndHostCaseButRejectsOtherPorts() {
        assertTrue(policy.allows("https://MEDIA.EXAMPLE.TEST:443/video"))
        assertFalse(policy.allows("https://media.example.test:8443/video"))
        val custom = MediaOriginPolicy("https://media.example.test:8443/jellyfin")
        assertTrue(custom.allows("https://media.example.test:8443/video"))
        assertFalse(custom.allows("https://media.example.test/video"))
    }

    @Test fun invalidServerOriginFailsClosed() {
        assertFalse(MediaOriginPolicy("http://media.example.test").allows("https://media.example.test/video"))
        assertFalse(MediaOriginPolicy("not a URL").allows("https://media.example.test/video"))
    }
}
