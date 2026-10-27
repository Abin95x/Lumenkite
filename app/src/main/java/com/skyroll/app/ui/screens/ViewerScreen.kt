package com.skyroll.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
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
    favoriteKeys: Set<String> = emptySet(),
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

    // The player of the video on screen, so the controls can live with the rest of the chrome.
    var activePlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    val playback = rememberPlayback(activePlayer)
    val videoControls = current.isVideo && activePlayer != null
    var scrubbing by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) }

    // While a video plays, hide the controls after a few seconds without interaction.
    LaunchedEffect(chrome, videoControls, playback.isPlaying, scrubbing, poke) {
        if (chrome && videoControls && playback.isPlaying && !scrubbing) {
            delay(3000)
            chrome = false
        }
    }
    LaunchedEffect(playback.ended) { if (playback.ended) chrome = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { items[it].key + (items[it].localId ?: 0) }) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(
                item,
                active = pager.settledPage == page,
                onTap = { chrome = !chrome },
                onPlayer = { activePlayer = it },
                onPlayerReleased = { if (activePlayer === it) activePlayer = null },
            )
            else ImagePage(item, onTap = { chrome = !chrome })
        }

        val player = activePlayer
        AnimatedVisibility(chrome && videoControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable {
                        poke++
                        val p = player ?: return@clickable
                        when {
                            playback.ended -> { p.seekTo(0); p.play() }
                            p.isPlaying -> p.pause()
                            else -> p.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        playback.ended -> Icons.Filled.Replay
                        playback.isPlaying -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = when {
                        playback.ended -> "Replay"
                        playback.isPlaying -> "Pause"
                        else -> "Play"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                    .navigationBarsPadding()
                    .padding(top = 24.dp),
            ) {
                if (videoControls && player != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(formatDuration(playback.position), style = NType.label, color = Color.White)
                        SeekBar(
                            position = playback.position,
                            duration = playback.duration,
                            onScrub = { scrubbing = it; poke++ },
                            onSeek = { player.seekTo(it); playback.position = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(formatDuration(playback.duration), style = NType.label, color = Color(0xB3FFFFFF))
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    RoundIconButton(Icons.Outlined.Share, "Share", tint = Color.White) { actions.share(listOf(current)) }
                    val fav = current.key in favoriteKeys
                    RoundIconButton(
                        if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (fav) "Remove from favorites" else "Add to favorites",
                        tint = if (fav) DarkN.accent else Color.White,
                    ) { actions.setFavorite(listOf(current), !fav) }
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

/** Video page. Playback controls are drawn by [ViewerScreen], so PlayerView's own controller is off. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    item: MediaItem,
    active: Boolean,
    onTap: () -> Unit,
    onPlayer: (ExoPlayer) -> Unit,
    onPlayerReleased: (ExoPlayer) -> Unit,
) {
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
            DisposableEffect(player) {
                onPlayer(player)
                onDispose {
                    onPlayerReleased(player)
                    player.release()
                }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Taps toggle the controls; horizontal swipes still reach the pager.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
            )
        } else {
            AsyncImage(item.thumbModel(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable { onTap() })
            if (active && item.isCloudOnly) DownloadingBadge(progress, Modifier.align(Alignment.Center))
        }
    }
}

/** Playback state of the active video, kept in sync with the player. */
private class Playback {
    var isPlaying by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
}

@Composable
private fun rememberPlayback(player: Player?): Playback {
    val state = remember(player) { Playback() }
    if (player == null) return state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                state.ended = playbackState == Player.STATE_ENDED
                if (player.duration > 0) state.duration = player.duration
            }
        }
        player.addListener(listener)
        state.isPlaying = player.isPlaying
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            state.position = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0) state.duration = player.duration
            delay(if (state.isPlaying) 200 else 500)
        }
    }
    return state
}

/** Thin seek bar: tap to jump, drag to scrub (seeks when the finger lifts). */
@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    onScrub: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    var width by remember { mutableIntStateOf(1) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)

    Box(
        modifier
            .height(32.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectTapGestures { if (duration > 0) seek((at(it.x) * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = at(it.x); scrub(true) },
                    onDragEnd = {
                        dragFraction?.let { if (duration > 0) seek((it * duration).toLong()) }
                        dragFraction = null
                        scrub(false)
                    },
                    onDragCancel = { dragFraction = null; scrub(false) },
                ) { change, _ ->
                    change.consume()
                    dragFraction = at(change.position.x)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val track = 3.dp.toPx()
            val x = size.width * fraction
            drawLine(Color(0x4DFFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = track, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(0f, y), Offset(x, y), strokeWidth = track, cap = StrokeCap.Round)
            drawCircle(Color.White, radius = (if (dragFraction != null) 8 else 6).dp.toPx(), center = Offset(x, y))
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
    val app = App.get(LocalContext.current)
    if (item.vaultPath != null) {
        val plain by produceState<File?>(null, item.vaultPath, enabled) {
            if (enabled) value = runCatching { app.vault.openPlain(item.vaultPath) }.getOrNull()
        }
        return plain to if (plain != null) 1f else 0f
    }
    val cloud = item.cloud ?: return null to 0f
    val tg = app.telegram
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
