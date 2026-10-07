package chat.hodhod.sdk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chat.hodhod.sdk.*
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.screens.*
import chat.hodhod.sdk.ui.theme.*
import chat.hodhod.sdk.ui.util.isTeamOnline

/**
 * Internal override hook of the home flow slot (the built-in flow runner is used when unset); it is
 * called at the top of the Home screen while there is no active conversation and no ticket panel. Return `true` when the flow
 * is mandatory / took over, which hides the default "start conversation" card; call `onStartConversation` to continue to the
 * normal live chat (it honours the pre-chat gate).
 */
internal object FlowRunnerSlot {
    @Volatile
    var content: (@Composable (onStartConversation: () -> Unit) -> Boolean)? = null
}

/**
 * Embeddable chat UI. Reads the configured SDK ([Hodhod.configure]) and shows Home / Chat / Tickets / Pre-chat according to the inbox.
 * Handles back navigation internally and calls [onClose] when the user leaves the root screen (or taps the close button).
 */
@Composable
public fun HodhodChat(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    HodhodChatContent(Hodhod.repository, Hodhod.config, modifier, onClose)
}

private fun parseColor(hex: String?): Color? = runCatching {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    when (h.length) { 6 -> Color(("FF$h").toLong(16)); 8 -> Color(h.toLong(16)); 3 -> Color(("FF" + h.map { "$it$it" }.joinToString("")).toLong(16)); else -> null }
}.getOrNull()

/** Same as [HodhodChat] against an explicit [repository] (previews, tests, custom hosts). */
@Composable
internal fun HodhodChatContent(repo: HodhodRepository, cfg: HodhodConfig?, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, applySystemBars: Boolean = false) {
    val current by repo.widgetConfig.collectAsState()
    // keep the last good config: a failed refresh (offline) must not blank a loaded UI
    var lastGood by remember(repo) { mutableStateOf<WidgetConfig?>(null) }
    if (current != null) lastGood = current
    val widget = current ?: lastGood
    val system = isSystemInDarkTheme()
    val dark = when (cfg?.darkMode ?: DarkMode.AUTO) { DarkMode.DARK -> true; DarkMode.LIGHT -> false; DarkMode.AUTO -> system }
    val accent = cfg?.accentColorOverride?.let { Color(it) } ?: parseColor(widget?.widgetColor) ?: Color(DEFAULT_ACCENT)
    val locale = remember(cfg?.locale, widget?.locale) { HodhodI18n.resolve(cfg?.locale, widget?.locale) }
    HodhodLocalized(locale) {
        HodhodTheme(accent, dark) {
            HodhodChatRoot(repo, widget, locale, modifier, onClose, applySystemBars, dark)
        }
    }
}

@Composable
private fun HodhodChatRoot(repo: HodhodRepository, widget: WidgetConfig?, locale: String, modifier: Modifier, onClose: (() -> Unit)?, applySystemBars: Boolean, dark: Boolean) {
    val c = HodhodTheme.colors
    val vm: HodhodChatViewModel = viewModel(key = "hodhod-chat-${repo.javaClass.simpleName}" /* stable across process death so SavedState is found */, factory = viewModelFactory {
        initializer { HodhodChatViewModel(repo, createSavedStateHandle()) }
    })
    val state by repo.state.collectAsState()
    LaunchedEffect(repo) { repo.refresh() }
    LifecycleResumeEffect(repo) { vm.refresh(); onPauseOrDispose { } }
    if (applySystemBars) {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
            }
        }
    }
    Box(modifier.fillMaxSize().background(c.background).then(if (applySystemBars) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier).imePadding(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth()) {
            if (widget == null) {
                if (state is HodhodState.Failed) LoadError(state as HodhodState.Failed, onRetry = { vm.refresh() }, onClose)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            } else HodhodChatLoaded(vm, widget, locale, onClose)
        }
    }
}

