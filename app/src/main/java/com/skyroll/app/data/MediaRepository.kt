package com.skyroll.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Reads photos and videos from the device's MediaStore. */
class MediaRepository(private val context: Context) {

    private val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Emits once immediately and again whenever device media changes. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val cr = context.contentResolver
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { cr.unregisterContentObserver(observer) }
    }

    /** Sets Android's shared favorite flag. Returns a request the user may need to confirm. */
    fun favoriteRequest(uris: List<Uri>, favorite: Boolean) =
        MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)

    suspend fun queryAll(): List<MediaItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA,
        ) + if (android.os.Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.MediaColumns.IS_FAVORITE) else emptyArray()
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)" +
            " AND ${MediaStore.MediaColumns.SIZE}>0"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val out = ArrayList<MediaItem>(4096)
        context.contentResolver.query(filesUri, projection, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(projection[0])
            val iType = c.getColumnIndexOrThrow(projection[1])
            val iName = c.getColumnIndexOrThrow(projection[2])
            val iSize = c.getColumnIndexOrThrow(projection[3])
            val iTaken = c.getColumnIndexOrThrow(projection[4])
            val iAdded = c.getColumnIndexOrThrow(projection[5])
            val iMime = c.getColumnIndexOrThrow(projection[6])
            val iBucket = c.getColumnIndexOrThrow(projection[7])
            val iBucketName = c.getColumnIndexOrThrow(projection[8])
            val iRel = c.getColumnIndexOrThrow(projection[9])
            val iDur = c.getColumnIndexOrThrow(projection[10])
            val iW = c.getColumnIndexOrThrow(projection[11])
            val iH = c.getColumnIndexOrThrow(projection[12])
            val iData = c.getColumnIndexOrThrow(projection[13])
            val iFav = if (projection.size > 14) c.getColumnIndex(projection[14]) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val name = c.getString(iName) ?: "media_$id"
                val size = c.getLong(iSize)
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000L)
                out += MediaItem(
                    key = MediaItem.keyOf(name, size),
                    localId = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = name,
                    mime = c.getString(iMime) ?: if (isVideo) "video/mp4" else "image/jpeg",
                    size = size,
                    dateTaken = taken,
                    bucketId = c.getString(iBucket),
                    bucketName = c.getString(iBucketName),
                    relativePath = c.getString(iRel),
                    durationMs = if (isVideo) c.getLong(iDur) else 0L,
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    path = c.getString(iData),
                    favorite = iFav >= 0 && c.getInt(iFav) == 1,
                )
            }
        }
        out.sortByDescending { it.dateTaken }
        out
    }

    /** Copies a downloaded backup file back into the device gallery. */
    suspend fun restore(file: File, item: MediaItem): Uri = withContext(Dispatchers.IO) {
        val isVideo = item.isVideo
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val fallback = if (isVideo) "Movies/Restored/" else "Pictures/Restored/"
        val paths = listOfNotNull(com.skyroll.app.security.Secrets.safeRelativePath(item.relativePath), fallback)
        val safeName = com.skyroll.app.security.Secrets.safeFileName(item.name, "restored")
        var lastError: Exception? = null
        for (rel in paths) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTaken)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values) ?: error("Could not create file")
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    }
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null) // don't leave a broken half-file behind
                    throw e
                }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                return@withContext uri
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Restore failed")
    }
}
