package chat.hodhod.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import chat.hodhod.sdk.internal.HodhodRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point of the Hodhod SDK.
 *
 * ```
 * Hodhod.configure(app, HodhodConfig(baseUrl = "https://hodhod.chat", websiteToken = "..."))
 * Hodhod.identify(HodhodUser(identifier = "user-42", identifierHash = hmacFromYourBackend, name = "Ali"))
 * Hodhod.open(context)
 * ```
 * Nothing touches the network until the first of: [start], [open], [identify], [setCustomAttributes], or
 * [HodhodRepository.refresh].
 */
public object Hodhod {
    private val idleState = MutableStateFlow<HodhodState>(HodhodState.Idle)
    private val idleUnread = MutableStateFlow(0)

    @Volatile
    private var runtime: HodhodRuntime? = null

    @Volatile
    private var overrideRepository: HodhodRepository? = null

    /** Configure the SDK. Idempotent: the first call wins, later calls with an identical config are ignored; a different config replaces the runtime. */
    @Synchronized
    public fun configure(app: Application, config: HodhodConfig) {
        val current = runtime
        if (current != null && current.config == config) return
        current?.shutdown()
        runtime = HodhodRuntime(app, config)
    }

    /** Starts the bootstrap (config, contact, conversation) and the websocket in the background (needed for [unreadCount] before the chat is opened). */
    public fun start() {
        runtime?.start()
    }

    /** Open the chat UI (`chat.hodhod.sdk.ui.HodhodChatActivity` from `hodhod-ui`, found by name). */
    public fun open(context: Context) {
        val rt = requireRuntime()
        rt.start()
        val intent = Intent().setClassName(context.packageName, UI_ACTIVITY)
        runCatching { Class.forName(UI_ACTIVITY) }.onFailure {
            throw IllegalStateException("hodhod-ui is not on the classpath; add chat.hodhod:hodhod-ui")
        }
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Identify the signed-in user (`PATCH /api/v1/widget/contact/set_user`). Re-identification with another identifier moves the session to that contact.
     * [onResult] runs on the main thread.
     */
    public fun identify(user: HodhodUser, onResult: (Result<Unit>) -> Unit = {}) {
        requireRuntime().identify(user, onResult)
    }

    /** Set contact custom attributes (queued until the session exists). */
    public fun setCustomAttributes(attrs: Map<String, Any?>) {
        requireRuntime().setCustomAttributes(attrs)
    }

    /** Sign out: forgets the contact/session/tokens locally and disconnects. A new anonymous contact is created on next use. */
    public fun logout() {
        runtime?.logout()
    }

    /** Unread agent messages (0 until [start]/open). */
    public val unreadCount: StateFlow<Int> get() = runtime?.repository?.unreadCount ?: idleUnread

    /** Bootstrap state. */
    public val state: StateFlow<HodhodState> get() = runtime?.repository?.state ?: idleState

    /** Repository used by the UI. Before [configure] this throws, unless one was injected with [installRepository]. */
    public val repository: HodhodRepository
        get() = overrideRepository ?: requireRuntime().repository

    /** Active configuration (null before [configure]). */
    public val config: HodhodConfig? get() = runtime?.config

    /** Inject a repository (previews, UI tests: `FakeHodhodRepository`). Pass null to remove. */
    public fun installRepository(repo: HodhodRepository?) {
        overrideRepository = repo
    }

    private fun requireRuntime(): HodhodRuntime =
        runtime ?: error("Hodhod.configure(app, config) must be called first (e.g. in Application.onCreate)")

    private const val UI_ACTIVITY = "chat.hodhod.sdk.ui.HodhodChatActivity"
}
