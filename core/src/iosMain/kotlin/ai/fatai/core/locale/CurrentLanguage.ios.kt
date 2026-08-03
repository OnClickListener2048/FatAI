@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.fatai.core.locale

import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun currentLanguageTag(): String = getenv("LANG")
    ?.toKString()
    ?.substringBefore('.')
    ?.replace('_', '-')
    ?.ifBlank { "en" }
    ?: "en"
