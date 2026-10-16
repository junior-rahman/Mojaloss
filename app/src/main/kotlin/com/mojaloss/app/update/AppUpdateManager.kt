package com.mojaloss.app.update

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Explicit, foreground-only updates. The app never sends its server credentials to GitHub. */
class AppUpdateManager(
    context: Context,
    private val scope: CoroutineScope,
    private val owner: String = "JuelHossain",
    private val repo: String = "Mojaloss",
) {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val connection = AtomicReference<HttpURLConnection?>(null)
    private val directory get() = File(app.cacheDir, "updates")
    private val installed get() = packageInfo(app.packageName)
    private val _state = MutableStateFlow(AppUpdateState(currentVersion = installed.versionName.orEmpty()))
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    @Volatile private var verifiedFile: File? = null

    /** Fetch a tiny public release description only when the customer asks. */
    @Synchronized fun check() {
        if (operation?.isActive == true) return
        val previous = operation
        val token = ++generation
        _state.value = AppUpdateState(AppUpdatePhase.CHECKING, installed.versionName.orEmpty())
        operation = scope.launch(Dispatchers.IO) {
            // A cancelled read may still be closing. Never let its cleanup overlap a new transfer.
            previous?.join()
            coroutineContext.ensureActive()
            try {
                // Keep a completed APK across process recreation; fresh release metadata
                // and all identity checks must still agree before it can be installed.
                clearFiles(keepApks = true)
                require(AppUpdatePolicy.validComponent(owner) && AppUpdatePolicy.validComponent(repo)) { "The update source is not configured." }
                val release = withTimeout(60_000) { latestRelease() }
                coroutineContext.ensureActive()
                if (release.versionCode <= installed.code()) {
                    clearFiles()
                    publish(token, AppUpdateState(AppUpdatePhase.UP_TO_DATE, installed.versionName.orEmpty(), message = "You have the latest version."))
                } else {
                    AppUpdatePolicy.validate(release, app.packageName, installed.code(), Build.VERSION.SDK_INT)
                    val recovered = recoverVerifiedFile(release)
                    synchronized(this@AppUpdateManager) { if (generation == token) verifiedFile = recovered }
                    publish(token, AppUpdateState(if (recovered != null) AppUpdatePhase.READY else AppUpdatePhase.AVAILABLE,
                        installed.versionName.orEmpty(), release, downloadedBytes = if (recovered != null) release.size else 0,
                        totalBytes = release.size, message = if (recovered != null) "Your saved update is ready to install." else null))
                }
            } catch (_: TimeoutCancellationException) {
                publish(token, _state.value.copy(phase = AppUpdatePhase.ERROR, message = "The update check took too long. Please try again."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publish(token, _state.value.copy(phase = AppUpdatePhase.ERROR, message = safeMessage(failure)))
            }
        }
    }

    @Synchronized fun download() {
        if (operation?.isActive == true) return
        val release = _state.value.release ?: return
        val previous = operation
        val token = ++generation
        _state.value = _state.value.copy(phase = AppUpdatePhase.DOWNLOADING, downloadedBytes = 0, totalBytes = release.size, message = null)
        operation = scope.launch(Dispatchers.IO) {
            previous?.join()
            coroutineContext.ensureActive()
            val part = File(directory, "${UUID.randomUUID()}.part")
            var ready: File? = null
            try {
                AppUpdatePolicy.validate(release, app.packageName, installed.code(), Build.VERSION.SDK_INT)
                clearFiles()
                kotlin.check(directory.exists() || directory.mkdirs()) { "Update storage is unavailable." }
                kotlin.check(AppUpdatePolicy.hasSpace(directory.usableSpace, release.size)) { "Free some device storage before downloading this update." }
                withTimeout(10 * 60_000L) {
                    val response = open(release.downloadUrl, allowRedirects = true)
                    try {
                        val declared = response.contentLengthLong
                        require(declared == release.size) { "The update file size changed. Check for updates again." }
                        val contentType = response.contentType.orEmpty().lowercase()
                        require(!contentType.contains("text/") && !contentType.contains("json") && !contentType.contains("html")) { "The server did not return an app update." }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        var lastProgress = 0L
                        response.inputStream.use { input -> part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                count += read
                                require(count <= release.size) { "The update file is larger than expected." }
                                require(directory.usableSpace >= AppUpdatePolicy.DISK_RESERVE_BYTES + read) { "Not enough device storage to finish this update." }
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                val now = System.nanoTime()
                                if (now - lastProgress >= 150_000_000L) {
                                    publish(token, _state.value.copy(downloadedBytes = count))
                                    lastProgress = now
                                }
                            }
                        } }
                        require(count == release.size) { "The download was interrupted. Please try again." }
                        require(hex(digest.digest()).equals(release.sha256, ignoreCase = true)) { "The update could not be verified. Please check for updates again." }
                        publish(token, _state.value.copy(phase = AppUpdatePhase.VERIFYING, downloadedBytes = count))
                    } finally { response.disconnect(); connection.compareAndSet(response, null) }
                }
                verifyArchive(part, release)
                coroutineContext.ensureActive()
                ready = File(directory, "update-${release.versionCode}-${UUID.randomUUID()}.apk")
                kotlin.check(part.renameTo(ready)) { "Could not prepare the downloaded update." }
                synchronized(this@AppUpdateManager) {
                    if (generation == token) verifiedFile = ready
                }
                publish(token, _state.value.copy(phase = AppUpdatePhase.READY, downloadedBytes = release.size, message = "Ready to install. Your account and downloads will stay on this device."))
            } catch (_: TimeoutCancellationException) {
                ready?.delete()
                publish(token, _state.value.copy(phase = AppUpdatePhase.ERROR, message = "The download took too long. Please try again on a faster connection."))
            } catch (cancelled: CancellationException) {
                ready?.delete()
                throw cancelled
            } catch (failure: Exception) {
                ready?.delete()
                publish(token, _state.value.copy(phase = AppUpdatePhase.ERROR, message = safeMessage(failure)))
            } finally { part.delete() }
        }
    }

    fun cancel() {
        val cancelledConnection = synchronized(this) {
            val wasChecking = _state.value.phase == AppUpdatePhase.CHECKING
            ++generation
            operation?.cancel()
            _state.value = _state.value.copy(phase = if (_state.value.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
                downloadedBytes = 0, message = if (wasChecking) "Update check cancelled." else "Download cancelled.")
            connection.getAndSet(null)
        }
        // Closing an active socket must not stall the UI thread or hold the state lock.
        scope.launch(Dispatchers.IO) { cancelledConnection?.disconnect() }
    }

    /** Android always presents the final install confirmation. Returning from settings never auto-installs. */
    @Synchronized fun install(activity: Activity) {
        if (operation?.isActive == true) return
        val release = _state.value.release ?: return
        val file = verifiedFile?.takeIf { it.isFile } ?: run {
            _state.value = _state.value.copy(phase = AppUpdatePhase.AVAILABLE, message = "The downloaded update was cleared. Download it again.")
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            try {
                _state.value = _state.value.copy(phase = AppUpdatePhase.INSTALL_PERMISSION,
                    message = "Allow updates from MojaLoss, then return here and tap Install update.")
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            } catch (_: Exception) {
                _state.value = _state.value.copy(phase = AppUpdatePhase.READY, message = "Open Android Settings and allow MojaLoss to install app updates, then try again.")
            }
            return
        }
        val previous = operation
        val token = ++generation
        _state.value = _state.value.copy(phase = AppUpdatePhase.VERIFYING, message = null)
        operation = scope.launch(Dispatchers.IO) {
            previous?.join()
            coroutineContext.ensureActive()
            try {
                verifyArchive(file, release)
                coroutineContext.ensureActive()
                withContext(Dispatchers.Main) {
                    try {
                        kotlin.check(!activity.isFinishing && !activity.isDestroyed) { "Return to the app to install this update." }
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        intent.clipData = ClipData.newRawUri("MojaLoss update", uri)
                        activity.startActivity(intent)
                        publish(token, _state.value.copy(phase = AppUpdatePhase.INSTALLER_OPENED,
                            message = "Complete the Android installation. If you cancelled, you can try again."))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // A launcher/Activity failure does not invalidate verified bytes.
                        publish(token, _state.value.copy(phase = AppUpdatePhase.READY,
                            message = "Android could not open the installer. Return to this screen and tap Install update to try again."))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { publish(token, _state.value.copy(phase = AppUpdatePhase.ERROR, message = safeMessage(failure))) }
        }
    }

    /** Success is based on the installed package version, never on launching an Intent. */
    @Synchronized fun onResume() {
        if (operation?.isActive == true) return
        val current = installed
        val release = _state.value.release
        if (release != null && current.code() >= release.versionCode) {
            verifiedFile?.delete(); verifiedFile = null
            _state.value = AppUpdateState(AppUpdatePhase.UP_TO_DATE, current.versionName.orEmpty(), message = "You have the latest version.")
        } else if (_state.value.phase == AppUpdatePhase.INSTALL_PERMISSION) {
            _state.value = _state.value.copy(phase = AppUpdatePhase.READY,
                message = if (app.packageManager.canRequestPackageInstalls()) "Ready. Tap Install update to continue." else "Installation permission is still off. Tap Install update to allow it.")
        }
    }

    private suspend fun latestRelease(): AppUpdateRelease {
        val root = json.parseToJsonElement(readText("https://api.github.com/repos/$owner/$repo/releases/latest", 512 * 1024, false)).jsonObject
        require(root["draft"]?.jsonPrimitive?.booleanOrNull == false && root["prerelease"]?.jsonPrimitive?.booleanOrNull == false) { "No stable update is available yet." }
        val tag = root.string("tag_name")
        require(AppUpdatePolicy.validComponent(tag)) { "The update release could not be verified." }
        val assets = (root["assets"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        val manifest = assets.singleOrNull { it.string("name") == "update.json" } ?: error("This release has no app update information yet.")
        require(manifest.number("size") in 1..64 * 1024L) { "The update information is invalid." }
        val manifestUrl = manifest.string("browser_download_url")
        require(AppUpdatePolicy.exactAssetUrl(manifestUrl, owner, repo, tag, "update.json")) { "The update information came from an unexpected source." }
        val fields = json.parseToJsonElement(readText(manifestUrl, 64 * 1024, true)).jsonObject
        val assetName = fields.string("assetName")
        val asset = assets.singleOrNull { it.string("name") == assetName } ?: error("The app update file is unavailable.")
        val url = asset.string("browser_download_url")
        require(AppUpdatePolicy.exactAssetUrl(url, owner, repo, tag, assetName)) { "The update file came from an unexpected source." }
        val size = fields.number("size")
        require(asset.number("size") == size) { "The update file size did not match its release." }
        val sha256 = fields.string("sha256").lowercase()
        asset["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            require(it == "sha256:$sha256") { "The update digest did not match its release." }
        }
        val minSdk = fields.number("minSdk")
        require(minSdk in 26..Int.MAX_VALUE.toLong()) { "The update Android version is invalid." }
        val release = AppUpdateRelease(fields.number("versionCode"), fields.string("versionName"), minSdk.toInt(),
            fields.string("applicationId"), assetName, size, sha256,
            fields["notes"]?.jsonPrimitive?.contentOrNull?.take(20_000) ?: root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20_000),
            url, "https://github.com/$owner/$repo/releases/tag/$tag")
        require(release.versionCode > 0 && release.versionCode <= Int.MAX_VALUE) { "The update version is invalid." }
        require(release.applicationId == app.packageName) { "This update is for a different app." }
        return release
    }

    private suspend fun readText(url: String, maximum: Int, redirects: Boolean): String {
        val response = open(url, redirects)
        return try {
            require(response.contentLengthLong <= maximum) { "The update information is too large." }
            response.inputStream.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(result.size() + read <= maximum) { "The update information is too large." }
                    result.write(buffer, 0, read)
                }
                result.toString(Charsets.UTF_8.name())
            }
        } finally { response.disconnect(); connection.compareAndSet(response, null) }
    }

    private suspend fun open(initial: String, allowRedirects: Boolean): HttpURLConnection {
        var target = initial
        repeat(5) { hop ->
            coroutineContext.ensureActive()
            if (allowRedirects) require(AppUpdatePolicy.allowedTransferUrl(target)) { "The update redirect was blocked." }
            else require(target == "https://api.github.com/repos/$owner/$repo/releases/latest") { "The update source was blocked." }
            val response = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("Accept", if (allowRedirects) "application/octet-stream" else "application/vnd.github+json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "MojaLoss-Android-Updater")
                if (!allowRedirects) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                useCaches = false
            }
            try {
                val requestContext = coroutineContext
                synchronized(this) {
                    // Register and cancellation-check atomically with cancel() so it cannot
                    // miss a socket created immediately after the previous active check.
                    requestContext.ensureActive()
                    connection.set(response)
                }
                val code = response.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    require(allowRedirects && hop < 4) { "The update redirect was blocked." }
                    val next = response.getHeaderField("Location") ?: error("The update redirect was missing.")
                    target = URL(URL(target), next).toString()
                    response.disconnect(); connection.compareAndSet(response, null)
                } else {
                    if (code == 404) error("No published app update is available yet. Please try again later.")
                    if (code == 403 || code == 429) error("The update server is busy. Please try again later.")
                    require(code == 200) { "The update server could not finish this request. Please try again." }
                    return response
                }
            } catch (failure: Exception) {
                response.disconnect(); connection.compareAndSet(response, null)
                throw failure
            }
        }
        error("The update redirected too many times.")
    }

    @Suppress("DEPRECATION")
    private suspend fun verifyArchive(file: File, release: AppUpdateRelease) {
        AppUpdatePolicy.validate(release, app.packageName, installed.code(), Build.VERSION.SDK_INT)
        require(file.length() == release.size) { "The downloaded update is incomplete. Download it again." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read)
        } }
        require(hex(digest.digest()).equals(release.sha256, true)) { "The downloaded update changed. Download it again." }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("This file is not a valid Android update.")
        val candidateSigners = if (Build.VERSION.SDK_INT >= 36) {
            // API 36 verifies the APK signing block before the Android installer is opened.
            PackageManager.getVerifiedSigningInfo(file.absolutePath, SigningInfo.VERSION_SIGNING_BLOCK_V2)
                .apkContentsSigners.map { AppUpdatePolicy.digest(it.toByteArray()) }.toSet()
        } else archive.signerDigests()
        require(AppUpdatePolicy.validIdentity(archive.packageName, app.packageName, archive.code(), release.versionCode,
            installed.signerDigests(), candidateSigners)) { "This update does not match the installed MojaLoss app." }
        require(archive.versionName == release.versionName) { "The update version did not match its release." }
        require(archive.applicationInfo?.minSdkVersion == release.minSdk) { "The update Android version did not match its release." }
    }

    @Suppress("DEPRECATION") private fun packageInfo(name: String): PackageInfo = app.packageManager.getPackageInfo(name,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    @Suppress("DEPRECATION") private fun PackageInfo.code(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
    @Suppress("DEPRECATION") private fun PackageInfo.signerDigests(): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty()
            .map { AppUpdatePolicy.digest(it.toByteArray()) }.toSet()

    private suspend fun recoverVerifiedFile(release: AppUpdateRelease): File? {
        val candidates = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }.orEmpty()
        for (candidate in candidates) {
            coroutineContext.ensureActive()
            try { verifyArchive(candidate, release); return candidate }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { candidate.delete() }
        }
        return null
    }

    private fun clearFiles(keepApks: Boolean = false) {
        verifiedFile = null
        if (!directory.exists()) kotlin.check(directory.mkdirs()) { "Update storage is unavailable." }
        directory.listFiles()?.filter { (!keepApks && it.name.endsWith(".apk")) || it.name.endsWith(".part") }?.forEach { it.delete() }
    }
    @Synchronized private fun publish(token: Long, value: AppUpdateState) { if (token == generation) _state.value = value }
    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("The update information is missing $name.")
    private fun JsonObject.number(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: error("The update information has an invalid $name.")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun safeMessage(failure: Exception): String = when (failure) {
        is java.net.UnknownHostException -> "Could not reach the update server. Check your connection and try again."
        is java.net.SocketTimeoutException -> "The update server took too long. Please try again."
        is IOException -> "The update could not finish. Check your connection and device storage, then try again."
        is IllegalArgumentException, is IllegalStateException -> failure.message?.take(240) ?: "The update could not be verified. Please try again."
        else -> "The update could not finish safely. Please try again."
    }
}
