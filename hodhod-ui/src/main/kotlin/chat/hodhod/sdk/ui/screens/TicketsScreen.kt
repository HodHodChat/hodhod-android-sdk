package chat.hodhod.sdk.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.*
import chat.hodhod.sdk.ui.*
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.Dates
import chat.hodhod.sdk.ui.util.widgetString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SUBJECT_MAX = 120
private const val DESCRIPTION_MAX = 5000
private const val FILES_MAX = 5
private const val FILE_MAX_MB = 10

@Composable
private fun errText(code: String?): String {
    val ctx = LocalContext.current
    return ctx.widgetString("WIDGET_TICKET.ERRORS.${code ?: "GENERIC"}") ?: ctx.getString(R.string.hodhod_widget_ticket_errors_generic)
}

internal fun statusLabelRes(s: TicketStatus) = when (s) {
    TicketStatus.OPEN, TicketStatus.UNKNOWN -> R.string.hodhod_widget_ticket_status_open
    TicketStatus.IN_PROGRESS -> R.string.hodhod_widget_ticket_status_in_progress
    TicketStatus.WAITING -> R.string.hodhod_widget_ticket_status_waiting_on_customer
    TicketStatus.RESOLVED -> R.string.hodhod_widget_ticket_status_resolved
    TicketStatus.CLOSED -> R.string.hodhod_widget_ticket_status_closed
}

@Composable
internal fun TicketStatusBadge(status: TicketStatus) {
    val c = HodhodTheme.colors
    val (bg, fg) = when (status) {
        TicketStatus.OPEN, TicketStatus.UNKNOWN -> (if (c.isDark) Color(0xFF1A1726) else Color(0xFFF2EDFD)) to c.violetText
        TicketStatus.IN_PROGRESS -> (if (c.isDark) Color(0xFF221C3A) else Color(0xFFE9E3FB)) to c.violetText
        TicketStatus.WAITING -> c.amberSoft to c.amberText
        TicketStatus.RESOLVED -> c.tealSoft to c.tealText
        TicketStatus.CLOSED -> c.surfaceMuted to c.textSecondary
    }
    Text(stringResource(statusLabelRes(status)), Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp), color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
}

/**
 * Tickets panel: my tickets (filters, pagination), form, success and thread (widget TicketPanel.vue). Navigation state lives in the view-model.
 * [canGoBack]: opened from the Home row / choice card (back returns to Home); [canCreate]: the inbox accepts new tickets (not chat-only).
 */
@Composable
internal fun TicketsPanel(
    vm: HodhodChatViewModel, config: WidgetConfig, offlineReason: Boolean, canGoBack: Boolean, locale: String?, onBack: () -> Unit, modifier: Modifier = Modifier,
    canCreate: Boolean = config.contactMode != ContactMode.CHAT, onOpenChat: () -> Unit = {},
) {
    val repo = vm.repo
    val summary by repo.ticketSummary.collectAsState()
    val listUi by vm.ticketList.ui.collectAsState()
    val view by vm.ticketView.collectAsState()
    val number by vm.ticketNumber.collectAsState()
    val created by vm.createdTicket.collectAsState()
    val convo by repo.conversation.collectAsState()
    LaunchedEffect(Unit) { vm.ticketList.open() }
    LaunchedEffect(Unit) { repo.ticketActivity.collect { vm.ticketList.onRemoteChange() } }
    // counters moved (agent closed / replied, other device): reload the visible filter quietly
    LaunchedEffect(summary) {
        val c = listUi.counts
        if (listUi.initialized && c != null && (c.open != summary.open || c.total != summary.total)) vm.ticketList.onRemoteChange()
    }
    val knowsTickets = listUi.hasTickets || summary.total > 0
    val decided = listUi.initialized || listUi.failed
    val current = view ?: if (!decided) null else if (knowsTickets || listUi.failed || !canCreate) TicketView.LIST else TicketView.FORM
    fun openTicket(t: TicketSummary) {
        val activeId = (convo as? ConversationState.Active)?.id
        if (t.conversationId != null && t.conversationId == activeId) onOpenChat() // the ticket IS the live chat: one UI only
        else vm.setTicketView(TicketView.THREAD, t.number)
    }
    if (current == TicketView.LIST) {
        TicketListPane(vm, listUi, locale, canGoBack, canCreate, prominentNew = config.contactMode == ContactMode.TICKET, onBack = onBack, onOpen = ::openTicket,
            onNew = { vm.setTicketView(TicketView.FORM) }, modifier = modifier)
        return
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (current) {
            null -> TicketSkeleton(3)
            TicketView.THREAD -> TicketThreadView(vm, number ?: 0, locale) { vm.setTicketView(TicketView.LIST); vm.ticketList.refresh() }
            TicketView.SUCCESS -> TicketSuccess(config, created, locale, onTrack = { created?.let { vm.setTicketView(TicketView.THREAD, it.number) } }, onNew = { vm.setTicketView(TicketView.FORM) })
            TicketView.LIST -> Unit
            TicketView.FORM -> TicketFormView(vm, config, offlineReason, locale, showBack = canGoBack || knowsTickets,
                onBack = { if (knowsTickets) { vm.setTicketView(TicketView.LIST); vm.ticketList.refresh() } else onBack() })
        }
    }
}

