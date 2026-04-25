package com.example.tng_digital.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TngLightColorScheme = lightColorScheme(
    primary = TngBlue,
    onPrimary = Color.White,
    primaryContainer = TngBlueLight,
    onPrimaryContainer = Color.White,
    secondary = TngYellow,
    onSecondary = TngTextPrimary,
    secondaryContainer = TngYellowLight,
    onSecondaryContainer = TngTextPrimary,
    tertiary = TngSuccess,
    onTertiary = Color.White,
    background = TngBgSecondary,
    onBackground = TngTextPrimary,
    surface = TngBgPrimary,
    onSurface = TngTextPrimary,
    surfaceVariant = TngBgSecondary,
    onSurfaceVariant = TngTextSecondary,
    error = TngError,
    onError = Color.White,
    errorContainer = TngErrorLight,
    onErrorContainer = TngError,
    outline = TngBorder,
    outlineVariant = TngDivider,
)

private val TngDarkColorScheme = darkColorScheme(
    primary = TngBlueLight,
    onPrimary = Color.White,
    primaryContainer = TngBlueDark,
    onPrimaryContainer = Color.White,
    secondary = TngYellow,
    onSecondary = TngTextPrimary,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    error = TngError,
    onError = Color.White,
    errorContainer = Color(0xFF442020),
    onErrorContainer = Color(0xFFFFB4AB),
)

@Composable
fun TngdigitalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) TngDarkColorScheme else TngLightColorScheme

    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
}
