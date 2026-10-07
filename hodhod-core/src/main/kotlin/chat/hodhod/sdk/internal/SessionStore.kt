package chat.hodhod.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** Key/value persistence of the widget session (auth token, pubsub token, ended conversation...). Implementations must be thread-safe. */
internal interface SessionStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun clear()

    companion object Keys {
        const val AUTH_TOKEN = "auth_token"
        const val PUBSUB_TOKEN = "pubsub_token"
        const val CONTACT_ID = "contact_id"
        const val IDENTIFIER = "identifier"
        const val ENDED_CONVERSATION_ID = "ended_conversation_id"
        const val PENDING_ATTRS = "pending_custom_attributes"
        const val DISMISSED_NOTICES = "dismissed_notices"
        const val DISMISSED_ANNOUNCEMENTS = "dismissed_announcements"
    }
}

internal class InMemorySessionStore : SessionStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun clear() = map.clear()
}

/**
 * Session persistence backed by EncryptedSharedPreferences (Android Keystore master key). If the Keystore is unusable
 * (some OEM bugs) it falls back to private plain SharedPreferences so chat still works; the data is only opaque tokens
 * scoped to the app sandbox.
 *
 * All sessions live in ONE fixed-name file ([PREFS_NAME], keys prefixed by the hashed baseUrl+token scope) so host apps can
 * exclude it from Auto Backup (see `hodhod_backup_rules` / `hodhod_data_extraction_rules` and the README): a restored
 * file would be undecryptable on another device (Keystore keys are not backed up) and the plain fallback holds tokens.
 */
internal class AndroidSessionStore(context: Context, baseUrl: String, websiteToken: String, private val log: (String) -> Unit) : SessionStore {
    private val ctx = context.applicationContext
    private val scope = scopeName(baseUrl, websiteToken)
    private val prefix = "$scope:"
    private val prefs: SharedPreferences = open(PREFS_NAME)

    init {
        migrateLegacy()
    }

    private fun openEncrypted(name: String): SharedPreferences {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx, name, masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun open(name: String): SharedPreferences = try {
        openEncrypted(name)
    } catch (e: Exception) {
        if (isUndecryptable(e)) {
            // Undecryptable file (restored backup / wiped Keystore): start clean once before giving up on encryption.
            log("encrypted prefs unreadable (${e.javaClass.simpleName}); recreating")
            ctx.deleteSharedPreferences(name)
            try {
                openEncrypted(name)
            } catch (e2: Exception) {
                log("encrypted prefs unavailable (${e2.javaClass.simpleName}); falling back to private prefs")
                ctx.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
            }
        } else {
            // Transient Keystore/IO failure: never delete the encrypted file (it would wipe the session and dismissed
            // announcements); retry once, then use the plain fallback for this process only.
            log("encrypted prefs failed (${e.javaClass.simpleName}); retrying without deleting")
            try {
                Thread.sleep(150)
                openEncrypted(name)
            } catch (e2: Exception) {
                log("encrypted prefs unavailable (${e2.javaClass.simpleName}); falling back to private prefs")
                ctx.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
            }
        }
    }

    /** True when the stored data can never be decrypted again (corrupt/foreign key), as opposed to a transient failure. */
    private fun isUndecryptable(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            val name = cause.javaClass.name
            val message = cause.message.orEmpty()
            if (name.endsWith("AEADBadTagException") || name.endsWith("InvalidProtocolBufferException") ||
                name.endsWith("BadPaddingException") || message.contains("Signature/MAC verification failed") ||
                message.contains("decrypt", ignoreCase = true) && message.contains("fail", ignoreCase = true)
            ) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    /** Beta releases used one hashed file per scope (`hodhod_session_<hash>[_plain]`); move its entries into the fixed file once. */
    private fun migrateLegacy() {
        val dir = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs")
        listOf(scope, "${scope}_plain").filter { java.io.File(dir, "$it.xml").exists() }.forEach { legacy ->
            runCatching {
                val old = if (legacy.endsWith("_plain")) ctx.getSharedPreferences(legacy, Context.MODE_PRIVATE) else openEncrypted(legacy)
                val e = prefs.edit()
                old.all.forEach { (k, v) -> if (v is String && prefs.getString(prefix + k, null) == null) e.putString(prefix + k, v) }
                e.apply()
            }
            ctx.deleteSharedPreferences(legacy)
        }
    }

    override fun get(key: String): String? = prefs.getString(prefix + key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(prefix + key) else putString(prefix + key, value) }.apply()
    }
    override fun clear() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }.apply()
    }

    companion object {
        /** Fixed file name (an exclusion rule cannot use wildcards): keep in sync with res/xml/hodhod_*_rules.xml. */
        const val PREFS_NAME = "hodhod_session"

        fun scopeName(baseUrl: String, websiteToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$baseUrl|$websiteToken".toByteArray())
            return "hodhod_session_" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
