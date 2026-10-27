package com.skyroll.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.skyroll.app.App
import com.skyroll.app.data.MediaItem
import com.skyroll.app.telegram.TdException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

/** Live backup progress shown in the UI. */
data class BackupState(
    val running: Boolean = false,
    val currentName: String? = null,
    val currentProgress: Float = 0f,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
    val lastFinishedAt: Long = 0L,
    val stopping: Boolean = false,
)

object BackupStatus {
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()
    internal fun set(block: (BackupState) -> BackupState) = _state.update(block)

    /** Only one backup pass at a time, whichever worker started it. */
    internal val runLock = Mutex()

    internal val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Stops the running backup after cancelling the file currently uploading. */
    fun requestStop() {
        if (!_state.value.running) return
        stopRequested.set(true)
        set { it.copy(stopping = true) }
    }
}

object BackupScheduler {
    private const val PERIODIC = "backup-periodic"
    private const val ON_NEW_MEDIA = "backup-on-new-media"
    private const val MANUAL = "backup-manual"
    const val CHANNEL_ID = "backup"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while photos are uploaded to Telegram"
            }
        )
    }

    private fun constraints(context: Context): Constraints {
        val s = App.get(context).prefs.settings.value
        return Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.chargingOnly)
            .build()
    }

    /** Call on app start and whenever backup settings change. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val s = App.get(context).prefs.settings.value
        if (!s.autoBackup) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(ON_NEW_MEDIA)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(context))
                .build(),
        )
        watchNewMedia(context, ExistingWorkPolicy.REPLACE)
    }

    /** Runs a backup shortly after new photos/videos appear on the device. */
    fun watchNewMedia(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val base = constraints(context)
        val c = Constraints.Builder()
            .setRequiredNetworkType(base.requiredNetworkType)
            .setRequiresCharging(base.requiresCharging())
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(20, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_NEW_MEDIA,
            policy,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(c).addTag(ON_NEW_MEDIA).build(),
        )
    }

    /** "Back up now": any network, no charging requirement. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = App.get(context)

    override suspend fun doWork(): Result {
        try {
            BackupStatus.runLock.withLock { runBackup() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            BackupStatus.set { it.copy(running = false, stopping = false, currentName = null, lastError = "Stopped") }
            throw e
        } catch (e: Exception) {
            Log.e("Backup", "backup failed", e)
            BackupStatus.set { it.copy(running = false, lastError = e.message) }
        } finally {
            if (tags.contains("backup-on-new-media") && app.prefs.settings.value.autoBackup) {
                BackupScheduler.watchNewMedia(applicationContext)
            }
        }
        return Result.success()
    }

    private suspend fun runBackup() = coroutineScope {
        val tg = app.telegram
        tg.start()
        if (!tg.awaitReady()) return@coroutineScope
        val chatId = tg.ensureChannel()
        runCatching { tg.syncIndex(chatId) }

        val settings = app.prefs.settings.value
        val forced = app.prefs.forcedKeys
        val cloudKeys = app.db.dao().keys().toHashSet()
        val vaultKeys = app.vault.entries.value.map { it.key }.toHashSet()
        val premium = tg.account.value?.isPremium == true
        val maxSize = if (premium) 4000L * 1024 * 1024 else 2000L * 1024 * 1024

        val pending = app.media.queryAll().filter { item ->
            item.key !in cloudKeys && item.key !in vaultKeys &&
                (item.key in forced || item.bucketId !in settings.excludedBuckets)
        }
        if (pending.isEmpty()) {
            BackupStatus.set { it.copy(running = false, total = 0, done = 0, lastFinishedAt = System.currentTimeMillis()) }
            app.prefs.forcedKeys = emptySet()
            return@coroutineScope
        }

        BackupStatus.stopRequested.set(false)
        BackupStatus.set { BackupState(running = true, total = pending.size) }
        promote("Preparing backup…", 0, pending.size)

        var done = 0
        var failed = 0
        for (item in pending) {
            if (isStopped || BackupStatus.stopRequested.get()) break
            if (item.size > maxSize) {
                failed++
                BackupStatus.set { it.copy(failed = failed, lastError = "${item.name} is larger than Telegram's limit") }
                continue
            }
            BackupStatus.set { it.copy(currentName = item.name, currentProgress = 0f) }
            promote(item.name, done, pending.size)
            var tempCopy: File? = null
            var thumbFile: File? = null
            try {
                val path = item.path?.takeIf { File(it).canRead() }
                    ?: copyToCache(item).also { tempCopy = it }.absolutePath
                val thumb = makeThumbnail(item)?.also { thumbFile = File(it.first) }
                var progressJob: kotlinx.coroutines.Job? = null
                val upload = async {
                    tg.upload(chatId, item, path, thumb) { fileId ->
                        progressJob = launch {
                            tg.fileProgress(fileId).collect { f ->
                                val total = f.size.takeIf { it > 0 } ?: f.expectedSize
                                if (total > 0) {
                                    BackupStatus.set { it.copy(currentProgress = f.remote.uploadedSize.toFloat() / total) }
                                }
                            }
                        }
                    }
                }
                val stopWatch = launch {
                    while (isActive) {
                        if (BackupStatus.stopRequested.get()) { upload.cancel(); break }
                        delay(250)
                    }
                }
                try {
                    upload.await()
                } finally {
                    stopWatch.cancel()
                    progressJob?.cancel()
                }
                done++
                BackupStatus.set { it.copy(done = done, currentProgress = 1f) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (BackupStatus.stopRequested.get() && !isStopped) break else throw e
            } catch (e: TdException) {
                failed++
                BackupStatus.set { it.copy(failed = failed, lastError = e.error.message) }
                // Flood control or auth trouble: stop and let the next run continue.
                if (e.error.code == 429 || e.error.code == 401) break
            } catch (e: Exception) {
                failed++
                BackupStatus.set { it.copy(failed = failed, lastError = e.message) }
            } finally {
                tempCopy?.delete()
                thumbFile?.delete()
            }
        }
        app.prefs.forcedKeys = app.prefs.forcedKeys - pending.take(done + failed).map { it.key }.toSet()
        val wasStopped = BackupStatus.stopRequested.getAndSet(false)
        BackupStatus.set {
            it.copy(
                running = false, stopping = false, currentName = null, lastFinishedAt = System.currentTimeMillis(),
                lastError = if (wasStopped) "Stopped by you" else it.lastError,
            )
        }
    }

    private suspend fun promote(text: String, done: Int, total: Int) {
        val n = NotificationCompat.Builder(applicationContext, BackupScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up to Telegram · $done/$total")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        runCatching { setForeground(ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
    }

    private fun copyToCache(item: MediaItem): File {
        val dir = File(applicationContext.cacheDir, "upload").apply { mkdirs() }
        val out = File(dir, com.skyroll.app.security.Secrets.safeFileName(item.name, "upload"))
        applicationContext.contentResolver.openInputStream(item.uri!!)!!.use { input ->
            out.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        return out
    }

    /** Telegram document thumbnails must be JPEG, at most 320px per side. */
    private fun makeThumbnail(item: MediaItem): Triple<String, Int, Int>? = runCatching {
        val bmp = applicationContext.contentResolver.loadThumbnail(item.uri!!, Size(320, 320), null)
        val scale = minOf(1f, 320f / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val dir = File(applicationContext.cacheDir, "thumbs").apply { mkdirs() }
        val f = File(dir, "${item.localId}.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        Triple(f.absolutePath, scaled.width, scaled.height)
    }.getOrNull()
}