@Composable
private fun TicketSuccess(config: WidgetConfig, created: TicketSummary?, locale: String?, onTrack: () -> Unit, onNew: () -> Unit) {
    val c = HodhodTheme.colors
    val custom = config.ticketForm.success?.resolve(locale ?: "en")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(26.dp)).padding(24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.tealSoft), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = c.tealText, modifier = Modifier.size(26.dp)) }
        SectionTitle(stringResource(R.string.hodhod_widget_ticket_success_title))
        Text(stringResource(R.string.hodhod_widget_ticket_success_number) + " \u2066#" + (created?.number ?: "") + "\u2069", color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        BodyHint(custom?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hodhod_widget_ticket_success_default), align = androidx.compose.ui.text.style.TextAlign.Center)
    }
    PrimaryButton(stringResource(R.string.hodhod_widget_ticket_track), onTrack, trailing = null)
    GhostButton(stringResource(R.string.hodhod_widget_ticket_new_ticket), onNew, Modifier.fillMaxWidth())
}

@Composable
private fun TicketFormView(vm: HodhodChatViewModel, config: WidgetConfig, offlineReason: Boolean, locale: String?, showBack: Boolean, onBack: () -> Unit) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val contact by vm.repo.contact.collectAsState()
    val scope = rememberCoroutineScope()
    val tf = config.ticketForm
    var subject by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Int?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val files = remember { mutableStateListOf<Attachment>() }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var formError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val realName = !contact.name.isNullOrBlank() && !Regex("^[a-z]+-[a-z]+-\\d+$").matches(contact.name!!)
    val knowsEmail = contact.hasEmail
    val showName = !realName && !knowsEmail
    val showEmail = !knowsEmail
    val emailRequired = tf.email != "optional"
    val showCategory = tf.category != "off" && config.ticketCategories.isNotEmpty()
    val lang = locale ?: "en"
    val title = tf.title?.resolve(lang)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hodhod_widget_ticket_form_title)
    val hint = tf.hint?.resolve(lang)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hodhod_widget_ticket_form_hint)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        formError = null
        val picked = uris.mapNotNull { ctx.attachmentFromUri(it) }
        if (picked.any { it.file.length() > FILE_MAX_MB * 1024 * 1024L }) { picked.forEach { it.file.delete() }; formError = ctx.getString(R.string.hodhod_widget_ticket_errors_file_too_big, FILE_MAX_MB.toString()); return@rememberLauncherForActivityResult }
        if (files.size + picked.size > FILES_MAX) { picked.forEach { it.file.delete() }; formError = ctx.getString(R.string.hodhod_widget_ticket_errors_files_too_many, FILES_MAX.toString()); return@rememberLauncherForActivityResult }
        files.addAll(picked)
    }
    fun err(code: String) = ctx.widgetString("WIDGET_TICKET.ERRORS.$code") ?: ctx.getString(R.string.hodhod_widget_ticket_errors_generic)
    fun validate(): Boolean {
        val e = mutableMapOf<String, String>()
        val s = subject.trim(); val d = description.trim(); val em = email.trim()
        if (s.isEmpty()) e["subject"] = err("subject_required") else if (s.length > SUBJECT_MAX) e["subject"] = err("subject_too_long")
        if (d.isEmpty()) e["description"] = err("description_required") else if (d.length > DESCRIPTION_MAX) e["description"] = err("description_too_long")
        if (showCategory && tf.category == "required" && categoryId == null) e["category"] = err("category_invalid")
        if (showEmail && em.isEmpty() && emailRequired) e["email"] = err("email_required")
        else if (em.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(em)) e["email"] = err("email_invalid")
        errors = e
        return e.isEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showBack) GhostButton(stringResource(R.string.hodhod_widget_ticket_back), onBack, leading = Icons.AutoMirrored.Rounded.ArrowBack)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { SectionTitle(title, Modifier.semantics { heading() }); BodyHint(hint) }
        if (offlineReason) OfflineTicketNotice()
        HodhodField(subject, { subject = it }, stringResource(R.string.hodhod_widget_ticket_subject), placeholder = stringResource(R.string.hodhod_widget_ticket_subject_placeholder), error = errors["subject"], required = true, maxLength = SUBJECT_MAX + 20)
        HodhodField(description, { description = it }, stringResource(R.string.hodhod_widget_ticket_description), placeholder = stringResource(R.string.hodhod_widget_ticket_description_placeholder), error = errors["description"], required = true, singleLine = false, minLines = 4, maxLines = 10, maxLength = DESCRIPTION_MAX + 50)
        if (showCategory) CategoryPicker(config.ticketCategories, categoryId, errors["category"], tf.category == "required") { categoryId = it }
        if (showName) HodhodField(name, { name = it }, stringResource(R.string.hodhod_widget_ticket_name))
        if (showEmail) HodhodField(email, { email = it }, stringResource(R.string.hodhod_widget_ticket_email), hint = stringResource(R.string.hodhod_widget_ticket_email_hint), error = errors["email"], required = emailRequired, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        // honeypot: the native form has no hidden field at all (TicketForm.website stays null); bots cannot fill what is not there
        if (tf.attachments) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.hodhod_widget_ticket_attach), { pick.launch("*/*") }, leading = Icons.Rounded.AttachFile, trailing = null, enabled = files.size < FILES_MAX)
                BodyHint(stringResource(R.string.hodhod_widget_ticket_attach_hint, java.text.NumberFormat.getInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(FILES_MAX)))
                files.forEachIndexed { i, a ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceField).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.fileName, Modifier.weight(1f), color = c.text, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        SquareIconButton(Icons.Rounded.Close, stringResource(R.string.hodhod_widget_ticket_remove_file, a.fileName), { a.file.delete(); files.removeAt(i) }, size = 48.dp, bordered = false)
                    }
                }
            }
        }
        formError?.let { Text(it, color = c.rubyText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        PrimaryButton(stringResource(if (submitting) R.string.hodhod_widget_ticket_submitting else R.string.hodhod_widget_ticket_submit), {
            if (submitting || !validate()) return@PrimaryButton
            submitting = true; formError = null
            scope.launch {
                val r = vm.repo.createTicket(TicketForm(subject.trim(), description.trim(), if (showCategory) categoryId else null, if (showName) name.trim().ifEmpty { null } else null,
                    if (showEmail) email.trim().ifEmpty { null } else null, files.toList(), null))
                submitting = false
                r.onSuccess { files.clear(); subject = ""; description = ""; vm.onTicketCreated(it) }
                    .onFailure { formError = ctx.widgetString("WIDGET_TICKET.ERRORS.${(it as? HodhodException)?.code}") ?: ctx.getString(R.string.hodhod_widget_ticket_errors_generic) }
            }
        }, loading = submitting, enabled = !submitting, trailing = null)
    }
}

