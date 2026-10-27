package com.skyroll.app.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.skyroll.app.App
import com.skyroll.app.data.MediaItem
import com.skyroll.app.data.thumbModel
import com.skyroll.app.ui.GalleryActions
import com.skyroll.app.ui.components.DotProgress
import com.skyroll.app.ui.components.RoundIconButton
import com.skyroll.app.ui.components.formatBytes
import com.skyroll.app.ui.components.formatDuration
import com.skyroll.app.ui.theme.DarkN
import com.skyroll.app.ui.theme.NType
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    start: Int,
    actions: GalleryActions,
    onBackUp: (MediaItem) -> Unit,
    onBack: () -> Unit,
    vault: Boolean = false,
) {
    if (vault) SecureWindow()
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { items[it].key + (items[it].localId ?: 0) }) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item, active = pager.settledPage == page, onTap = { chrome = !chrome })
            else ImagePage(item, onTap = { chrome = !chrome })
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White, onClick = onBack)
                Column(Modifier.padding(start = 4.dp)) {
                    Text(dateFmt.format(Date(current.dateTaken)), style = NType.bodyMedium, color = Color.White)
                    Text(timeFmt.format(Date(current.dateTaken)).uppercase(), style = NType.label, color = Color(0xB3FFFFFF))
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIconButton(Icons.Outlined.Share, "Share", tint = Color.White) { actions.share(listOf(current)) }
                RoundIconButton(Icons.Outlined.Info, "Details", tint = Color.White) { showInfo = true }
                if (vault) {
                    RoundIconButton(Icons.Outlined.LockOpen, "Move out of vault", tint = Color.White) {
                        actions.moveOutOfVault(listOf(current)) { onBack() }
                    }
                } else {
                    when {
                        current.isCloudOnly -> RoundIconButton(Icons.Outlined.CloudDownload, "Save to device", tint = Color.White) { actions.restore(current) }
                        current.isBackedUp -> RoundIconButton(Icons.Outlined.CloudDone, "Backed up", tint = Color.White) { actions.toast("Backed up to Telegram") }
                        else -> RoundIconButton(Icons.Outlined.CloudUpload, "Back up", tint = DarkN.accent) { onBackUp(current); actions.toast("Backing up…") }
                    }
                    RoundIconButton(Icons.Outlined.Lock, "Move to vault", tint = Color.White) {
                        actions.moveToVault(listOf(current)) { onBack() }
                    }
                }
                RoundIconButton(Icons.Outlined.Delete, "Delete", tint = Color.White) { confirmDelete = true }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = DarkN.surface) {
            InfoSheet(current)
        }
    }
    if (confirmDelete && vault) {
        VaultDeleteDialog(listOf(current), actions, onDismiss = { confirmDelete = false }, onDeleted = { confirmDelete = false; onBack() })
    } else if (confirmDelete) {
        DeleteDialog(
            items = listOf(current),
            actions = actions,
            onDismiss = { confirmDelete = false },
            onDeleted = { confirmDelete = false; onBack() },
        )
    }
}

