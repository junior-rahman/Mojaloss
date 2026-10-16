package com.mojaloss.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DelegatingDiagnosticCrashHandlerTest {
    @Test fun recordsThenDelegatesOriginalThrowableAndThread() {
        val calls = mutableListOf<String>()
        val original = NoClassDefFoundError("private")
        val thread = Thread.currentThread()
        val handler = DelegatingDiagnosticCrashHandler(
            previous = Thread.UncaughtExceptionHandler { actualThread, actualFailure ->
                calls += "delegate"
                assertSame(thread, actualThread)
                assertSame(original, actualFailure)
            },
            record = { assertSame(original, it); calls += "record" },
            fallback = { fail("Previous OS handler must be used") },
        )
        handler.uncaughtException(thread, original)
        assertEquals(listOf("record", "delegate"), calls)
    }

    @Test fun diagnosticCollectionFailureCannotSwallowOriginalFatalError() {
        val original = LinkageError("private")
        var delegated: Throwable? = null
        val handler = DelegatingDiagnosticCrashHandler(
            previous = Thread.UncaughtExceptionHandler { _, failure -> delegated = failure },
            record = { throw IllegalStateException("diagnostic persistence failed") },
            fallback = { fail("Previous OS handler must be used") },
        )
        handler.uncaughtException(Thread.currentThread(), original)
        assertSame(original, delegated)
    }

    @Test fun missingPreviousHandlerInvokesFatalFallback() {
        var fallbackCalled = false
        val handler = DelegatingDiagnosticCrashHandler(null, { }, { fallbackCalled = true })
        handler.uncaughtException(Thread.currentThread(), LinkageError("private"))
        assertTrue(fallbackCalled)
    }
}
