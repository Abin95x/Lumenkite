package com.skyroll.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.skyroll.app.data.Prefs.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.hypot

// Slow start, long gentle finish: the circle eases out instead of snapping to the edges.
private const val REVEAL_MS = 850
private val RevealEasing = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)

/** Switches theme with Telegram's circular reveal, starting from [origin] (root coordinates). */
class ThemeSwitcher(
    val isDark: () -> Boolean,
    val set: (mode: ThemeMode, origin: Offset) -> Unit,
) {
    fun toggle(origin: Offset) = set(if (isDark()) ThemeMode.Light else ThemeMode.Dark, origin)
}

val LocalThemeSwitcher = staticCompositionLocalOf<ThemeSwitcher> { error("No ThemeRevealHost") }

/**
 * How it works: before switching, take a picture of the screen in the old theme, switch, then
 * draw that picture on top with a growing circular hole. The new theme shows through the hole.
 */
@Composable
fun ThemeRevealHost(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val darkNow by rememberUpdatedState(dark)
    val changeMode by rememberUpdatedState(onModeChange)

    val layer = rememberGraphicsLayer()
    val radius = remember { Animatable(0f) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    val switcher = remember {
        ThemeSwitcher(
            isDark = { darkNow },
            set = { newMode, from ->
                val willBeDark = when (newMode) {
                    ThemeMode.System -> systemDark
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
                if (snapshot == null) {
                    if (willBeDark == darkNow || size == IntSize.Zero) {
                        changeMode(newMode)
                    } else scope.launch {
                        val bmp = runCatching { layer.toImageBitmap() }.getOrNull()
                        if (bmp == null) { changeMode(newMode); return@launch }
                        origin = from
                        radius.snapTo(0f)
                        snapshot = bmp
                        changeMode(newMode)
                        // Let the new theme finish its first two frames under the cover,
                        // so the reveal starts on a fully drawn screen (no stutter at the start).
                        withFrameNanos { }
                        withFrameNanos { }
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val maxR = maxOf(
                            hypot(from.x, from.y), hypot(w - from.x, from.y),
                            hypot(from.x, h - from.y), hypot(w - from.x, h - from.y),
                        )
                        radius.animateTo(maxR, tween(REVEAL_MS, easing = RevealEasing))
                        snapshot = null
                    }
                }
            },
        )
    }

    // Status/navigation bar icons follow the app theme, not the system one.
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val t = android.graphics.Color.TRANSPARENT
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
            navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
        )
    }

    SkyrollTheme(dark) {
        CompositionLocalProvider(LocalThemeSwitcher provides switcher) {
            Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                ) { content() }

                snapshot?.let { bmp ->
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                    ) {
                        val hole = Path().apply { addOval(Rect(origin, radius.value)) }
                        clipPath(hole, ClipOp.Difference) { drawImage(bmp) }
                    }
                }
            }
        }
    }
}

/** Sun/moon button that triggers the reveal from its own centre. */
@Composable
fun ThemeToggleButton(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val switcher = LocalThemeSwitcher.current
    val n = LocalN.current
    var center by remember { mutableStateOf(Offset.Zero) }
    LayoutBox(
        modifier
            .size(size)
            .clip(CircleShape)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clickable { switcher.toggle(center) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (n.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = if (n.isDark) "Switch to light mode" else "Switch to dark mode",
            tint = n.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}
