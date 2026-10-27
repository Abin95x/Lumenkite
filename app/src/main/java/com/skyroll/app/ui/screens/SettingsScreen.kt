package com.skyroll.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.skyroll.app.BuildConfig
import com.skyroll.app.data.Prefs
import com.skyroll.app.ui.components.RoundIconButton
import com.skyroll.app.ui.components.SectionLabel
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.LocalThemeSwitcher
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.components.Gutter
import com.skyroll.app.ui.theme.ThemeToggleButton

/** Support link shown in Settings. */
const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/abin95x"

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    val n = LocalN.current
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(n.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                // Telegram-style quick switch: the new theme grows out from this button.
                ThemeToggleButton(Modifier.padding(end = 4.dp))
            }
            Text("Settings", style = NType.display, color = n.text, modifier = Modifier.padding(start = Gutter, top = 8.dp, bottom = 16.dp))

            SectionLabel("Appearance")
            AppearancePicker(prefs)

            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SectionLabel("Favorites", Modifier.padding(top = 24.dp))
                var allowed by remember { mutableStateOf(android.provider.MediaStore.canManageMedia(context)) }
                // Re-check when coming back from Android's settings page.
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                androidx.compose.runtime.LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                        allowed = android.provider.MediaStore.canManageMedia(context)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !allowed) {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))
                            )
                        }
                        .padding(horizontal = Gutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("One-tap favorites", style = NType.body, color = n.text)
                        Text(
                            if (allowed) "On · favorites sync with your phone's gallery without asking"
                            else "Android asks to confirm each favorite. Tap to allow \"Media management\" so it doesn't.",
                            style = NType.label, color = n.textTertiary,
                        )
                    }
                    if (!allowed) Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = n.textSecondary, modifier = Modifier.size(20.dp))
                }
            }

            SectionLabel("Support", Modifier.padding(top = 24.dp))
            Row(
                Modifier
                    .padding(horizontal = Gutter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFFFDD00))
                    .clickable {
                        if (BUY_ME_A_COFFEE_URL.isBlank()) {
                            Toast.makeText(context, "Link not set yet", Toast.LENGTH_SHORT).show()
                        } else {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BUY_ME_A_COFFEE_URL)))
                        }
                    }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.LocalCafe, null, tint = Color(0xFFFFDD00), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Buy me a coffee", style = NType.title, color = Color.Black)
                    Text("Lumenkite is free. A coffee keeps it going.", style = NType.body, color = Color(0xCC000000))
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            }

            SectionLabel("About", Modifier.padding(top = 24.dp))
            Column(Modifier.padding(horizontal = Gutter)) {
                Text("Lumenkite ${BuildConfig.VERSION_NAME}", style = NType.body, color = n.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A private gallery with free, unlimited backup to your own Telegram account.",
                    style = NType.body, color = n.textSecondary,
                )
            }
        }
    }
}

/** System / Light / Dark segmented control; switching plays the circular reveal from the tapped option. */
@Composable
fun AppearancePicker(prefs: Prefs) {
    val n = LocalN.current
    val switcher = LocalThemeSwitcher.current
    val mode by prefs.themeMode.collectAsState()
    Row(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(n.surface)
            .padding(4.dp),
    ) {
        Prefs.ThemeMode.entries.forEach { m ->
            var center by remember { mutableStateOf(Offset.Zero) }
            val selected = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) n.text else Color.Transparent)
                    .onGloballyPositioned { center = it.boundsInRoot().center }
                    .clickable { switcher.set(m, center) },
                contentAlignment = Alignment.Center,
            ) {
                Text(m.name, style = NType.bodyMedium, color = if (selected) n.bg else n.textSecondary)
            }
        }
    }
}
