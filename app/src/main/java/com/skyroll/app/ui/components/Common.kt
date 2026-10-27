package com.skyroll.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType

/** One side margin for every screen: titles, labels, rows and card edges all line up on it. */
val Gutter = 16.dp

/** Lets a full-width item ignore its parent's horizontal padding (used in padded grids). */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val px = horizontal.roundToPx()
    val wider = constraints.copy(
        minWidth = constraints.minWidth + 2 * px,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * px else constraints.maxWidth,
    )
    val p = measurable.measure(wider)
    layout(p.width - 2 * px, p.height) { p.place(-px, 0) }
}

/** Large page title (104dp tall header). */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val n = LocalN.current
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 104.dp)
            .padding(start = Gutter, end = 4.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = NType.display, color = n.text)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle.uppercase(), style = NType.label, color = n.textTertiary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Text(
        text.uppercase(),
        style = NType.label,
        color = n.textTertiary,
        modifier = modifier.padding(horizontal = Gutter, vertical = 10.dp),
    )
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalN.current.text,
    background: Color = Color.Transparent,
    size: Dp = 48.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

enum class PillStyle { Primary, Secondary, Accent }

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val n = LocalN.current
    val (bg, fg) = when (style) {
        PillStyle.Primary -> n.text to n.bg
        PillStyle.Secondary -> Color.Transparent to n.text
        PillStyle.Accent -> n.accent to Color.White
    }
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) bg else n.surfaceHigh)
            .then(if (style == PillStyle.Secondary) Modifier.border(1.dp, n.outline, RoundedCornerShape(50)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else n.textTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = NType.bodyMedium, color = if (enabled) fg else n.textTertiary, maxLines = 1)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val n = LocalN.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(n.surface)
            .padding(18.dp)
    ) { content() }
}

@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val n = LocalN.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = NType.body, color = n.text)
            if (summary != null) Text(summary, style = NType.label.copy(letterSpacing = NType.label.letterSpacing), color = n.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = n.accent,
                checkedBorderColor = n.accent,
                uncheckedThumbColor = n.textTertiary,
                uncheckedTrackColor = n.surfaceHigh,
                uncheckedBorderColor = n.outline,
            ),
        )
    }
}

/** Progress drawn as a row of dots — the Nothing look. */
@Composable
fun DotProgress(progress: Float, modifier: Modifier = Modifier, dots: Int = 28) {
    val n = LocalN.current
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val gap = size.width / dots
        val r = minOf(gap * 0.32f, size.height / 2)
        val lit = (progress.coerceIn(0f, 1f) * dots).toInt()
        for (i in 0 until dots) {
            drawCircle(
                color = if (i < lit) n.accent else n.outline,
                radius = r,
                center = Offset(gap * i + gap / 2, size.height / 2),
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    RoundIconButton(
        androidx.compose.material.icons.Icons.Outlined.Settings, "Settings",
        tint = LocalN.current.textSecondary, onClick = onClick,
    )
}
