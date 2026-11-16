package app.tono.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Palette straight from the Tono design canvas (dark + light). */
@Immutable
data class TonoColors(
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val off: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val isDark: Boolean,
)

val DarkTono = TonoColors(
    bg = Color(0xFF0D0D0F),
    surface = Color(0xFF17171B),
    raised = Color(0xFF202026),
    text = Color(0xFFF4F4F5),
    muted = Color(0xFFA1A1AA),
    line = Color(0xFF26262C),
    off = Color(0xFF3A3A42),
    accent = Color(0xFFA59BFF),
    onAccent = Color(0xFF0D0D0F),
    accentSoft = Color(0x24A59BFF), // 14%
    danger = Color(0xFFFF8A80),
    isDark = true,
)

val LightTono = TonoColors(
    bg = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFEDEDEA),
    text = Color(0xFF111113),
    muted = Color(0xFF5C5C66),
    line = Color(0xFFE4E4E0),
    off = Color(0xFFC9C9CF),
    accent = Color(0xFF5B4BDB),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1A5B4BDB), // 10%
    danger = Color(0xFFC62828),
    isDark = false,
)

val LocalTonoColors = staticCompositionLocalOf { DarkTono }

enum class ThemeMode(val label: String) { System("Match phone"), Light("Light"), Dark("Dark") }

object Tono {
    val colors: TonoColors
        @Composable get() = LocalTonoColors.current
}

@Composable
fun TonoTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val c = if (dark) DarkTono else LightTono
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.raised, onSurfaceVariant = c.muted,
            surfaceContainer = c.surface, surfaceContainerHigh = c.raised,
            outline = c.line, outlineVariant = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.raised, onSurfaceVariant = c.muted,
            surfaceContainer = c.surface, surfaceContainerHigh = c.raised,
            outline = c.line, outlineVariant = c.line, error = c.danger,
        )
    }
    // LocalContentColor defaults to black; without this, any Text without an explicit color
    // is unreadable on the dark background.
    CompositionLocalProvider(LocalTonoColors provides c, LocalContentColor provides c.text) {
        MaterialTheme(colorScheme = scheme, typography = TonoTypography, content = content)
    }
}
