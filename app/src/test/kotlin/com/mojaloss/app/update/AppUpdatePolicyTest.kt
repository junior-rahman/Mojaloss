package com.mojaloss.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    private val release = AppUpdateRelease(8, "0.1.7", 26, "com.mojaloss.android.debug", "MojaLoss-Sage-0.1.7-debug.apk",
        38_000_000, "a".repeat(64), "Release notes", "https://github.com/JuelHossain/MojaLoss/releases/download/v0.1.7/MojaLoss-Sage-0.1.7-debug.apk", "")

    @Test fun exactAssetMustBelongToConfiguredRepositoryAndRelease() {
        assertTrue(AppUpdatePolicy.exactAssetUrl(release.downloadUrl, "JuelHossain", "MojaLoss", "v0.1.7", release.assetName))
        listOf(
            release.downloadUrl.replace("https:", "http:"),
            release.downloadUrl.replace("github.com/", "github.com.evil.example/"),
            release.downloadUrl.replace("/MojaLoss/", "/AnotherApp/"),
            release.downloadUrl.replace("/v0.1.7/", "/v0.1.6/"),
            release.downloadUrl + "?target=another.apk",
            release.downloadUrl + "#fragment",
            release.downloadUrl.replace("github.com/", "github.com:443/"),
            release.downloadUrl.replace("github.com/", "user@github.com/"),
        ).forEach { assertFalse(it, AppUpdatePolicy.exactAssetUrl(it, "JuelHossain", "MojaLoss", "v0.1.7", release.assetName)) }
    }

    @Test fun redirectAdmissionRejectsHostSuffixTricksInsecureTransportAndCredentials() {
        assertTrue(AppUpdatePolicy.allowedTransferUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/file?signature=temporary"))
        assertTrue(AppUpdatePolicy.allowedTransferUrl(release.downloadUrl))
        listOf("https://release-assets.githubusercontent.com.evil.example/file", "https://raw.githubusercontent.com/file",
            "http://github.com/file", "https://github.com:8443/file", "https://user:password@github.com/file",
            "file:///tmp/update.apk", "https://evil.example/update.apk", "https://github.com/file#other").forEach {
            assertFalse(it, AppUpdatePolicy.allowedTransferUrl(it))
        }
    }

    @Test fun metadataRejectsWrongAppDowngradeUnsupportedAndroidAndMissingHash() {
        AppUpdatePolicy.validate(release, release.applicationId, 7, 37)
        listOf(release.copy(applicationId = "com.other.app"), release.copy(versionCode = 7), release.copy(versionCode = 6),
            release.copy(minSdk = 38), release.copy(minSdk = 0), release.copy(size = 0),
            release.copy(size = AppUpdatePolicy.MAX_APK_BYTES + 1), release.copy(sha256 = "abc"),
            release.copy(assetName = "../update.apk"), release.copy(assetName = "update.json")).forEach {
            expectRejected { AppUpdatePolicy.validate(it, release.applicationId, 7, 37) }
        }
    }

    @Test fun candidateMustMatchExactVersionPackageAndCurrentPinnedSigner() {
        val pin = setOf(AppUpdatePolicy.CERTIFICATE_SHA256)
        assertTrue(AppUpdatePolicy.validIdentity(release.applicationId, release.applicationId, 8, 8, pin, pin))
        assertFalse(AppUpdatePolicy.validIdentity("other", release.applicationId, 8, 8, pin, pin))
        assertFalse(AppUpdatePolicy.validIdentity(release.applicationId, release.applicationId, 9, 8, pin, pin))
        assertFalse(AppUpdatePolicy.validIdentity(release.applicationId, release.applicationId, 8, 8, pin, setOf("wrong")))
        assertFalse(AppUpdatePolicy.validIdentity(release.applicationId, release.applicationId, 8, 8, setOf("wrong"), setOf("wrong")))
        assertFalse(AppUpdatePolicy.validIdentity(release.applicationId, release.applicationId, 8, 8, pin, pin + "another"))
        assertFalse(AppUpdatePolicy.validIdentity(release.applicationId, release.applicationId, 8, 8, emptySet(), emptySet()))
    }

    @Test fun storageKeepsReserveAndDoesNotOverflowOnLargeValues() {
        val needed = release.size + AppUpdatePolicy.DISK_RESERVE_BYTES
        assertTrue(AppUpdatePolicy.hasSpace(needed, release.size))
        assertFalse(AppUpdatePolicy.hasSpace(needed - 1, release.size))
        assertTrue(AppUpdatePolicy.hasSpace(Long.MAX_VALUE, release.size))
        assertFalse(AppUpdatePolicy.hasSpace(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(AppUpdatePolicy.hasSpace(-1, release.size))
    }

    @Test fun digestIsRealSha256AndUnsafePathComponentsAreRejected() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppUpdatePolicy.digest("abc".toByteArray()))
        listOf("..", ".", "../file", "owner/repo", "a?query", "a#hash", "", "name%2Fother").forEach {
            assertFalse(it, AppUpdatePolicy.validComponent(it))
        }
    }

    private fun expectRejected(block: () -> Unit) {
        try { block(); throw AssertionError("Expected update metadata to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
