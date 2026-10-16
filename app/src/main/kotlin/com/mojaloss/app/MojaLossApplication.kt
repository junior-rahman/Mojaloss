package com.mojaloss.app

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.mojaloss.core.data.JellyfinMediaRepository
import androidx.media3.common.util.UnstableApi
import com.mojaloss.core.downloads.DownloadRuntimeProvider
import com.mojaloss.core.downloads.OfflineDownloads
import com.mojaloss.app.diagnostics.SafeDiagnostics
import com.mojaloss.app.update.AppUpdateManager
import com.mojaloss.core.model.SessionState
import com.mojaloss.core.model.SessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.File
import java.net.URI
import java.security.MessageDigest

@UnstableApi
class MojaLossApplication : Application(), SingletonImageLoader.Factory, DownloadRuntimeProvider {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val diagnostics by lazy { SafeDiagnostics(this, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
    val repository by lazy { JellyfinMediaRepository(this, appScope, onConnectionDiagnostic = diagnostics::recordConnection) }
    val appUpdater by lazy { AppUpdateManager(this, appScope, owner = "JuelHossain", repo = "MojaLoss") }

    override val offlineDownloads by lazy { OfflineDownloads(this, appScope, repository::downloadAuthorization) }

    override fun onCreate() {
        super.onCreate()
        diagnostics.installUncaughtHandler()
        // A scheduled download may start the process before an Activity/ViewModel exists.
        // Restore only the saved account; visible screens still own all catalog requests.
        appScope.launch {
            repository.session.collect { state ->
                offlineDownloads.setOwner(when (state) {
                    is SessionState.Connected -> state.scope
                    is SessionState.Restoring -> state.scope
                    else -> null
                })
            }
        }
        appScope.launch {
            try { repository.initialize() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Repository exposes recoverable connection state. */ }
        }
    }

    /** Retain same-account artwork across launches, and purge it when account identity changes. */
    @Synchronized fun updateArtworkAccount(scope: SessionScope?) {
        val currentScope = when (val current = repository.session.value) {
            is SessionState.Connected -> current.scope
            is SessionState.Restoring -> current.scope
            else -> null
        }
        if (scope != currentScope) return
        try {
        val file = AtomicFile(File(noBackupFilesDir, "artwork-account-scope"))
        val identity = scope?.let {
            MessageDigest.getInstance("SHA-256").digest("${it.serverUrl}\n${it.accountId}".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        val previous = runCatching { file.openRead().use { input ->
            val bytes = ByteArray(65)
            val count = input.read(bytes)
            if (count == 64) String(bytes, 0, count, Charsets.US_ASCII) else null
        } }.getOrNull()
        if (previous == identity) return
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        if (identity == null) file.delete()
        else {
            val output = file.startWrite()
            try { output.write(identity.toByteArray()); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
        } catch (_: Exception) { /* Artwork cache housekeeping must not block account or UI state. */ }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val authorized = request.header("X-Emby-Token") != null || request.header("Authorization") != null
                if (authorized) {
                    val account = repository.session.value as? SessionState.Connected
                    val origin = account?.scope?.serverUrl?.let { runCatching { URI(it) }.getOrNull() }
                    val expectedPort = if (origin?.port == -1) 443 else origin?.port
                    if (origin?.scheme != "https" || request.url.scheme != "https" ||
                        !origin.host.equals(request.url.host, ignoreCase = true) || expectedPort != request.url.port)
                        throw IOException("Artwork connection changed. Reopen the title.")
                }
                chain.proceed(request)
            }.build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}

