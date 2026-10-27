package com.skyroll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.skyroll.app.data.Album
import com.skyroll.app.data.thumbModel
import com.skyroll.app.ui.GalleryViewModel
import com.skyroll.app.ui.components.ScreenHeader
import com.skyroll.app.ui.components.SectionLabel
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.components.SettingsButton
import com.skyroll.app.ui.components.Gutter
import com.skyroll.app.ui.components.bleed
import com.skyroll.app.ui.theme.ThemeToggleButton

@Composable
fun AlbumsScreen(albums: List<Album>, onOpenVault: () -> Unit, onSettings: () -> Unit, onOpen: (Album) -> Unit) {
    val n = LocalN.current
    val pinned = albums.filter { it.pinned }
    val others = albums.filter { !it.pinned }
    Box(Modifier.fillMaxSize().background(n.bg)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = Gutter - 4.dp, end = Gutter - 4.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScreenHeader("Albums", subtitle = "${albums.count { !it.id.startsWith("__") }} folders", modifier = Modifier.bleed(Gutter - 4.dp)) { ThemeToggleButton(); SettingsButton(onSettings) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Pinned albums", Modifier.bleed(Gutter - 4.dp)) }
            item(key = "vault") { VaultCard(onOpenVault) }
            items(pinned, key = { "p" + it.id }) { PinnedAlbum(it) { onOpen(it) } }
            if (others.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("More albums", Modifier.bleed(Gutter - 4.dp).padding(top = 18.dp)) }
                items(others, key = { "o" + it.id }) { OtherAlbum(it) { onOpen(it) } }
            }
        }
        StatusBarScrim()
    }
}

/** Pinned: rounded thumbnail with name + count below (radius 14dp / 11dp like Nothing). */
@Composable
private fun PinnedAlbum(album: Album, onClick: () -> Unit) {
    val n = LocalN.current
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(n.surface)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        AlbumThumb(album, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp)))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(album.name, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("%,d".format(album.count), style = NType.label, color = n.textTertiary)
        }
    }
}

/** Other albums: full-bleed cover with the label over a soft gradient. */
@Composable
private fun OtherAlbum(album: Album, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        AlbumThumb(album, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000)))
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(album.name, style = NType.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("%,d".format(album.count), style = NType.label, color = Color(0xCCFFFFFF))
        }
    }
}

@Composable
private fun AlbumThumb(album: Album, modifier: Modifier) {
    val n = LocalN.current
    val model = album.cover?.thumbModel()
    Box(modifier.background(n.surfaceHigh), contentAlignment = Alignment.Center) {
        if (model != null) {
            AsyncImage(model, album.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                when (album.id) {
                    GalleryViewModel.CLOUD_ID, GalleryViewModel.BACKED_UP_ID -> Icons.Outlined.Cloud
                    GalleryViewModel.FAVORITES_ID -> Icons.Outlined.FavoriteBorder
                    else -> Icons.Outlined.PhotoLibrary
                },
                null, tint = n.textTertiary, modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Locked album: no thumbnail, so nothing private shows before unlocking. */
@Composable
private fun VaultCard(onClick: () -> Unit) {
    val n = LocalN.current
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(n.surface)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp)).background(n.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Lock, null, tint = n.accent, modifier = Modifier.size(36.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text("Vault", style = NType.body, color = n.text)
            Text("LOCKED", style = NType.label, color = n.textTertiary)
        }
    }
}
