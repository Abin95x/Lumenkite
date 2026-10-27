package com.skyroll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.skyroll.app.data.MediaItem
import com.skyroll.app.data.thumbModel
import com.skyroll.app.explore.ScanState
import com.skyroll.app.ui.ExploreData
import com.skyroll.app.ui.GroupUi
import com.skyroll.app.ui.PersonUi
import com.skyroll.app.ui.components.Card
import com.skyroll.app.ui.components.DotProgress
import com.skyroll.app.ui.components.Gutter
import com.skyroll.app.ui.components.PillButton
import com.skyroll.app.ui.components.ScreenHeader
import com.skyroll.app.ui.components.SectionLabel
import com.skyroll.app.ui.components.SettingsButton
import com.skyroll.app.ui.components.Tile
import com.skyroll.app.ui.components.bleed
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.theme.ThemeToggleButton

@Composable
fun ExploreScreen(
    data: ExploreData,
    scan: ScanState,
    query: String,
    onQuery: (String) -> Unit,
    results: List<MediaItem>,
    columns: Int,
    onOpenGroup: (title: String, items: List<MediaItem>, personIds: List<Long>) -> Unit,
    onOpenItem: (List<MediaItem>, Int) -> Unit,
    onScanNow: () -> Unit,
    onSettings: () -> Unit,
) {
    val n = LocalN.current
    Box(Modifier.fillMaxSize().background(n.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Explore", subtitle = "On your phone · private") {
                ThemeToggleButton(); SettingsButton(onSettings)
            }
            SearchBar(query, onQuery)
            Spacer(Modifier.height(8.dp))
            if (query.isNotBlank()) {
                Results(query, results, columns, onOpenItem)
            } else {
                Overview(data, scan, onOpenGroup, onScanNow)
            }
        }
        StatusBarScrim()
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
    val n = LocalN.current
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(n.surface)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = n.textTertiary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("People, places, things, text…", style = NType.body, color = n.textTertiary)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = NType.body.copy(color = n.text),
                cursorBrush = SolidColor(n.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, "Clear", tint = n.textSecondary, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun Results(query: String, results: List<MediaItem>, columns: Int, onOpenItem: (List<MediaItem>, Int) -> Unit) {
    val n = LocalN.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (results.isEmpty()) "No results for \"$query\"" else "%,d results".format(results.size).uppercase(),
                style = NType.label, color = n.textTertiary,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 10.dp),
            )
        }
        items(results.size, key = { results[it].key + (results[it].localId ?: 0) }) { i ->
            Tile(results[i], compact = columns >= 6, selected = false, selecting = false,
                onClick = { onOpenItem(results, i) }, onLongClick = {})
        }
    }
}

@Composable
private fun Overview(
    data: ExploreData,
    scan: ScanState,
    onOpenGroup: (String, List<MediaItem>, List<Long>) -> Unit,
    onScanNow: () -> Unit,
) {
    val n = LocalN.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = Gutter - 4.dp, end = Gutter - 4.dp, bottom = 120.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }

        if (scan.running || data.scanned == 0) {
            item(span = full) {
                Box(Modifier.bleed(Gutter - 4.dp).padding(horizontal = Gutter, vertical = 8.dp)) {
                    Card {
                        Column {
                            if (scan.running) {
                                Text("Organizing your photos", style = NType.title, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (scan.total > 0) "%,d of %,d · runs on your phone, nothing is uploaded".format(scan.done, scan.total)
                                    else "Getting ready…",
                                    // One line, fixed-width digits: the card no longer jumps as the count changes.
                                    style = NType.body.copy(fontFeatureSettings = "tnum"),
                                    color = n.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(12.dp))
                                DotProgress(if (scan.total == 0) 0f else scan.done.toFloat() / scan.total)
                            } else {
                                Text("Find people, places and things", style = NType.title, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Lumenkite looks at your photos on this phone to group faces, read text and sort them into categories. Free, offline and private.",
                                    style = NType.body, color = n.textSecondary,
                                )
                                Spacer(Modifier.height(14.dp))
                                PillButton("Scan now", onClick = onScanNow)
                            }
                        }
                    }
                }
            }
        }

        if (data.people.isNotEmpty()) {
            item(span = full) { SectionLabel("People", Modifier.bleed(Gutter - 4.dp)) }
            item(span = full) {
                LazyRow(
                    Modifier.bleed(Gutter - 4.dp),
                    contentPadding = PaddingValues(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(data.people, key = { it.ids.first() }) { p -> PersonAvatar(p) { onOpenGroup(p.name ?: "Add a name", p.items, p.ids) } }
                }
            }
        }

        if (data.places.isNotEmpty()) {
            item(span = full) { SectionLabel("Places", Modifier.bleed(Gutter - 4.dp).padding(top = 14.dp)) }
            item(span = full) {
                LazyRow(
                    Modifier.bleed(Gutter - 4.dp),
                    contentPadding = PaddingValues(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(data.places, key = { it.title }) { g ->
                        CoverCard(g, Modifier.width(150.dp)) { onOpenGroup(g.title, g.items, emptyList()) }
                    }
                }
            }
        }

        if (data.categories.isNotEmpty()) {
            item(span = full) { SectionLabel("Categories", Modifier.bleed(Gutter - 4.dp).padding(top = 14.dp)) }
            items(data.categories, key = { "c" + it.title }) { g ->
                CoverCard(g, Modifier.padding(4.dp)) { onOpenGroup(g.title, g.items, emptyList()) }
            }
        }

        if (!scan.running && data.scanned > 0 && data.people.isEmpty() && data.categories.isEmpty()) {
            item(span = full) {
                Text("Nothing found yet.", style = NType.body, color = n.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
        }
    }
}

@Composable
private fun PersonAvatar(p: PersonUi, onClick: () -> Unit) {
    val n = LocalN.current
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(n.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (p.cover != null) {
                AsyncImage(p.cover, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Person, null, tint = n.textTertiary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            p.name ?: "Add name", style = NType.label.copy(letterSpacing = NType.label.letterSpacing),
            color = if (p.name != null) n.text else n.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Cover photo with the title over a soft gradient. */
@Composable
private fun CoverCard(g: GroupUi, modifier: Modifier, onClick: () -> Unit) {
    val n = LocalN.current
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(n.surfaceHigh)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(g.items.firstOrNull()?.thumbModel(), g.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xB3000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(g.title, style = NType.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("%,d".format(g.items.size), style = NType.label, color = Color(0xCCFFFFFF))
        }
    }
}
