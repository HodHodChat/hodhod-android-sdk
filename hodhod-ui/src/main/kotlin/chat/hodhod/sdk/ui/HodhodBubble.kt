package chat.hodhod.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.Hodhod
import androidx.compose.material.icons.rounded.ChatBubble
import chat.hodhod.sdk.ui.theme.DEFAULT_ACCENT
import chat.hodhod.sdk.ui.theme.HodhodLocalized
import chat.hodhod.sdk.ui.theme.HodhodTheme

/**
 * Floating launcher: the assistant orb with an unread badge. Place it in a `Box` (e.g. `Modifier.align(Alignment.BottomEnd)`);
 * the default click opens [HodhodChatActivity] through [Hodhod.open]. Requires [Hodhod.configure].
 */
@Composable
public fun HodhodBubble(modifier: Modifier = Modifier, accent: Color? = null, onClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val unread by Hodhod.unreadCount.collectAsState()
    val widget by (runCatching { Hodhod.repository.widgetConfig }.getOrNull() ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val color = accent ?: Hodhod.config?.accentColorOverride?.let { Color(it) }
        ?: widget?.widgetColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(DEFAULT_ACCENT)
    HodhodLocalized(chat.hodhod.sdk.HodhodI18n.resolve(Hodhod.config?.locale, widget?.locale)) {
        HodhodTheme(color, androidx.compose.foundation.isSystemInDarkTheme()) {
            val c = HodhodTheme.colors
            val label = stringResource(R.string.hodhod_ux_widget_launcher_open) + if (unread > 0) ", " + stringResource(R.string.hodhod_ux_widget_unread_count, unread) else ""
            Box(modifier.size(64.dp).semantics { contentDescription = label }
                .clickable(role = Role.Button) { (onClick ?: { Hodhod.open(ctx) })() }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ChatBubble, null, tint = c.onAccent, modifier = Modifier.size(28.dp))
                }
                if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).defaultMinSize(20.dp, 20.dp).clip(CircleShape).background(c.ruby).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                    Text(if (unread > 99) "99+" else unread.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
