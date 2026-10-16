package com.mojaloss.core.player

import java.net.URI
import java.util.Locale

/** Every media resource must remain on the authenticated provider's HTTPS origin. */
internal class MediaOriginPolicy(serverUrl: String) {
    private data class Origin(val host: String, val port: Int)
    private val permitted = origin(serverUrl)

    fun allows(resourceUrl: String): Boolean = permitted != null && origin(resourceUrl) == permitted

    private fun origin(value: String): Origin? = runCatching {
        val uri = URI(value)
        val host = uri.host ?: return@runCatching null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            uri.rawFragment != null || uri.port > 65_535) return@runCatching null
        Origin(host.lowercase(Locale.ROOT), if (uri.port == -1) 443 else uri.port)
    }.getOrNull()
}
