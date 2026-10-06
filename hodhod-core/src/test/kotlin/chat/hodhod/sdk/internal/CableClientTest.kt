package chat.hodhod.sdk.internal

import chat.hodhod.sdk.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CableClientTest {
    private lateinit var srv: TestServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = CopyOnWriteArrayList<ConnectionState>()
    private val events = CopyOnWriteArrayList<Pair<String, String>>()
    private val reconnected = AtomicInteger()

    @Before fun setUp() { srv = TestServer() }
    @After fun tearDown() { scope.cancel(); srv.stop() }

    private fun client(staleMs: Long = 15_000, presenceMs: Long = 60_000) = CableClient(
        OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        cableUrl(srv.url()), srv.url(), "PUBSUB", scope,
        { states.add(it) }, { e, d -> events.add(e to d.toString()) }, { reconnected.incrementAndGet() },
        Backoff(50, 200), staleMs, presenceMs,
    )

    @Test fun subscribesToRoomChannelAndDeliversEvents() = runBlocking<Unit> {
        val c = client(); c.start()
        awaitUntil(message = "connected") { ConnectionState.CONNECTED in states }
        val sub = parseJson(srv.cableFrames.first { it.contains("subscribe") }).obj()!!
        assertEquals("subscribe", sub.str("command"))
        val identifier = parseJson(sub.str("identifier")!!).obj()!!
        assertEquals("RoomChannel", identifier.str("channel")); assertEquals("PUBSUB", identifier.str("pubsub_token"))
        srv.broadcast("message.created", """{"id":1}""")
        awaitUntil(message = "event") { events.isNotEmpty() }
        assertEquals("message.created", events[0].first)
        assertEquals(0, reconnected.get())
        c.stop()
        assertEquals(ConnectionState.DISCONNECTED, states.last())
    }

    @Test fun reconnectsWithBackoffAfterServerDropAndSignalsResync() = runBlocking<Unit> {
        val c = client(); c.start()
        awaitUntil { ConnectionState.CONNECTED in states }
        srv.sockets.last().close(1011, "boom")
        awaitUntil(message = "reconnecting state") { ConnectionState.RECONNECTING in states }
        awaitUntil(message = "second connection") { srv.cableConnections >= 2 && states.last() == ConnectionState.CONNECTED }
        awaitUntil(message = "resync callback") { reconnected.get() == 1 }
        c.stop()
    }

    @Test fun staleConnectionWithoutPingsIsReplaced() = runBlocking<Unit> {
        srv.cableSendPings = false
        val c = client(staleMs = 600); c.start()
        awaitUntil(message = "first connection") { srv.cableConnections >= 1 }
        awaitUntil(timeoutMs = 6_000, message = "reconnect after silence") { srv.cableConnections >= 2 }
        c.stop()
    }

    @Test fun rejectedSubscriptionTriggersReconnectLoop() = runBlocking<Unit> {
        srv.cableAutoConfirm = false
        val c = client(); c.start()
        awaitUntil(message = "subscribe frame") { srv.cableFrames.isNotEmpty() }
        srv.sockets.last().send("""{"type":"reject_subscription","identifier":"x"}""")
        awaitUntil(message = "reconnect attempt") { srv.cableConnections >= 2 }
        c.stop()
    }

    @Test fun sendsPresencePings() = runBlocking<Unit> {
        val c = client(presenceMs = 150); c.start()
        awaitUntil { ConnectionState.CONNECTED in states }
        awaitUntil(message = "update_presence") { srv.cableFrames.any { it.contains("update_presence") } }
        assertTrue(srv.cableFrames.any { it.contains("\"command\":\"message\"") })
        c.stop()
    }
}
