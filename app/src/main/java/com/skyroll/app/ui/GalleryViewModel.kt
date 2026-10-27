package com.skyroll.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyroll.app.App
import com.skyroll.app.backup.BackupScheduler
import com.skyroll.app.backup.BackupStatus
import com.skyroll.app.data.Album
import com.skyroll.app.data.MediaItem
import com.skyroll.app.data.Prefs
import com.skyroll.app.data.FaceThumb
import com.skyroll.app.explore.ExploreScheduler
import com.skyroll.app.explore.ExploreStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** A row in a date-grouped grid. */
sealed interface GridEntry {
    val id: String
    data class Header(override val id: String, val title: String, val count: Int) : GridEntry
    data class Tile(val item: MediaItem, val index: Int) : GridEntry { override val id get() = item.key + "#" + (item.localId ?: 0) }
}

data class Timeline(val items: List<MediaItem>, val entries: List<GridEntry>)

data class PersonUi(val ids: List<Long>, val name: String?, val cover: FaceThumb?, val items: List<MediaItem>)
data class GroupUi(val title: String, val items: List<MediaItem>)
data class ExploreData(
    val people: List<PersonUi> = emptyList(),
    val places: List<GroupUi> = emptyList(),
    val categories: List<GroupUi> = emptyList(),
    val scanned: Int = 0,
)

/** Per-photo search data, lowercased. */
private class SearchIndex(
    val labels: Map<Long, List<String>> = emptyMap(),
    val text: Map<Long, String> = emptyMap(),
    val place: Map<Long, String> = emptyMap(),
    val people: Map<Long, List<String>> = emptyMap(),
)

sealed interface Screen {
    data object Main : Screen
    data class AlbumDetail(val albumId: String) : Screen
    data class Viewer(val items: List<MediaItem>, val start: Int, val vault: Boolean = false) : Screen
    data object Vault : Screen
    data object Settings : Screen
    /** A ready-made list: a category, a place or a person. [personIds] enables renaming. */
    data class Collection(val title: String, val items: List<MediaItem>, val personIds: List<Long> = emptyList()) : Screen
}

