package chat.hodhod.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import chat.hodhod.sdk.DarkMode
import chat.hodhod.sdk.Hodhod
import chat.hodhod.sdk.HodhodUser
import chat.hodhod.sdk.ui.HodhodBubble

/** Demo: configure (base URL + website token), identify a user, open the chat, show the floating bubble. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyLaunchExtras()
        setContent { MaterialTheme { SampleScreen() } }
    }
}

/**
 * Offline UI demo: `--es fake chat|ticket|both|offline|messages` (add `-tickets`, e.g. `chat-tickets`, `ticket-tickets`: a visitor with open, closed and
 * chat-converted tickets, 25 in total so pagination shows) swaps the real repository for the in-memory fake. */
internal var lastFake: chat.hodhod.sdk.FakeHodhodRepository? = null
private fun installFake(kind: String) {
    val mode = when (kind) { "ticket", "ticket-tickets" -> chat.hodhod.sdk.ContactMode.TICKET; "both", "both-tickets" -> chat.hodhod.sdk.ContactMode.BOTH; else -> chat.hodhod.sdk.ContactMode.CHAT }
    val now = System.currentTimeMillis() / 1000
    fun msg(id: Int, text: String, mine: Boolean, ago: Long, sender: chat.hodhod.sdk.Sender? = chat.hodhod.sdk.Sender(1, "Sara", null, "user")) = chat.hodhod.sdk.Message(
        id.toString(), id.toLong(), null, 1, text, if (mine) chat.hodhod.sdk.MessageType.INCOMING else chat.hodhod.sdk.MessageType.OUTGOING, null, emptyMap(), now - ago, emptyList(),
        if (mine) null else sender, chat.hodhod.sdk.MessageStatus.SENT)
    val msgs = if (kind == "messages") listOf(msg(1, "سلام، سفارشم هنوز نرسیده", true, 90000), msg(2, "سلام! وقت بخیر 👋 شماره سفارش را بفرمایید؟", false, 89000),
        msg(3, "**ORD-1234** — https://example.com/track", true, 300), msg(4, "پیگیری می‌کنم، چند لحظه لطفا", false, 120), msg(5, "Thanks, take your time", true, 60), msg(6, "Sure.", false, 30)) else emptyList()
    val repo = chat.hodhod.sdk.FakeHodhodRepository(chat.hodhod.sdk.FakeHodhodRepository.sampleConfig(mode), msgs)
    if (kind.endsWith("-tickets")) {
        val subjects = listOf("Refund for order 1042", "App crashes on login", "درخواست فاکتور", "Change my plan", "Delivery address wrong")
        repo.setTickets((1..25).map { n ->
            val status = when { n <= 3 -> chat.hodhod.sdk.TicketStatus.OPEN; n == 4 -> chat.hodhod.sdk.TicketStatus.WAITING; n % 2 == 0 -> chat.hodhod.sdk.TicketStatus.CLOSED; else -> chat.hodhod.sdk.TicketStatus.RESOLVED }
            chat.hodhod.sdk.TicketSummary(100 - n, subjects[n % subjects.size], status, null, now - n * 7200L, now - n * 3600L, null, 200 + n,
                source = if (n == 2) "conversation" else "widget")
        })
    }
    lastFake = repo
    chat.hodhod.sdk.Hodhod.installRepository(repo)
}

/**
 * Test/automation hook: `adb shell am start -n chat.hodhod.sample/.MainActivity --es token XXX [--es baseUrl ..] [--es locale fa]
 * [--es dark DARK] [--es identifier u1 --es hash <hmac> --es name Ali --es email a@b.c] [--ez open true]` configures (and optionally opens) without typing.
 */
