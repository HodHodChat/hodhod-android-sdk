package chat.hodhod.sample

import android.app.Application
import android.content.Context
import chat.hodhod.sdk.DarkMode
import chat.hodhod.sdk.Hodhod
import chat.hodhod.sdk.HodhodConfig

/** Reads the saved sample settings and configures the SDK at startup (so the unread badge works before the chat opens). */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.load(this)?.let { Settings.apply(this, it) }
    }
}

/** Values of the configure form, persisted in plain SharedPreferences (demo only; never store real secrets like this). */
data class Settings(
    val baseUrl: String, val token: String, val locale: String, val dark: String, val accent: String,
    val identifier: String, val identifierHash: String, val name: String, val email: String, val phone: String,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:3000" // the emulator's alias of the host machine

        private fun prefs(c: Context) = c.getSharedPreferences("hodhod_sample", Context.MODE_PRIVATE)

        fun load(c: Context): Settings? = prefs(c).takeIf { it.contains("token") }?.let {
            Settings(it.getString("baseUrl", DEFAULT_BASE_URL)!!, it.getString("token", "")!!, it.getString("locale", "")!!, it.getString("dark", "AUTO")!!,
                it.getString("accent", "")!!, it.getString("identifier", "")!!, it.getString("identifierHash", "")!!, it.getString("name", "")!!, it.getString("email", "")!!, it.getString("phone", "")!!)
        }

        fun save(c: Context, s: Settings) {
            prefs(c).edit().putString("baseUrl", s.baseUrl).putString("token", s.token).putString("locale", s.locale).putString("dark", s.dark).putString("accent", s.accent)
                .putString("identifier", s.identifier).putString("identifierHash", s.identifierHash).putString("name", s.name).putString("email", s.email).putString("phone", s.phone).apply()
        }

        /** Calls [Hodhod.configure] with the form values. */
        fun apply(app: Context, s: Settings) {
            if (s.token.isBlank()) return
            Hodhod.configure(
                app.applicationContext as Application,
                HodhodConfig(
                    baseUrl = s.baseUrl.trim().trimEnd('/'), websiteToken = s.token.trim(), locale = s.locale.ifBlank { null },
                    darkMode = runCatching { DarkMode.valueOf(s.dark) }.getOrDefault(DarkMode.AUTO),
                    accentColorOverride = s.accent.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it },
                    enableLogging = BuildConfigDebug.enabled, allowCleartext = s.baseUrl.startsWith("http://"),
                ),
            )
        }
    }
}

internal object BuildConfigDebug { val enabled: Boolean get() = true }
