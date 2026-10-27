package com.skyroll.app

import android.app.Application
import kotlinx.coroutines.launch
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import com.skyroll.app.backup.BackupScheduler
import com.skyroll.app.data.CloudDb
import com.skyroll.app.data.MediaRepository
import com.skyroll.app.data.Prefs
import com.skyroll.app.data.TgThumbFetcher
import com.skyroll.app.data.TgThumbKeyer
import com.skyroll.app.data.DeviceThumbFetcher
import com.skyroll.app.data.DeviceThumbKeyer
import com.skyroll.app.telegram.TelegramClient

class App : Application(), SingletonImageLoader.Factory {

    val prefs by lazy { Prefs(this) }
    val db by lazy { CloudDb.create(this) }
    val media by lazy { MediaRepository(this) }
    val telegram by lazy { TelegramClient(this, db.dao(), prefs, secrets) }
    val explore by lazy { com.skyroll.app.explore.ExploreDb.create(this) }
    val secrets by lazy { com.skyroll.app.security.Secrets(this) }
    val vault by lazy { com.skyroll.app.data.VaultRepository(this, telegram, secrets) }
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        BackupScheduler.createChannel(this)
        com.skyroll.app.explore.ExploreScheduler.createChannel(this)
        appScope.launch { vault.init() }
        telegram.start()
        BackupScheduler.schedule(this)
        com.skyroll.app.explore.ExploreScheduler.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(DeviceThumbFetcher.Factory())
                add(DeviceThumbKeyer())
                add(TgThumbFetcher.Factory(telegram))
                add(TgThumbKeyer())
                add(com.skyroll.app.data.FaceThumbFetcher.Factory())
                add(com.skyroll.app.data.FaceThumbKeyer())
                add(com.skyroll.app.data.VaultThumbFetcher.Factory(vault))
                add(com.skyroll.app.data.VaultThumbKeyer())
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .build()

    companion object {
        fun get(context: Context): App = context.applicationContext as App
    }
}
