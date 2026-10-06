package chat.hodhod.sdk.internal

import chat.hodhod.sdk.Attachment
import chat.hodhod.sdk.HodhodException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of an HTTP call: parsed JSON (null for empty/non-JSON), raw text and status. */
internal class ApiResponse(val code: Int, val text: String, val json: JsonElement?)

/** Multipart text/file part. */
internal sealed interface FormPart {
    data class Text(val name: String, val value: String) : FormPart
    data class File(val name: String, val attachment: Attachment) : FormPart
}

/**
 * Thin HTTP layer for the public widget API. Every call carries `website_token` + `locale` query params and the `X-Auth-Token`
 * header (when a session exists). Errors become [HodhodException] with a stable code (see HodhodRepository).
 */
internal class WidgetApi(
    private val baseUrl: HttpUrl,
    val websiteToken: String,
    private val client: OkHttpClient,
    private val locale: () -> String?,
    private val authToken: () -> String?,
    private val log: (String) -> Unit = {},
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val b = baseUrl.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        b.addQueryParameter("website_token", websiteToken)
        locale()?.let { b.addQueryParameter("locale", it) }
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    suspend fun get(path: String, query: Map<String, String?> = emptyMap(), auth: Boolean = true): ApiResponse =
        execute(Request.Builder().url(url(path, query)).get(), auth)

    suspend fun postJson(path: String, body: JsonElement? = null, query: Map<String, String?> = emptyMap(), auth: Boolean = true): ApiResponse =
        execute(Request.Builder().url(url(path, query)).post(jsonBody(body)), auth)

    suspend fun patchJson(path: String, body: JsonElement? = null, auth: Boolean = true): ApiResponse =
        execute(Request.Builder().url(url(path)).patch(jsonBody(body)), auth)

    suspend fun deleteJson(path: String, body: JsonElement? = null): ApiResponse =
        execute(Request.Builder().url(url(path)).delete(jsonBody(body)), true)

    suspend fun postMultipart(path: String, parts: List<FormPart>): ApiResponse {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
        for (p in parts) when (p) {
            is FormPart.Text -> mb.addFormDataPart(p.name, p.value)
            is FormPart.File -> mb.addFormDataPart(
                p.name, p.attachment.fileName,
                p.attachment.file.asRequestBody(p.attachment.mimeType.toMediaType()),
            )
        }
        return execute(Request.Builder().url(url(path)).post(mb.build()), true)
    }

    /** Fetch an arbitrary same-origin page (the `/widget` HTML) without the JSON headers. */
    suspend fun getText(path: String, query: Map<String, String?>): String =
        execute(Request.Builder().url(url(path, query)).get().header("Accept", "text/html"), false).text

    private fun jsonBody(body: JsonElement?): RequestBody = (body?.toString() ?: "{}").toRequestBody(jsonType)

    private suspend fun execute(builder: Request.Builder, auth: Boolean): ApiResponse {
        builder.header("Accept", "application/json, text/html;q=0.5")
        if (auth) authToken()?.let { builder.header("X-Auth-Token", it) }
        val request = builder.build()
        log("--> ${request.method} ${request.url.encodedPath}")
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            log("<-- network error ${e.javaClass.simpleName}")
            throw HodhodException("network", e.message, null, e)
        }
        // The body is read from the socket: never on the caller's (possibly Main) dispatcher (NetworkOnMainThreadException).
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            response.use {
                val text = it.body?.string().orEmpty()
                log("<-- ${it.code} ${request.url.encodedPath}")
                if (it.isSuccessful) ApiResponse(it.code, text, parseJson(text)) else throw errorFor(it.code, text)
            }
        }
    }

    companion object {
        private val KNOWN = Regex("^[a-z][a-z0-9_]{2,40}$")

        fun errorFor(status: Int, body: String): HodhodException {
            val json = parseJson(body).obj()
            val serverCode = json?.str("code") ?: json?.str("error")?.takeIf { KNOWN.matches(it) }
            val message = json?.str("message") ?: json?.str("error")
            val code = when {
                serverCode != null && (status in 400..499) -> serverCode
                status == 401 -> if (message?.contains("suspended", true) == true) "suspended" else "unauthorized"
                status == 402 -> "payment_required"
                status == 403 -> if (message?.contains("resolved", true) == true) "conversation_resolved" else "forbidden"
                status == 404 -> "not_found"
                status == 422 -> "invalid_param"
                status == 429 -> "rate_limited"
                else -> "server"
            }
            return HodhodException(code, message, status)
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

internal fun JsonElement?.objOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
