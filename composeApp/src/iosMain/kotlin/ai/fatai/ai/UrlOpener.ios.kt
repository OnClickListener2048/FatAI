package ai.fatai.ai

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String): Boolean =
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) } ?: false
