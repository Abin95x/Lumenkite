package com.skyroll.app.data

import android.net.Uri

/**
 * One photo or video. It can exist on the device, in the Telegram backup, or both.
 * [key] links the two copies: file name + size in bytes.
 */
data class MediaItem(
    val key: String,
    val localId: Long?,
    val uri: Uri?,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
    val bucketId: String?,
    val bucketName: String?,
    val relativePath: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val path: String?,
    val cloud: CloudItem? = null,
    /** Set when the item is hidden in the vault (private app storage). */
    val vaultPath: String? = null,
) {
    val isVideo: Boolean get() = mime.startsWith("video")
    val isLocal: Boolean get() = localId != null
    val isCloudOnly: Boolean get() = localId == null && vaultPath == null
    val isVault: Boolean get() = vaultPath != null
    val isBackedUp: Boolean get() = cloud != null

    companion object {
        fun keyOf(name: String, size: Long) = "$name|$size"

        fun fromCloud(c: CloudItem) = MediaItem(
            key = c.key,
            localId = null,
            uri = null,
            name = c.name,
            mime = c.mime,
            size = c.size,
            dateTaken = c.dateTaken,
            bucketId = null,
            bucketName = null,
            relativePath = c.relativePath,
            durationMs = c.durationMs,
            width = c.width,
            height = c.height,
            path = null,
            cloud = c,
        )
    }
}

data class Album(
    val id: String,
    val name: String,
    val items: List<MediaItem>,
    val pinned: Boolean,
) {
    val cover: MediaItem? get() = items.firstOrNull()
    val count: Int get() = items.size
}
