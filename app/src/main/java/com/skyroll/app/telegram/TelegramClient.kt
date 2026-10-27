package com.skyroll.app.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import com.skyroll.app.BuildConfig
import com.skyroll.app.data.CloudDao
import com.skyroll.app.data.CloudItem
import com.skyroll.app.data.MediaItem
import com.skyroll.app.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val error: TdApi.Error) : Exception("${error.code}: ${error.message}")

/**
 * Wraps TDLib (Telegram's official client library). Logs in as the user's own account and
 * stores backups as documents in a private channel only that account can see.
 */
class TelegramClient(
    private val context: Context,
    private val dao: CloudDao,
    private val prefs: Prefs,
) {
    sealed interface Auth {
        data object Loading : Auth
        data object MissingApiKeys : Auth
        data object WaitPhone : Auth
        data class WaitCode(val phone: String, val via: String) : Auth
        data class WaitPassword(val hint: String) : Auth
        data class Unsupported(val message: String) : Auth
        data object Ready : Auth
        data object LoggingOut : Auth
    }

    data class Account(val name: String, val phone: String, val isPremium: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Client? = null

    private val _auth = MutableStateFlow<Auth>(Auth.Loading)
    val auth: StateFlow<Auth> = _auth.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** True once the backup channel is found/created and its history indexed. */
    private val _cloudReady = MutableStateFlow(false)
    val cloudReady: StateFlow<Boolean> = _cloudReady.asStateFlow()

    private val fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 512)
    private val pendingSends = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Message>>()
    private val channelMutex = Mutex()

    fun start() {
        if (BuildConfig.TG_API_ID == 0 || BuildConfig.TG_API_HASH.isBlank()) {
            _auth.value = Auth.MissingApiKeys
            return
        }
        if (client != null) return
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        client = Client.create({ onUpdate(it) }, null, null)
    }

    // ---------------------------------------------------------------- requests

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(fn: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resumeWithException(IllegalStateException("Telegram is not started"))
                return@suspendCancellableCoroutine
            }
            c.send(fn) { result ->
                if (result is TdApi.Error) cont.resumeWithException(TdException(result))
                else cont.resume(result as T)
            }
        }

    // ---------------------------------------------------------------- updates

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuthState(obj.authorizationState)
            is TdApi.UpdateMessageSendSucceeded -> pendingSends.remove(obj.oldMessageId)?.complete(obj.message)
            is TdApi.UpdateMessageSendFailed ->
                pendingSends.remove(obj.oldMessageId)?.completeExceptionally(TdException(obj.error))
            is TdApi.UpdateFile -> fileUpdates.tryEmit(obj.file)
            is TdApi.UpdateDeleteMessages -> if (obj.isPermanent && obj.chatId == prefs.backupChatId) {
                scope.launch { dao.delete(obj.messageIds.toList()) }
            }
            else -> Unit
        }
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> scope.launch { sendParameters() }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _auth.value = Auth.WaitPhone
                scope.launch { forgetSession() }
            }
            is TdApi.AuthorizationStateWaitCode -> _auth.value = Auth.WaitCode(
                phone = state.codeInfo.phoneNumber,
                via = when (state.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> "your Telegram app"
                    is TdApi.AuthenticationCodeTypeSms,
                    is TdApi.AuthenticationCodeTypeSmsWord,
                    is TdApi.AuthenticationCodeTypeSmsPhrase -> "SMS"
                    is TdApi.AuthenticationCodeTypeCall -> "a phone call"
                    else -> "Telegram"
                },
            )
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = Auth.WaitPassword(state.passwordHint ?: "")
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode -> _auth.value = Auth.Unsupported(
                "Telegram wants an email set up for this account. Open the official Telegram app, finish that step, then try again."
            )
            is TdApi.AuthorizationStateWaitRegistration -> _auth.value = Auth.Unsupported(
                "This number has no Telegram account yet. Sign up in the official Telegram app first."
            )
            is TdApi.AuthorizationStateWaitPremiumPurchase -> _auth.value = Auth.Unsupported(
                "Telegram requires Premium to sign in from a new app on this number."
            )
            is TdApi.AuthorizationStateReady -> {
                _auth.value = Auth.Ready
                scope.launch { onReady() }
            }
            is TdApi.AuthorizationStateLoggingOut -> _auth.value = Auth.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                // A closed client cannot be reused; start a fresh one (shows the login screen).
                client = null
                _cloudReady.value = false
                _account.value = null
                start()
            }
            else -> Unit
        }
    }

    private suspend fun sendParameters() {
        val root = File(context.filesDir, "tdlib")
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(root, "db").absolutePath
            filesDirectory = File(root, "files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TG_API_ID
            apiHash = BuildConfig.TG_API_HASH
            systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        runCatching { send(params) }.onFailure { Log.e(TAG, "setTdlibParameters", it) }
    }

    private suspend fun forgetSession() {
        prefs.backupChatId = 0
        prefs.fullSyncDone = false
        _cloudReady.value = false
        dao.clear()
    }

    private suspend fun onReady() {
        runCatching {
            val me = send(TdApi.GetMe())
            _account.value = Account(
                name = listOf(me.firstName, me.lastName).filter { !it.isNullOrBlank() }.joinToString(" "),
                phone = me.phoneNumber,
                isPremium = me.isPremium,
            )
        }
        runCatching {
            val chatId = ensureChannel()
            syncIndex(chatId)
            _cloudReady.value = true
        }.onFailure { Log.e(TAG, "cloud init", it) }
    }

    // ---------------------------------------------------------------- login

    suspend fun submitPhone(phone: String) =
        send(TdApi.SetAuthenticationPhoneNumber(phone.filter { it.isDigit() || it == '+' }, TdApi.PhoneNumberAuthenticationSettings()))

    suspend fun submitCode(code: String) = send(TdApi.CheckAuthenticationCode(code.trim()))

    suspend fun submitPassword(password: String) = send(TdApi.CheckAuthenticationPassword(password))

    suspend fun logOut() = send(TdApi.LogOut())

    suspend fun awaitReady(timeoutMs: Long = 30_000): Boolean =
        withTimeoutOrNull(timeoutMs) { auth.first { it !is Auth.Loading } } is Auth.Ready

    // ---------------------------------------------------------------- channel + index

    /** Finds the private backup channel, or creates it. */
    suspend fun ensureChannel(): Long = channelMutex.withLock {
        prefs.backupChatId.takeIf { it != 0L }?.let { id ->
            val chat = runCatching { send(TdApi.GetChat(id)) }.getOrNull()
            if (chat != null && runCatching { isOurChannel(chat) }.getOrDefault(false)) {
                if (chat.title != CHANNEL_TITLE) runCatching { send(TdApi.SetChatTitle(id, CHANNEL_TITLE)) }
                return id
            }
        }
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), 500)) }
        val candidates = LinkedHashSet<Long>()
        runCatching { candidates += send(TdApi.SearchChats().apply { query = CHANNEL_TITLE; limit = 20 }).chatIds.toList() }
        runCatching { candidates += send(TdApi.SearchChatsOnServer().apply { query = CHANNEL_TITLE; limit = 20 }).chatIds.toList() }
        for (title in LEGACY_TITLES) {
            runCatching { candidates += send(TdApi.SearchChatsOnServer().apply { query = title; limit = 20 }).chatIds.toList() }
        }
        for (id in candidates) {
            val chat = runCatching { send(TdApi.GetChat(id)) }.getOrNull() ?: continue
            if ((chat.title == CHANNEL_TITLE || chat.title in LEGACY_TITLES) && isOurChannel(chat)) {
                if (chat.title != CHANNEL_TITLE) runCatching { send(TdApi.SetChatTitle(id, CHANNEL_TITLE)) }
                prefs.backupChatId = id
                return id
            }
        }
        val chat = send(TdApi.CreateNewSupergroupChat().apply {
            title = CHANNEL_TITLE
            isChannel = true
            isForum = false
            description = "Private photo backup for Lumenkite. Each message is one original file. Don't post here."
        })
        prefs.backupChatId = chat.id
        prefs.fullSyncDone = true // brand-new channel, nothing to index
        chat.id
    }

    private suspend fun isOurChannel(chat: TdApi.Chat): Boolean {
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return false
        if (!type.isChannel) return false
        val sg = send(TdApi.GetSupergroup(type.supergroupId))
        return sg.status is TdApi.ChatMemberStatusCreator
    }

    /** Reads the channel history into the local index. Stops early once it reaches known messages. */
    suspend fun syncIndex(chatId: Long) {
        val known = dao.messageIds().toHashSet()
        val incremental = prefs.fullSyncDone
        var from = 0L
        while (true) {
            val page = send(TdApi.GetChatHistory(chatId, from, 0, 100, false)).messages
            if (page.isEmpty()) break
            dao.upsert(page.mapNotNull { toCloudItem(it) })
            if (incremental && page.all { it.id in known }) break
            from = page.last().id
        }
        prefs.fullSyncDone = true
    }

    /** Removes index rows whose Telegram message no longer exists. Returns the ids that still exist. */
    suspend fun verifyExists(chatId: Long, ids: List<Long>): Set<Long> {
        val alive = HashSet<Long>()
        val gone = ArrayList<Long>()
        ids.chunked(100).forEach { chunk ->
            val msgs = send(TdApi.GetMessages().apply { this.chatId = chatId; messageIds = chunk.toLongArray() }).messages
            chunk.forEachIndexed { i, id ->
                val m = msgs.getOrNull(i)
                if (m != null && m.content is TdApi.MessageDocument) alive += id else gone += id
            }
        }
        if (gone.isNotEmpty()) dao.delete(gone)
        return alive
    }

    private fun toCloudItem(m: TdApi.Message): CloudItem? {
        val content = m.content as? TdApi.MessageDocument ?: return null
        if (m.sendingState != null) return null
        val doc = content.document
        val meta = Meta.decode(content.caption?.text)
        val name = meta?.name ?: doc.fileName.ifBlank { "file_${m.id}" }
        val size = meta?.size ?: doc.document.size
        return CloudItem(
            messageId = m.id,
            chatId = m.chatId,
            key = MediaItem.keyOf(name, size),
            name = name,
            mime = meta?.mime ?: doc.mimeType.ifBlank { "application/octet-stream" },
            size = size,
            dateTaken = meta?.dateTaken ?: (m.date * 1000L),
            relativePath = meta?.relativePath,
            width = meta?.width ?: 0,
            height = meta?.height ?: 0,
            durationMs = meta?.durationMs ?: 0,
            fileId = doc.document.id,
            thumbFileId = doc.thumbnail?.file?.id ?: 0,
        )
    }

    // ---------------------------------------------------------------- upload / download

    fun fileProgress(fileId: Int): Flow<TdApi.File> = fileUpdates.filter { it.id == fileId }

    /**
     * Uploads one file as a document (original quality) and waits until Telegram has it.
     * [onFileId] gets the TDLib file id right away so callers can follow progress.
     */
    suspend fun upload(
        chatId: Long,
        item: MediaItem,
        localPath: String,
        thumb: Triple<String, Int, Int>?,
        onFileId: (Int) -> Unit = {},
    ): CloudItem {
        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal(localPath)
                thumbnail = thumb?.let { TdApi.InputThumbnail(TdApi.InputFileLocal(it.first), it.second, it.third) }
                disableContentTypeDetection = true
            }
            caption = TdApi.FormattedText(Meta.encode(item), emptyArray())
        }
        val request = TdApi.SendMessage().apply {
            this.chatId = chatId
            inputMessageContent = content
            options = TdApi.MessageSendOptions().apply { disableNotification = true }
        }
        val done = CompletableDeferred<TdApi.Message>()
        val tmpId = java.util.concurrent.atomic.AtomicLong(0)
        // Register inside TDLib's callback so the "send succeeded" update can't arrive first.
        suspendCancellableCoroutine { cont ->
            val c = client ?: run {
                cont.resumeWithException(IllegalStateException("Telegram is not started")); return@suspendCancellableCoroutine
            }
            c.send(request) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TdException(result))
                } else {
                    val tmp = result as TdApi.Message
                    pendingSends[tmp.id] = done
                    tmpId.set(tmp.id)
                    (tmp.content as? TdApi.MessageDocument)?.document?.document?.id?.let(onFileId)
                    cont.resume(Unit)
                }
            }
        }
        val sent = try {
            done.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the user: deleting the not-yet-sent message cancels the upload.
            val id = tmpId.get()
            pendingSends.remove(id)
            if (id != 0L) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { send(TdApi.DeleteMessages().apply { this.chatId = chatId; messageIds = longArrayOf(id); revoke = true }) }
            }
            throw e
        }
        val cloud = toCloudItem(sent) ?: error("Upload finished but message is not a document")
        dao.upsert(listOf(cloud))
        return cloud
    }

    /** Downloads a file (or returns the cached copy). Suspends until complete. */
    suspend fun download(fileId: Int, priority: Int = 16): File {
        val f = send(TdApi.DownloadFile(fileId, priority, 0, 0, true))
        check(f.local.isDownloadingCompleted) { "Download incomplete" }
        return File(f.local.path)
    }

    suspend fun cancelDownload(fileId: Int) {
        runCatching { send(TdApi.CancelDownloadFile(fileId, false)) }
    }

    suspend fun deleteMessages(chatId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        send(TdApi.DeleteMessages().apply { this.chatId = chatId; messageIds = ids.toLongArray(); revoke = true })
        dao.delete(ids)
    }

    companion object {
        const val CHANNEL_TITLE = "Lumenkite Backup"
        /** Older names of the channel, found and renamed on first run. */
        private val LEGACY_TITLES = setOf("Skyroll Backup")
        private const val TAG = "Telegram"
    }
}
