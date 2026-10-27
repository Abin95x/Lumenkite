package com.skyroll.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.skyroll.app.ui.Tab
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType

/** Floating pill navigation: translucent, 1dp stroke. The active tab grows to show its label. */
@Composable
fun FloatingNavBar(current: Tab, uploading: Boolean, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(n.navBg)
            .border(1.dp, n.navStroke, RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTab(Icons.Outlined.Photo, "Photos", current == Tab.Photos, false) { onSelect(Tab.Photos) }
        NavTab(Icons.Outlined.Collections, "Albums", current == Tab.Albums, false) { onSelect(Tab.Albums) }
        NavTab(Icons.Outlined.Explore, "Explore", current == Tab.Explore, false) { onSelect(Tab.Explore) }
        NavTab(Icons.Outlined.Cloud, "Backup", current == Tab.Backup, uploading) { onSelect(Tab.Backup) }
    }
}

// One shared spring so width, colour and label all move together without bounce.
private val navSpring = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private const val NAV_MS = 280

@Composable
private fun NavTab(icon: ImageVector, label: String, active: Boolean, badge: Boolean, onClick: () -> Unit) {
    val n = LocalN.current
    val bg by animateColorAsState(if (active) n.text else Color.Transparent, tween(NAV_MS, easing = FastOutSlowInEasing), label = "navBg")
    val fg by animateColorAsState(if (active) n.bg else n.textSecondary, tween(NAV_MS, easing = FastOutSlowInEasing), label = "navFg")
    val pad by animateDpAsState(if (active) 18.dp else 14.dp, tween(NAV_MS, easing = FastOutSlowInEasing), label = "navPad")
    Row(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(icon, label, tint = fg, modifier = Modifier.size(24.dp))
            if (badge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(n.accent)
                )
            }
        }
        AnimatedVisibility(
            visible = active,
            enter = expandHorizontally(navSpring, expandFrom = Alignment.Start) + fadeIn(tween(NAV_MS, delayMillis = 60)),
            exit = shrinkHorizontally(navSpring, shrinkTowards = Alignment.Start) + fadeOut(tween(NAV_MS / 2)),
        ) {
            Text(label, style = NType.bodyMedium, color = fg, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Replaces the nav bar while items are selected. */
@Composable
fun SelectionBar(
    count: Int,
    onShare: () -> Unit,
    onBackup: () -> Unit,
    onDelete: () -> Unit,
    onVault: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = LocalN.current
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(n.navBg)
            .border(1.dp, n.navStroke, RoundedCornerShape(50))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.Outlined.Close, "Clear selection", size = 52.dp, onClick = onClose)
        Text("$count", style = NType.labelLarge, color = n.text, modifier = Modifier.padding(horizontal = 8.dp))
        RoundIconButton(Icons.Outlined.Share, "Share", size = 52.dp, onClick = onShare)
        RoundIconButton(Icons.Outlined.CloudUpload, "Back up", size = 52.dp, onClick = onBackup)
        RoundIconButton(Icons.Outlined.Lock, "Move to vault", size = 52.dp, onClick = onVault)
        RoundIconButton(Icons.Outlined.Delete, "Delete", size = 52.dp, tint = n.accent, onClick = onDelete)
    }
}
