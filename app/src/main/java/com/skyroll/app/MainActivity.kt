package com.skyroll.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.skyroll.app.ui.GalleryViewModel
import com.skyroll.app.ui.Screen
import com.skyroll.app.ui.Tab
import com.skyroll.app.ui.components.FloatingNavBar
import com.skyroll.app.ui.components.SelectionBar
import com.skyroll.app.ui.rememberGalleryActions
import com.skyroll.app.ui.screens.AlbumScreen
import com.skyroll.app.ui.screens.AlbumsScreen
import com.skyroll.app.ui.screens.BackupScreen
import com.skyroll.app.ui.screens.DeleteDialog
import com.skyroll.app.ui.screens.PermissionScreen
import com.skyroll.app.ui.screens.PhotosScreen
import com.skyroll.app.ui.screens.ViewerScreen
import com.skyroll.app.ui.screens.VaultScreen
import com.skyroll.app.ui.screens.SettingsScreen
import com.skyroll.app.ui.screens.ExploreScreen
import com.skyroll.app.ui.components.RoundIconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.layout.padding
import com.skyroll.app.ui.screens.authenticateVault
import com.skyroll.app.ui.screens.hasMediaAccess
import com.skyroll.app.ui.screens.mediaPermissions
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.ThemeRevealHost
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Lock the vault whenever the app leaves the screen.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                if (!isChangingConfigurations) vm.lockVault()
            }
        })
        setContent {
            val mode by vm.prefs.themeMode.collectAsState()
            ThemeRevealHost(mode, vm.prefs::setThemeMode) { Root(vm) }
        }
    }
}

@Composable
private fun Root(vm: GalleryViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaAccess(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMediaAccess(context)
    }
    // Re-check when returning from Settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { granted = hasMediaAccess(context) }
    }
    // Ask once for photo locations (needed for Places); Android hides GPS data from apps without it.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) vm.scanNow()
    }
    LaunchedEffect(granted) {
        if (granted) {
            vm.onPermissionGranted()
            val perm = android.Manifest.permission.ACCESS_MEDIA_LOCATION
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationLauncher.launch(perm)
            }
        }
    }

    if (!granted) {
        PermissionScreen { launcher.launch(mediaPermissions()) }
    } else {
        Gallery(vm)
    }
}

/**
 * Keeps each visited tab composed and just fades between them, so switching tabs never
 * rebuilds the photo grid (that rebuild is what made the nav animation stutter).
 */
@Composable
private fun KeepAliveTabs(current: Tab, content: @Composable (Tab) -> Unit) {
    val visited = remember { mutableStateListOf(current) }
    if (current !in visited) visited += current
    Box(Modifier.fillMaxSize()) {
        visited.forEach { t ->
            key(t) {
                val active = t == current
                val alpha by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "tabAlpha")
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (active) 1f else 0f)
                        .graphicsLayer { this.alpha = alpha }
                ) {
                    content(t)
                    // Hidden tabs must not catch touches.
                    if (!active) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                    })
                }
            }
        }
    }
}

