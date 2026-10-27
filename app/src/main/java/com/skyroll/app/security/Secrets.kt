package com.skyroll.app.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * App encryption, built on Google Tink. The keys are themselves encrypted by a master key that
 * lives in the phone's hardware keystore and can't be copied off the device.
 */
class Secrets(context: Context) {
    private val app = context.applicationContext

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    /** For small secrets (the Telegram database key, the vault index). */
    private val aead: Aead by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(app, "aead_keyset", PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://lumenkite_master_aead")
            .build().keysetHandle.getPrimitive(Aead::class.java)
    }

    /** For files of any size (vault photos and videos), encrypted in 1 MB chunks. */
    private val streaming: StreamingAead by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(app, "vault_keyset", PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
            .withMasterKeyUri("android-keystore://lumenkite_master_vault")
            .build().keysetHandle.getPrimitive(StreamingAead::class.java)
    }

    fun encrypt(data: ByteArray, context: String): ByteArray = aead.encrypt(data, context.toByteArray())
    fun decrypt(data: ByteArray, context: String): ByteArray = aead.decrypt(data, context.toByteArray())

    fun encryptingStream(out: OutputStream, context: String): OutputStream =
        streaming.newEncryptingStream(out, context.toByteArray())

    fun decryptingStream(input: InputStream, context: String): InputStream =
        streaming.newDecryptingStream(input, context.toByteArray())

    // ------------------------------------------------------------ Telegram database key

    private val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readKey(name: String): ByteArray? = sp.getString(name, null)?.let {
        runCatching { decrypt(Base64.decode(it, Base64.NO_WRAP), "tdlib-key") }.getOrNull()
    }

    private fun writeKey(name: String, key: ByteArray?) {
        val e = sp.edit()
        if (key == null) e.remove(name) else e.putString(name, Base64.encodeToString(encrypt(key, "tdlib-key"), Base64.NO_WRAP))
        e.commit()
    }

    /** Keys to try when opening the Telegram database, most likely first. Empty = never encrypted yet. */
    fun tdlibKeyCandidates(): List<ByteArray> =
        listOfNotNull(readKey(TD_ACTIVE), readKey(TD_PENDING), ByteArray(0)).distinctBy { it.contentHashCode() }

    fun newTdlibKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }.also { writeKey(TD_PENDING, it) }

    /** Called once TDLib has re-encrypted its database with the pending key (or opened with it). */
    fun confirmTdlibKey(key: ByteArray) {
        writeKey(TD_ACTIVE, key)
        writeKey(TD_PENDING, null)
    }

    companion object {
        private const val PREFS = "lumenkite_secrets"
        private const val TD_ACTIVE = "td_active"
        private const val TD_PENDING = "td_pending"

        /** Keeps only safe characters for a file name (no slashes, no "..", no control chars). */
        fun safeFileName(name: String, fallback: String = "file"): String {
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_")
                .trim('.', ' ')
                .take(120)
            return cleaned.ifEmpty { fallback }
        }

        /** Only accepts gallery folders like "DCIM/Camera/" — never anything with "..". */
        fun safeRelativePath(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.any { it == ".." || it == "." }) return null
            if (parts.firstOrNull() !in setOf("DCIM", "Pictures", "Movies")) return null
            return parts.joinToString("/", postfix = "/") { safeFileName(it, "Folder") }
        }

        fun wipeDir(dir: File) {
            dir.listFiles()?.forEach { if (it.isDirectory) wipeDir(it); it.delete() }
        }
    }
}