@Composable
fun DeleteDialog(items: List<MediaItem>, actions: GalleryActions, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val local = items.filter { it.isLocal }
    val cloud = items.filter { it.cloud != null }
    val what = if (items.size == 1) "this item" else "${items.size} items"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $what?", style = NType.title) },
        text = {
            Text(
                when {
                    local.isEmpty() -> "It's only stored on Telegram. Deleting removes it for good."
                    cloud.isEmpty() -> "It isn't backed up yet. Deleting removes it for good."
                    else -> "\"Device only\" keeps the Telegram backup, so you can still see it here."
                },
                style = NType.body,
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (local.isNotEmpty()) {
                    TextButton(onClick = { actions.deleteFromDevice(local, after = onDeleted) }) {
                        Text(if (cloud.isEmpty()) "Delete" else "Device only")
                    }
                }
                if (cloud.isNotEmpty()) {
                    TextButton(onClick = {
                        actions.deleteFromCloud(cloud) {
                            if (local.isNotEmpty()) actions.deleteFromDevice(local, after = onDeleted) else onDeleted()
                        }
                    }) { Text(if (local.isEmpty()) "Delete" else "Device and Telegram", color = DarkN.accent) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InfoSheet(item: MediaItem) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text(item.name, style = NType.title, color = Color.White)
        Spacer(Modifier.height(16.dp))
        InfoRow("Date", "${dateFmt.format(Date(item.dateTaken))} · ${timeFmt.format(Date(item.dateTaken))}")
        InfoRow("Size", formatBytes(item.size))
        if (item.width > 0) InfoRow("Resolution", "${item.width} × ${item.height}")
        if (item.isVideo && item.durationMs > 0) InfoRow("Duration", formatDuration(item.durationMs))
        item.relativePath?.let { InfoRow("Folder", it) }
        InfoRow(
            "Backup",
            when {
                item.isCloudOnly -> "Telegram only (not on this device)"
                item.isBackedUp -> "On device and Telegram"
                else -> "Device only — not backed up yet"
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label.uppercase(), style = NType.label, color = DarkN.textTertiary, modifier = Modifier.width(110.dp))
        Text(value, style = NType.body, color = Color.White)
    }
}

/** Full image: device copy directly, or downloads the original from Telegram first. */
@Composable
private fun ImagePage(item: MediaItem, onTap: () -> Unit) {
    val (model, progress) = rememberOriginal(item)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (model != null) {
            ZoomableAsyncImage(
                model = model,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                onClick = { onTap() },
            )
        } else {
            AsyncImage(item.thumbModel(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable { onTap() })
            DownloadingBadge(progress, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val (model, progress) = rememberOriginal(item, enabled = active)
    val uri: Uri? = when (model) {
        is Uri -> model
        is File -> Uri.fromFile(model)
        else -> null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (active && uri != null) {
            val player = remember(uri) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(ExoItem.fromUri(uri))
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(item.thumbModel(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable { onTap() })
            if (active && item.isCloudOnly) DownloadingBadge(progress, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun DownloadingBadge(progress: Float, modifier: Modifier) {
    Column(
        modifier
            .background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(16.dp)
            .width(180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FROM TELEGRAM", style = NType.label, color = Color.White)
        Spacer(Modifier.height(10.dp))
        DotProgress(progress, dots = 16)
    }
}

/** Returns (model, progress). Model is the device Uri, or the downloaded File for cloud-only items. */
@Composable
private fun rememberOriginal(item: MediaItem, enabled: Boolean = true): Pair<Any?, Float> {
    if (item.uri != null) return item.uri to 1f
    val cloud = item.cloud ?: return null to 0f
    val tg = App.get(LocalContext.current).telegram
    var progress by remember(cloud.fileId) { mutableFloatStateOf(0f) }
    LaunchedEffect(cloud.fileId, enabled) {
        if (!enabled) return@LaunchedEffect
        tg.fileProgress(cloud.fileId).collect { f ->
            if (f.size > 0) progress = f.local.downloadedSize.toFloat() / f.size
        }
    }
    val file by produceState<File?>(null, cloud.fileId, enabled) {
        if (enabled) value = runCatching { tg.download(cloud.fileId, 32) }.getOrNull()
    }
    return file to progress
}

private val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

@Composable
fun VaultDeleteDialog(items: List<MediaItem>, actions: GalleryActions, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val what = if (items.size == 1) "this item" else "${items.size} items"
    val hasCloud = items.any { it.cloud != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $what for good?", style = NType.title) },
        text = {
            Text(
                if (hasCloud) "Removes it from the vault and from your Telegram backup. This can't be undone."
                else "Removes it from the vault. This can't be undone.",
                style = NType.body,
            )
        },
        confirmButton = { TextButton(onClick = { actions.deleteFromVault(items, onDeleted) }) { Text("Delete", color = DarkN.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Blocks screenshots and blanks the recent-apps preview while vault content is visible. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        secureCount++
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            // Vault screen and vault viewer can overlap; clear only when the last one leaves.
            if (--secureCount == 0) activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private var secureCount = 0
