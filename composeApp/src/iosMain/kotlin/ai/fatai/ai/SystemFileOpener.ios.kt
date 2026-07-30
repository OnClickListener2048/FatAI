package ai.fatai.ai

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openFileWithSystemApplication(localPath: String, mimeType: String): Boolean =
    UIApplication.sharedApplication.openURL(NSURL.fileURLWithPath(localPath))
