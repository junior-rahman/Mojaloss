package com.mojaloss.app.diagnostics

/** Pure delegation policy is testable without Android or terminating the test process. */
internal class DelegatingDiagnosticCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val record: (Throwable) -> Unit,
    private val fallback: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try { record(failure) } catch (_: Throwable) { /* Still delegate the original fatal error. */ }
        if (previous != null) previous.uncaughtException(thread, failure) else fallback()
    }
}
