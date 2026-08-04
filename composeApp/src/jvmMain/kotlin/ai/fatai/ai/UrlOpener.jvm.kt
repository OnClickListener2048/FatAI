package ai.fatai.ai

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String): Boolean = runCatching {
    Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
        run {
            Desktop.getDesktop().browse(URI(url))
            true
        }
}.getOrDefault(false)
