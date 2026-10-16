package com.mojaloss.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException

class SafeDiagnosticFormatterTest {
    @Test fun languageShelfFailureNamesItsFixedStageWithoutLeakingServerCategoryOrCredentials() {
        val report = SafeDiagnosticFormatter.format("0.1.8 (9)", 37, "home_language_shelf",
            IllegalArgumentException("Hindi private-library https://private/?api_key=secret-token"))
        assertTrue(report.contains("stage=home_language_shelf"))
        assertTrue(report.contains("failure[0]=java.lang.IllegalArgumentException"))
        listOf("Hindi", "private-library", "https", "api_key", "secret-token").forEach { assertFalse(report.contains(it)) }
        assertEquals(report, SafeDiagnosticFormatter.restoredReport(report))
    }
    @Test fun recentUploadFailureIdentifiesItsSectionWithoutExposingNetworkDetails() {
        val report = SafeDiagnosticFormatter.format("0.1.7 (8)", 37, "home_recent_uploads",
            java.net.UnknownHostException("https://private-server/?api_key=secret-token"))
        assertTrue(report.contains("stage=home_recent_uploads"))
        assertTrue(report.contains("failure[0]=java.net.UnknownHostException"))
        listOf("https", "private-server", "api_key", "secret-token").forEach { assertFalse(report.contains(it)) }
        assertEquals(report, SafeDiagnosticFormatter.restoredReport(report))
    }
    @OptIn(ExperimentalSerializationApi::class)
    @Test fun catalogSchemaReportContainsOnlyAllowedMissingFieldNames() {
        val failure = IllegalStateException("private response", MissingFieldException(
            listOf("ItemId", "Id", "secret-token", "482911", "https://private/"), "private schema"))
        val report = SafeDiagnosticFormatter.format("0.1.2 (3)", 36, "catalog_refresh", failure)
        assertTrue(report.contains("missing_fields[1]=Id,ItemId"))
        listOf("private", "secret-token", "482911", "https").forEach { assertFalse(report.contains(it)) }
        assertEquals(report, SafeDiagnosticFormatter.restoredReport(report))
        assertNull(SafeDiagnosticFormatter.restoredReport(report.replace("Id,ItemId", "Id,secret-token")))
    }
    @Test fun exceptionMessagesAndUntrustedTraceTextNeverEnterReport() {
        val failure = NoClassDefFoundError("https://www.mojaloss.stream/jelly Authorization: secret-token password=private 482911")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.mojaloss.core.data.JellyfinMediaRepository", "api", "user_password=private", 88),
            StackTraceElement("https://www.mojaloss.stream/jelly", "X-Emby-Token=secret-token", "482911", 21),
            StackTraceElement("io.ktor.client.secret_token", "password_private", "https://private/", 29),
        )
        val report = SafeDiagnosticFormatter.format("0.1.1 (2)", 36, "initialize_client", failure)
        assertTrue(report.contains("failure[0]=java.lang.NoClassDefFoundError"))
        assertTrue(report.contains("data:JellyfinMediaRepository/api:88"))
        assertTrue(report.contains("ktor-client/unknown:29"))
        listOf("https", "mojaloss.stream", "Authorization", "X-Emby-Token", "secret-token", "secret_token", "password", "private", "482911").forEach {
            assertFalse("Unexpected sensitive text: $it", report.contains(it))
        }
        assertEquals(report, SafeDiagnosticFormatter.restoredReport(report))
    }

    @Test fun formatterDoesNotReadThrowableMessageOrToString() {
        val failure = object : RuntimeException() {
            override val message: String get() = error("message must not be read")
            override fun toString(): String = error("toString must not be read")
        }
        val report = SafeDiagnosticFormatter.format("0.1.1", 36, "server_info", failure)
        assertTrue(report.startsWith("MojaLoss sanitized diagnostics"))
        assertTrue(report.endsWith("END"))
    }

    @Test fun invalidStageAndVersionCannotLeakArbitraryInput() {
        val report = SafeDiagnosticFormatter.format("https://private/?token=secret", 36, "Authorization: secret 482911", IllegalArgumentException("credential"))
        assertTrue(report.contains("app=unknown"))
        assertTrue(report.contains("stage=unknown"))
        listOf("https", "private", "secret", "482911", "Authorization", "credential").forEach { assertFalse(report.contains(it)) }
    }

    @Test fun causeCyclesAndLongTraceAreBounded() {
        val first = IllegalStateException("private-first")
        val second = IllegalArgumentException("private-second")
        first.initCause(second)
        second.initCause(first)
        first.stackTrace = Array(5_000) { StackTraceElement("com.mojaloss.app.AppViewModel", "connectQuick", "secret-file", it) }
        val report = SafeDiagnosticFormatter.format("0.1.1", 36, "server_info", first)
        assertTrue(report.contains("cause_cycle=true"))
        assertTrue(report.contains("omitted_frames=4988"))
        assertFalse(report.contains("frame[12]"))
        assertTrue(report.toByteArray(Charsets.US_ASCII).size <= SafeDiagnosticFormatter.MAX_REPORT_BYTES)
        assertEquals(report, SafeDiagnosticFormatter.restoredReport(report))
    }

    @Test fun deeplyNestedCausesAreBoundedAndCorruptStoredReportIsRejected() {
        var failure: Throwable = IllegalStateException("private")
        repeat(100) { failure = IllegalStateException("private", failure) }
        val report = SafeDiagnosticFormatter.format("0.1.1", 36, "initialize_client", failure, uncaught = true)
        assertTrue(report.contains("causes_truncated=true"))
        assertFalse(report.contains("failure[8]"))
        assertTrue(report.length <= SafeDiagnosticFormatter.MAX_REPORT_BYTES)
        assertNull(SafeDiagnosticFormatter.restoredReport(report.replace("END", "Authorization: private\nEND")))
        assertNull(SafeDiagnosticFormatter.restoredReport("private".repeat(3_000)))
    }

    @Test fun androidExitSummaryContainsOnlyFixedReasonAndNumericStatus() {
        val report = SafeDiagnosticFormatter.formatPreviousExit("0.1.1 (2)", 36, "native_crash", 11)
        assertTrue(report.contains("source=android_exit_info"))
        assertTrue(report.contains("exit_reason=native_crash"))
        assertTrue(report.contains("exit_status=11"))
        assertEquals(report, SafeDiagnosticFormatter.restoredReport(report))
        val untrusted = SafeDiagnosticFormatter.formatPreviousExit("0.1.1", 36, "token=secret 482911", 0)
        assertTrue(untrusted.contains("exit_reason=unknown"))
        assertFalse(untrusted.contains("secret"))
        assertFalse(untrusted.contains("482911"))
    }
}
