package chat.hodhod.sdk.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import chat.hodhod.sdk.Hodhod

/**
 * Full-screen chat. Open with `Hodhod.open(context)` or the deep link `hodhod://chat`. The theme is isolated from the host
 * app (`Theme.Hodhod`), edge-to-edge, singleTop; back navigation is handled inside [HodhodChat].
 */
public class HodhodChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported for the hodhod://chat deep link: if the SDK was never configured (foreign link in a cold process), close quietly instead of crashing the host app.
        val repository = runCatching { Hodhod.repository }.getOrNull()
        if (repository == null) {
            finish()
            return
        }
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent {
            HodhodChatContent(repository, Hodhod.config, Modifier, onClose = { finish() }, applySystemBars = true)
        }
    }
}
