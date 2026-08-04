package ai.fatai.ai

import android.content.Intent
import android.net.Uri
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context

actual fun openUrl(url: String): Boolean = runCatching {
    val context = FileKit.context
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) == null) {
        false
    } else {
        context.startActivity(intent)
        true
    }
}.getOrDefault(false)
