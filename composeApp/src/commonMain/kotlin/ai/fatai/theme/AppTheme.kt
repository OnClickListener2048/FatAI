package ai.fatai.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import ai.fatai.feature.settings.SettingsRepository
import ai.fatai.feature.settings.ThemeMode
import org.koin.compose.koinInject

// OpenWebUI palette (Tailwind gray scale, neutral; blue-600 accent).
private val Gray50 = Color(0xFFF8F8F8)
private val Gray100 = Color(0xFFEBEBEB)
private val Gray200 = Color(0xFFE4E4E4)
private val Gray300 = Color(0xFFCECECE)
private val Gray400 = Color(0xFFB4B4B4)
private val Gray500 = Color(0xFF9B9B9B)
private val Gray600 = Color(0xFF666666)
private val Gray700 = Color(0xFF4D4D4D)
private val Gray800 = Color(0xFF333333)
private val Gray850 = Color(0xFF262626)
private val Gray900 = Color(0xFF161616)
private val Gray950 = Color(0xFF0D0D0D)
private val Blue600 = Color(0xFF2563EB)

private val LightColors = lightColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    primaryContainer = Gray100,
    onPrimaryContainer = Gray900,
    secondary = Gray500,
    onSecondary = Color.White,
    secondaryContainer = Gray200,
    onSecondaryContainer = Gray900,
    tertiary = Blue600,
    surface = Color.White,
    onSurface = Gray900,
    surfaceVariant = Gray50,
    onSurfaceVariant = Gray600,
    background = Color.White,
    onBackground = Gray900,
    outline = Gray200,
    outlineVariant = Gray200,
    error = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    primaryContainer = Gray800,
    onPrimaryContainer = Gray100,
    secondary = Gray500,
    onSecondary = Gray950,
    secondaryContainer = Gray700,
    onSecondaryContainer = Gray100,
    tertiary = Blue600,
    surface = Gray900,
    onSurface = Gray100,
    surfaceVariant = Gray850,
    onSurfaceVariant = Gray400,
    background = Gray900,
    onBackground = Gray100,
    outline = Gray700,
    outlineVariant = Gray700,
    error = Color(0xFFF87171)
)

/** OpenWebUI switch track/thumb colors, keyed by on/off and dark/light. */
object OpenWebUISwitchColors {
    val trackOnLight = Gray900
    val trackOnDark = Color.White
    val trackOffLight = Gray300
    val trackOffDark = Gray700
    val thumbOnLight = Color.White
    val thumbOnDark = Gray900
    val thumbOffLight = Color.White
    val thumbOffDark = Gray500
}

@Composable
fun FatAITheme(content: @Composable () -> Unit) {
    val settings = koinInject<SettingsRepository>()
    val mode by settings.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = appTypography(platformFontFamily()),
        content = content
    )
}

@Composable
private fun appTypography(fontFamily: FontFamily) = MaterialTheme.typography.copy(
    headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = fontFamily),
    titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = fontFamily),
    titleSmall = MaterialTheme.typography.titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = fontFamily),
    labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = fontFamily),
    labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = fontFamily),
    labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = fontFamily)
)
