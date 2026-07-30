package ai.fatai.ai

import java.awt.Desktop
import java.io.File

actual fun openFileWithSystemApplication(localPath: String, mimeType: String): Boolean = runCatching {
    val file = File(localPath)
    Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.OPEN) &&
        file.isFile &&
        run {
            Desktop.getDesktop().open(file)
            true
        }
}.getOrDefault(false)
