package com.skyroll.app.data

import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import com.skyroll.app.telegram.TelegramClient
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Grid thumbnail for a device photo/video, using Android's fast thumbnail cache. */
data class DeviceThumb(val uri: Uri)

/** Thumbnail of a backup that only exists on Telegram. */
data class TgThumb(val fileId: Int)

fun MediaItem.thumbModel(): Any? = when {
    vaultPath != null -> java.io.File(vaultPath)
    uri != null -> DeviceThumb(uri)
    cloud != null && cloud.thumbFileId != 0 -> TgThumb(cloud.thumbFileId)
    else -> null
}

class DeviceThumbFetcher(private val data: DeviceThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 384
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 384
        val px = maxOf(w, h, 96).coerceAtMost(768)
        val bmp = options.context.contentResolver.loadThumbnail(data.uri, android.util.Size(px, px), null)
        return ImageFetchResult(image = bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<DeviceThumb> {
        override fun create(data: DeviceThumb, options: Options, imageLoader: ImageLoader) = DeviceThumbFetcher(data, options)
    }
}

class DeviceThumbKeyer : Keyer<DeviceThumb> {
    override fun key(data: DeviceThumb, options: Options) = "thumb:${data.uri}:${options.size}"
}

class TgThumbFetcher(private val data: TgThumb, private val tg: TelegramClient) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = tg.download(data.fileId, priority = 2)
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val tg: TelegramClient) : Fetcher.Factory<TgThumb> {
        override fun create(data: TgThumb, options: Options, imageLoader: ImageLoader) = TgThumbFetcher(data, tg)
    }
}

class TgThumbKeyer : Keyer<TgThumb> {
    override fun key(data: TgThumb, options: Options) = "tg:${data.fileId}"
}

/** A face cut out of a photo, for the round People avatars. Box is in 0..1 fractions. */
data class FaceThumb(val uri: Uri, val left: Float, val top: Float, val right: Float, val bottom: Float)

class FaceThumbFetcher(private val data: FaceThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val src = options.context.contentResolver.loadThumbnail(data.uri, android.util.Size(1280, 1280), null)
        val cx = (data.left + data.right) / 2 * src.width
        val cy = (data.top + data.bottom) / 2 * src.height
        // Square crop around the face with some breathing room.
        val half = maxOf((data.right - data.left) * src.width, (data.bottom - data.top) * src.height) * 0.8f
        val l = (cx - half).toInt().coerceIn(0, src.width - 1)
        val t = (cy - half).toInt().coerceIn(0, src.height - 1)
        val size = minOf((half * 2).toInt(), src.width - l, src.height - t).coerceAtLeast(1)
        val crop = android.graphics.Bitmap.createBitmap(src, l, t, size, size)
        val out = android.graphics.Bitmap.createScaledBitmap(crop, 256, 256, true)
        if (crop != src) crop.recycle()
        src.recycle()
        return ImageFetchResult(image = out.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<FaceThumb> {
        override fun create(data: FaceThumb, options: Options, imageLoader: ImageLoader) = FaceThumbFetcher(data, options)
    }
}

class FaceThumbKeyer : Keyer<FaceThumb> {
    override fun key(data: FaceThumb, options: Options) = "face:${data.uri}:${data.left}:${data.top}"
}
