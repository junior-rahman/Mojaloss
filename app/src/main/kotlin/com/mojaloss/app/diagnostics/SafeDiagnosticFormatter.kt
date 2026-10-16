package com.mojaloss.app.diagnostics

import java.util.IdentityHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException

/** Only explicitly safe code metadata enters a report; exception text is never inspected. */
object SafeDiagnosticFormatter {
    const val MAX_REPORT_BYTES = 16_000
    private const val MAX_CAUSES = 8
    private const val MAX_FRAMES = 12
    private const val HEADER = "MojaLoss sanitized diagnostics"
    private val versionPattern = Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}(?: \\([0-9]{1,9}\\))?")
    private val typePattern = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,159}")
    private val stages = setOf(
        "idle", "validate_server", "initialize_client", "server_info",
        "quick_connect_enabled", "quick_connect_request", "quick_connect_wait",
        "quick_connect_authenticate", "password_authenticate", "verify_account",
        "save_session", "catalog_refresh", "home_recent_uploads", "home_language_shelf", "connection_ready", "connection_failed",
        "connection_cancelled", "unknown",
    )
    private val typePrefixes = listOf(
        "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.",
        "org.jellyfin.", "io.ktor.", "okhttp3.", "okio.", "com.mojaloss.",
    )
    private val catalogFields = setOf("Items", "TotalRecordCount", "StartIndex", "Id", "Type",
        "Key", "ItemId", "PlaybackPositionTicks", "PlayCount", "IsFavorite", "Played")
    private val appSources = mapOf(
        "com.mojaloss.app.MainActivity" to "app:MainActivity",
        "com.mojaloss.app.MainActivityKt" to "app:MainActivity",
        "com.mojaloss.app.AppViewModel" to "app:AppViewModel",
        "com.mojaloss.app.MojaLossApplication" to "app:Application",
        "com.mojaloss.core.data.JellyfinMediaRepository" to "data:JellyfinMediaRepository",
        "com.mojaloss.core.data.PrivateStorage" to "data:PrivateStorage",
        "com.mojaloss.core.data.ServerUrls" to "data:ServerUrls",
        "com.mojaloss.app.feature.ui.FeatureScreensKt" to "ui:ConnectScreen",
    )
    private val librarySources = listOf(
        "org.jellyfin.sdk." to "jellyfin-sdk", "io.ktor.client." to "ktor-client",
        "io.ktor." to "ktor", "okhttp3." to "okhttp", "okio." to "okio",
        "kotlinx.coroutines." to "coroutines", "kotlin." to "kotlin",
        "androidx.compose." to "compose", "androidx.lifecycle." to "lifecycle",
        "androidx." to "androidx", "android." to "android", "java." to "java",
        "javax." to "javax",
    )
    private val methods = setOf(
        "connectQuick", "connectPassword", "connectQuickConnect", "connect",
        "api", "checkServerVersion", "refresh", "restore", "cancelConnection",
        "publishAttempt", "ensureAttempt", "deviceId", "newDeviceId", "saveSession",
        "loadSession", "clearSession", "write", "key", "normalize", "onCreate",
        "ConnectScreen", "MojaLossRoot", "invoke", "invokeSuspend", "create",
        "getCurrentUser", "authenticateUserByName", "authenticateWithQuickConnect",
        "getPublicSystemInfo", "getQuickConnectEnabled", "initiateQuickConnect",
        "getQuickConnectState", "unknown",
    )
    private val sourceLabels = (appSources.values + librarySources.map { it.second } + "other").toSet()

    fun safeStage(stage: String): String = stage.takeIf { it in stages } ?: "unknown"

    /** Android exit metadata is deliberately limited to numeric reason/status and fixed labels. */
    fun formatPreviousExit(appVersion: String, apiLevel: Int, reason: String, status: Int): String {
        val safeReason = reason.takeIf { it in setOf("crash", "native_crash", "anr") } ?: "unknown"
        return listOf(
            HEADER, "format=1",
            "app=${appVersion.takeIf { versionPattern.matches(it) } ?: "unknown"}",
            "android_api=${apiLevel.takeIf { it in 1..999 } ?: 0}",
            "stage=unknown", "fatal=true", "source=android_exit_info",
            "version_context=current_install_previous_exit_version_unknown",
            "exit_reason=$safeReason", "exit_status=$status", "END",
        ).joinToString("\n")
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun format(
        appVersion: String,
        apiLevel: Int,
        stage: String,
        failure: Throwable?,
        uncaught: Boolean = false,
    ): String {
        val lines = mutableListOf(
            HEADER, "format=1",
            "app=${appVersion.takeIf { versionPattern.matches(it) } ?: "unknown"}",
            "android_api=${apiLevel.takeIf { it in 1..999 } ?: 0}",
            "stage=${safeStage(stage)}", "fatal=$uncaught",
        )
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current = failure
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (seen.put(current, true) != null) { lines += "cause_cycle=true"; current = null; break }
            val type = current.javaClass.name
            lines += "failure[$depth]=${safeType(type)}"
            if (current is MissingFieldException) {
                val safeFields = current.missingFields.mapNotNull { field ->
                    catalogFields.firstOrNull { it.equals(field, ignoreCase = true) }
                }.distinct().sorted()
                if (safeFields.isNotEmpty()) lines += "missing_fields[$depth]=${safeFields.joinToString(",")}"
            }
            val frames = try { current.stackTrace } catch (_: Throwable) { lines += "trace_unavailable=true"; emptyArray() }
            frames.take(MAX_FRAMES).forEachIndexed { index, frame ->
                val source = sourceLabel(frame.className)
                val method = frame.methodName.takeIf { it in methods } ?: "unknown"
                val line = frame.lineNumber.takeIf { it in 0..1_000_000 } ?: -1
                lines += "frame[$index]=$source/$method:$line"
            }
            if (frames.size > MAX_FRAMES) lines += "omitted_frames=${frames.size - MAX_FRAMES}"
            current = try { current.cause } catch (_: Throwable) { lines += "cause_unavailable=true"; null }
            depth++
        }
        if (current != null) lines += "causes_truncated=true"
        lines += "END"
        // All output is ASCII from fixed labels or validated identifiers. Characters = bytes.
        return lines.joinToString("\n").take(MAX_REPORT_BYTES)
    }

    /** Reject a corrupt or non-format file rather than exposing it as a copyable report. */
    fun restoredReport(value: String): String? {
        if (value.length > MAX_REPORT_BYTES || value.any { it.code !in 10..126 }) return null
        val lines = value.split('\n')
        if (lines.size < 7 || lines[0] != HEADER || lines[1] != "format=1" || lines.last() != "END") return null
        if (lines[2] != "app=unknown" && !versionPattern.matches(lines[2].removePrefix("app="))) return null
        if (!lines[2].startsWith("app=") || !lines[3].matches(Regex("android_api=[0-9]{1,3}"))) return null
        if (!lines[4].startsWith("stage=") || lines[4].removePrefix("stage=") !in stages) return null
        if (lines[5] !in setOf("fatal=true", "fatal=false")) return null
        if (!lines.subList(6, lines.lastIndex).all(::safeMetadataLine)) return null
        return value
    }

    private fun safeType(value: String): String = if (typePattern.matches(value) && typePrefixes.any(value::startsWith)) value else "unrecognized_failure_type"
    private fun sourceLabel(value: String): String {
        appSources.entries.firstOrNull { value == it.key || value.startsWith(it.key + "$" ) }?.let { return it.value }
        return librarySources.firstOrNull { value.startsWith(it.first) }?.second ?: "other"
    }
    private fun safeMetadataLine(value: String): Boolean {
        if (value in setOf("cause_cycle=true", "causes_truncated=true", "trace_unavailable=true", "cause_unavailable=true")) return true
        if (value in setOf("source=android_exit_info", "version_context=current_install_previous_exit_version_unknown", "exit_reason=crash", "exit_reason=native_crash", "exit_reason=anr", "exit_reason=unknown")) return true
        Regex("exit_status=-?[0-9]{1,10}").matchEntire(value)?.let { return value.removePrefix("exit_status=").toIntOrNull() != null }
        Regex("failure\\[[0-7]]=(.+)").matchEntire(value)?.let { return safeType(it.groupValues[1]) == it.groupValues[1] || it.groupValues[1] == "unrecognized_failure_type" }
        Regex("missing_fields\\[[0-7]]=(.+)").matchEntire(value)?.let {
            val names = it.groupValues[1].split(',')
            return names.size in 1..catalogFields.size && names.distinct().size == names.size && names.all { name -> name in catalogFields }
        }
        if (value.matches(Regex("omitted_frames=[0-9]{1,10}"))) return true
        val frame = Regex("frame\\[(?:[0-9]|1[01])]=([^/]+)/([^:]+):(-?[0-9]{1,7})").matchEntire(value) ?: return false
        return frame.groupValues[1] in sourceLabels && frame.groupValues[2] in methods &&
            frame.groupValues[3].toIntOrNull()?.let { it in -1..1_000_000 } == true
    }
}
