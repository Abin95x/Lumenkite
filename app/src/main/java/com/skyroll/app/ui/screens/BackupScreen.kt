package com.skyroll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.skyroll.app.backup.BackupState
import com.skyroll.app.data.Album
import com.skyroll.app.data.MediaItem
import com.skyroll.app.data.Prefs
import com.skyroll.app.telegram.TdException
import com.skyroll.app.telegram.TelegramClient
import com.skyroll.app.telegram.TelegramClient.Auth
import com.skyroll.app.ui.GalleryActions
import com.skyroll.app.ui.GalleryViewModel
import com.skyroll.app.ui.components.Card
import com.skyroll.app.ui.components.DotProgress
import com.skyroll.app.ui.components.PillButton
import com.skyroll.app.ui.components.PillStyle
import com.skyroll.app.ui.components.ScreenHeader
import com.skyroll.app.ui.components.SectionLabel
import com.skyroll.app.ui.components.SwitchRow
import com.skyroll.app.ui.components.formatBytes
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.components.Gutter
import com.skyroll.app.ui.components.SettingsButton
import com.skyroll.app.ui.theme.ThemeToggleButton
import com.skyroll.app.ui.theme.LocalThemeSwitcher
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(vm: GalleryViewModel, actions: GalleryActions, onOpenAlbum: (String) -> Unit, onSettings: () -> Unit) {
    val n = LocalN.current
    val auth by vm.telegram.auth.collectAsState()
    Box(Modifier.fillMaxSize().background(n.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 120.dp)
        ) {
            ScreenHeader("Backup", subtitle = "Unlimited · Telegram") { ThemeToggleButton(); SettingsButton(onSettings) }
            when (val a = auth) {
                Auth.Loading, Auth.LoggingOut -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = n.text, strokeWidth = 2.dp)
                }
                Auth.MissingApiKeys -> MissingKeys()
                Auth.WaitPhone, is Auth.WaitCode, is Auth.WaitPassword, is Auth.Unsupported -> LoginFlow(vm.telegram, a)
                Auth.Ready -> Dashboard(vm, actions, onOpenAlbum)
            }
        }
        StatusBarScrim()
    }
}