@Composable
private fun LoadError(f: HodhodState.Failed, onRetry: () -> Unit, onClose: (() -> Unit)?) {
    val c = HodhodTheme.colors
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BrandTile(64.dp)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.hodhod_ui_generic_error), color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text(f.code, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
        PrimaryButton(stringResource(R.string.hodhod_ui_retry), onRetry, trailing = null, modifier = Modifier.widthIn(max = 280.dp))
        if (onClose != null) GhostButton(stringResource(R.string.hodhod_ux_widget_close_window), onClose)
    }
}

@Composable
private fun HodhodChatLoaded(vm: HodhodChatViewModel, config: WidgetConfig, locale: String, onClose: (() -> Unit)?) {
    val repo = vm.repo
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val route by vm.route.collectAsState()
    val convo by repo.conversation.collectAsState()
    val messages by repo.messages.collectAsState()
    val agents by repo.agents.collectAsState()
    val hasActive by repo.hasActiveConversation.collectAsState()
    val unread by repo.unreadCount.collectAsState()
    val contact by repo.contact.collectAsState()
    val notices by repo.issueNotices.collectAsState()
    val announcements by repo.announcements.collectAsState()
    val connection by repo.connection.collectAsState()
    val ticketSummary by repo.ticketSummary.collectAsState()
    val showTickets by vm.showTickets.collectAsState()
    val ticketView by vm.ticketView.collectAsState()
    val decision = remember(config) { config.startMode() }
    // Tickets panel = Home for ticket inboxes; in every other mode the visitor opens it from the «My tickets» row / choice card (showTickets).
    val showTicketPanel = showTickets || (decision.mode == StartMode.TICKET && (config.contactMode == ContactMode.TICKET || !hasActive))
    val needsPreChat = config.preChatForm.enabled && config.preChatForm.fields.any { it.enabled } && !hasActive && !contact.preChatSatisfied
    var confirmEnd by remember { mutableStateOf(false) }

    // Home: a closed conversation must not remain as home content (web Home.created).
    LaunchedEffect(route, convo) { if (route == Route.HOME && convo is ConversationState.Ended) repo.resetConversation() }
    // Home entry: unknown visitors go straight to the pre-chat form (never in ticket panel).
    LaunchedEffect(route, needsPreChat, showTicketPanel) { if (route == Route.HOME && needsPreChat && !showTicketPanel) vm.go(Route.PRECHAT) }

    // Announcements lead the scrolling start screens (Home, ticket panel acting as Home, pre-chat); incident notices move below them there.
    // (Not on a ticket thread / success screen: there the notices stay in the fixed area and the announcements are not repeated.)
    val ticketEntryView = ticketView == null || ticketView == TicketView.LIST || ticketView == TicketView.FORM
    val startTopInBody = route == Route.PRECHAT || (route == Route.HOME && !showTickets && (!showTicketPanel || ticketEntryView))
    val startTop: @Composable () -> Unit = { StartTopBanners(announcements, notices, repo::dismissAnnouncement, repo::dismissIssueNotice) }

    fun startChat() { if (needsPreChat) vm.go(Route.PRECHAT) else vm.go(Route.CHAT) }

    BackHandler(enabled = true) {
        when {
            route == Route.CHAT || route == Route.PRECHAT -> vm.go(Route.HOME)
            route == Route.HOME && !hasActive && !showTicketPanel && repo.flow.view.value?.nav?.canBack == true -> repo.flow.goBack()
            route == Route.HOME && ticketView == TicketView.THREAD -> vm.setTicketView(TicketView.LIST)
            route == Route.HOME && showTickets -> vm.setShowTickets(false)
            else -> onClose?.invoke() ?: Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- header
        val active = convo as? ConversationState.Active
        val lastAgent = remember(messages) { messages.lastOrNull { !it.isFromContact && it.sender?.type == "user" }?.sender?.let { Agent(it.id ?: 0, it.name ?: "", it.avatarUrl, null) } }
        val menu = buildList {
            if (route == Route.CHAT) {
                if (contact.hasEmail && messages.isNotEmpty() && config.enabledFeatures.emailTranscript)
                    add(MenuEntry(stringResource(R.string.hodhod_email_transcript_button_text), enabled = !vm.transcriptBusy.collectAsState().value) {
                        vm.sendTranscript { ok, _ -> vm.showNotice(ctx.getString(if (ok) R.string.hodhod_email_transcript_send_email_success else R.string.hodhod_email_transcript_send_email_error), !ok) }
                    })
                if (active != null && active.status in setOf(ConversationStatus.OPEN, ConversationStatus.PENDING, ConversationStatus.SNOOZED, ConversationStatus.UNKNOWN) && config.enabledFeatures.endConversation)
                    add(MenuEntry(stringResource(R.string.hodhod_end_conversation), danger = true) { confirmEnd = true })
            }
        }
        when (route) {
            Route.HOME, Route.PRECHAT -> {
                val ticketHome = route == Route.HOME && showTicketPanel
                HeroHeader(config, if (ticketHome) emptyList() else agents, showIntro = route == Route.HOME && !ticketHome,
                    introTitle = config.welcomeTitle?.takeIf { it.isNotBlank() }.takeIf { !ticketHome && route == Route.HOME } ?: if (route == Route.PRECHAT) config.welcomeTitle else null,
                    introBody = config.welcomeTagline?.takeIf { it.isNotBlank() }, menu = emptyList(), onClose = onClose, showStatus = !ticketHome)
            }
            Route.CHAT -> CompactHeader(config, agents, active?.assignee ?: lastAgent, connecting = messages.isNotEmpty() && lastAgent == null && active?.assignee == null && active != null && active.status != ConversationStatus.RESOLVED && isTeamOnline(config, agents),
                menu = menu, onBack = { vm.go(Route.HOME) }, onClose = onClose)
        }
        HorizontalDivider(color = c.borderWeak)
        Banners(connection, loaded = true, notices = if (startTopInBody) emptyList() else notices, onDismiss = repo::dismissIssueNotice)
        // ---- body
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (route) {
                Route.HOME -> if (showTicketPanel) TicketsPanel(vm, config, decision.offlineReason, canGoBack = showTickets, locale = locale, onBack = { vm.setShowTickets(false) },
                    onOpenChat = { vm.setShowTickets(false); vm.go(Route.CHAT) }, top = if (startTopInBody) startTop else ({}))
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HomeBody(config, agents, hasActive, unread, decision, showTickets, ticketSummary,
                        flowSlot = {
                            val slot = FlowRunnerSlot.content
                            if (slot != null && !hasActive) slot { startChat() }
                            else chat.hodhod.sdk.ui.flow.FlowRunnerHost(repo, config, hasActive) { startChat() }
                        },
                        // Direct start card: mark the flow session as "manual" so the first message carries the handoff body (web onDirectStart).
                        onStartChat = { if (!hasActive) repo.flow.prepareDirectStart(); startChat() }, onOpenTickets = { vm.setShowTickets(true) }, top = startTop)
                }
                Route.PRECHAT -> PreChatBody(vm, config, top = startTop) { vm.go(Route.CHAT) }
                Route.CHAT -> ChatBody(vm, config, convo, messages, locale, onStartNew = { vm.startNewConversation() })
            }
        }
        if (!config.disableBranding && route != Route.CHAT) PoweredBy()
    }
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false }, containerColor = c.surface,
        title = { Text(stringResource(R.string.hodhod_ux_widget_end_title), color = c.text, fontWeight = FontWeight.ExtraBold) },
        text = { Text(stringResource(R.string.hodhod_ux_widget_end_hint), color = c.textSecondary) },
        confirmButton = { TextButton({ confirmEnd = false; vm.endChat() }) { Text(stringResource(R.string.hodhod_ux_widget_end_confirm), color = c.rubyText, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmEnd = false }) { Text(stringResource(R.string.hodhod_ux_widget_end_cancel), color = c.textSecondary) } },
    )
}