private fun MainActivity.applyLaunchExtras() {
    val x = intent?.extras ?: return
    val token = x.getString("token") ?: return
    x.getString("fake")?.let { installFake(it) }
    if (x.getString("conn") == "down") lastFake?.setConnection(chat.hodhod.sdk.ConnectionState.RECONNECTING)
    val old = Settings.load(this)
    val s = Settings(x.getString("baseUrl") ?: old?.baseUrl ?: Settings.DEFAULT_BASE_URL, token, x.getString("locale") ?: "", x.getString("dark") ?: "AUTO", x.getString("accent") ?: "",
        x.getString("identifier") ?: "", x.getString("hash") ?: "", x.getString("name") ?: "", x.getString("email") ?: "", old?.phone ?: "")
    if (x.getBoolean("logout")) Hodhod.logout()
    Settings.save(this, s); Settings.apply(this, s); Hodhod.start()
    if (s.identifier.isNotBlank()) Hodhod.identify(HodhodUser(s.identifier, s.identifierHash.ifBlank { null }, s.name.ifBlank { null }, s.email.ifBlank { null }))
    if (x.getBoolean("open")) Hodhod.open(this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen() {
    val ctx = LocalContext.current
    val saved = remember { Settings.load(ctx) }
    var baseUrl by rememberSaveable { mutableStateOf(saved?.baseUrl ?: Settings.DEFAULT_BASE_URL) }
    var token by rememberSaveable { mutableStateOf(saved?.token ?: "") }
    var locale by rememberSaveable { mutableStateOf(saved?.locale ?: "") }
    var dark by rememberSaveable { mutableStateOf(saved?.dark ?: "AUTO") }
    var accent by rememberSaveable { mutableStateOf(saved?.accent ?: "") }
    var identifier by rememberSaveable { mutableStateOf(saved?.identifier ?: "") }
    var hash by rememberSaveable { mutableStateOf(saved?.identifierHash ?: "") }
    var name by rememberSaveable { mutableStateOf(saved?.name ?: "") }
    var email by rememberSaveable { mutableStateOf(saved?.email ?: "") }
    var phone by rememberSaveable { mutableStateOf(saved?.phone ?: "") }
    var status by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(saved?.token?.isNotBlank() == true) }
    val form = { Settings(baseUrl, token, locale, dark, accent, identifier, hash, name, email, phone) }
    val unread by Hodhod.unreadCount.collectAsState()
    val tickets by Hodhod.repository.ticketSummary.collectAsState()
    val state by Hodhod.state.collectAsState()

    Scaffold(Modifier.fillMaxSize(), topBar = { TopAppBar(title = { Text("Hodhod SDK sample") }) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding().padding(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Configure", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth().testTag("baseUrl"), label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().testTag("token"), label = { Text("Website token") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(locale, { locale = it }, Modifier.weight(1f).testTag("locale"), label = { Text("Locale (fa/en/ar/…)") }, singleLine = true)
                    OutlinedTextField(accent, { accent = it }, Modifier.weight(1f).testTag("accent"), label = { Text("Accent #RRGGBB") }, singleLine = true)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Language:")
                    FilterChip(locale.isBlank(), { locale = "" }, { Text("System") }, Modifier.testTag("lang-system"))
                    listOf("fa" to "فارسی", "en" to "English", "ar" to "العربية", "de" to "Deutsch", "es" to "Español", "fr" to "Français").forEach { (code, label) ->
                        FilterChip(locale == code, { locale = code }, { Text(label) }, Modifier.testTag("lang-$code"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark:")
                    DarkMode.entries.forEach { m -> FilterChip(dark == m.name, { dark = m.name }, { Text(m.name) }, Modifier.testTag("dark-${m.name}")) }
                }
                Button({ Settings.save(ctx, form()); Settings.apply(ctx, form()); Hodhod.start(); configured = true; status = "Configured" }, Modifier.fillMaxWidth().testTag("configure"), enabled = token.isNotBlank()) { Text("Save & configure") }

                HorizontalDivider()
                Text("2. Identify (optional)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(identifier, { identifier = it }, Modifier.fillMaxWidth().testTag("identifier"), label = { Text("User identifier") }, singleLine = true)
                OutlinedTextField(hash, { hash = it }, Modifier.fillMaxWidth(), label = { Text("identifier_hash (HMAC from your backend)") }, singleLine = true)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("name"), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().testTag("email"), label = { Text("Email") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        Settings.save(ctx, form()); Settings.apply(ctx, form())
                        Hodhod.identify(HodhodUser(identifier.ifBlank { null }, hash.ifBlank { null }, name.ifBlank { null }, email.ifBlank { null }, phone.ifBlank { null },
                            customAttributes = mapOf("source" to "sample-app"))) { r -> status = if (r.isSuccess) "Identified" else "Identify failed: ${r.exceptionOrNull()?.message}" }
                    }, Modifier.weight(1f).testTag("identify"), enabled = configured) { Text("Identify") }
                    OutlinedButton({ Hodhod.logout(); status = "Logged out" }, Modifier.weight(1f).testTag("logout"), enabled = configured) { Text("Logout") }
                }

                HorizontalDivider()
                Text("3. Chat", style = MaterialTheme.typography.titleMedium)
                Button({ Settings.save(ctx, form()); Settings.apply(ctx, form()); Hodhod.open(ctx) }, Modifier.fillMaxWidth().testTag("open"), enabled = token.isNotBlank()) { Text("Open chat" + (if (unread > 0) " ($unread unread)" else "") + (if (tickets.total > 0) " · ${tickets.open} open / ${tickets.total} tickets" else "")) }

                Text("State: $state   ${if (status.isNotEmpty()) "· $status" else ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("status"))
            }
            if (configured) HodhodBubble(Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}