@Composable
private fun Gallery(vm: GalleryViewModel) {
    val n = LocalN.current
    val actions = rememberGalleryActions()
    val scope = rememberCoroutineScope()
    val screen by vm.screen.collectAsState()
    val tab by vm.tab.collectAsState()
    val timeline by vm.timeline.collectAsState()
    val albums by vm.albums.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val columns by vm.columns.collectAsState()
    val selection by vm.selection.collectAsState()
    val backup by vm.backupState.collectAsState()
    val vaultItems by vm.vaultItems.collectAsState()
    val exploreData by vm.explore.collectAsState()
    val scanState by vm.scanState.collectAsState()
    val query by vm.query.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val context = LocalContext.current
    val photosGrid = rememberLazyGridState()
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler(enabled = selection.isNotEmpty() || screen != Screen.Main || tab != Tab.Photos) {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            vm.pop() -> Unit
            else -> vm.tab.value = Tab.Photos
        }
    }

    Box(Modifier.fillMaxSize().background(n.bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it::class },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.Main -> KeepAliveTabs(tab) { t ->
                    when (t) {
                        Tab.Photos -> PhotosScreen(
                            items = timeline.items,
                            entries = timeline.entries,
                            loaded = loaded,
                            columns = columns,
                            selection = selection,
                            uploading = backup.running,
                            gridState = photosGrid,
                            onOpen = { index -> vm.push(Screen.Viewer(timeline.items, index)) },
                            onToggle = vm::toggle,
                            onZoom = vm::zoom,
                            onBackupClick = { vm.tab.value = Tab.Backup },
                            onSettings = { vm.push(Screen.Settings) },
                        )
                        Tab.Albums -> AlbumsScreen(
                            albums,
                            onOpenVault = {
                                val activity = context as FragmentActivity
                                authenticateVault(activity, onSuccess = vm::unlockVault, onFail = actions::toast)
                            },
                            onSettings = { vm.push(Screen.Settings) },
                        ) { vm.push(Screen.AlbumDetail(it.id)) }
                        Tab.Explore -> ExploreScreen(
                            data = exploreData,
                            scan = scanState,
                            query = query,
                            onQuery = { vm.query.value = it },
                            results = searchResults,
                            columns = columns,
                            onOpenGroup = { title, items, ids -> vm.push(Screen.Collection(title, items, ids)) },
                            onOpenItem = { list, index -> vm.push(Screen.Viewer(list, index)) },
                            onScanNow = vm::scanNow,
                            onSettings = { vm.push(Screen.Settings) },
                        )
                        Tab.Backup -> BackupScreen(vm, actions, onOpenAlbum = { vm.push(Screen.AlbumDetail(it)) }, onSettings = { vm.push(Screen.Settings) })
                    }
                }
                is Screen.AlbumDetail -> {
                    val (name, items) = remember(s.albumId, albums) { vm.albumItems(s.albumId) }
                    AlbumScreen(
                        name = name,
                        items = items,
                        columns = columns,
                        selection = selection,
                        onBack = { vm.pop() },
                        onOpen = { list, index -> vm.push(Screen.Viewer(list, index)) },
                        onToggle = vm::toggle,
                        onZoom = vm::zoom,
                    )
                }
                is Screen.Viewer -> ViewerScreen(
                    items = s.items,
                    start = s.start,
                    actions = actions,
                    onBackUp = { vm.backUpNow(listOf(it.key)) },
                    onBack = { vm.pop() },
                    vault = s.vault,
                )
                is Screen.Collection -> {
                    var title by remember(s) { mutableStateOf(s.title) }
                    var renaming by remember(s) { mutableStateOf(false) }
                    AlbumScreen(
                        name = title,
                        items = s.items,
                        columns = columns,
                        selection = selection,
                        onBack = { vm.pop() },
                        onOpen = { list, index -> vm.push(Screen.Viewer(list, index)) },
                        onToggle = vm::toggle,
                        onZoom = vm::zoom,
                        extraAction = if (s.personIds.isEmpty()) null else ({
                            RoundIconButton(Icons.Outlined.Edit, "Name this person", modifier = Modifier.padding(top = 4.dp)) { renaming = true }
                        }),
                    )
                    if (renaming) {
                        RenameDialog(
                            initial = if (title == "Add a name") "" else title,
                            onDismiss = { renaming = false },
                            onSave = { name ->
                                vm.renamePerson(s.personIds, name)
                                title = name.ifBlank { "Add a name" }
                                renaming = false
                            },
                        )
                    }
                }
                Screen.Settings -> SettingsScreen(vm.prefs, onBack = { vm.pop() })
                Screen.Vault -> VaultScreen(
                    items = vaultItems,
                    columns = columns,
                    onBack = { vm.pop() },
                    onLock = { vm.lockVault() },
                    onOpen = { list, index -> vm.push(Screen.Viewer(list, index, vault = true)) },
                    onZoom = vm::zoom,
                )
            }
        }

        val showBars = screen !is Screen.Viewer
        AnimatedVisibility(
            visible = showBars && selection.isEmpty() && screen == Screen.Main,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(tab, backup.running, onSelect = { t ->
                if (t == tab && t == Tab.Photos) scope.launch { photosGrid.animateScrollToItem(0) }
                vm.tab.value = t
            })
        }
        AnimatedVisibility(
            visible = showBars && selection.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = selection.size,
                onShare = { actions.share(vm.selectedItems()) },
                onBackup = {
                    val keys = vm.selectedItems().filter { it.isLocal && !it.isBackedUp }.map { it.key }
                    if (keys.isEmpty()) actions.toast("Already backed up") else { vm.backUpNow(keys); actions.toast("Backing up ${keys.size} items") }
                    vm.clearSelection()
                },
                onDelete = { confirmDelete = true },
                onVault = { actions.moveToVault(vm.selectedItems()) { vm.clearSelection() } },
                onClose = vm::clearSelection,
            )
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            items = vm.selectedItems(),
            actions = actions,
            onDismiss = { confirmDelete = false },
            onDeleted = { confirmDelete = false; vm.clearSelection() },
        )
    }
}

/** Name a person. Giving two groups the same name merges them. */
@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val n = LocalN.current
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = n.surface,
        title = { androidx.compose.material3.Text("Who is this?", style = com.skyroll.app.ui.theme.NType.title, color = n.text) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { androidx.compose.material3.Text("Name") },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = n.text, unfocusedBorderColor = n.outline, cursorColor = n.accent,
                        focusedTextColor = n.text, unfocusedTextColor = n.text,
                    ),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                androidx.compose.material3.Text(
                    "Tip: if the same person shows up as two groups, give both the same name to merge them.",
                    style = com.skyroll.app.ui.theme.NType.label, color = n.textTertiary,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(text) }) { androidx.compose.material3.Text("Save", color = n.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Cancel", color = n.text) } },
    )
}
