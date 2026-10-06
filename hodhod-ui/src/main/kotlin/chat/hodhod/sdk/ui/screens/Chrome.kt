package chat.hodhod.sdk.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.*
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.Notice
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.availabilityText
import chat.hodhod.sdk.ui.util.isTeamOnline
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

internal data class MenuEntry(val label: String, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/** Brand mark: inbox avatar (squircle) or the assistant orb. */
@Composable
internal fun BrandMark(avatarUrl: String?, accent: Color, size: androidx.compose.ui.unit.Dp = 44.dp) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(chat.hodhod.sdk.ui.util.fixUrl(avatarUrl), null, Modifier.size(size).clip(RoundedCornerShape(15.dp)), contentScale = ContentScale.Crop)
    } else BrandTile(size, square = true)
}

@Composable
private fun HeaderActionsRow(menu: List<MenuEntry>, menuLabel: String, onClose: (() -> Unit)?) {
    val c = HodhodTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (menu.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                SquareIconButton(Icons.Rounded.MoreHoriz, menuLabel, { open = true })
                DropdownMenu(open, { open = false }, containerColor = c.surface) {
                    menu.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label, color = if (m.danger) c.rubyText else c.text, fontWeight = FontWeight.Medium) },
                            enabled = m.enabled, onClick = { open = false; m.onClick() },
                        )
                    }
                }
            }
        }
        if (onClose != null) SquareIconButton(Icons.Rounded.Close, stringResource(R.string.hodhod_ux_widget_close_window), onClose)
    }
}

/** Home / prechat hero header: brand mark + name + availability, then greeting + intro. */
@Composable
internal fun HeroHeader(
    config: WidgetConfig, agents: List<Agent>, showIntro: Boolean, introTitle: String?, introBody: String?,
    menu: List<MenuEntry>, onClose: (() -> Unit)?, showStatus: Boolean = true,
) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box { BrandMark(config.avatarUrl, c.accent) }
            Column(Modifier.weight(1f)) {
                Text(config.websiteName, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showStatus) Text(availabilityText(ctx, config, agents), color = c.textSecondary, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HeaderActionsRow(menu, stringResource(R.string.hodhod_lark_header_menu), onClose)
        }
        if (showIntro) {
            Text(introTitle ?: stringResource(R.string.hodhod_lark_home_greeting), Modifier.padding(top = 20.dp, bottom = 4.dp),
                color = c.text, fontWeight = FontWeight.Black, fontSize = 20.sp, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content))
            Text(introBody ?: stringResource(R.string.hodhod_lark_home_intro), color = c.textSecondary, fontSize = 14.sp, lineHeight = 24.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content))
        } else if (!introTitle.isNullOrBlank()) {
            Text(introTitle, Modifier.padding(top = 16.dp), color = c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 24.sp, maxLines = 1)
        }
    }
}

/** Compact header of the chat/ticket screens: back, agent/orb + name/status, actions. */
@Composable
internal fun CompactHeader(
    config: WidgetConfig, agents: List<Agent>, assignee: Agent?, connecting: Boolean, titleOverride: String? = null,
    menu: List<MenuEntry>, onBack: (() -> Unit)?, onClose: (() -> Unit)?, subtitleOverride: String? = null,
) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val online = isTeamOnline(config, agents)
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.hodhod_lark_home_back), onBack, size = 44.dp)
        Box {
            when {
                assignee != null -> HodhodAvatar(assignee.name, assignee.avatarUrl, 44.dp)
                connecting -> BrandTile(44.dp, square = true)
                else -> BrandMark(config.avatarUrl, c.accent)
            }
            if (online && !connecting) StatusDot(c.teal, Modifier.align(Alignment.BottomEnd), size = 12.dp, glow = false)
        }
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            val (t, sub) = when {
                titleOverride != null -> titleOverride to subtitleOverride
                assignee != null -> assignee.name to (stringResource(R.string.hodhod_lark_header_agent_role) + if (online) " · " + stringResource(R.string.hodhod_lark_header_online) else "")
                connecting -> stringResource(R.string.hodhod_lark_header_connecting) to stringResource(R.string.hodhod_lark_header_connecting_hint)
                else -> config.websiteName to availabilityText(ctx, config, agents)
            }
            Text(t, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, color = c.textSecondary, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HeaderActionsRow(menu, stringResource(R.string.hodhod_lark_header_menu), onClose)
    }
}

/** Connection / issue banners under the header. */
@Composable
internal fun Banners(connection: ConnectionState, loaded: Boolean, notices: List<IssueNotice>, onDismiss: (Long) -> Unit) {
    val c = HodhodTheme.colors
    val down = loaded && (connection == ConnectionState.DISCONNECTED || connection == ConnectionState.RECONNECTING)
    // grace period: do not flash the banner for short reconnects
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(down) { if (down) { kotlinx.coroutines.delay(3000); offline = true } else offline = false }
    AnimatedVisibility(offline) {
        Row(Modifier.fillMaxWidth().background(c.amberSoft).padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WifiOff, null, tint = c.amberText, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.hodhod_ui_offline), color = c.amberText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
    notices.forEach { n ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.amberSoft).padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(n.message, Modifier.weight(1f).padding(vertical = 8.dp), color = c.amberText, fontSize = 13.sp, lineHeight = 21.sp)
            SquareIconButton(Icons.Rounded.Close, stringResource(R.string.hodhod_issue_notice_dismiss), { onDismiss(n.id) }, size = 48.dp, tint = c.amberText, bordered = false)
        }
    }
}

/** Transient toast-like message above the composer. */
@Composable
internal fun NoticeBar(notice: Notice?, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    AnimatedVisibility(notice != null, modifier) {
        if (notice != null) Text(
            notice.text,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (notice.isError) c.rubySoft else c.tealSoft)
                .border(1.dp, if (notice.isError) c.rubyBorder else c.teal.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = if (notice.isError) c.rubyText else c.tealText, fontSize = 13.sp, lineHeight = 20.sp,
        )
    }
}

@Composable
internal fun PoweredBy(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.hodhod_powered_by), modifier.fillMaxWidth().padding(vertical = 8.dp), color = HodhodTheme.colors.placeholder, fontSize = 11.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
