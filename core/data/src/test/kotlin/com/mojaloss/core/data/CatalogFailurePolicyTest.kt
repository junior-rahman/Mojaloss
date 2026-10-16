package com.mojaloss.core.data

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class CatalogFailurePolicyTest {
    @Test fun nestedDnsResolutionIsNotMisreportedAsTimeout() {
        val timeout = SocketTimeoutException("test wrapper").apply { initCause(UnknownHostException("test host")) }
        assertEquals(CatalogNetworkFailure.DNS, catalogNetworkFailure(RuntimeException(timeout)))
    }
    @Test fun connectionAndTimeoutFailuresAreEligibleForBoundedRecovery() {
        assertEquals(CatalogNetworkFailure.CONNECTION, catalogNetworkFailure(RuntimeException(ConnectException())))
        assertEquals(CatalogNetworkFailure.TIMEOUT, catalogNetworkFailure(SocketTimeoutException()))
    }
    @Test fun certificateAndSchemaFailuresMustNotReceiveNetworkRetry() {
        assertNull(catalogNetworkFailure(SSLHandshakeException("invalid certificate")))
        assertNull(catalogNetworkFailure(SocketTimeoutException().apply { initCause(SSLHandshakeException("invalid certificate")) }))
        assertNull(catalogNetworkFailure(IllegalArgumentException("bad schema")))
    }
    @Test fun malformedCyclicCauseCannotLoopForever() {
        val first = RuntimeException(); val second = RuntimeException()
        first.initCause(second); second.initCause(first)
        assertNull(catalogNetworkFailure(first))
    }
}
