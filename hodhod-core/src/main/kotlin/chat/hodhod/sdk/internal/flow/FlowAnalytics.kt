package chat.hodhod.sdk.internal.flow

import chat.hodhod.sdk.HodhodException
import chat.hodhod.sdk.internal.arr
import chat.hodhod.sdk.internal.int
import chat.hodhod.sdk.internal.jsonObjectOf
import chat.hodhod.sdk.internal.obj
import chat.hodhod.sdk.internal.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Flow analytics ingestion (port of widget `useFlowAnalytics.js`): maps runtime events to the privacy allow-listed wire events and posts batches
 * `{sid, sent_at, ctx{locale,page,referrer?,test}, events[], vid?, rev?}` to `/api/v1/widget/chatbot_flow_events`. Debounced (2 s, max 8 s,
 * immediately at 20 queued events or on handoff/resolve), exponential backoff on 429/5xx (6 attempts), de-duplicated by (sid, seq) on the server.
 * Nothing the visitor typed ever leaves (the mapper only copies ids).
 */
internal class FlowAnalytics(
    private val scope: CoroutineScope,
    private val post: suspend (JsonObject) -> Pair<Int?, JsonElement?>,
    private val locale: () -> String,
    private val visitorId: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private class Session(val sid: String) {
        var rev: Int? = null
        var last = 0
        val queue = ArrayList<Map<String, Any?>>()
        val mapper = MapperState()
        var closed = false
    }

    private val sessions = LinkedHashMap<String, Session>()
    private var disabled = false
    private var inFlight = false
    private var attempts = 0
    private var backoffUntil = 0L
    private var flushJob: Job? = null
    private var firstPendingAt: Long? = null
    private var ctxPage: String? = null
    private var ctxLocale: String? = null

    /** Feed one runtime event (call on the machine's thread). */
    fun onEvent(evt: FlowRuntimeEvent) {
        if (disabled) return
        runCatching {
            val s = sessions.getOrPut(evt.sessionId) { Session(evt.sessionId) }
            if (s.closed) return
            evt.revision?.let { s.rev = it }
            val res = mapFlowEvent(s.mapper, evt)
            res.ctx?.let { c ->
                (c["locale"] as? String)?.takeIf { it.isNotEmpty() }?.let { ctxLocale = it }
                (c["page"] as? String)?.takeIf { it.isNotEmpty() }?.let { ctxPage = it }
            }
            if (res.events.isEmpty()) return
            res.events.forEach { w ->
                val seq = (w["seq"] as? Int)?.takeIf { it > s.last } ?: (s.last + 1)
                s.last = seq
                s.queue += w + ("seq" to seq)
            }
            if (s.queue.size > QUEUE_CAP) repeat(s.queue.size - QUEUE_CAP) { s.queue.removeAt(0) }
            if (res.flushNow) flush() else scheduleFlush()
        }
    }

    private fun total() = sessions.values.sumOf { it.queue.size }

    private fun scheduleFlush() {
        val t = now()
        if (firstPendingAt == null) firstPendingAt = t
        flushJob?.cancel()
        val delayMs = if (total() >= FLUSH_BATCH_THRESHOLD) 0L else minOf(DEBOUNCE_MS, maxOf(0L, firstPendingAt!! + MAX_WAIT_MS - t))
        flushJob = scope.launch {
            sleep(delayMs)
            flush()
        }
    }

    /** Send everything queued now (also called when the app goes to the background). */
    fun flush() {
        flushJob?.cancel()
        flushJob = null
        firstPendingAt = null
        scope.launch { sendNext() }
    }

    private fun pending(): Session? {
        val cutoff = now() - MAX_EVENT_AGE_MS
        var found: Session? = null
        sessions.values.forEach { s ->
            s.queue.removeAll { (it["t"] as? Long ?: 0L) < cutoff }
            if (found == null && s.queue.isNotEmpty()) found = s
        }
        return found
    }

    private fun envelope(s: Session, events: List<Map<String, Any?>>): JsonObject {
        val ctx = linkedMapOf<String, Any?>()
        (locale().takeIf { it.isNotEmpty() } ?: ctxLocale)?.let { ctx["locale"] = it.take(12) }
        ctxPage?.let { ctx["page"] = it.take(300) }
        ctx["test"] = false
        return jsonObjectOf(
            "sid" to s.sid, "sent_at" to now(), "ctx" to ctx, "events" to events, "vid" to visitorId(), "rev" to s.rev,
        )
    }

    private suspend fun sendNext() {
        if (disabled || inFlight) return
        if (now() < backoffUntil) return
        val s = pending() ?: return
        val batch = s.queue.take(BATCH_SIZE)
        inFlight = true
        val (status, body) = try {
            post(envelope(s, batch))
        } catch (e: HodhodException) {
            null to null
        } catch (e: kotlinx.coroutines.CancellationException) {
            inFlight = false
            throw e
        } catch (e: Exception) {
            null to null
        }
        inFlight = false
        when {
            status == null || status == 429 || status >= 500 -> {
                if (failure(s)) return
            }
            status == 200 -> {
                attempts = 0
                val b = body.obj()
                val listed = listOf("accepted", "duplicate", "rejected").flatMap { k ->
                    (b?.get(k).arr() ?: emptyList()).mapNotNull { x -> x.obj()?.int("seq") ?: (x as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                }
                val drop = (if (listed.isNotEmpty()) listed else batch.map { it["seq"] as Int }).toSet()
                s.queue.removeAll { (it["seq"] as Int) in drop }
                if ((b?.get("capped") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
                    s.closed = true
                    s.queue.clear()
                }
            }
            status == 202 || status == 401 || status == 404 -> {
                disabled = true
                sessions.values.forEach { it.queue.clear() }
                return
            }
            else -> {
                attempts = 0
                val drop = batch.map { it["seq"] as Int }.toSet()
                s.queue.removeAll { (it["seq"] as Int) in drop }
            }
        }
        if (pending() != null) sendNext()
    }

    private fun failure(s: Session): Boolean {
        attempts += 1
        if (attempts >= MAX_ATTEMPTS) {
            attempts = 0
            repeat(minOf(BATCH_SIZE, s.queue.size)) { s.queue.removeAt(0) }
            return false
        }
        val d = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L shl (attempts - 1)))
        backoffUntil = now() + d
        scope.launch {
            sleep(d)
            sendNext()
        }
        return true
    }

    /** Test hook: events waiting to be sent. */
    fun queuedCount(): Int = total()

    companion object {
        const val QUEUE_CAP = 200
        const val BATCH_SIZE = 50
        const val FLUSH_BATCH_THRESHOLD = 20
        const val DEBOUNCE_MS = 2000L
        const val MAX_WAIT_MS = 8000L
        const val BACKOFF_BASE_MS = 2000L
        const val BACKOFF_MAX_MS = 60000L
        const val MAX_ATTEMPTS = 6
        const val MAX_EVENT_AGE_MS = 6 * 3600 * 1000L
    }
}
