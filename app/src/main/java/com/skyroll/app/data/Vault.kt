package com.skyroll.app.data

import android.content.Context
import android.net.Uri
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
import java.io.File
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
    val path: String,
) {
    fun toMediaItem(cloud: CloudItem?) = MediaItem(
        key = key, localId = null, uri = Uri.fromFile(File(path)), name = name, mime = mime, size = size,
        dateTaken = dateTaken, bucketId = null, bucketName = null, relativePath = relativePath,
        durationMs = durationMs, width = width, height = height, path = path, cloud = cloud, vaultPath = path,
    )
}

/**
 * Hidden photos, kept in the app's private storage (other apps and the system gallery can't see them).
 * The list lives in a small JSON file next to the files.
 */
class VaultRepository(private val context: Context, private val tg: TelegramClient) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lock = Mutex()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    private fun load(): List<VaultEntry> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VaultEntry(
                id = o.getString("id"), key = o.getString("key"), name = o.getString("name"),
                mime = o.getString("mime"), size = o.getLong("size"), dateTaken = o.getLong("t"),
                width = o.optInt("w"), height = o.optInt("h"), durationMs = o.optLong("d"),
                relativePath = o.optString("p", "").ifEmpty { null }, path = o.getString("path"),
            )
        }.filter { File(it.path).exists() }
    }.getOrDefault(emptyList())

    private fun save(list: List<VaultEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("key", e.key).put("name", e.name).put("mime", e.mime)
                    .put("size", e.size).put("t", e.dateTaken).put("w", e.width).put("h", e.height)
                    .put("d", e.durationMs).put("p", e.relativePath ?: "").put("path", e.path)
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
        _entries.value = list.sortedByDescending { it.dateTaken }
    }

    /** Copies the item (from the device, or downloads it from Telegram) into the vault. */
    suspend fun add(item: MediaItem): VaultEntry = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val ext = item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val dest = File(dir, id + ext)
        when {
            item.uri != null && item.vaultPath == null ->
                context.contentResolver.openInputStream(item.uri)!!.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
            item.cloud != null -> tg.download(item.cloud.fileId, 32).inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
            else -> error("Nothing to copy")
        }
        val entry = VaultEntry(
            id = id, key = item.key, name = item.name, mime = item.mime, size = item.size, dateTaken = item.dateTaken,
            width = item.width, height = item.height, durationMs = item.durationMs, relativePath = item.relativePath,
            path = dest.absolutePath,
        )
        lock.withLock { save(_entries.value + entry) }
        entry
    }

    /** Removes entries and their files. */
    suspend fun remove(paths: Collection<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            paths.forEach { File(it).delete() }
            save(_entries.value.filter { it.path !in paths })
        }
    }
}
