package ai.fatai.core.locale

import java.util.Locale

actual fun currentLanguageTag(): String = Locale.getDefault().toLanguageTag().ifBlank { "en" }
