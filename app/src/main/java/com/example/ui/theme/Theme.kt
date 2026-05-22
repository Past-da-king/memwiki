package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode { DEFAULT, ADAPTIVE, EDITORIAL }

// DEFAULT — restrained achromatic: true neutrals, slight warm tint so it isn't sterile.
private val DefaultDarkColorScheme = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF111111),
    secondary = Color(0xFFB8B8B8),
    onSecondary = Color(0xFF111111),
    tertiary = Color(0xFF8A8A8A),
    onTertiary = Color(0xFFEDEDED),
    background = Color(0xFF0E0E0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF161617),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF1F1F21),
    onSurfaceVariant = Color(0xFFA8A8A8),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF26262A),
    error = Color(0xFFE89E8A)
)

private val DefaultLightColorScheme = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFAFAFA),
    secondary = Color(0xFF3A3A3A),
    onSecondary = Color(0xFFFAFAFA),
    tertiary = Color(0xFF6B6B6B),
    onTertiary = Color(0xFFFAFAFA),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFFC8C8C8),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFB54632)
)

// EDITORIAL — warm-dark parchment palette matching the onboarding aesthetic.
private val EditorialDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD8C9B6),
    onPrimary = Color(0xFF1A1320),
    secondary = Color(0xFFB59E84),
    onSecondary = Color(0xFF1A1320),
    tertiary = Color(0xFF8A8EA8),
    onTertiary = Color(0xFF14151D),
    background = Color(0xFF14111A),
    onBackground = Color(0xFFEDE5D9),
    surface = Color(0xFF1B1722),
    onSurface = Color(0xFFEDE5D9),
    surfaceVariant = Color(0xFF26212F),
    onSurfaceVariant = Color(0xFFA8A095),
    outline = Color(0xFF433C4E),
    outlineVariant = Color(0xFF2F2A38),
    error = Color(0xFFE89E8A)
)

private val EditorialLightColorScheme = lightColorScheme(
    primary = Color(0xFF4A3C2A),
    onPrimary = Color.White,
    secondary = Color(0xFF6B5840),
    onSecondary = Color.White,
    tertiary = Color(0xFF565A75),
    onTertiary = Color.White,
    background = Color(0xFFF8F3EA),
    onBackground = Color(0xFF1A1612),
    surface = Color(0xFFFFFAF1),
    onSurface = Color(0xFF1A1612),
    surfaceVariant = Color(0xFFEFE7D8),
    onSurfaceVariant = Color(0xFF564E40),
    outline = Color(0xFFB8AC97),
    outlineVariant = Color(0xFFD6CCB8),
    error = Color(0xFFB54632)
)

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when (themeMode) {
        AppThemeMode.ADAPTIVE -> {
            // Material You — pulled from the system wallpaper. Falls back to Default scheme on
            // pre-Android-12 devices where dynamic colours aren't available.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) DefaultDarkColorScheme else DefaultLightColorScheme
            }
        }
        AppThemeMode.EDITORIAL -> {
            if (darkTheme) EditorialDarkColorScheme else EditorialLightColorScheme
        }
        AppThemeMode.DEFAULT -> {
            if (darkTheme) DefaultDarkColorScheme else DefaultLightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
