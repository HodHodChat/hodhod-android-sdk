package chat.hodhod.sdk.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.hodhod.sdk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Top-level screens of the chat UI (a tiny state machine mirroring the widget router: home / prechat-form / messages / tickets). */
internal enum class Route { HOME, PRECHAT, CHAT }

/** Sub-view inside the tickets panel. */
internal enum class TicketView { FORM, LIST, SUCCESS, THREAD }

/** Short-lived toast-like message (transcript sent, file too big...). */
internal data class Notice(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

/**
 * Presentation state for [HodhodChatContent]. Navigation survives process death through [SavedStateHandle]; domain state
 * (session, conversation) is restored by the core module from its own storage.
 */
internal class HodhodChatViewModel(val repo: HodhodRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val _route = MutableStateFlow(saved.get<String>("route")?.let { runCatching { Route.valueOf(it) }.getOrNull() } ?: Route.HOME)
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _showTickets = MutableStateFlow(saved.get<Boolean>("showTickets") ?: false)
    /** Mode CHOICE: visitor picked «Submit a ticket». */
    val showTickets: StateFlow<Boolean> = _showTickets.asStateFlow()

    private val _ticketView = MutableStateFlow(saved.get<String>("ticketView")?.let { runCatching { TicketView.valueOf(it) }.getOrNull() })
    /** Null = not decided yet (list if the visitor has tickets, else form). */
    val ticketView: StateFlow<TicketView?> = _ticketView.asStateFlow()

    private val _ticketNumber = MutableStateFlow(saved.get<Int>("ticketNumber"))
    val ticketNumber: StateFlow<Int?> = _ticketNumber.asStateFlow()

    private val _createdTicket = MutableStateFlow<TicketSummary?>(null)
    val createdTicket: StateFlow<TicketSummary?> = _createdTicket.asStateFlow()

    /** «My tickets» list state (filter, pages, counters); survives navigation between list and thread. */
    val ticketList = TicketListController(repo, viewModelScope)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _transcriptBusy = MutableStateFlow(false)
    val transcriptBusy: StateFlow<Boolean> = _transcriptBusy.asStateFlow()
    private val _transcriptSent = MutableStateFlow(false)
    val transcriptSent: StateFlow<Boolean> = _transcriptSent.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Draft text of the composer (survives rotation/process death). */
    var draft: String
        get() = saved.get<String>("draft") ?: ""
        set(v) { saved["draft"] = v }

    private var noticeJob: Job? = null

    fun go(route: Route) { _route.value = route; saved["route"] = route.name }

    fun setShowTickets(v: Boolean) {
        _showTickets.value = v; saved["showTickets"] = v
        if (!v) setTicketView(null, null) // leaving the tickets panel: next time start from the list again
    }

    fun setTicketView(v: TicketView?, number: Int? = _ticketNumber.value) {
        _ticketView.value = v; saved["ticketView"] = v?.name
        _ticketNumber.value = number; saved["ticketNumber"] = number
    }

    fun onTicketCreated(t: TicketSummary) { _createdTicket.value = t; setTicketView(TicketView.SUCCESS, t.number) }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repo.refresh()
            _refreshing.value = false
        }
    }

    fun showNotice(text: String, isError: Boolean = false) {
        _notice.value = Notice(text, isError)
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(3500); _notice.value = null }
    }

    fun dismissNotice() { _notice.value = null }

    /** Back to the initial home (web `resetConversation` + router.replace home). */
    fun startNewConversation() {
        repo.resetConversation()
        _transcriptSent.value = false
        setShowTickets(false)
        go(Route.HOME)
    }

    fun sendTranscript(onDone: (Boolean, String?) -> Unit) {
        if (_transcriptBusy.value || _transcriptSent.value) return
        viewModelScope.launch {
            _transcriptBusy.value = true
            val r = repo.sendTranscriptByEmail()
            _transcriptBusy.value = false
            if (r.isSuccess) _transcriptSent.value = true
            onDone(r.isSuccess, (r.exceptionOrNull() as? HodhodException)?.code)
            if (r.isSuccess) { delay(30_000); _transcriptSent.value = false }
        }
    }

    fun endChat() {
        viewModelScope.launch {
            val r = repo.endConversation()
            if (r.isFailure) showNotice((r.exceptionOrNull() as? HodhodException)?.code ?: "error", true)
        }
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        draft = ""
        viewModelScope.launch { repo.sendMessage(t) }
    }

    fun sendAttachment(a: Attachment, caption: String = "") {
        viewModelScope.launch { repo.sendMessage(caption, listOf(a)) }
    }
}

/** Copies a picked content [uri] into the app cache and wraps it as an [Attachment] (core uploads from a [File]). */
internal fun Context.attachmentFromUri(uri: Uri): Attachment? = runCatching {
    var name = "file"
    var size = -1L
    contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (ni >= 0) name = c.getString(ni) ?: name
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    val dir = File(cacheDir, "hodhod-uploads").apply { mkdirs() }
    val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").ifEmpty { "file" }
    val out = File(dir, "${System.nanoTime()}_$safe")
    contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: return null
    Attachment(out, name, contentResolver.getType(uri) ?: "application/octet-stream")
}.getOrNull()

internal const val MAX_UPLOAD_BYTES = 40L * 1024 * 1024
