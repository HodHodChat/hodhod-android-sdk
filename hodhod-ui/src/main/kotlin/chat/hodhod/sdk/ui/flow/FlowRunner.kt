package chat.hodhod.sdk.ui.flow

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import chat.hodhod.sdk.FlowStatus
import chat.hodhod.sdk.HodhodRepository
import chat.hodhod.sdk.WidgetConfig

/**
 * Chatbot-flow runner of the Home screen (web `ChatbotFlowRunner.vue`): loads the inbox flow, draws it above the regular start card and
 * hands over to the live chat after a handoff. Returns true when the flow is mandatory (`require_flow`) so the caller hides the direct
 * "start conversation" shortcut; the visitor then reaches an agent only through the flow.
 */
@Composable
internal fun FlowRunnerHost(repo: HodhodRepository, config: WidgetConfig, hasActive: Boolean, onStartConversation: () -> Unit): Boolean {
    val engine = repo.flow
    val ctx = LocalContext.current
    val reduced = remember(ctx) { animationsDisabled(ctx) }
    val status by engine.status.collectAsState()
    val view by engine.view.collectAsState()
    val require by engine.requireFlow.collectAsState()
    val handedOff by engine.handedOff.collectAsState()

    LaunchedEffect(engine, ctx) { engine.setTranslator(flowTranslator(ctx)) }
    // The load runs in a composition-scoped scope (not in the effect): the effect restarts when the status flips to LOADING and would cancel it.
    val scope = rememberCoroutineScope()
    LaunchedEffect(engine, hasActive, config.hasFlowBot, status) {
        if (config.hasFlowBot && !hasActive && status == FlowStatus.IDLE) {
            engine.setReducedMotion(reduced)
            scope.launch { engine.load() }
        }
    }
    LaunchedEffect(handedOff) { if (handedOff) onStartConversation() }

    val v = view
    val show = config.hasFlowBot && !hasActive && status == FlowStatus.READY && v != null
    if (show) FlowStepView(v!!, engine, reduced)
    return show && require
}

/** True when the user turned system animations off ("Remove animations" / animator scale 0). */
internal fun animationsDisabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * Chrome-string translator for the engine: `INPUT.ERROR_MIN_LENGTH` -> `hodhod_chatbot_flow_input_error_min_length`; `{name}` params are
 * positional in alphabetical order of their names (same convention as `tools/gen_strings.py`).
 */
internal fun flowTranslator(context: Context): (String, Map<String, Any?>) -> String = { key, params ->
    val name = "hodhod_chatbot_flow_" + key.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    val args = params.toSortedMap().values.map { v -> if (v is Double && v == Math.floor(v)) v.toLong().toString() else v?.toString().orEmpty() }
    if (id == 0) key else runCatching { context.getString(id, *args.toTypedArray()) }.getOrDefault(key)
}
