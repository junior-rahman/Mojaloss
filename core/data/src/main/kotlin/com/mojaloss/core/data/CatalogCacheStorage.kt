package com.mojaloss.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.mojaloss.core.model.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Previously observed catalog data, never a substitute for confirming a service mutation. */
internal data class CachedCatalog(
    val serverUrl: String,
    val accountId: String,
    val displayName: String,
    val savedAtEpochMs: Long,
    val complete: Boolean,
    val titles: List<CachedCatalogTitle>,
    val queryKey: String = "home-v1",
    val totalCount: Int? = null,
    val nextStart: Int? = null,
)

/** Deliberately excludes credentials, full artwork URLs, synopsis, cast, trailers and ratings. */
internal data class CachedCatalogTitle(
    val id: String,
    val title: String,
    val kind: MediaKind = MediaKind.MOVIE,
    val year: Int? = null,
    val genre: String = "",
    val runtimeMs: Long = 0,
    val isPlayable: Boolean = false,
    val isUpcoming: Boolean = false,
    val posterTag: String? = null,
    val backdropTag: String? = null,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val confirmedResumeMs: Long = 0,
    val favorite: Boolean = false,
)

/**
 * One account's encrypted catalog queries in noBackupFilesDir. The caller supplies only image tags and
 * rebuilds image requests with the current session. Scope and format are authenticated as AAD.
 * Cache failure is optional: corrupt data and unavailable storage/keys never prevent sign-in.
 */
internal class CatalogCacheStorage(context: Context) {
    private val directory = File(context.noBackupFilesDir, "mojaloss")

