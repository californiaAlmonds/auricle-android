package com.auricle.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.auricle.app.model.AccentColorPreset
import com.auricle.app.model.ThemeMode

val LocalAuricleColors = staticCompositionLocalOf<AuricleColors> {
    error("No AuricleColors provided")
}

object AuricleTheme {
    val colors: AuricleColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAuricleColors.current
}

@Composable
fun AuricleAppTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentPreset: AccentColorPreset = AccentColorPreset.COBALT,
    dynamicAccentColor: Color? = null,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val baseAccent = dynamicAccentColor ?: Color(accentPreset.hex)
    val auricleColors = if (isDark) {
        createDarkColors(baseAccent)
    } else {
        createLightColors(baseAccent)
    }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = auricleColors.accent,
            background = auricleColors.canvas,
            surface = auricleColors.surface,
            onPrimary = Color.White,
            onBackground = auricleColors.textPrimary,
            onSurface = auricleColors.textPrimary
        )
    } else {
        lightColorScheme(
            primary = auricleColors.accent,
            background = auricleColors.canvas,
            surface = auricleColors.surface,
            onPrimary = Color.White,
            onBackground = auricleColors.textPrimary,
            onSurface = auricleColors.textPrimary
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalAuricleColors provides auricleColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
