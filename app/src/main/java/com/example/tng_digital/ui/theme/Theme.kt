package com.example.tng_digital.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val TngLightColorScheme = lightColorScheme(
    primary = TngBlue,
    onPrimary = Color.White,
    primaryContainer = TngBlueLight,
    onPrimaryContainer = Color.White,
    secondary = TngYellow,
    onSecondary = TextPrimary,
    secondaryContainer = TngYellowLight,
    onSecondaryContainer = TextPrimary,
    tertiary = TngBlueDark,
    onTertiary = Color.White,
    background = BgSecondary,
    onBackground = TextPrimary,
    surface = BgPrimary,
    onSurface = TextPrimary,
    surfaceVariant = BgSecondary,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    outlineVariant = BorderLight,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorLight,
    onErrorContainer = Error,
)

private val TngDarkColorScheme = darkColorScheme(
    primary = TngBlueLight,
    onPrimary = Color.White,
    primaryContainer = TngBlueDark,
    onPrimaryContainer = Color.White,
    secondary = TngYellow,
    onSecondary = TextPrimary,
    secondaryContainer = TngYellowLight,
    onSecondaryContainer = TextPrimary,
    tertiary = TngBlue,
    onTertiary = Color.White,
    background = Color(0xFF1A1A2E),
    onBackground = Color.White,
    surface = Color(0xFF1A1A2E),
    onSurface = Color.White,
    surfaceVariant = BgBlueDark,
    onSurfaceVariant = TextMuted,
    outline = Border,
    outlineVariant = BorderLight,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorLight,
    onErrorContainer = Error,
)

@Composable
fun TngdigitalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) TngDarkColorScheme else TngLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = TngBlue.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
