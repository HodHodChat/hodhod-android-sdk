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
 */
internal class AndroidSessionStore(context: Context, baseUrl: String, websiteToken: String, private val log: (String) -> Unit) : SessionStore {
    private val prefs: SharedPreferences = open(context.applicationContext, scopeName(baseUrl, websiteToken))

    private fun open(ctx: Context, name: String): SharedPreferences = try {
        val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx, name, masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        log("encrypted prefs unavailable (${e.javaClass.simpleName}); falling back to private prefs")
        ctx.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
    }

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        fun scopeName(baseUrl: String, websiteToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$baseUrl|$websiteToken".toByteArray())
            return "hodhod_session_" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