@Composable
private fun CategoryPicker(categories: List<TicketCategory>, selected: Int?, error: String?, required: Boolean, onSelect: (Int?) -> Unit) {
    val c = HodhodTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = categories.firstOrNull { it.id == selected }?.name
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (error != null) c.rubySoft else c.surfaceField).border(1.dp, if (error != null) c.rubyText else c.border, RoundedCornerShape(18.dp))
                .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.hodhod_widget_ticket_category) + if (required) " *" else "", color = if (error != null) c.rubyText else c.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label ?: stringResource(R.string.hodhod_widget_ticket_category_none), color = if (label == null) c.placeholder else c.text, fontSize = 15.sp)
                    Icon(Icons.Rounded.ExpandMore, null, tint = c.textSecondary)
                }
            }
            DropdownMenu(open, { open = false }, containerColor = c.surface) {
                if (!required) DropdownMenuItem({ Text(stringResource(R.string.hodhod_widget_ticket_category_none), color = c.textSecondary) }, onClick = { onSelect(null); open = false })
                categories.forEach { cat -> DropdownMenuItem({ Text(cat.name, color = c.text) }, onClick = { onSelect(cat.id); open = false }) }
            }
        }
        if (error != null) Text(error, Modifier.padding(horizontal = 6.dp), color = c.rubyText, fontSize = 12.sp)
    }
}

