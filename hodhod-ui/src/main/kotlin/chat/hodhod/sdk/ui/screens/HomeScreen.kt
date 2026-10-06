package chat.hodhod.sdk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.*
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.availabilityText
import chat.hodhod.sdk.ui.util.isTeamOnline

/**
 * Home: team status card with the start/continue button (chat modes), ticket choice card (mode both), or the ticket
 * panel (ticket modes) -- same decision table as widget/views/Home.vue.
 */
@Composable
internal fun HomeBody(
    config: WidgetConfig, agents: List<Agent>, hasActive: Boolean, unread: Int, decision: StartDecision, showTickets: Boolean,
    ticketSummary: TicketSummaryCounts, flowSlot: @Composable () -> Boolean,
    onStartChat: () -> Unit, onOpenTickets: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    Column(modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // FlowRunnerSlot: the FLOW engineer plugs the chatbot-flow runner here (returns true when it took over the start card).
        val flowTookOver = flowSlot()
        if (!flowTookOver) {
            val online = isTeamOnline(config, agents)
            HodhodCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(if (online) c.teal else c.amber)
                        Text(
                            stringResource(if (online) R.string.hodhod_lark_home_status_online else R.string.hodhod_lark_home_status_away),
                            color = if (online) c.tealText else c.amberText, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        )
                    }
                    AgentStack(agents.filter { it.availability == "online" }.ifEmpty { agents }.take(4))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(if (hasActive) R.string.hodhod_lark_home_continue_title else R.string.hodhod_lark_home_card_title))
                    BodyHint(availabilityText(ctx, config, agents))
                }
                PrimaryButton(
                    stringResource(if (hasActive) R.string.hodhod_continue_conversation else R.string.hodhod_start_conversation),
                    onStartChat, badge = if (hasActive) unread else 0,
                )
            }
            if (decision.mode == StartMode.CHOICE && !showTickets) TicketChoiceCard(onOpenTickets)
        }
        // «My tickets»: every contact mode, also during a live chat -- the visitor can always reach all their tickets (open and closed).
        if (ticketSummary.total > 0) MyTicketsRow(ticketSummary, onOpenTickets)
    }
}

@Composable
internal fun TicketChoiceCard(onOpen: () -> Unit) {
    HodhodCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.hodhod_widget_ticket_ticket_choice))
            BodyHint(stringResource(R.string.hodhod_widget_ticket_ticket_choice_hint))
        }
        SecondaryButton(stringResource(R.string.hodhod_widget_ticket_ticket_choice), onOpen, leading = Icons.Rounded.ConfirmationNumber)
    }
}

/** Overlapping circular avatars (GroupedAvatars). */
@Composable
internal fun AgentStack(agents: List<Agent>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        agents.forEach { a ->
            Box(Modifier.clip(CircleShape)) { HodhodAvatar(a.name, a.avatarUrl, 28.dp) }
        }
    }
}

@Composable
internal fun OfflineTicketNotice() {
    val c = HodhodTheme.colors
    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(c.amberSoft).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.NightsStay, null, tint = c.amberText, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(stringResource(R.string.hodhod_widget_ticket_offline_notice), color = c.amberText, fontSize = 13.sp, lineHeight = 22.sp)
    }
}

