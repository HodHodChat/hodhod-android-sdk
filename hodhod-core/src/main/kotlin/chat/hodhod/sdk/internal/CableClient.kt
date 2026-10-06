package chat.hodhod.sdk.internal

import chat.hodhod.sdk.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

/** Jittered exponential backoff ("full jitter" capped at [maxMs]). */
internal class Backoff(private val baseMs: Long = 1_000, private val maxMs: Long = 30_000, private val random: Random = Random.Default) {
    private var attempt = 0
    fun next(): Long {
        val cap = min(maxMs, baseMs shl min(attempt, 10))
        attempt++
        // 50%..100% of the cap: avoids thundering herd while keeping progress.
        return (cap / 2) + random.nextLong(cap / 2 + 1)
    }
    fun reset() { attempt = 0 }
}

/**
 * Minimal ActionCable client (RoomChannel) on an OkHttp WebSocket: welcome -> subscribe -> confirm; server pings are the keep-alive
 * (no ping for [staleMs] = dead connection -> reconnect); reconnects forever with [Backoff] until [stop].
 * [onEvent] gets `{event, data}` payloads; [onReconnected] fires on every confirmed subscription after the first.
 */
internal class CableClient(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val origin: String,
    private val pubsubToken: String,
    private val scope: CoroutineScope,
    private val onState: (ConnectionState) -> Unit,
    private val onEvent: (String, JsonObject) -> Unit,
    private val onReconnected: () -> Unit,
    private val backoff: Backoff = Backoff(),
    private val staleMs: Long = 15_000,
    private val presenceIntervalMs: Long = 60_000,
    private val log: (String) -> Unit = {},
) {
    private val identifier = jsonObjectOf("channel" to "RoomChannel", "pubsub_token" to pubsubToken).toString()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var lastPing = 0L
    @Volatile private var confirmedOnce = false
    @Volatile private var generation = 0
    private var loop: Job? = null
    private var watchdog: Job? = null
    private var presence: Job? = null

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        stopped = false
        loop = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        stopped = true
        loop?.cancel(); watchdog?.cancel(); presence?.cancel()
        socket?.close(1000, "bye")
        socket = null
        onState(ConnectionState.DISCONNECTED)
    }

    /** Force a reconnect (e.g. app returned to foreground / network came back). */
    fun reconnectNow() {
        socket?.cancel()
    }

    private val gate = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private suspend fun runLoop() {
        var first = true
        while (scope.isActive && !stopped) {
            onState(if (first) ConnectionState.CONNECTING else ConnectionState.RECONNECTING)
            first = false
            val myGen = ++generation
            val request = Request.Builder().url(wsUrl).header("Origin", origin).header("Sec-WebSocket-Protocol", "actioncable-v1-json").build()
            lastPing = System.currentTimeMillis()
            socket = client.newWebSocket(request, Listener(myGen))
            // Wait until this socket dies.
            gate.receive()
            socket = null
            if (stopped) break
            val wait = backoff.next()
            log("cable reconnect in ${wait}ms")
            onState(ConnectionState.RECONNECTING)
            delay(wait)
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log("cable open")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val msg = parseJson(text).obj() ?: return
            when (msg.str("type")) {
                "welcome" -> webSocket.send(jsonObjectOf("command" to "subscribe", "identifier" to identifier).toString())
                "ping" -> lastPing = System.currentTimeMillis()
                "confirm_subscription" -> {
                    lastPing = System.currentTimeMillis()
                    backoff.reset()
                    onState(ConnectionState.CONNECTED)
                    startWatchdogs(webSocket)
                    if (confirmedOnce) onReconnected()
                    confirmedOnce = true
                }
                "reject_subscription" -> {
                    log("cable subscription rejected")
                    webSocket.cancel() // do not wait for the server's close handshake; the loop reconnects with backoff
                }
                "disconnect" -> webSocket.cancel()
                null -> {
                    val m = msg.sub("message") ?: return
                    val event = m.str("event") ?: return
                    val data = m.sub("data") ?: JsonObject(emptyMap())
                    try { onEvent(event, data) } catch (e: Exception) { log("cable event handler error ${e.javaClass.simpleName}") }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dead(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("cable failure ${t.javaClass.simpleName}")
            dead(webSocket)
        }

        private fun dead(webSocket: WebSocket) {
            if (gen != generation) return
            watchdog?.cancel(); presence?.cancel()
            gate.trySend(Unit)
        }
    }

    private fun startWatchdogs(webSocket: WebSocket) {
        watchdog?.cancel(); presence?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(staleMs / 3)
                if (System.currentTimeMillis() - lastPing > staleMs) {
                    log("cable stale, reconnecting")
                    webSocket.cancel()
                    return@launch
                }
            }
        }
        presence = scope.launch {
            while (isActive) {
                delay(presenceIntervalMs)
                webSocket.send(
                    jsonObjectOf("command" to "message", "identifier" to identifier, "data" to jsonObjectOf("action" to "update_presence").toString()).toString(),
                )
            }
        }
    }
}

internal fun cableUrl(baseUrl: String): String =
    baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/cable"
