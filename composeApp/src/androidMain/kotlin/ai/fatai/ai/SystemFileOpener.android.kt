package ai.fatai.ai

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import java.io.File

actual fun openFileWithSystemApplication(localPath: String, mimeType: String): Boolean = runCatching {
    val context = FileKit.context
    val uri = if (localPath.startsWith("content://") || localPath.startsWith("file://")) {
        Uri.parse(localPath)
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(localPath))
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (intent.resolveActivity(context.packageManager) == null) {
        false
    } else {
        context.startActivity(intent)
        true
    }
}.getOrDefault(false)
