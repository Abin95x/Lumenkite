package com.skyroll.app.explore

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PointF
import android.location.Geocoder
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.skyroll.app.App
import com.skyroll.app.backup.BackupScheduler
import com.skyroll.app.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ScanState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

object ExploreStatus {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    internal fun set(s: ScanState) { _state.value = s }
    internal val lock = Mutex()
}

/**
 * Looks at each device photo once, entirely on the phone (free, offline):
 * what's in it (labels), any text in it, faces (grouped into people) and where it was taken.
 */
class ExploreScanner(private val context: Context) {
    private val app = App.get(context)
    private val dao = app.explore.dao()

    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int, Int) -> Unit) = withContext(Dispatchers.Default) {
        val all = app.media.queryAll()
        val ids = all.mapNotNull { it.localId }
        if (ids.isNotEmpty()) {
            dao.pruneScanned(ids); dao.pruneLabels(ids); dao.pruneFaces(ids)
        }
        val done = dao.scannedIds(VERSION).toHashSet()
        // Newest first, so recent photos become searchable soonest.
        val todo = all.filter { it.localId != null && it.localId !in done }
        if (todo.isNotEmpty()) scan(todo, isStopped, onProgress)
        lookUpPlaces(isStopped)
    }

    private suspend fun scan(todo: List<MediaItem>, isStopped: () -> Boolean, onProgress: suspend (Int, Int) -> Unit) {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build())
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build()
        )
        val embedder = runCatching { FaceEmbedder(context) }.onFailure { Log.e(TAG, "face model", it) }.getOrNull()
        val people = PeopleClusterer(dao)
        people.load()
        try {
            todo.forEachIndexed { i, item ->
                if (isStopped()) return
                runCatching { scanOne(item, labeler, ocr, faces, embedder, people) }
                    .onFailure { Log.w(TAG, "scan ${item.name}", it) }
                if (i % 5 == 0 || i == todo.lastIndex) onProgress(i + 1, todo.size)
            }
        } finally {
            labeler.close(); ocr.close(); faces.close(); embedder?.close()
        }
    }

    private suspend fun scanOne(
        item: MediaItem,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        ocr: com.google.mlkit.vision.text.TextRecognizer,
        faceDetector: com.google.mlkit.vision.face.FaceDetector,
        embedder: FaceEmbedder?,
        people: PeopleClusterer,
    ) {
        val id = item.localId!!
        val bmp: Bitmap = context.contentResolver.loadThumbnail(item.uri!!, Size(1280, 1280), null)
        try {
            val input = InputImage.fromBitmap(bmp, 0)
            val labels = labeler.process(input).await().map { Label(id, it.text, it.confidence) }
            dao.clearLabels(id)
            if (labels.isNotEmpty()) dao.insertLabels(labels)

            var text: String? = null
            dao.clearFaces(id)
            if (!item.isVideo) {
                text = ocr.process(input).await().text.lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ").trim()
                    .take(3000).ifBlank { null }

                if (embedder != null) {
                    for (f in faceDetector.process(input).await()) {
                        val box = f.boundingBox
                        if (box.width() < 48) continue // too small to recognise reliably
                        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
                        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                        val nose = f.getLandmark(FaceLandmark.NOSE_BASE)?.position
                        val ml = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                        val mr = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
                        if (le == null || re == null || nose == null || ml == null || mr == null) continue
                        val emb = embedder.embed(bmp, listOf(PointF(le.x, le.y), PointF(re.x, re.y)), PointF(nose.x, nose.y),
                            listOf(PointF(ml.x, ml.y), PointF(mr.x, mr.y))) ?: continue
                        val personId = people.assign(emb)
                        dao.insertFace(
                            Face(
                                mediaId = id, personId = personId, embedding = FaceEmbedder.toBytes(emb),
                                left = box.left / bmp.width.toFloat(), top = box.top / bmp.height.toFloat(),
                                right = box.right / bmp.width.toFloat(), bottom = box.bottom / bmp.height.toFloat(),
                            )
                        )
                    }
                }
            }
            val latLng = if (item.isVideo) null else readLatLng(item)
            dao.upsertScanned(Scanned(id, VERSION, text, latLng?.get(0), latLng?.get(1), null))
        } finally {
            bmp.recycle()
        }
    }

    private fun readLatLng(item: MediaItem): DoubleArray? = runCatching {
        val original = MediaStore.setRequireOriginal(item.uri!!)
        context.contentResolver.openInputStream(original)?.use { ExifInterface(it).latLong }
    }.getOrNull()?.takeIf { it[0] != 0.0 || it[1] != 0.0 }

    /** Turns GPS positions into "City, State". Needs internet; retried on the next scan if offline. */
    private suspend fun lookUpPlaces(isStopped: () -> Boolean) {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(context, Locale.getDefault())
        val cache = HashMap<String, String?>()
        for (s in dao.missingPlaces()) {
            if (isStopped()) return
            val key = "%.2f,%.2f".format(Locale.US, s.lat, s.lng)
            val place = if (key in cache) cache[key] else runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(s.lat!!, s.lng!!, 1)?.firstOrNull()?.let { a ->
                    val city = a.locality ?: a.subAdminArea ?: a.adminArea
                    listOfNotNull(city, a.adminArea.takeIf { it != city }).joinToString(", ").ifBlank { a.countryName }
                }
            }.getOrNull().also { cache[key] = it }
            if (place != null) dao.setPlace(s.mediaId, place)
        }
    }

    companion object {
        /** Bump to rescan everything after improving the scanner. */
        const val VERSION = 1
        private const val TAG = "Explore"
    }
}

