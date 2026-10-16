package com.mojaloss.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.system.exitProcess

/** Last sanitized failure, stored privately outside Android backup/device transfer. */
class SafeDiagnostics(
    context: Context,
    private val appVersion: String,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "last-connection-diagnostic.txt"))
    private val mutableReport = MutableStateFlow(load() ?: previousAndroidCrash(context))
    val report: StateFlow<String?> = mutableReport.asStateFlow()
    @Volatile private var connectionStage = "idle"
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    /** Normal stage changes stay in memory; only a failure creates a persisted report. */
    fun recordConnection(stage: String, failure: Throwable? = null) {
        connectionStage = SafeDiagnosticFormatter.safeStage(stage)
        if (failure != null) record(failure, uncaught = false)
    }

    @Synchronized fun installUncaughtHandler() {
        if (installedHandler === Thread.getDefaultUncaughtExceptionHandler() && installedHandler != null) return
        val handler = DelegatingDiagnosticCrashHandler(
            previous = Thread.getDefaultUncaughtExceptionHandler(),
            record = { failure -> record(failure, uncaught = true) },
            fallback = { Process.killProcess(Process.myPid()); exitProcess(10) },
        )
        installedHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    @Synchronized private fun record(failure: Throwable, uncaught: Boolean) {
        // Diagnostic collection must never replace the originating failure or prevent Android's
        // crash handling. No exception contents are read even if persistence itself fails.
        try {
            val safe = SafeDiagnosticFormatter.format(appVersion, apiLevel, connectionStage, failure, uncaught)
            mutableReport.value = safe
            val output = file.startWrite()
            try { output.write(safe.toByteArray(Charsets.US_ASCII)); file.finishWrite(output) }
            catch (failureToWrite: Throwable) { file.failWrite(output); throw failureToWrite }
        } catch (_: Throwable) { /* Best effort only; the caller retains the original failure. */ }
    }

    private fun load(): String? = try {
        file.openRead().use { input ->
            val bytes = ByteArray(SafeDiagnosticFormatter.MAX_REPORT_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
            if (count > SafeDiagnosticFormatter.MAX_REPORT_BYTES) null
            else SafeDiagnosticFormatter.restoredReport(String(bytes, 0, count, Charsets.US_ASCII))
        }
    } catch (_: Exception) { null }

    /** No descriptions, process names, traceStream, URLs, or other app/account data are read. */
    private fun previousAndroidCrash(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return null
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            if (exit.reason !in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) return null
            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                else -> "anr"
            }
            SafeDiagnosticFormatter.formatPreviousExit(appVersion, apiLevel, reason, exit.status)
        } catch (_: Exception) { null }
        catch (_: LinkageError) { null } // Optional OEM exit-info support must not block startup.
    }
}

