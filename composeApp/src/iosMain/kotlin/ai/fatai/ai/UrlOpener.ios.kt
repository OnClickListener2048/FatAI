package ai.fatai.ai

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String): Boolean =
    UIApplication.sharedApplication.openURL(NSURL.URLWithString(url))