/** Groups face fingerprints into people: join the closest person if similar enough, else start a new one. */
private class PeopleClusterer(private val dao: ExploreDao) {
    private class P(val id: Long, var name: String?, var sum: FloatArray, var count: Int)
    private val people = ArrayList<P>()

    suspend fun load() {
        dao.persons().forEach { p ->
            val c = FaceEmbedder.fromBytes(p.centroid)
            people += P(p.id, p.name, FloatArray(c.size) { c[it] * p.faceCount }, p.faceCount)
        }
    }

    suspend fun assign(emb: FloatArray): Long {
        var best: P? = null
        var bestSim = -1f
        for (p in people) {
            val sim = FaceEmbedder.cosine(emb, FaceEmbedder.normalize(p.sum))
            if (sim > bestSim) { bestSim = sim; best = p }
        }
        val target = if (best != null && bestSim >= MATCH) best else null
        return if (target != null) {
            for (i in emb.indices) target.sum[i] += emb[i]
            target.count++
            dao.upsertPerson(Person(target.id, target.name, FaceEmbedder.toBytes(FaceEmbedder.normalize(target.sum)), target.count))
            target.id
        } else {
            val id = dao.insertPerson(Person(name = null, centroid = FaceEmbedder.toBytes(emb), faceCount = 1))
            people += P(id, null, emb.copyOf(), 1)
            id
        }
    }

    companion object {
        /** A bit stricter than OpenCV's pair threshold (0.363), since we compare against a group average. */
        const val MATCH = 0.42f
    }
}

class ExploreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!ExploreStatus.lock.tryLock()) return Result.success()
        try {
            ExploreStatus.set(ScanState(running = true))
            ExploreScanner(applicationContext).run(isStopped = { isStopped }) { done, total ->
                ExploreStatus.set(ScanState(true, done, total))
                promote(done, total)
            }
        } catch (e: Exception) {
            Log.e("Explore", "scan failed", e)
        } finally {
            ExploreStatus.set(ScanState(running = false))
            ExploreStatus.lock.unlock()
        }
        return Result.success()
    }

    private suspend fun promote(done: Int, total: Int) {
        if (total < 20) return // small catch-up scans don't need a notification
        val n = NotificationCompat.Builder(applicationContext, BackupScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Organizing your photos · $done/$total")
            .setContentText("Finding people, places and things — on your phone")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, false)
            .build()
        runCatching { setForeground(ForegroundInfo(43, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
    }
}

object ExploreScheduler {
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "explore-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExploreWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build(),
        )
        // Catch up on new photos soon after the app opens.
        wm.enqueueUniqueWork(
            "explore-soon", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExploreWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "explore-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ExploreWorker>().build(),
        )
    }
}
