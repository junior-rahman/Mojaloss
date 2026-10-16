package com.mojaloss.app.update

import java.net.URI
import java.security.MessageDigest

enum class AppUpdatePhase {
    IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING, READY,
    INSTALL_PERMISSION, INSTALLER_OPENED, ERROR,
}

data class AppUpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val applicationId: String,
    val assetName: String,
    val size: Long,
    val sha256: String,
    val notes: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val currentVersion: String = "",
    val release: AppUpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
)

/** Pure admission rules, kept separate from Android and network effects. */
internal object AppUpdatePolicy {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val DISK_RESERVE_BYTES = 64L * 1024 * 1024
    const val CERTIFICATE_SHA256 = "dd4c36aace6f8c030ea40a5783015040987c94e2353b7ea48ea8522d6152d9d1"
    private val component = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val hexDigest = Regex("[a-fA-F0-9]{64}")

    fun validComponent(value: String): Boolean = component.matches(value) && value != "." && value != ".."

    fun exactAssetUrl(url: String, owner: String, repo: String, tag: String, asset: String): Boolean =
        validComponent(owner) && validComponent(repo) && validComponent(tag) && validComponent(asset) &&
            runCatching {
                val uri = URI(url)
                uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    uri.rawPath == "/$owner/$repo/releases/download/$tag/$asset"
            }.getOrDefault(false)

    /** Redirects are followed manually without cookies, authorization, or caller headers. */
    fun allowedTransferUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
            (uri.host == "github.com" || uri.host == "release-assets.githubusercontent.com" ||
                uri.host == "objects.githubusercontent.com" || uri.host == "github-releases.githubusercontent.com")
    }.getOrDefault(false)

    fun validate(release: AppUpdateRelease, packageName: String, currentCode: Long, sdk: Int) {
        require(release.applicationId == packageName) { "This update is for a different app." }
        require(release.versionCode > currentCode) { "This update is already installed." }
        require(release.versionName.isNotBlank() && release.versionName.length <= 80) { "Invalid update version." }
        require(release.minSdk in 26..sdk) { "This update needs a newer Android version." }
        require(release.size in 1..MAX_APK_BYTES) { "The update file size is not supported." }
        require(hexDigest.matches(release.sha256)) { "The update has no valid verification digest." }
        require(validComponent(release.assetName) && release.assetName.endsWith(".apk")) { "Invalid update file." }
    }

    fun validIdentity(
        actualPackage: String, expectedPackage: String, actualCode: Long, expectedCode: Long,
        installedSigners: Set<String>, candidateSigners: Set<String>,
    ): Boolean = actualPackage == expectedPackage && actualCode == expectedCode &&
        installedSigners == setOf(CERTIFICATE_SHA256) && candidateSigners == installedSigners

    fun hasSpace(freeBytes: Long, expectedBytes: Long): Boolean = expectedBytes in 1..MAX_APK_BYTES &&
        freeBytes >= expectedBytes && freeBytes - expectedBytes >= DISK_RESERVE_BYTES

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
