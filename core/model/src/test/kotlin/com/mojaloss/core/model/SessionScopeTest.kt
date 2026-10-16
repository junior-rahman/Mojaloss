package com.mojaloss.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionScopeTest {
    @Test
    fun scopedKeysDistinguishAnotherServerAnotherAccountAndReauthentication() {
        val original = SessionScope("https://server-one.example/jellyfin", "viewer", 1)
        val anotherServer = original.copy(serverUrl = "https://server-two.example/jellyfin")
        val anotherAccount = original.copy(accountId = "other-viewer")
        val reauthenticated = original.copy(generation = 2)
        val scopedEntries = mapOf(
            original to "original",
            anotherServer to "another server",
            anotherAccount to "another account",
            reauthenticated to "new session"
        )

        assertEquals("All three session boundaries must survive map-key identity", 4, scopedEntries.size)
        assertEquals("original", scopedEntries[SessionScope(original.serverUrl, original.accountId, original.generation)])
        assertEquals("another server", scopedEntries[anotherServer])
        assertEquals("another account", scopedEntries[anotherAccount])
        assertEquals("new session", scopedEntries[reauthenticated])
    }
}
