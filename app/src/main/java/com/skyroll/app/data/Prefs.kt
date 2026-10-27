package com.skyroll.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small settings store. Exposes the settings the UI reacts to as flows. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("skyroll", Context.MODE_PRIVATE)

    data class BackupSettings(
        val autoBackup: Boolean,
        val wifiOnly: Boolean,
        val chargingOnly: Boolean,
        val excludedBuckets: Set<String>,
    )

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    private fun readSettings() = BackupSettings(
        autoBackup = sp.getBoolean("auto_backup", true),
        wifiOnly = sp.getBoolean("wifi_only", true),
        chargingOnly = sp.getBoolean("charging_only", false),
        excludedBuckets = sp.getStringSet("excluded_buckets", emptySet())!!.toSet(),
    )

    fun update(block: (BackupSettings) -> BackupSettings) {
        val s = block(_settings.value)
        sp.edit()
            .putBoolean("auto_backup", s.autoBackup)
            .putBoolean("wifi_only", s.wifiOnly)
            .putBoolean("charging_only", s.chargingOnly)
            .putStringSet("excluded_buckets", s.excludedBuckets)
            .apply()
        _settings.value = s
    }

    /** Telegram chat id of the private backup channel (0 = unknown). */
    var backupChatId: Long
        get() = sp.getLong("backup_chat_id", 0L)
        set(v) = sp.edit().putLong("backup_chat_id", v).apply()

    /** True once the whole channel history has been indexed at least once. */
    var fullSyncDone: Boolean
        get() = sp.getBoolean("full_sync_done", false)
        set(v) = sp.edit().putBoolean("full_sync_done", v).apply()

    /** Keys the user explicitly asked to back up (ignores album exclusions). */
    var forcedKeys: Set<String>
        get() = sp.getStringSet("forced_keys", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("forced_keys", v).apply()

    enum class ThemeMode { System, Light, Dark }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(sp.getString("theme_mode", "System")!!) }.getOrDefault(ThemeMode.System)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    fun setThemeMode(mode: ThemeMode) {
        sp.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    private val _appFavorites = MutableStateFlow(sp.getStringSet("app_favorites", emptySet())!!.toSet())
    /** Favorites for items not on the device (Android's flag only works for device files). */
    val appFavorites: StateFlow<Set<String>> = _appFavorites.asStateFlow()
    fun setAppFavorite(keys: Collection<String>, favorite: Boolean) {
        val s = if (favorite) _appFavorites.value + keys else _appFavorites.value - keys.toSet()
        sp.edit().putStringSet("app_favorites", s).apply()
        _appFavorites.value = s
    }

    var gridColumns: Int
        get() = sp.getInt("grid_columns", 4)
        set(v) = sp.edit().putInt("grid_columns", v).apply()
}
