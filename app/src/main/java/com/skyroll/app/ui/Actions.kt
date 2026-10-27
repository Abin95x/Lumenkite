package com.skyroll.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.skyroll.app.App
import com.skyroll.app.data.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Share, delete, restore — things that need an Activity or a coroutine. */
class GalleryActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deleteLauncher: ActivityResultLauncher<IntentSenderRequest>,
    private val pendingAfterDelete: Array<(() -> Unit)?>,
    private val pendingCancel: Array<(() -> Unit)?>,
) {
    private val app = App.get(context)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun share(items: List<MediaItem>) {
        scope.launch {
            val uris = ArrayList<Uri>()
            for (item in items) {
                val uri = if (item.vaultPath != null) {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", app.vault.openPlain(item.vaultPath))
                } else item.uri ?: runCatching {
                    val f = app.telegram.download(item.cloud!!.fileId, 32)
                    FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                }.getOrNull()
                if (uri != null) uris += uri
            }
            if (uris.isEmpty()) { toast("Nothing to share"); return@launch }
            val mime = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Removes the device copies (system asks the user to confirm). */
    fun deleteFromDevice(items: List<MediaItem>, onCancel: () -> Unit = {}, after: () -> Unit = {}) {
        val uris = items.filter { it.isLocal }.mapNotNull { it.uri }
        if (uris.isEmpty()) { after(); return }
        val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
        pendingAfterDelete[0] = after
        pendingCancel[0] = onCancel
        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    /** Deletes the Telegram copies. */
    fun deleteFromCloud(items: List<MediaItem>, after: () -> Unit = {}) {
        scope.launch {
            val byChat = items.mapNotNull { it.cloud }.groupBy { it.chatId }
            runCatching { byChat.forEach { (chat, list) -> app.telegram.deleteMessages(chat, list.map { it.messageId }) } }
                .onFailure { toast("Couldn't delete from Telegram: ${it.message}") }
            after()
        }
    }

    /**
     * Frees space: deletes device copies only after checking each one still exists on Telegram.
     */
    fun freeUpSpace(items: List<MediaItem>, after: () -> Unit = {}) {
        scope.launch {
            val candidates = items.filter { it.isLocal && it.cloud != null }
            if (candidates.isEmpty()) { toast("Nothing to free up"); return@launch }
            val alive = runCatching {
                candidates.groupBy { it.cloud!!.chatId }.flatMap { (chat, list) ->
                    app.telegram.verifyExists(chat, list.map { it.cloud!!.messageId })
                }.toSet()
            }.getOrElse { toast("Couldn't verify backups: ${it.message}"); return@launch }
            val safe = candidates.filter { it.cloud!!.messageId in alive }
            if (safe.size < candidates.size) toast("${candidates.size - safe.size} items weren't on Telegram anymore — kept on device")
            deleteFromDevice(safe, after = after)
        }
    }

    // ------------------------------------------------------------ favorites

    /**
     * Device photos use Android's shared favorite flag (also shown in other gallery apps);
     * Android may ask to confirm unless "Media management" is allowed in Settings.
     * Telegram-only and vault items are kept in the app's own list.
     */
    fun setFavorite(items: List<MediaItem>, favorite: Boolean, after: () -> Unit = {}) {
        val local = items.filter { it.isLocal && it.uri != null }
        val others = items.filter { !it.isLocal }
        if (others.isNotEmpty()) app.prefs.setAppFavorite(others.map { it.key }, favorite)
        // Keep the app list in step for device items too, so a favorite survives "free up space".
        app.prefs.setAppFavorite(local.map { it.key }, favorite)
        if (local.isEmpty()) { after(); return }
        val pi = app.media.favoriteRequest(local.map { it.uri!! }, favorite)
        pendingAfterDelete[0] = after
        pendingCancel[0] = { app.prefs.setAppFavorite(local.map { it.key }, !favorite) }
        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // ------------------------------------------------------------ vault

    /** Copies items into the vault, then removes the gallery copies (system confirms). */
    fun moveToVault(items: List<MediaItem>, after: () -> Unit = {}) {
        scope.launch {
            val candidates = items.filter { !it.isVault }
            if (candidates.isEmpty()) return@launch
            toast("Moving ${candidates.size} to vault…")
            val added = ArrayList<Pair<MediaItem, String>>()
            for (item in candidates) {
                runCatching { app.vault.add(item) }
                    .onSuccess { added += item to it.path }
                    .onFailure { toast("Couldn't move ${item.name}: ${it.message}") }
            }
            val local = added.filter { it.first.isLocal }
            if (local.isEmpty()) { if (added.isNotEmpty()) toast("Moved to vault"); after(); return@launch }
            deleteFromDevice(
                local.map { it.first },
                onCancel = {
                    // User said no to deleting the originals: undo the vault copies.
                    scope.launch { app.vault.remove(local.map { it.second }) }
                    toast("Cancelled")
                },
                after = { toast("Moved to vault"); after() },
            )
        }
    }

    /** Puts vault items back into the gallery. */
    fun moveOutOfVault(items: List<MediaItem>, after: () -> Unit = {}) {
        scope.launch {
            val done = ArrayList<String>()
            for (item in items) {
                val path = item.vaultPath ?: continue
                runCatching {
                    val plain = app.vault.openPlain(path)
                    try { app.media.restore(plain, item) } finally { plain.delete() }
                }
                    .onSuccess { done += path }
                    .onFailure { toast("Couldn't restore ${item.name}: ${it.message}") }
            }
            app.vault.remove(done)
            if (done.isNotEmpty()) toast("Moved back to gallery")
            after()
        }
    }

    /** Deletes vault items for good, including any Telegram copies. */
    fun deleteFromVault(items: List<MediaItem>, after: () -> Unit = {}) {
        scope.launch {
            val cloud = items.mapNotNull { it.cloud }
            runCatching { cloud.groupBy { it.chatId }.forEach { (chat, l) -> app.telegram.deleteMessages(chat, l.map { it.messageId }) } }
            app.vault.remove(items.mapNotNull { it.vaultPath })
            after()
        }
    }

    fun restore(item: MediaItem, onDone: () -> Unit = {}) {
        val cloud = item.cloud ?: return
        scope.launch {
            toast("Restoring ${item.name}…")
            runCatching {
                val f = app.telegram.download(cloud.fileId, 32)
                app.media.restore(f, item)
            }.onSuccess { toast("Saved to device"); onDone() }
                .onFailure { toast("Restore failed: ${it.message}") }
        }
    }
}

@Composable
fun rememberGalleryActions(): GalleryActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<(() -> Unit)?>(1) }
    val cancel = remember { arrayOfNulls<(() -> Unit)?>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pending[0]?.invoke() else cancel[0]?.invoke()
        pending[0] = null
        cancel[0] = null
    }
    return remember(launcher) { GalleryActions(context, scope, launcher, pending, cancel) }
}
