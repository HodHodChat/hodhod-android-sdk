package chat.hodhod.sdk.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.TicketFilter
import chat.hodhod.sdk.TicketSummary
import chat.hodhod.sdk.TicketSummaryCounts
import chat.hodhod.sdk.ui.HodhodChatViewModel
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.TicketListUi
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.Dates
import java.text.NumberFormat
import java.util.Locale

private fun fmt(n: Int, tag: String?): String = NumberFormat.getInstance(Locale.forLanguageTag(tag ?: Locale.getDefault().toLanguageTag())).format(n)

internal fun filterLabelRes(f: TicketFilter) = when (f) {
    TicketFilter.OPEN -> R.string.hodhod_ui_tickets_filter_open
    TicketFilter.CLOSED -> R.string.hodhod_ui_tickets_filter_closed
    TicketFilter.ALL -> R.string.hodhod_ui_tickets_filter_all
}

/** Compact «My tickets» row of the Home screen (any contact mode, also during a live chat): open-count badge when there are open tickets. */
@Composable
internal fun MyTicketsRow(summary: TicketSummaryCounts, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    val tag = LocalConfiguration.current.locales[0].toLanguageTag()
    val title = stringResource(R.string.hodhod_widget_ticket_my_tickets)
    val hint = if (summary.open > 0) stringResource(R.string.hodhod_ui_tickets_open_count, fmt(summary.open, tag)) else stringResource(R.string.hodhod_ui_tickets_total_count, fmt(summary.total, tag))
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(22.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $hint" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.violetSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ConfirmationNumber, null, tint = c.violetText, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Text(hint, color = c.textSecondary, fontSize = 12.5.sp)
        }
        if (summary.open > 0) {
            Text(
                fmt(summary.open, tag), Modifier.defaultMinSize(minWidth = 26.dp, minHeight = 26.dp).clip(RoundedCornerShape(50)).background(c.accent).padding(horizontal = 8.dp, vertical = 3.dp),
                color = c.onAccent, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textTertiary, modifier = Modifier.size(22.dp))
    }
}

/** Filter chips «Open / Closed / All» with counters. */
@Composable
internal fun TicketFilterChips(ui: TicketListUi, onSelect: (TicketFilter) -> Unit, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    val tag = LocalConfiguration.current.locales[0].toLanguageTag()
    val groupLabel = stringResource(R.string.hodhod_ui_tickets_filter_label)
    Row(modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = groupLabel }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TicketFilter.entries.sortedBy { listOf(TicketFilter.OPEN, TicketFilter.CLOSED, TicketFilter.ALL).indexOf(it) }.forEach { f ->
            val selected = ui.filter == f
            val n = ui.countOf(f)
            val label = stringResource(filterLabelRes(f)) + (n?.let { " · " + fmt(it, tag) } ?: "")
            Box(Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = { onSelect(f) }), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.surfaceField)
                        .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (selected) c.onAccent else c.text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp,
                )
            }
        }
    }
}

/** Placeholder rows while the first page loads. */
@Composable
internal fun TicketSkeleton(rows: Int) {
    val c = HodhodTheme.colors
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val loading = stringResource(R.string.hodhod_widget_ticket_list_loading)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = loading; liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().alpha(alpha).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(90.dp).height(10.dp).clip(RoundedCornerShape(50)).background(c.surfaceMuted))
                    Box(Modifier.width(180.dp).height(14.dp).clip(RoundedCornerShape(50)).background(c.surfaceMuted))
                }
                Box(Modifier.width(56.dp).height(22.dp).clip(RoundedCornerShape(50)).background(c.surfaceMuted))
            }
        }
    }
}

@Composable
internal fun TicketCard(t: TicketSummary, locale: String?, openChatHint: Boolean, onOpen: () -> Unit) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val tag = locale ?: LocalConfiguration.current.locales[0].toLanguageTag()
    val time = Dates.relative(t.updatedAt, tag, justNow = stringResource(R.string.hodhod_ui_tickets_now))
    val status = stringResource(statusLabelRes(t.status))
    val fromChat = stringResource(R.string.hodhod_ui_tickets_from_chat)
    val a11y = buildString {
        append("#${t.number}, ${t.subject}, $status, ").append(ctx.getString(R.string.hodhod_ui_tickets_updated, time))
        if (t.isFromConversation) append(", $fromChat")
    }
    val chatHint = stringResource(R.string.hodhod_ui_tickets_open_chat_hint)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpen).padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                if (openChatHint) onClick(label = chatHint) { onOpen(); true }
            },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("\u2066#${t.number}\u2069 · $time", color = c.textSecondary, fontSize = 12.sp)
            Text(t.subject, color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(textDirection = TextDirection.Content))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TicketStatusBadge(t.status)
            if (t.isFromConversation) {
                Text(fromChat, Modifier.clip(RoundedCornerShape(50)).background(c.surfaceMuted).padding(horizontal = 8.dp, vertical = 2.dp), color = c.textSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The list screen: title (+ back), filter chips, cards, load more, empty / error / skeleton states, pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TicketListPane(
    vm: HodhodChatViewModel, ui: TicketListUi, locale: String?, canGoBack: Boolean, canCreate: Boolean, prominentNew: Boolean,
    onBack: () -> Unit, onOpen: (TicketSummary) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = HodhodTheme.colors
    val ctl = vm.ticketList
    val activeChat by vm.repo.conversation.collectAsState()
    val activeId = (activeChat as? chat.hodhod.sdk.ConversationState.Active)?.id
    PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { ctl.refresh() }, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.hodhod_widget_ticket_my_tickets), Modifier.semantics { heading() })
                if (canGoBack) GhostButton(stringResource(R.string.hodhod_widget_ticket_back), onBack)
            }
            if (ui.hasTickets || ui.initialized) TicketFilterChips(ui, ctl::selectFilter)
            when {
                ui.loading || (!ui.initialized && !ui.failed) -> TicketSkeleton(3)
                ui.failed -> Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.hodhod_widget_ticket_load_failed), color = c.rubyText, fontSize = 13.sp)
                    SecondaryButton(stringResource(R.string.hodhod_ui_retry), { ctl.refresh() }, trailing = null)
                }
                ui.current.isEmpty() -> BodyHint(
                    stringResource(when (ui.filter) {
                        TicketFilter.OPEN -> R.string.hodhod_ui_tickets_empty_open
                        TicketFilter.CLOSED -> R.string.hodhod_ui_tickets_empty_closed
                        TicketFilter.ALL -> R.string.hodhod_widget_ticket_list_empty
                    }),
                    Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.current.forEach { t -> TicketCard(t, locale, openChatHint = t.conversationId != null && t.conversationId == activeId) { onOpen(t) } }
                    if (ui.hasMore[ui.filter] == true) {
                        if (ui.loadingMore) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent, modifier = Modifier.size(24.dp)) }
                        else SecondaryButton(stringResource(R.string.hodhod_ui_tickets_load_more), { ctl.loadMore() }, trailing = null)
                    }
                }
            }
            if (canCreate) {
                val label = stringResource(R.string.hodhod_ui_tickets_new)
                if (prominentNew) PrimaryButton(label, onNew, leading = Icons.Rounded.Add, trailing = null)
                else SecondaryButton(label, onNew, leading = Icons.Rounded.Add, trailing = null)
            }
        }
    }
}