@Composable
private fun TicketThreadView(vm: HodhodChatViewModel, number: Int, locale: String?, onBack: () -> Unit) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var thread by remember { mutableStateOf<TicketThread?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reply by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var announce by remember { mutableStateOf("") }
    suspend fun load(initial: Boolean) {
        val r = vm.repo.loadTicket(number)
        r.onSuccess { t -> if (!initial && thread != null && t.messages.size > thread!!.messages.size) announce = ctx.getString(R.string.hodhod_widget_ticket_new_message); thread = t }
            .onFailure { if (initial) error = ctx.getString(R.string.hodhod_widget_ticket_load_failed) }
    }
    LaunchedEffect(number) { load(true); while (true) { delay(15_000); load(false) } }
    val t = thread
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            GhostButton(stringResource(R.string.hodhod_widget_ticket_back), onBack, leading = Icons.AutoMirrored.Rounded.ArrowBack)
            if (t != null) TicketStatusBadge(t.ticket.status)
        }
        if (t != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("\u2066#${t.ticket.number}\u2069", color = c.textSecondary, fontSize = 12.sp)
            SectionTitle(t.ticket.subject, Modifier.semantics { heading() })
        } else if (error == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        error?.let { Text(it, color = c.rubyText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (announce.isNotEmpty()) Box(Modifier.size(0.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announce })
        t?.messages?.forEach { m ->
            val mine = m.messageType == MessageType.INCOMING
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (mine) stringResource(R.string.hodhod_widget_ticket_you) else m.sender?.name ?: "") + " · " + Dates.dateTime(m.createdAt, locale), color = c.textSecondary, fontSize = 11.sp)
                if (!m.content.isNullOrBlank()) BubbleText(m.content!!, if (mine) Bubble.USER else Bubble.AGENT, true, Modifier.widthIn(max = 320.dp))
                if (m.attachments.isNotEmpty()) Attachments(m, mine, true)
            }
        }
        when (t?.ticket?.status) {
            TicketStatus.CLOSED -> Text(stringResource(R.string.hodhod_widget_ticket_closed_notice), Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceMuted).padding(14.dp), color = c.textSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            null -> {}
            else -> {
                if (t.ticket.status == TicketStatus.RESOLVED) BodyHint(stringResource(R.string.hodhod_widget_ticket_resolved_notice))
                HodhodField(reply, { reply = it }, stringResource(R.string.hodhod_widget_ticket_reply_label), placeholder = stringResource(R.string.hodhod_widget_ticket_reply_placeholder), singleLine = false, minLines = 2, maxLines = 6, maxLength = 5000)
                PrimaryButton(stringResource(R.string.hodhod_widget_ticket_send), {
                    val text = reply.trim()
                    if (text.isEmpty() || sending) return@PrimaryButton
                    sending = true; error = null
                    scope.launch {
                        val r = vm.repo.replyToTicket(number, text)
                        sending = false
                        r.onSuccess { reply = ""; load(false) }.onFailure { error = ctx.widgetString("WIDGET_TICKET.ERRORS.${(it as? HodhodException)?.code}") ?: ctx.getString(R.string.hodhod_widget_ticket_errors_generic) }
                    }
                }, enabled = reply.isNotBlank() && !sending, loading = sending, trailing = Icons.AutoMirrored.Rounded.Send)
            }
        }
    }
}
