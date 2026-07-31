package ai.fatai.feature.settings

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class SettingsRepository(private val queries: WatsonQueries, private val currentUser: CurrentUserProvider) {
    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        putValue(THEME_MODE_KEY, mode.name)
        _themeMode.value = mode
    }

    fun getValue(key: String): String? =
        queries.selectAppSetting(currentUser.currentUserId, key).executeAsOneOrNull()?.value_

    fun putValue(key: String, value: String) {
        queries.upsertAppSetting(currentUser.currentUserId, key, value, now())
    }

    private fun readThemeMode(): ThemeMode =
        queries.selectAppSetting(currentUser.currentUserId, THEME_MODE_KEY).executeAsOneOrNull()
            ?.value_
            ?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
            ?: ThemeMode.SYSTEM

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val THEME_MODE_KEY = "theme_mode"
    }
}
