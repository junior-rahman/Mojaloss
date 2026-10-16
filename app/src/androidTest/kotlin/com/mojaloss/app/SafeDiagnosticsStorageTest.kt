package com.mojaloss.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.mojaloss.app.diagnostics.SafeDiagnosticFormatter
import com.mojaloss.app.diagnostics.SafeDiagnostics
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Private persistence only: no repository, login, or service traffic. */
class SafeDiagnosticsStorageTest {
    @Test fun persistedReportRestoresWithoutMessagesCredentialsOrCodes() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.noBackupFilesDir, "diagnostics-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val diagnostics = SafeDiagnostics(context, "0.1.1 (2)", 36)
            diagnostics.recordConnection("initialize_client", NoClassDefFoundError("https://private/?token=secret 482911 password=private"))
            val copyPayload = requireNotNull(diagnostics.report.value)
            assertTrue(copyPayload.contains("stage=initialize_client"))
            assertFalse(copyPayload.contains("secret"))
            assertFalse(copyPayload.contains("482911"))
            assertFalse(copyPayload.contains("private"))
            val stored = File(directory, "last-connection-diagnostic.txt")
            assertTrue(stored.length() <= SafeDiagnosticFormatter.MAX_REPORT_BYTES)
            assertTrue(stored.canonicalPath.startsWith(target.noBackupFilesDir.canonicalPath + File.separator))
            assertEquals(copyPayload, SafeDiagnostics(context, "0.1.1 (2)", 36).report.value)
        } finally { directory.deleteRecursively() }
    }
}
