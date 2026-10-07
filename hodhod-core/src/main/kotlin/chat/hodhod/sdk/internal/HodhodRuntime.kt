package chat.hodhod.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import chat.hodhod.sdk.BuildConfig
import chat.hodhod.sdk.HodhodConfig
import chat.hodhod.sdk.HodhodException
import chat.hodhod.sdk.HodhodRepository
import chat.hodhod.sdk.HodhodState
import chat.hodhod.sdk.HodhodUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Wires storage, HTTP, websocket and the repository for one [HodhodConfig]. */
internal class HodhodRuntime(private val app: Application, val config: HodhodConfig) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val store: SessionStore
    private val impl: DefaultHodhodRepository
    val repository: HodhodRepository get() = impl
    private val configError: HodhodException?

    private fun log(msg: String) {
        if (config.enableLogging) Log.d("Hodhod", msg)
    }

    init {
        val base = config.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        configError = when {
            base == null -> HodhodException("config", "baseUrl is not a valid URL")
            base.scheme != "https" && !config.allowCleartext -> HodhodException("cleartext", "http:// baseUrl requires HodhodConfig(allowCleartext = true)")
            config.websiteToken.isBlank() -> HodhodException("config", "websiteToken is blank")
            else -> null
        }
        val safeBase = base ?: "https://invalid.invalid".toHttpUrlOrNull()!!
        store = AndroidSessionStore(app, config.baseUrl, config.websiteToken, ::log)
        val ua = "HodhodAndroidSDK/${BuildConfig.SDK_VERSION} (Android ${android.os.Build.VERSION.RELEASE})" +
            (config.userAgentSuffix?.let { " $it" } ?: "")
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build()) }
            .build()
        val wsHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(0, TimeUnit.MILLISECONDS).build()
        lateinit var repo: DefaultHodhodRepository
        val api = WidgetApi(safeBase, config.websiteToken, http, locale = { repo.uiLocale.value.takeIf { config.locale != null || repo.widgetConfig.value != null } }, authToken = { store.get(SessionStore.AUTH_TOKEN) }, log = ::log)
        repo = DefaultHodhodRepository(
            api = api, store = store, config = config, scope = scope,
            cableFactory = { token, onState, onEvent, onReconnected ->
                CableClient(wsHttp, cableUrl(safeBase.toString()), safeBase.toString().trimEnd('/'), token, scope, onState, onEvent, onReconnected, log = ::log)
            },
            log = ::log,
        )
        impl = repo
        if (configError != null) impl.failBootstrap(configError)
        registerLifecycle()
        registerNetworkCallback()
    }

    /** Recover by itself when connectivity returns (airplane mode off, wifi back). Needs ACCESS_NETWORK_STATE (declared by the SDK). */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        try {
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    impl.onNetworkAvailable()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: RuntimeException) {
            log("network callback unavailable: ${e.javaClass.simpleName}")
        }
    }

    private var started = 0

    private fun registerLifecycle() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) impl.onForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) impl.onBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun start() {
        if (configError != null) return
        scope.launch { impl.refresh() }
    }

    fun shutdown() {
        networkCallback?.let { cb ->
            runCatching { (app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        impl.shutdown()
        scope.cancel()
    }

    fun identify(user: HodhodUser, onResult: (Result<Unit>) -> Unit) {
        if (configError != null) return onResult(Result.failure(configError))
        scope.launch {
            val r = impl.identify(user)
            main.post { onResult(r) }
        }
    }

    fun setCustomAttributes(attrs: Map<String, Any?>) {
        if (configError != null) return
        scope.launch { impl.setContactCustomAttributes(attrs) }
    }

    fun logout() {
        scope.launch {
            store.clear()
            // Privacy: picked/captured files copied for upload by the UI module must not outlive the session.
            listOf("hodhod-uploads", "hodhod-camera").forEach { java.io.File(app.cacheDir, it).deleteRecursively() }
            impl.resetSession()
        }
    }
}
