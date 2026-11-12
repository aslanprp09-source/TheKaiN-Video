package com.thekain.video.presentation

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFFE5484D), onPrimary = Color(0xFF0D1016),
    background = Color(0xFF0D1016), onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF171C25), onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF222936), onSurfaceVariant = Color(0xFFAEB7C5),
    outline = Color(0xFF525C6B), secondaryContainer = Color(0xFF38232B), onSecondaryContainer = Color(0xFFF5F6F8),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF4A2024), onErrorContainer = Color(0xFFFFDAD6))
private val Light = lightColorScheme(
    primary = Color(0xFFC52A38), onPrimary = Color.White,
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF1A202B),
    surface = Color.White, onSurface = Color(0xFF1A202B),
    surfaceVariant = Color(0xFFEBEEF2), onSurfaceVariant = Color(0xFF505B6D),
    outline = Color(0xFF7B8492), secondaryContainer = Color(0xFFFBE7E8), onSecondaryContainer = Color(0xFF69202B))

@Composable fun TheKaiNTheme(settings: UiSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val currentConfiguration = LocalConfiguration.current
    val localized = remember(context, currentConfiguration, settings.language) { context.localized(settings.language) }
    val configuration = remember(localized) { Configuration(localized.resources.configuration) }
    val dark = settings.theme == ThemeMode.DARK || settings.theme == ThemeMode.SYSTEM && isSystemInDarkTheme()
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
    }
}
