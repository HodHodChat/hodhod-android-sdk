package chat.hodhod.sdk.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Looks up a generated widget string by its JSON key path, e.g. `WIDGET_TICKET.ERRORS.GENERIC`. Null if absent. */
internal fun Context.widgetString(keyPath: String, vararg args: Any): String? {
    val name = "hodhod_" + keyPath.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    val id = resources.getIdentifier(name, "string", packageName)
    return if (id == 0) null else getString(id, *args)
}

@Composable
internal fun widgetString(keyPath: String, fallbackKeyPath: String, vararg args: Any): String {
    val ctx = LocalContext.current
    return ctx.widgetString(keyPath, *args) ?: ctx.widgetString(fallbackKeyPath, *args) ?: keyPath
}

@Composable
internal fun s(id: Int): String = stringResource(id)
