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
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent {
            HodhodChatContent(Hodhod.repository, Hodhod.config, Modifier, onClose = { finish() }, applySystemBars = true)
        }
    }
}
