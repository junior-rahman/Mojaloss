package com.mojaloss.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.mojaloss.core.model.AppPreferences
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredSession(val serverUrl: String, val accountId: String, val token: String)

/** noBackupFilesDir excludes tokens from both cloud backup and device-transfer backups. */
internal class PrivateStorage(context: Context) {
    private val directory = File(context.noBackupFilesDir, "mojaloss").apply { mkdirs() }
    private val sessionFile = AtomicFile(File(directory, "session.enc"))
    private val preferencesFile = AtomicFile(File(directory, "preferences.json"))
    private val deviceFile = AtomicFile(File(directory, "device-id"))
    private val alias = "mojaloss_session_aes_v1"

    @Synchronized fun deviceId(): String = try {
        deviceFile.openRead().use { it.readBytes().decodeToString() }.takeIf { it.isNotBlank() }
            ?: newDeviceId()
    } catch (_: Exception) { newDeviceId() }
    private fun newDeviceId(): String = java.util.UUID.randomUUID().toString().also {
        write(deviceFile, it.encodeToByteArray())
    }

    @Synchronized fun saveSession(value: StoredSession) {
        val plain = JSONObject().put("server", value.serverUrl).put("account", value.accountId).put("token", value.token).toString().encodeToByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        write(sessionFile, byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        plain.fill(0)
    }

    @Synchronized fun loadSession(): StoredSession? = try {
        val bytes = sessionFile.openRead().use { it.readBytes() }
        val ivSize = bytes.first().toInt() and 0xff
        require(ivSize == 12 && bytes.size > ivSize + 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, ivSize + 1)))
        val plain = cipher.doFinal(bytes.copyOfRange(ivSize + 1, bytes.size))
        val json = JSONObject(plain.decodeToString())
        plain.fill(0)
        StoredSession(json.getString("server"), json.getString("account"), json.getString("token"))
    } catch (_: Exception) { sessionFile.delete(); null }

    @Synchronized fun clearSession() { sessionFile.delete() }
    @Synchronized fun preferences(): AppPreferences = try {
        val json = JSONObject(preferencesFile.openRead().use { it.readBytes().decodeToString() })
        AppPreferences(json.optBoolean("reducedMotion"), json.optBoolean("haptics", true),
            json.optString("audioLanguage", "System"), json.optString("subtitleLanguage", "Off"), json.optBoolean("autoplayNext"))
    } catch (_: Exception) { AppPreferences() }
    @Synchronized fun savePreferences(value: AppPreferences) {
        write(preferencesFile, JSONObject().put("reducedMotion", value.reducedMotion).put("haptics", value.haptics)
            .put("audioLanguage", value.audioLanguage).put("subtitleLanguage", value.subtitleLanguage)
            .put("autoplayNext", value.autoplayNext).toString().encodeToByteArray())
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
}
