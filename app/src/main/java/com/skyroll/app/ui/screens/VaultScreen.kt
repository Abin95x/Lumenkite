package com.skyroll.app.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.skyroll.app.data.MediaItem
import com.skyroll.app.ui.GalleryViewModel
import com.skyroll.app.ui.components.MediaGrid
import com.skyroll.app.ui.components.RoundIconButton
import com.skyroll.app.ui.theme.LocalN
import com.skyroll.app.ui.theme.NType
import com.skyroll.app.ui.theme.ThemeToggleButton
import com.skyroll.app.ui.components.Gutter

/** Asks for fingerprint / face / screen lock. Calls [onFail] with a reason if it can't. */
fun authenticateVault(activity: FragmentActivity, onSuccess: () -> Unit, onFail: (String) -> Unit) {
    // Strong biometrics (fingerprint / secure face) or the screen lock. Android 10 can't combine
    // "strong" with screen lock, so it falls back to the system's default there.
    val allowed = if (android.os.Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    when (BiometricManager.from(activity).canAuthenticate(allowed)) {
        BiometricManager.BIOMETRIC_SUCCESS -> Unit
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> { onFail("Set a screen lock in Settings to use the vault"); return }
        else -> { onFail("This phone can't lock the vault"); return }
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) onFail(errString.toString())
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Open vault")
            .setSubtitle("Use your fingerprint or screen lock")
            .setAllowedAuthenticators(allowed)
            .build()
    )
}

@Composable
fun VaultScreen(
    items: List<MediaItem>,
    columns: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpen: (List<MediaItem>, Int) -> Unit,
    onZoom: (Int) -> Unit,
) {
    SecureWindow()
    val n = LocalN.current
    val entries = remember(items) { GalleryViewModel.group(items) }
    Box(Modifier.fillMaxSize().background(n.bg)) {
        MediaGrid(
            entries = entries,
            columns = columns,
            selection = emptySet(),
            onOpen = { _, index -> onOpen(items, index) },
            onToggle = {},
            onZoom = onZoom,
            header = {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.padding(start = 4.dp, top = 4.dp), onClick = onBack)
                        Box(Modifier.weight(1f))
                        ThemeToggleButton(Modifier.padding(top = 4.dp))
                        RoundIconButton(Icons.Outlined.Lock, "Lock vault", tint = n.accent, modifier = Modifier.padding(end = 4.dp, top = 4.dp), onClick = onLock)
                    }
                    Text("Vault", style = NType.display, color = n.text, modifier = Modifier.padding(start = Gutter, top = 8.dp))
                    Text(
                        "%,d items · stored only on this phone".format(items.size).uppercase(),
                        style = NType.label, color = n.textTertiary, modifier = Modifier.padding(start = Gutter, top = 4.dp),
                    )
                }
            },
        )
        if (items.isEmpty()) {
            Text(
                "Hide photos here.\nLong-press photos in the gallery, then tap the lock.",
                style = NType.body, color = n.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        StatusBarScrim()
    }
}