enum class Tab { Photos, Albums, Explore, Backup }

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = App.get(application)
    val telegram = app.telegram
    val prefs: Prefs = app.prefs
    val backupState = BackupStatus.state

    private val local = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Device items with their backup attached, plus backups no longer on the device. */
    private val cloudFlow = app.db.dao().observeAll()

    val all: StateFlow<List<MediaItem>> = combine(local, cloudFlow, app.vault.entries, prefs.appFavorites) { loc, cloud, vault, appFav ->
        val byKey = cloud.associateBy { it.key }
        // Vault keys are treated as "already seen" so hidden items never show in the gallery.
        val localKeys = HashSet<String>(loc.size + vault.size)
        vault.forEach { localKeys += it.key }
        val merged = ArrayList<MediaItem>(loc.size + cloud.size)
        loc.forEach { if (it.key !in localKeys) { localKeys += it.key; merged += it.copy(cloud = byKey[it.key]) } }
        cloud.forEach { if (it.key !in localKeys) { localKeys += it.key; merged += MediaItem.fromCloud(it).copy(favorite = it.key in appFav) } }
        merged.sortByDescending { it.dateTaken }
        merged as List<MediaItem>
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val vaultItems: StateFlow<List<MediaItem>> = combine(app.vault.entries, cloudFlow, prefs.appFavorites) { vault, cloud, appFav ->
        val byKey = cloud.associateBy { it.key }
        vault.map { it.toMediaItem(byKey[it.key]).copy(favorite = it.key in appFav) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked.asStateFlow()
    fun unlockVault() { _vaultUnlocked.value = true; push(Screen.Vault) }

    /** Called when the app leaves the screen: lock and close any vault screens. */
    fun lockVault() {
        if (!_vaultUnlocked.value) return
        _vaultUnlocked.value = false
        // Forget everything decrypted: temp files and thumbnails held in memory.
        app.vault.wipePlain()
        coil3.SingletonImageLoader.get(getApplication()).memoryCache?.clear()
        val firstVault = _stack.value.indexOfFirst { it is Screen.Vault || (it is Screen.Viewer && it.vault) }
        if (firstVault > 0) _stack.value = _stack.value.take(firstVault)
    }

    /** Live favorite state (the viewer's list is a snapshot, so it asks here). */
    val favoriteKeys: StateFlow<Set<String>> = combine(all, prefs.appFavorites, app.vault.entries) { items, appFav, _ ->
        // Device items follow Android's flag (so changes made in other gallery apps show here);
        // the app's own list only counts for items that aren't on the device.
        val onDevice = items.filter { it.isLocal }.mapTo(HashSet()) { it.key }
        items.filter { it.favorite }.mapTo(HashSet()) { it.key } + appFav.filter { it !in onDevice }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val timeline: StateFlow<Timeline> = all.map { Timeline(it, group(it)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Timeline(emptyList(), emptyList()))

    val albums: StateFlow<List<Album>> = all.map { buildAlbums(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------ explore
    val scanState = ExploreStatus.state
    private val exploreDao = app.explore.dao()

    private data class Raw(
        val scanned: List<com.skyroll.app.explore.Scanned>,
        val labels: List<com.skyroll.app.explore.Label>,
        val faces: List<com.skyroll.app.explore.FaceLite>,
        val persons: List<com.skyroll.app.explore.Person>,
    )

    private val raw = combine(
        exploreDao.observeScanned(), exploreDao.observeLabels(), exploreDao.observeFaces(), exploreDao.observePersons(),
    ) { s, l, f, p -> Raw(s, l, f, p) }

    @OptIn(FlowPreview::class)
    private val exploreAll = combine(all, raw) { items, r -> items to r }
        .debounce(300) // scanning writes often; don't rebuild on every row
        .flowOn(Dispatchers.Default)

    val explore: StateFlow<ExploreData> = exploreAll.map { (items, r) -> buildExplore(items, r) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, ExploreData())

    private val searchIndex: StateFlow<SearchIndex> = exploreAll.map { (_, r) ->
        val names = r.persons.associate { it.id to it.name?.lowercase() }
        SearchIndex(
            labels = r.labels.groupBy({ it.mediaId }, { it.label.lowercase() }),
            text = r.scanned.mapNotNull { s -> s.text?.let { s.mediaId to it } }.toMap(),
            place = r.scanned.mapNotNull { s -> s.place?.let { s.mediaId to it.lowercase() } }.toMap(),
            people = r.faces.groupBy({ it.mediaId }, { names[it.personId] }).mapValues { (_, v) -> v.filterNotNull() },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SearchIndex())

    val query = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<MediaItem>> = combine(query.debounce(150), all, searchIndex) { q, items, idx ->
        search(q, items, idx)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun scanNow() = ExploreScheduler.scanNow(getApplication())

    fun renamePerson(ids: List<Long>, name: String) {
        viewModelScope.launch { exploreDao.rename(ids, name.trim().ifEmpty { null }) }
    }

    private fun buildExplore(items: List<MediaItem>, r: Raw): ExploreData {
        val byId = HashMap<Long, MediaItem>(items.size)
        items.forEach { it.localId?.let { id -> byId[id] = it } }
        fun list(ids: Collection<Long>) = ids.distinct().mapNotNull { byId[it] }.sortedByDescending { it.dateTaken }

        // People: groups with the same name are shown as one person (that's how you merge groups).
        val personName = r.persons.associate { it.id to it.name?.trim()?.ifEmpty { null } }
        val facesByPerson = r.faces.groupBy { it.personId }
        val people = facesByPerson.entries
            .groupBy { (pid, _) -> personName[pid]?.lowercase() ?: "#$pid" }
            .map { (_, groups) ->
                val faces = groups.flatMap { it.value }
                val name = personName[groups.first().key]
                val cover = faces.filter { byId[it.mediaId]?.uri != null }
                    .maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                PersonUi(
                    ids = groups.map { it.key },
                    name = name,
                    cover = cover?.let { FaceThumb(byId[it.mediaId]!!.uri!!, it.left, it.top, it.right, it.bottom) },
                    items = list(faces.map { it.mediaId }),
                )
            }
            .filter { it.cover != null && (it.name != null || it.items.size >= 3) }
            .sortedWith(compareByDescending<PersonUi> { it.name != null }.thenByDescending { it.items.size })

        val places = r.scanned.filter { it.place != null }.groupBy { it.place!! }
            .map { (place, s) -> GroupUi(place, list(s.map { it.mediaId })) }
            .filter { it.items.isNotEmpty() }
            .sortedByDescending { it.items.size }

        val categories = buildList {
            val docs = r.scanned.filter { (it.text?.length ?: 0) > 150 }.map { it.mediaId }
                .filter { byId[it]?.bucketName?.contains("screenshot", true) != true }
            if (docs.size >= 3) add(GroupUi("Documents", list(docs)))
            addAll(
                r.labels.groupBy { it.label }
                    .map { (label, l) -> GroupUi(label, list(l.map { it.mediaId })) }
                    .filter { it.items.size >= 3 }
                    .sortedByDescending { it.items.size }
                    .take(40)
            )
        }
        return ExploreData(people, places, categories, scanned = r.scanned.size)
    }

    private fun search(raw: String, items: List<MediaItem>, idx: SearchIndex): List<MediaItem> {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return items.filter { item ->
            val id = item.localId
            (id != null && (
                idx.labels[id]?.any { it.contains(q) } == true ||
                    idx.text[id]?.contains(q) == true ||
                    idx.place[id]?.contains(q) == true ||
                    idx.people[id]?.any { it.contains(q) } == true
                )) ||
                item.name.lowercase().contains(q) ||
                item.bucketName?.lowercase()?.contains(q) == true ||
                monthFmt.format(java.util.Date(item.dateTaken)).lowercase().contains(q)
        }
    }

    // ------------------------------------------------------------ navigation + ui state
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Main))
    val screen = _stack.map { it.last() }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Main)
    fun push(s: Screen) { _stack.value = _stack.value + s }
    fun pop(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.value = _stack.value.dropLast(1); return true
    }

    val tab = MutableStateFlow(Tab.Photos)

    private val _columns = MutableStateFlow(prefs.gridColumns)
    val columns: StateFlow<Int> = _columns.asStateFlow()
    fun zoom(steps: Int) {
        val levels = COLUMN_LEVELS
        val i = levels.indexOf(_columns.value).let { if (it < 0) 2 else it }
        val n = levels[(i + steps).coerceIn(0, levels.lastIndex)]
        _columns.value = n; prefs.gridColumns = n
    }

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()
    fun toggle(item: MediaItem) {
        val k = item.key
        _selection.value = if (k in _selection.value) _selection.value - k else _selection.value + k
    }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectedItems(): List<MediaItem> = all.value.filter { it.key in _selection.value }

    // ------------------------------------------------------------ loading
    private var started = false
    fun onPermissionGranted() {
        if (started) return
        started = true
        viewModelScope.launch {
            app.media.changes().collect {
                local.value = app.media.queryAll()
                _loaded.value = true
            }
        }
    }

    fun backUpNow(keys: Collection<String> = emptyList()) {
        if (keys.isNotEmpty()) prefs.forcedKeys = prefs.forcedKeys + keys
        BackupScheduler.runNow(getApplication())
    }

    fun updateSettings(block: (Prefs.BackupSettings) -> Prefs.BackupSettings) {
        prefs.update(block)
        BackupScheduler.schedule(getApplication())
    }

    // ------------------------------------------------------------ helpers
    fun albumItems(id: String): Pair<String, List<MediaItem>> {
        val a = albums.value.firstOrNull { it.id == id }
        return (a?.name ?: "Album") to (a?.items ?: emptyList())
    }

    private fun buildAlbums(items: List<MediaItem>): List<Album> {
        val local = items.filter { it.isLocal }
        val buckets = local.groupBy { it.bucketId ?: "?" }.map { (id, list) ->
            val name = list.first().bucketName ?: "Unknown"
            Album(id = id, name = name, items = list, pinned = name.equals("Camera", true) || name.equals("Screenshots", true))
        }.sortedWith(compareByDescending<Album> { it.pinned }.thenByDescending { it.cover?.dateTaken ?: 0 })
        val videos = local.filter { it.isVideo }
        val cloudOnly = items.filter { it.isCloudOnly }
        val virtual = buildList {
            add(Album(FAVORITES_ID, "Favorites", items.filter { it.favorite }, pinned = true))
            add(Album(BACKED_UP_ID, "Backed up", items.filter { it.isBackedUp }, pinned = true))
            if (videos.isNotEmpty()) add(Album(VIDEOS_ID, "Videos", videos, pinned = true))
            add(Album(CLOUD_ID, "Telegram only", cloudOnly, pinned = true))
        }
        return buckets.filter { it.pinned } + virtual + buckets.filter { !it.pinned }
    }

    companion object {
        val COLUMN_LEVELS = listOf(2, 3, 4, 6, 8)
        const val VIDEOS_ID = "__videos"
        const val CLOUD_ID = "__cloud"
        const val BACKED_UP_ID = "__backed_up"
        const val FAVORITES_ID = "__favorites"

        fun group(items: List<MediaItem>): List<GridEntry> {
            val out = ArrayList<GridEntry>(items.size + items.size / 8)
            val cal = Calendar.getInstance()
            val nowYear = cal.get(Calendar.YEAR)
            val now = System.currentTimeMillis()
            val today = dayIndex(cal, now)
            val yesterday = dayIndex(cal, now - 86_400_000L)
            val fmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
            val fmtYear = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
            var i = 0
            while (i < items.size) {
                val day = dayIndex(cal, items[i].dateTaken)
                var j = i
                while (j < items.size && dayIndex(cal, items[j].dateTaken) == day) j++
                cal.timeInMillis = items[i].dateTaken
                val title = when (day) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> if (cal.get(Calendar.YEAR) == nowYear) fmt.format(cal.time) else fmtYear.format(cal.time)
                }
                out += GridEntry.Header("h$day", title, j - i)
                for (k in i until j) out += GridEntry.Tile(items[k], k)
                i = j
            }
            return out
        }

        private fun dayIndex(cal: Calendar, ms: Long): Int {
            cal.timeInMillis = ms
            return cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR)
        }
    }
}
