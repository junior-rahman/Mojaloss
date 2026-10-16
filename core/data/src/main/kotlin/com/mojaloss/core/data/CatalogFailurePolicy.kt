package com.mojaloss.core.data

import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException as SdkTimeoutException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** The SDK can wrap DNS resolution failures in TimeoutException. Inspect causes before classifying. */
internal enum class CatalogNetworkFailure { DNS, CONNECTION, TIMEOUT, SERVER }

internal fun catalogNetworkFailure(error: Throwable): CatalogNetworkFailure? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = mutableListOf<Throwable>()
    var next: Throwable? = error
    while (next != null && causes.size < 16 && seen.add(next)) {
        causes += next
        next = next.cause
    }
    if (causes.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException }) return null
    return when {
        causes.any { it is UnknownHostException } -> CatalogNetworkFailure.DNS
        causes.any { it is ConnectException || it is SocketException } -> CatalogNetworkFailure.CONNECTION
        causes.any { it is SdkTimeoutException || it is SocketTimeoutException || it is CatalogPageTimeout } -> CatalogNetworkFailure.TIMEOUT
        causes.any { it is InvalidStatusException && it.status in 500..599 } -> CatalogNetworkFailure.SERVER
        else -> null
    }
}
