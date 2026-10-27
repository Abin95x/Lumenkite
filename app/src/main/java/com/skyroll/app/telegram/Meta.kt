package com.skyroll.app.telegram

import com.skyroll.app.data.MediaItem
import org.json.JSONObject

/**
 * Metadata stored in each backup message's caption, so the index can be rebuilt
 * from the channel alone (e.g. on a new phone).
 */
object Meta {
    private const val TAG = "#lumenkite "
    private val TAGS = listOf(TAG, "#skyroll ")

    data class Info(
        val name: String,
        val size: Long,
        val mime: String,
        val dateTaken: Long,
        val relativePath: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
    )

    fun encode(item: MediaItem): String {
        val json = JSONObject()
            .put("v", 1)
            .put("n", item.name)
            .put("s", item.size)
            .put("m", item.mime)
            .put("t", item.dateTaken)
            .put("w", item.width)
            .put("h", item.height)
        item.relativePath?.let { json.put("p", it) }
        if (item.durationMs > 0) json.put("d", item.durationMs)
        return item.name + "\n" + TAG + json.toString()
    }

    fun decode(caption: String?): Info? {
        val line = caption?.lineSequence()?.firstOrNull { l -> TAGS.any { l.startsWith(it) } } ?: return null
        val tag = TAGS.first { line.startsWith(it) }
        return runCatching {
            val j = JSONObject(line.removePrefix(tag))
            Info(
                name = j.getString("n"),
                size = j.getLong("s"),
                mime = j.optString("m", "application/octet-stream"),
                dateTaken = j.optLong("t", 0L),
                relativePath = j.optString("p", "").ifEmpty { null },
                width = j.optInt("w", 0),
                height = j.optInt("h", 0),
                durationMs = j.optLong("d", 0L),
            )
        }.getOrNull()
    }
}
