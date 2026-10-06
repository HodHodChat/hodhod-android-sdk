package chat.hodhod.sdk

/** Dark-mode policy of the SDK UI. */
public enum class DarkMode { AUTO, LIGHT, DARK }

/**
 * SDK configuration, passed once to [Hodhod.configure].
 *
 * @param baseUrl origin of the Hodhod server, e.g. `https://hodhod.chat` (no trailing path). HTTPS is enforced unless [allowCleartext].
 * @param websiteToken website token of the web-widget inbox.
 * @param locale forced locale (BCP-47 language such as `fa`, `en`); null = server/device locale (see [HodhodI18n]).
 * @param accentColorOverride ARGB colour (`0xFF1F93FF`) replacing the inbox widget colour in the UI.
 * @param enableLogging when true the SDK logs requests (never tokens) to logcat / stdout.
 * @param allowCleartext allow `http://` base URLs (debug builds / local servers only).
 */
public data class HodhodConfig(
    val baseUrl: String,
    val websiteToken: String,
    val locale: String? = null,
    val darkMode: DarkMode = DarkMode.AUTO,
    val accentColorOverride: Long? = null,
    val enableLogging: Boolean = false,
    val userAgentSuffix: String? = null,
    val allowCleartext: Boolean = false,
)

/**
 * The signed-in end user of the host app.
 *
 * @param identifier stable id of the user in the host app; null = anonymous visitor.
 * @param identifierHash HMAC-SHA256(identifier, inbox HMAC token) computed by the customer's backend (never in the app)
 *   when the inbox enforces identity validation.
 */
public data class HodhodUser(
    val identifier: String?,
    val identifierHash: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val customAttributes: Map<String, Any?> = emptyMap(),
)

/** Lifecycle of the widget bootstrap (config + contact). */
public sealed interface HodhodState {
    /** Nothing requested yet (lazy start). */
    public data object Idle : HodhodState

    /** Bootstrap request in flight. */
    public data object Loading : HodhodState

    /** [HodhodRepository.widgetConfig] is available. */
    public data object Ready : HodhodState

    /** Bootstrap failed; [HodhodRepository.refresh] retries. [code] is a stable machine code (`network`, `not_found`, `suspended`, `server`, `cleartext`...). */
    public data class Failed(val code: String, val message: String?) : HodhodState
}

/** Websocket connection state. */
public enum class ConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }
