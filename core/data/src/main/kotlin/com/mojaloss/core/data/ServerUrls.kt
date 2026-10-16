package com.mojaloss.core.data

import java.net.URI

internal object ServerUrls {
    fun normalize(value: String): String {
        val url = URI(value.trim())
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.query == null && url.fragment == null)
        return url.toString().trimEnd('/') + "/"
    }
    fun sameOriginResource(base: String, resource: String): String {
        val origin = URI(base)
        val reference = URI(resource)
        val result = if (reference.isAbsolute) reference else {
            val prefix = origin.path.trimEnd('/')
            if (resource.startsWith("$prefix/") && prefix.isNotEmpty()) origin.resolve(reference)
            else URI(base + resource.trimStart('/'))
        }
        require(result.scheme == "https" && result.host.equals(origin.host, ignoreCase = true) &&
            result.port == origin.port && result.userInfo == null) { "The service returned an unsupported external stream." }
        return result.toString()
    }
}
