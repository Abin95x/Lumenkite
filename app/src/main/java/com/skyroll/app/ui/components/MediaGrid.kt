package com.skyroll.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.skyroll.app.data.MediaItem
import com.skyroll.app.data.thumbModel
import com.skyroll.app.ui.GridEntry
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType

/**
 * Date-grouped photo grid. Pinch to change density, long-press to select.
 * Square tiles with ~1dp gaps and no rounding, as in Nothing Gallery.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    entries: List<GridEntry>,
    columns: Int,
    selection: Set<String>,
    onOpen: (MediaItem, Int) -> Unit,
    onToggle: (MediaItem) -> Unit,
    onZoom: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
    bottomPadding: androidx.compose.ui.unit.Dp = 120.dp,
) {
    val selecting = selection.isNotEmpty()
    val zoom by rememberUpdatedState(onZoom)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var scale = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            scale *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            if (scale > 1.3f) { zoom(-1); scale = 1f }
                            else if (scale < 0.75f) { zoom(1); scale = 1f }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        if (header != null) {
            item(key = "__header", span = { GridItemSpan(maxLineSpan) }, contentType = "top") { header() }
        }
        items(
            entries,
            key = { it.id },
            span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is GridEntry.Header) 0 else 1 },
        ) { e ->
            when (e) {
                is GridEntry.Header -> DateHeader(e.title, e.count)
                is GridEntry.Tile -> Tile(
                    item = e.item,
                    compact = columns >= 6,
                    selected = e.item.key in selection,
                    selecting = selecting,
                    onClick = { if (selecting) onToggle(e.item) else onOpen(e.item, e.index) },
                    onLongClick = { onToggle(e.item) },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(title: String, count: Int) {
    val n = LocalN.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = NType.bodyMedium, color = n.text, modifier = Modifier.weight(1f))
        Text("$count", style = NType.label, color = n.textTertiary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    item: MediaItem,
    compact: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = LocalN.current
    val s by animateFloatAsState(if (selected) 0.78f else 1f, label = "sel")
    Box(
        modifier
            .aspectRatio(1f)
            .background(n.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = item.thumbModel(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(s)
                .then(if (selected) Modifier.clip(RoundedCornerShape(10.dp)) else Modifier),
        )
        if (item.favorite && !compact && !selecting) {
            Icon(
                Icons.Filled.Favorite, "Favorite", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(16.dp),
            )
        }
        if (item.isVideo && !compact) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                if (item.durationMs > 0) Text(formatDuration(item.durationMs), style = NType.label, color = Color.White)
            }
        }
        // Only flag items that are no longer on the device; backed-up local items stay clean.
        if (!compact && !selecting && item.isCloudOnly) {
            CornerIcon(Icons.Outlined.CloudQueue, Modifier.align(Alignment.TopEnd))
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(Color.White)
                        else Modifier.border(1.5.dp, Color.White, CircleShape).background(Color(0x33000000))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = n.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CornerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Box(
        modifier
            .padding(5.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Color(0x80000000)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(13.dp))
    }
}
