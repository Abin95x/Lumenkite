package com.skyroll.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import com.skyroll.app.security.Secrets
import com.skyroll.app.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

data class VaultEntry(
    val id: String,
    val key: String,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val relativePath: String?,
    /** Encrypted file. */
    val path: String,
    /** Encrypted small JPEG for the grid (may be missing for old entries). */
    val thumbPath: String?,
) {
    fun toMediaItem(cloud: CloudItem?) = MediaItem(
        key = key, localId = null, uri = null, name = name, mime = mime, size = size,
        dateTaken = dateTaken, bucketId = null, bucketName = null, relativePath = relativePath,
        durationMs = durationMs, width = width, height = height, path = null, cloud = cloud, vaultPath = path,
        vaultThumb = thumbPath,
    )
}

/**
 * Hidden photos. Every file, thumbnail and the list itself is encrypted (AES-256-GCM, key in the
 * phone's hardware keystore). Decrypted copies are made only while viewing and wiped on lock.
 */
class VaultRepository(private val context: Context, private val tg: TelegramClient, private val secrets: Secrets) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    /** Short-lived decrypted copies for viewing/sharing; wiped on lock and at start. */
    private val plainDir = File(context.cacheDir, "vault_open")
    private val indexFile = File(dir, "index.enc")
    private val legacyIndex = File(dir, "index.json")
    private val lock = Mutex()

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    /** Loads the list; encrypts anything left over from the old unencrypted vault. */
    suspend fun init() = withContext(Dispatchers.IO) {
        lock.withLock {
            wipePlain()
            _entries.value = load()
            if (legacyIndex.exists()) migrateLegacy()
        }
    }

    fun wipePlain() {
        if (plainDir.exists()) Secrets.wipeDir(plainDir)
    }

    // ------------------------------------------------------------ index

    private fun load(): List<VaultEntry> = runCatching {
        parse(String(secrets.decrypt(indexFile.readBytes(), AD_INDEX)))
    }.getOrDefault(emptyList())

    private fun parse(json: String): List<VaultEntry> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VaultEntry(
                id = o.getString("id"), key = o.getString("key"), name = o.getString("name"),
                mime = o.getString("mime"), size = o.getLong("size"), dateTaken = o.getLong("t"),
                width = o.optInt("w"), height = o.optInt("h"), durationMs = o.optLong("d"),
                relativePath = o.optString("p", "").ifEmpty { null }, path = o.getString("path"),
                thumbPath = o.optString("thumb", "").ifEmpty { null },
            )
        }.filter { File(it.path).exists() }
    }

    private fun save(list: List<VaultEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("key", e.key).put("name", e.name).put("mime", e.mime)
                    .put("size", e.size).put("t", e.dateTaken).put("w", e.width).put("h", e.height)
                    .put("d", e.durationMs).put("p", e.relativePath ?: "").put("path", e.path)
                    .put("thumb", e.thumbPath ?: "")
            )
        }
        val tmp = File(dir, "index.enc.tmp")
        tmp.writeBytes(secrets.encrypt(arr.toString().toByteArray(), AD_INDEX))
        tmp.renameTo(indexFile)
        _entries.value = list.sortedByDescending { it.dateTaken }
    }

    // ------------------------------------------------------------ add / remove

    /** Copies the item (from the device, or downloads it from Telegram) into the vault, encrypted. */
    suspend fun add(item: MediaItem): VaultEntry = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dest = File(dir, "$id.enc")
        val source: () -> InputStream = when {
            item.uri != null -> { { context.contentResolver.openInputStream(item.uri)!! } }
            item.cloud != null -> { val f = tg.download(item.cloud.fileId, 32); { f.inputStream() } }
            else -> error("Nothing to copy")
        }
        try {
            dest.outputStream().use { out ->
                secrets.encryptingStream(out, AD_FILE + id).use { enc -> source().use { it.copyTo(enc, 1 shl 16) } }
            }
            val thumb = makeThumb(item)?.let { writeThumb(id, it) }
            val entry = VaultEntry(
                id = id, key = item.key, name = item.name, mime = item.mime, size = item.size, dateTaken = item.dateTaken,
                width = item.width, height = item.height, durationMs = item.durationMs, relativePath = item.relativePath,
                path = dest.absolutePath, thumbPath = thumb,
            )
            lock.withLock { save(_entries.value + entry) }
            entry
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    suspend fun remove(paths: Collection<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val gone = _entries.value.filter { it.path in paths }
            gone.forEach { File(it.path).delete(); it.thumbPath?.let { t -> File(t).delete() } }
            save(_entries.value.filter { it.path !in paths })
        }
    }

    // ------------------------------------------------------------ reading

    private fun entryFor(path: String) = _entries.value.firstOrNull { it.path == path }

    /** Decrypted grid thumbnail, kept in memory only. */
    suspend fun thumbBytes(thumbPath: String): ByteArray = withContext(Dispatchers.IO) {
        val id = File(thumbPath).name.removeSuffix(".thumb")
        secrets.decrypt(File(thumbPath).readBytes(), AD_THUMB + id)
    }

    /** A temporary decrypted copy for viewing/sharing/restoring. Deleted when the vault locks. */
    suspend fun openPlain(path: String): File = withContext(Dispatchers.IO) {
        val e = entryFor(path) ?: error("Not in vault")
        plainDir.mkdirs()
        val ext = Secrets.safeFileName(e.name).substringAfterLast('.', "").let { if (it.isEmpty() || it.length > 5) "" else ".$it" }
        val out = File(plainDir, e.id + ext)
        if (!out.exists()) {
            val tmp = File(plainDir, e.id + ".part")
            File(path).inputStream().use { input ->
                secrets.decryptingStream(input, AD_FILE + e.id).use { dec -> tmp.outputStream().use { dec.copyTo(it, 1 shl 16) } }
            }
            tmp.renameTo(out)
        }
        out
    }

    // ------------------------------------------------------------ helpers

    private fun makeThumb(item: MediaItem): Bitmap? = runCatching {
        when {
            item.uri != null -> context.contentResolver.loadThumbnail(item.uri, Size(384, 384), null)
            else -> null
        }
    }.getOrNull()

    private fun writeThumb(id: String, bmp: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
        val f = File(dir, "$id.thumb")
        f.writeBytes(secrets.encrypt(bytes, AD_THUMB + id))
        return f.absolutePath
    }

    /** One-time upgrade: encrypt files from the earlier plain vault, then delete the plain copies. */
    private fun migrateLegacy() {
        val old = runCatching { parse(legacyIndex.readText()) }.getOrDefault(emptyList())
        val migrated = ArrayList(_entries.value)
        for (e in old) {
            val plain = File(e.path)
            if (!plain.exists()) continue
            runCatching {
                val dest = File(dir, "${e.id}.enc")
                dest.outputStream().use { out ->
                    secrets.encryptingStream(out, AD_FILE + e.id).use { enc -> plain.inputStream().use { it.copyTo(enc, 1 shl 16) } }
                }
                val thumb = runCatching {
                    val bmp = if (e.mime.startsWith("video")) {
                        ThumbnailUtils.createVideoThumbnail(plain, Size(384, 384), null)
                    } else {
                        ThumbnailUtils.createImageThumbnail(plain, Size(384, 384), null)
                    }
                    writeThumb(e.id, bmp)
                }.getOrNull()
                migrated += e.copy(path = dest.absolutePath, thumbPath = thumb)
                plain.delete()
            }
        }
        save(migrated)
        legacyIndex.delete()
    }

    companion object {
        private const val AD_INDEX = "vault-index"
        private const val AD_FILE = "vault-file:"
        private const val AD_THUMB = "vault-thumb:"
    }
}
