package com.skyroll.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.skyroll.app.R

// Monochrome neutral palette with a red accent.
@Immutable
data class NColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val navBg: Color,
    val navStroke: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val DarkN = NColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF121212),
    surfaceHigh = Color(0xFF1C1C1C),
    outline = Color(0xFF292929),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFADADAD),
    textTertiary = Color(0xFF777777),
    accent = Color(0xFFD71921),
    navBg = Color(0xCF000000),
    navStroke = Color(0x29FFFFFF),
    scrim = Color(0xFF000000),
    isDark = true,
)

val LightN = NColors(
    bg = Color(0xFFFCFCFC),
    surface = Color(0xFFF2F2F2),
    surfaceHigh = Color(0xFFEBEBEB),
    outline = Color(0xFFE3E3E3),
    text = Color(0xFF000000),
    textSecondary = Color(0xFF606060),
    textTertiary = Color(0xFF929292),
    accent = Color(0xFFD71921),
    navBg = Color(0xCFFFFFFF),
    navStroke = Color(0x29000000),
    scrim = Color(0xFFFCFCFC),
    isDark = false,
)

val LocalN = staticCompositionLocalOf { DarkN }

@OptIn(ExperimentalTextApi::class)
val Geist = FontFamily(
    Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.geist, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.geist, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@OptIn(ExperimentalTextApi::class)
val GeistMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

object NType {
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em)
    val displaySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em)
    val number = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.03).em)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em)
    val labelLarge = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.06.em)
}

@Composable
fun SkyrollTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val n = if (dark) DarkN else LightN
    val scheme = if (dark) {
        darkColorScheme(
            primary = n.text, onPrimary = n.bg, background = n.bg, onBackground = n.text,
            surface = n.surface, onSurface = n.text, surfaceVariant = n.surfaceHigh, onSurfaceVariant = n.textSecondary,
            surfaceContainer = n.surface, surfaceContainerHigh = n.surfaceHigh, surfaceContainerLow = n.surface,
            outline = n.outline, error = n.accent,
        )
    } else {
        lightColorScheme(
            primary = n.text, onPrimary = n.bg, background = n.bg, onBackground = n.text,
            surface = n.surface, onSurface = n.text, surfaceVariant = n.surfaceHigh, onSurfaceVariant = n.textSecondary,
            surfaceContainer = n.surface, surfaceContainerHigh = n.surfaceHigh, surfaceContainerLow = n.surface,
            outline = n.outline, error = n.accent,
        )
    }
    CompositionLocalProvider(LocalN provides n) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
