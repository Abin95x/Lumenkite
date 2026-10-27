package com.skyroll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyroll.app.data.MediaItem
import com.skyroll.app.ui.GalleryViewModel
import com.skyroll.app.ui.components.MediaGrid
import com.skyroll.app.ui.components.RoundIconButton
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.theme.ThemeToggleButton
import com.skyroll.app.ui.components.Gutter

@Composable
fun AlbumScreen(
    name: String,
    items: List<MediaItem>,
    columns: Int,
    selection: Set<String>,
    onBack: () -> Unit,
    onOpen: (List<MediaItem>, Int) -> Unit,
    onToggle: (MediaItem) -> Unit,
    onZoom: (Int) -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
) {
    val n = LocalN.current
    val entries = remember(items) { GalleryViewModel.group(items) }
    Box(Modifier.fillMaxSize().background(n.bg)) {
        MediaGrid(
            entries = entries,
            columns = columns,
            selection = selection,
            onOpen = { _, index -> onOpen(items, index) },
            onToggle = onToggle,
            onZoom = onZoom,
            header = {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.padding(start = 4.dp, top = 4.dp), onClick = onBack)
                        Box(Modifier.weight(1f))
                        extraAction?.invoke()
                        ThemeToggleButton(Modifier.padding(end = 4.dp, top = 4.dp))
                    }
                    Row(Modifier.padding(start = Gutter, end = Gutter, top = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(name, style = NType.display, color = n.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text("%,d ITEMS".format(items.size), style = NType.label, color = n.textTertiary, modifier = Modifier.padding(start = Gutter, top = 4.dp))
                }
            },
        )
        StatusBarScrim()
    }
}
