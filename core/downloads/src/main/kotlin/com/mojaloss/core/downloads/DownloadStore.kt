package com.mojaloss.core.downloads

import com.mojaloss.core.model.DownloadSettings
import com.mojaloss.core.model.MediaKind
import com.mojaloss.core.model.MediaTitle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal data class StoredDownload(
    val id: String,
    val owner: String,
    val server: String,
    val account: String,
    val title: MediaTitle,
    val url: String,
    val sourceId: String,
    val mimeType: String?,
    val expectedBytes: Long?,
    val fingerprint: String,
    val addedAt: Long,
    val validator: String? = null,
    val positionMs: Long = 0,
    val userPaused: Boolean = false,
    val problem: String? = null,
) {
    fun json() = JSONObject().apply {
        put("id", id); put("owner", owner); put("server", server); put("account", account)
        put("url", url); put("source", sourceId); put("mime", mimeType)
        put("bytes", expectedBytes); put("fingerprint", fingerprint); put("added", addedAt)
        put("validator", validator); put("position", positionMs); put("paused", userPaused)
        put("problem", problem)
        put("title", JSONObject().apply {
            put("id", title.id); put("name", title.title); put("kind", title.kind.name)
            put("year", title.year); put("genre", title.genre); put("runtime", title.runtimeMs)
            put("series", title.seriesId); put("season", title.seasonNumber); put("episode", title.episodeNumber)
        })
    }
    companion object {
        fun read(value: JSONObject): StoredDownload {
            val t = value.getJSONObject("title")
            return StoredDownload(
                id = value.getString("id"), owner = value.getString("owner"),
                server = value.getString("server"), account = value.getString("account"),
                title = MediaTitle(id = t.getString("id"), title = t.getString("name"),
                    kind = MediaKind.valueOf(t.getString("kind")), year = t.optionalInt("year"),
                    genre = t.optString("genre"), runtimeMs = t.optLong("runtime"), isPlayable = true,
                    seriesId = t.optionalString("series"), seasonNumber = t.optionalInt("season"),
                    episodeNumber = t.optionalInt("episode")),
                url = value.getString("url"), sourceId = value.getString("source"),
                mimeType = value.optionalString("mime"), expectedBytes = value.optionalLong("bytes"),
                fingerprint = value.getString("fingerprint"), addedAt = value.getLong("added"),
                validator = value.optionalString("validator"), positionMs = value.optLong("position"),
                userPaused = value.optBoolean("paused"), problem = value.optionalString("problem"),
            )
        }
    }
}

/** Private no-backup metadata. Tokens, artwork headers and playback sessions are never serialized. */
internal class DownloadStore(private val directory: File) {
    private val file = File(directory, "library.json")
    @Synchronized fun read(): Pair<List<StoredDownload>, DownloadSettings> {
        if (!file.exists()) return emptyList<StoredDownload>() to DownloadSettings()
        val root = JSONObject(file.readText())
        val rows = root.optJSONArray("downloads") ?: JSONArray()
        return (0 until rows.length()).map { StoredDownload.read(rows.getJSONObject(it)) } to DownloadSettings(root.optBoolean("wifiOnly", true), root.optLong("storageLimit", DownloadPolicy.QUOTA_BYTES).coerceIn(256L * 1024 * 1024, DownloadPolicy.MAX_QUOTA_BYTES))
    }
    @Synchronized fun readBlockedOwners(): Set<String> {
        if (!file.exists()) return emptySet()
        val values = JSONObject(file.readText()).optJSONArray("blockedOwners") ?: return emptySet()
        return (0 until values.length()).map(values::getString).toSet()
    }
    @Synchronized fun write(rows: Collection<StoredDownload>, settings: DownloadSettings, blockedOwners: Set<String> = emptySet()) {
        check(directory.exists() || directory.mkdirs()) { "Download storage is unavailable" }
        val temp = File(directory, "library.json.partial")
        val root = JSONObject().put("version", 1).put("wifiOnly", settings.wifiOnly).put("storageLimit", settings.storageLimitBytes)
            .put("downloads", JSONArray(rows.map(StoredDownload::json)))
            .put("blockedOwners", JSONArray(blockedOwners.toList()))
        FileOutputStream(temp).use { stream -> stream.write(root.toString().toByteArray()); stream.fd.sync() }
        if (!temp.renameTo(file)) { temp.delete(); error("Download metadata could not be saved") }
    }
}
private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
private fun JSONObject.optionalInt(key: String) = if (isNull(key)) null else getInt(key)
private fun JSONObject.optionalLong(key: String) = if (isNull(key)) null else getLong(key)