@Composable
private fun MissingKeys() {
    val n = LocalN.current
    Column(Modifier.padding(horizontal = Gutter)) {
        Card {
            Column {
                Text("Telegram keys missing", style = NType.title, color = n.text)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add your api_hash to local.properties (telegram.apiHash=…) and rebuild the app. " +
                        "You get it from my.telegram.org → API development tools.",
                    style = NType.body, color = n.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun LoginFlow(tg: TelegramClient, auth: Auth) {
    val n = LocalN.current
    val scope = rememberCoroutineScope()
    var input by remember(auth) { mutableStateOf("") }
    var busy by remember(auth) { mutableStateOf(false) }
    var error by remember(auth) { mutableStateOf<String?>(null) }

    val (title, body, label, keyboard, secret) = when (auth) {
        is Auth.WaitCode -> LoginStep("Enter the code", "Telegram sent a code to ${auth.via} for ${auth.phone}.", "Code", KeyboardType.NumberPassword, false)
        is Auth.WaitPassword -> LoginStep("Two-step password", "Your account has a cloud password." + if (auth.hint.isNotBlank()) " Hint: ${auth.hint}" else "", "Password", KeyboardType.Password, true)
        is Auth.Unsupported -> LoginStep("Can't sign in here", auth.message, "", KeyboardType.Text, false)
        else -> LoginStep(
            "Connect Telegram",
            "Your photos are uploaded in original quality to a private channel in your own Telegram account. Telegram gives every account unlimited cloud storage.",
            "Phone number with country code", KeyboardType.Phone, false,
        )
    }

    Column(Modifier.padding(horizontal = Gutter)) {
        Card {
            Column {
                Text(title, style = NType.title, color = n.text)
                Spacer(Modifier.height(8.dp))
                Text(body, style = NType.body, color = n.textSecondary)
                if (auth !is Auth.Unsupported) {
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        label = { Text(label, style = NType.label) },
                        placeholder = if (auth is Auth.WaitPhone) ({ Text("+91 98765 43210", style = NType.labelLarge) }) else null,
                        singleLine = true,
                        textStyle = NType.labelLarge.copy(color = n.text),
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = n.text, unfocusedBorderColor = n.outline,
                            focusedLabelColor = n.text, unfocusedLabelColor = n.textTertiary, cursorColor = n.accent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = NType.label, color = n.accent)
                    }
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        text = if (busy) "Please wait…" else "Continue",
                        enabled = !busy && input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        busy = true
                        scope.launch {
                            try {
                                when (auth) {
                                    is Auth.WaitCode -> tg.submitCode(input)
                                    is Auth.WaitPassword -> tg.submitPassword(input)
                                    else -> tg.submitPhone(input)
                                }
                            } catch (e: TdException) {
                                error = friendlyError(e)
                                busy = false
                            } catch (e: Exception) {
                                error = e.message
                                busy = false
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Signing in uses the official Telegram library (TDLib). Your login stays on this phone.",
            style = NType.label, color = n.textTertiary, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private data class LoginStep(val title: String, val body: String, val label: String, val keyboard: KeyboardType, val secret: Boolean)

private fun friendlyError(e: TdException) = when {
    e.error.message.contains("PHONE_NUMBER_INVALID") -> "That phone number doesn't look right. Include the country code, e.g. +91."
    e.error.message.contains("PHONE_CODE_INVALID") -> "Wrong code. Try again."
    e.error.message.contains("PHONE_CODE_EXPIRED") -> "The code expired. Go back and request a new one."
    e.error.message.contains("PASSWORD_HASH_INVALID") -> "Wrong password."
    e.error.code == 429 -> "Too many attempts. Telegram asks you to wait: ${e.error.message}"
    else -> e.error.message
}

@Composable
private fun Dashboard(vm: GalleryViewModel, actions: GalleryActions, onOpenAlbum: (String) -> Unit) {
    val n = LocalN.current
    val scope = rememberCoroutineScope()
    val all by vm.all.collectAsState()
    val albums by vm.albums.collectAsState()
    val state by vm.backupState.collectAsState()
    val settings by vm.prefs.settings.collectAsState()
    val account by vm.telegram.account.collectAsState()
    val cloudReady by vm.telegram.cloudReady.collectAsState()

    var confirmFreeUp by remember { mutableStateOf(false) }
    // Recompute only when the data changes, not on every progress tick.
    val stats = remember(all, settings.excludedBuckets) { BackupStats.of(all, settings.excludedBuckets) }
    val included = stats.included
    val backedUp = stats.backedUp
    val cloudCount = stats.cloudCount
    val cloudBytes = stats.cloudBytes
    val freeable = stats.freeable
    val freeableBytes = stats.freeableBytes

    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusCard(state, backedUp, included.size, cloudCount, cloudBytes, cloudReady)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                PillButton(
                    if (state.stopping) "Stopping…" else "Stop backup",
                    style = PillStyle.Accent,
                    icon = Icons.Outlined.StopCircle,
                    enabled = !state.stopping,
                    modifier = Modifier.weight(1f),
                ) { com.skyroll.app.backup.BackupStatus.requestStop() }
            } else {
                PillButton(
                    "Back up now",
                    icon = Icons.Outlined.CloudUpload,
                    enabled = cloudReady,
                    modifier = Modifier.weight(1f),
                ) { vm.backUpNow() }
            }
            PillButton(
                "Free up ${formatBytes(freeableBytes)}",
                style = PillStyle.Secondary,
                icon = Icons.Outlined.CleaningServices,
                enabled = freeable.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { confirmFreeUp = true }
        }
        PillButton(
            "View backed-up photos (%,d)".format(cloudCount),
            style = PillStyle.Secondary,
            icon = Icons.Outlined.PhotoLibrary,
            enabled = cloudCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { onOpenAlbum(GalleryViewModel.BACKED_UP_ID) }
    }


    if (confirmFreeUp) {
        FreeUpDialog(
            count = freeable.size,
            bytes = freeableBytes,
            onDismiss = { confirmFreeUp = false },
            onConfirm = { confirmFreeUp = false; actions.freeUpSpace(freeable) },
        )
    }

    SectionLabel("Backup settings", Modifier.padding(top = 20.dp))
    SwitchRow("Automatic backup", "Uploads new photos and videos in the background", settings.autoBackup) { v ->
        vm.updateSettings { it.copy(autoBackup = v) }
    }
    SwitchRow("Wi-Fi only", "Don't use mobile data for automatic backup", settings.wifiOnly) { v ->
        vm.updateSettings { it.copy(wifiOnly = v) }
    }
    SwitchRow("Only while charging", null, settings.chargingOnly) { v ->
        vm.updateSettings { it.copy(chargingOnly = v) }
    }

    SectionLabel("Folders to back up", Modifier.padding(top = 20.dp))
    albums.filter { !it.id.startsWith("__") }.forEach { album ->
        FolderRow(album, settings) { on ->
            vm.updateSettings {
                it.copy(excludedBuckets = if (on) it.excludedBuckets - album.id else it.excludedBuckets + album.id)
            }
        }
    }

    SectionLabel("Account", Modifier.padding(top = 20.dp))
    Column(Modifier.padding(horizontal = Gutter)) {
        Card {
            Column {
                Text(account?.name?.ifBlank { null } ?: "Telegram", style = NType.title, color = n.text)
                account?.phone?.let { Text("+$it", style = NType.labelLarge, color = n.textSecondary) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Backups live in the private channel \"${TelegramClient.CHANNEL_TITLE}\". Only you can see it. " +
                        "Free, with no limit on total storage. Each single file can be up to ${if (account?.isPremium == true) "4 GB" else "2 GB"}.",
                    style = NType.body, color = n.textSecondary,
                )
                Spacer(Modifier.height(14.dp))
                PillButton("Log out", style = PillStyle.Secondary) {
                    scope.launch { runCatching { vm.telegram.logOut() } }
                }
            }
        }
    }
}

private class BackupStats(
    val included: List<MediaItem>,
    val backedUp: Int,
    val cloudCount: Int,
    val cloudBytes: Long,
    val freeable: List<MediaItem>,
    val freeableBytes: Long,
) {
    companion object {
        fun of(all: List<MediaItem>, excluded: Set<String>): BackupStats {
            val local = all.filter { it.isLocal }
            val included = local.filter { it.bucketId !in excluded }
            val freeable = local.filter { it.isBackedUp }
            return BackupStats(
                included = included,
                backedUp = included.count { it.isBackedUp },
                cloudCount = all.count { it.cloud != null },
                cloudBytes = all.sumOf { it.cloud?.size ?: 0L },
                freeable = freeable,
                freeableBytes = freeable.sumOf { it.size },
            )
        }
    }
}

@Composable
private fun StatusCard(state: BackupState, backedUp: Int, total: Int, cloudCount: Int, cloudBytes: Long, cloudReady: Boolean) {
    val n = LocalN.current
    Card {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%,d".format(backedUp), style = NType.number, color = n.text)
                Text(" / %,d".format(total), style = NType.labelLarge, color = n.textTertiary, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text("BACKED UP", style = NType.label, color = n.textTertiary)
            Spacer(Modifier.height(14.dp))
            DotProgress(if (total == 0) 1f else backedUp.toFloat() / total)
            Spacer(Modifier.height(14.dp))
            val line = when {
                !cloudReady -> "Connecting to Telegram…"
                state.running && state.currentName != null ->
                    "Uploading ${state.done + 1} of ${state.total} · ${(state.currentProgress * 100).toInt()}%\n${state.currentName}"
                state.running -> "Preparing…"
                total > 0 && backedUp == total -> "Everything is backed up"
                else -> "${total - backedUp} waiting"
            }
            Text(line, style = NType.body, color = n.textSecondary)
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                DotProgress(state.currentProgress, dots = 40)
            }
            state.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text("Last problem: $it", style = NType.label, color = n.accent)
            }
            Spacer(Modifier.height(10.dp))
            Text("%,d files · %s on Telegram".format(cloudCount, formatBytes(cloudBytes)).uppercase(), style = NType.label, color = n.textTertiary)
        }
    }
}

@Composable
private fun FolderRow(album: Album, settings: Prefs.BackupSettings, onChange: (Boolean) -> Unit) {
    val on = album.id !in settings.excludedBuckets
    val done = album.items.count(MediaItem::isBackedUp)
    SwitchRow(album.name, "$done / ${album.count} backed up", on, onChange)
}

/** Clear warning before deleting device copies that are already on Telegram. */
@Composable
private fun FreeUpDialog(count: Int, bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val n = LocalN.current
    var understood by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = n.surface,
        title = { Text("Free up ${formatBytes(bytes)}?", style = NType.title, color = n.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This deletes %,d photos and videos from this phone. They have already been backed up to Telegram.".format(count),
                    style = NType.body, color = n.text,
                )
                WarningLine("They stay in Lumenkite, marked with a cloud. Opening one downloads it from Telegram, so it needs internet.")
                WarningLine("Other apps (WhatsApp, Instagram, the system gallery) won't see them anymore.")
                WarningLine("The Telegram copy is now the only copy. If you delete the \"Lumenkite Backup\" channel or lose your Telegram account, they're gone for good.")
                WarningLine("Before deleting, Lumenkite checks that each file is still on Telegram. Anything missing is kept on the phone.")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { understood = !understood }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = understood, onCheckedChange = { understood = it },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = n.accent, uncheckedColor = n.textTertiary),
                    )
                    Text("I understand", style = NType.bodyMedium, color = n.text)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm, enabled = understood) {
                Text("Delete from phone", color = if (understood) n.accent else n.textTertiary)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = n.text) } },
    )
}

@Composable
private fun WarningLine(text: String) {
    val n = LocalN.current
    Row {
        Text("•  ", style = NType.body, color = n.accent)
        Text(text, style = NType.body, color = n.textSecondary)
    }
}
