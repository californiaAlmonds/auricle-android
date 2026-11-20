package com.auricle.app.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.auricle.app.model.AccentColorPreset
import com.auricle.app.model.ThemeMode

object AuricleRadii {
    val thumb = 8.dp
    val art = 10.dp
    val card = 14.dp
    val row = 16.dp
    val hero = 20.dp
    val sheet = 28.dp
    val pill = 32.dp
}

object AuricleSpacing {
    val screenMargin = 20.dp
    val sectionGap = 28.dp
    val headerToContent = 12.dp
    val carouselGap = 14.dp
    val listRowHeight = 60.dp
    val trackRowHeight = 52.dp
}

val LocalAuricleColors = compositionLocalOf<AuricleColors> {
    error("No AuricleColors provided")
}

val LocalAuricleTypography = staticCompositionLocalOf { DefaultAuricleTypeScale }

val LocalReducedMotion = compositionLocalOf { false }

val LocalBackdropLayer = compositionLocalOf<GraphicsLayer?> { null }

object AuricleTheme {
    val colors: AuricleColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAuricleColors.current

    val typography: AuricleTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalAuricleTypography.current

    val radii: AuricleRadii
        get() = AuricleRadii

    val spacing: AuricleSpacing
        get() = AuricleSpacing

    val reducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReducedMotion.current
}

@Composable
fun AuricleAppTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentPreset: AccentColorPreset = AccentColorPreset.DEFAULT_BLUE,
    dynamicAccentColor: Color? = null,
    forceLightStatusBarIcons: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
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
                insetsController.isAppearanceLightStatusBars = if (forceLightStatusBarIcons) false else !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalAuricleColors provides auricleColors,
        LocalAuricleTypography provides DefaultAuricleTypeScale,
        LocalReducedMotion provides reducedMotion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
