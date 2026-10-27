package com.skyroll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyroll.app.data.MediaItem
import com.skyroll.app.ui.GridEntry
import com.skyroll.app.ui.components.MediaGrid
import com.skyroll.app.ui.components.RoundIconButton
import com.skyroll.app.ui.components.ScreenHeader
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.components.SettingsButton
import com.skyroll.app.ui.theme.ThemeToggleButton

@Composable
fun PhotosScreen(
    items: List<MediaItem>,
    entries: List<GridEntry>,
    loaded: Boolean,
    columns: Int,
    selection: Set<String>,
    uploading: Boolean,
    gridState: LazyGridState,
    onOpen: (Int) -> Unit,
    onToggle: (MediaItem) -> Unit,
    onZoom: (Int) -> Unit,
    onBackupClick: () -> Unit,
    onSettings: () -> Unit,
) {
    val n = LocalN.current
    val backedUp = items.count { it.isBackedUp }
    Box(Modifier.fillMaxSize().background(n.bg)) {
        MediaGrid(
            entries = entries,
            columns = columns,
            selection = selection,
            onOpen = { _, index -> onOpen(index) },
            onToggle = onToggle,
            onZoom = onZoom,
            state = gridState,
            header = {
                ScreenHeader(
                    title = if (selection.isEmpty()) "Photos" else "${selection.size} selected",
                    subtitle = if (items.isEmpty()) null else "%,d items · %,d backed up".format(items.size, backedUp),
                ) {
                    ThemeToggleButton()
                    SettingsButton(onSettings)
                }
            },
        )
        if (loaded && items.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing here yet", style = NType.displaySmall, color = n.text, textAlign = TextAlign.Center)
            }
        }
        StatusBarScrim()
    }
}

/** Fades content under the status bar, like Nothing's translucent toolbar. */
@Composable
fun StatusBarScrim() {
    val n = LocalN.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .height(top + 16.dp)
            .background(Brush.verticalGradient(listOf(n.scrim, n.scrim.copy(alpha = 0.85f), Color.Transparent)))
    )
}