    fun save(value: CachedCatalog): Boolean = synchronized(STORAGE_LOCK) {
        var plain: ByteArray? = null
        try {
            val scoped = validate(value)
            val file = queryFile(scoped.queryKey)
            val encoded = encode(scoped).toString().encodeToByteArray()
            plain = encoded
            require(encoded.size <= MAX_PLAIN_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            require(cipher.iv.size == IV_SIZE)
            cipher.updateAAD(aad(scoped.serverUrl, scoped.accountId, scoped.queryKey))
            val encrypted = cipher.doFinal(encoded)
            require(directory.isDirectory || directory.mkdirs())
            write(file, HEADER + cipher.iv + encrypted)
            trimQueries(file.baseFile.name)
            true
        } catch (_: Exception) {
            false
        } finally {
            plain?.fill(0)
        }
    }

    fun load(serverUrl: String, accountId: String, queryKey: String = "home-v1"): CachedCatalog? = synchronized(STORAGE_LOCK) {
        var plain: ByteArray? = null
        try {
            val server = normalizeServer(serverUrl)
            val account = normalizeId(accountId)
            val query = validateQueryKey(queryKey)
            val file = queryFile(query)
            val bytes = file.openRead().use { input ->
                require(input.channel.size() in MIN_FILE_BYTES..MAX_FILE_BYTES.toLong())
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= MAX_FILE_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size >= MIN_FILE_BYTES && bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Do not create a replacement key when a saved file has lost its original key.
            cipher.init(Cipher.DECRYPT_MODE, key(create = false),
                GCMParameterSpec(128, bytes.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)))
            cipher.updateAAD(aad(server, account, query))
            val decoded = cipher.doFinal(bytes.copyOfRange(HEADER.size + IV_SIZE, bytes.size))
            plain = decoded
            require(decoded.size <= MAX_PLAIN_BYTES)
            val value = validate(decode(JSONObject(decoded.decodeToString())))
            require(value.serverUrl == server && value.accountId == account && value.queryKey == query)
            value
        } catch (_: Exception) {
            // A different-account lookup must not destroy a valid catalog for the saved account.
            null
        } finally {
            plain?.fill(0)
        }
    }

    fun clear() = synchronized(STORAGE_LOCK) {
        directory.listFiles()?.filter { CACHE_FILE.matches(it.name) }?.forEach { it.delete() }
        // This name was used by the unreleased single-query helper, and contains only our cache.
        AtomicFile(File(directory, "catalog.enc")).delete()
        Unit
    }

    /** Remove an invalidated small shelf without discarding unrelated catalog query anchors. */
    fun deleteQuery(queryKey: String) = synchronized(STORAGE_LOCK) {
        queryFile(queryKey).delete()
    }

    private fun queryFile(queryKey: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(validateQueryKey(queryKey).encodeToByteArray())
        val fingerprint = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return AtomicFile(File(directory, "catalog-v2-$fingerprint.enc"))
    }

    private fun trimQueries(savedName: String) {
        val files = directory.listFiles().orEmpty().filter { CACHE_FILE.matches(it.name) }
        val groups = files.groupBy { it.name.removeSuffix(".bak").removeSuffix(".new") }
        var count = groups.size
        var bytes = files.sumOf { it.length() }
        val homeNames = setOf(queryFile("home-v1").baseFile.name, queryFile("home-v2-recent").baseFile.name)
        groups.entries.filter { it.key !in homeNames && it.key != savedName }
            .sortedBy { entry -> entry.value.maxOf { it.lastModified() } }
            .forEach { (name, entries) ->
                if (count > MAX_QUERIES || bytes > MAX_AGGREGATE_BYTES) {
                    val entryBytes = entries.sumOf { it.length() }
                    AtomicFile(File(directory, name)).delete()
                    check(entries.none { it.exists() })
                    count--
                    bytes -= entryBytes
                }
            }
        check(count <= MAX_QUERIES && bytes <= MAX_AGGREGATE_BYTES)
    }

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "Catalog key is unavailable" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun validate(value: CachedCatalog): CachedCatalog {
        require(value.savedAtEpochMs > 0)
        require(value.displayName.isNotBlank() && value.displayName.length <= 2048)
        validateQueryKey(value.queryKey)
        require(value.totalCount == null || value.totalCount >= 0)
        require(!value.complete || value.totalCount == null || value.totalCount == value.titles.size)
        require(value.nextStart == null || value.nextStart >= 0)
        require(value.nextStart == null || value.totalCount == null || value.nextStart <= value.totalCount)
        require(!value.complete || value.totalCount == null || value.nextStart == null || value.nextStart == value.totalCount)
        require(value.titles.size <= MAX_TITLES)
        val ids = HashSet<String>()
        val titles = value.titles.map { title ->
            val id = normalizeId(title.id)
            require(ids.add(id))
            require(title.title.isNotBlank() && title.title.length <= 2048)
            require(title.genre.length <= 4096)
            require(title.year == null || title.year in 0..9999)
            require(title.runtimeMs >= 0 && title.confirmedResumeMs >= 0)
            require(title.seasonNumber == null || title.seasonNumber >= 0)
            require(title.episodeNumber == null || title.episodeNumber >= 0)
            require(title.posterTag == null || IMAGE_TAG.matches(title.posterTag))
            require(title.backdropTag == null || IMAGE_TAG.matches(title.backdropTag))
            title.copy(id = id, seriesId = title.seriesId?.let(::normalizeId))
        }
        return value.copy(serverUrl = normalizeServer(value.serverUrl),
            accountId = normalizeId(value.accountId), titles = titles)
    }

    private fun encode(value: CachedCatalog): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("server", value.serverUrl)
        .put("account", value.accountId)
        .put("displayName", value.displayName)
        .put("savedAt", value.savedAtEpochMs)
        .put("complete", value.complete)
        .put("queryKey", value.queryKey)
        .putNullable("totalCount", value.totalCount)
        .putNullable("nextStart", value.nextStart)
        .put("titles", JSONArray().apply {
            value.titles.forEach { title ->
                put(JSONObject().put("id", title.id).put("title", title.title)
                    .put("kind", title.kind.name).putNullable("year", title.year)
                    .put("genre", title.genre).put("runtimeMs", title.runtimeMs)
                    .put("playable", title.isPlayable).put("upcoming", title.isUpcoming)
                    .putNullable("posterTag", title.posterTag).putNullable("backdropTag", title.backdropTag)
                    .putNullable("seriesId", title.seriesId).putNullable("season", title.seasonNumber)
                    .putNullable("episode", title.episodeNumber).put("resumeMs", title.confirmedResumeMs)
                    .put("favorite", title.favorite))
            }
        })

    private fun decode(json: JSONObject): CachedCatalog {
        require(json.requiredLong("version") == VERSION.toLong())
        val entries = json.getJSONArray("titles")
        require(entries.length() <= MAX_TITLES)
        return CachedCatalog(
            serverUrl = json.requiredString("server"), accountId = json.requiredString("account"),
            displayName = json.requiredString("displayName"),
            savedAtEpochMs = json.requiredLong("savedAt"), complete = json.requiredBoolean("complete"),
            queryKey = json.requiredString("queryKey"), totalCount = json.nullableInt("totalCount"),
            nextStart = json.nullableInt("nextStart"),
            titles = List(entries.length()) { index ->
                val item = entries.getJSONObject(index)
                CachedCatalogTitle(id = item.requiredString("id"), title = item.requiredString("title"),
                    kind = MediaKind.valueOf(item.requiredString("kind")), year = item.nullableInt("year"),
                    genre = item.requiredString("genre"), runtimeMs = item.requiredLong("runtimeMs"),
                    isPlayable = item.requiredBoolean("playable"), isUpcoming = item.requiredBoolean("upcoming"),
                    posterTag = item.nullableString("posterTag"), backdropTag = item.nullableString("backdropTag"),
                    seriesId = item.nullableString("seriesId"), seasonNumber = item.nullableInt("season"),
                    episodeNumber = item.nullableInt("episode"), confirmedResumeMs = item.requiredLong("resumeMs"),
                    favorite = item.requiredBoolean("favorite"))
            },
        )
    }

    private companion object {
        const val VERSION = 2
        const val IV_SIZE = 12
        const val KEY_ALIAS = "mojaloss_catalog_aes_v1"
        const val MAX_PLAIN_BYTES = 8 * 1024 * 1024
        const val MAX_FILE_BYTES = MAX_PLAIN_BYTES + 5 + IV_SIZE + 16
        const val MIN_FILE_BYTES = 5L + IV_SIZE + 16
        const val MAX_TITLES = 50_000
        const val MAX_QUERIES = 12
        const val MAX_AGGREGATE_BYTES = 24L * 1024 * 1024
        val HEADER = byteArrayOf('M'.code.toByte(), 'J'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), VERSION.toByte())
        val STORAGE_LOCK = Any()
        val KEY_LOCK = Any()
        val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val IMAGE_TAG = Regex("[A-Za-z0-9._-]{1,256}")
        val CACHE_FILE = Regex("catalog-v2-[0-9a-f]{64}\\.enc(?:\\.bak|\\.new)?")

        fun normalizeId(value: String): String {
            require(ID.matches(value))
            return UUID.fromString(value).toString()
        }

        fun normalizeServer(value: String): String {
            require(value.length <= 2048)
            val parsed = URI(ServerUrls.normalize(value))
            require(parsed.port == -1 || parsed.port in 1..65535)
            val host = parsed.host.lowercase(Locale.ROOT).let {
                if (':' in it && !it.startsWith('[')) "[$it]" else it
            }
            val port = if (parsed.port == -1 || parsed.port == 443) "" else ":${parsed.port}"
            return "https://$host$port${parsed.rawPath}"
        }

        fun validateQueryKey(value: String): String {
            require(value.isNotBlank() && value.length <= 4096)
            return value
        }
        fun aad(server: String, account: String, queryKey: String) =
            "MojaLossCatalog\n$VERSION\n$server\n$account\n$queryKey".encodeToByteArray()
        fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
        fun JSONObject.requiredString(key: String): String = get(key).also { require(it is String) } as String
        fun JSONObject.requiredBoolean(key: String): Boolean = get(key).also { require(it is Boolean) } as Boolean
        fun JSONObject.requiredLong(key: String): Long {
            val value = get(key)
            require(value is Number)
            return requireNotNull(value.toString().toLongOrNull())
        }
        fun JSONObject.nullableString(key: String): String? {
            require(has(key))
            return if (isNull(key)) null else requiredString(key)
        }
        fun JSONObject.nullableInt(key: String): Int? {
            require(has(key))
            if (isNull(key)) return null
            val value = requiredLong(key)
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return value.toInt()
        }
    }
}
