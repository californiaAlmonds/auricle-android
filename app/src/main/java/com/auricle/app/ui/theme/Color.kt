package com.auricle.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand Gradient Constants
val BrandTeal = Color(0xFF2FD3A0)
val BrandBlue = Color(0xFF1E8BF0)
val AuricleBrandGradient: Brush = Brush.linearGradient(listOf(BrandTeal, BrandBlue))

// Base Preset Raw Colors (Legacy/Helper references)
val BaseCobalt = Color(0xFF5B8DEF)
val BaseViolet = Color(0xFF8B5CF6)
val BaseEmerald = Color(0xFF34D399)
val BaseRose = Color(0xFFF43F5E)
val BaseAmber = Color(0xFFF59E0B)
val BaseCyan = Color(0xFF06B6D4)

val RawCanvas = Color(0xFF06080D)
val RawBg = Color(0xFF0D1118)
val RawSurface = Color(0xFF12161F)
val RawSurface2 = Color(0xFF171D27)
val RawElevated = Color(0xFF1B212C)

val RawTextPrimary = Color(0xFFF2F4F8)
val RawTextMuted = Color(0xFF97A1AE)
val RawTextDim = Color(0xFF5A6470)

val AuricleSuccess = Color(0xFF62C97E)
val AuricleDanger = Color(0xFFF0685F)

fun blendWithAccent(base: Color, accent: Color, ratio: Float): Color {
    val r = base.red * (1f - ratio) + accent.red * ratio
    val g = base.green * (1f - ratio) + accent.green * ratio
    val b = base.blue * (1f - ratio) + accent.blue * ratio
    return Color(red = r, green = g, blue = b, alpha = base.alpha)
}

fun brightenColor(color: Color, amount: Float): Color {
    val r = color.red + (1f - color.red) * amount.coerceIn(0f, 1f)
    val g = color.green + (1f - color.green) * amount.coerceIn(0f, 1f)
    val b = color.blue + (1f - color.blue) * amount.coerceIn(0f, 1f)
    return Color(red = r, green = g, blue = b, alpha = color.alpha)
}

fun darkenColor(color: Color, amount: Float): Color {
    val factor = 1f - amount.coerceIn(0f, 1f)
    return Color(red = color.red * factor, green = color.green * factor, blue = color.blue * factor, alpha = color.alpha)
}

@Immutable
data class AuricleColors(
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val bg: Color,
    val canvas: Color,
    val surface: Color,
    val surface2: Color,
    val elevated: Color,
    val surfaceHover: Color,
    val sheetBg: Color,
    val tabBarGlass: Color,
    val miniPlayerGlass: Color,
    val glass: Color,
    val frost: Color,
    val border: Color,
    val borderSoft: Color,
    val fillMed: Color,
    val fillSoft: Color,
    val hoverVeil: Color,
    val pressVeil: Color,
    val primaryFill: Color,
    val onPrimaryFill: Color,
    val segmentedSelected: Color,
    val scrim: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val isDark: Boolean
)

fun createDarkColors(accent: Color): AuricleColors {
    val bg = blendWithAccent(Color(0xFF0C1016), accent, 0.05f)
    val tabBarGlass = Color(0xD9141923)
    val miniPlayerGlass = Color(0xEB1E2532)

    return AuricleColors(
        accent = accent,
        accentStrong = brightenColor(accent, 0.22f),
        accentSoft = accent.copy(alpha = 0.16f),
        onAccent = Color.White,
        bg = bg,
        canvas = bg,
        surface = blendWithAccent(Color(0xFF12161F), accent, 0.06f),
        surface2 = blendWithAccent(Color(0xFF171D27), accent, 0.06f),
        elevated = blendWithAccent(Color(0xFF1B212C), accent, 0.06f),
        surfaceHover = blendWithAccent(Color(0xFF212835), accent, 0.07f),
        sheetBg = blendWithAccent(Color(0xFF161B24), accent, 0.05f),
        tabBarGlass = tabBarGlass,
        miniPlayerGlass = miniPlayerGlass,
        glass = miniPlayerGlass,
        frost = tabBarGlass,
        border = Color(0x12FFFFFF),
        borderSoft = Color(0x0BFFFFFF),
        fillMed = Color(0x17FFFFFF),
        fillSoft = Color(0x0DFFFFFF),
        hoverVeil = Color(0x0DFFFFFF),
        pressVeil = Color(0x1AFFFFFF),
        primaryFill = Color(0xFFECEEF0),
        onPrimaryFill = Color(0xFF0C1016),
        segmentedSelected = Color(0xFF3A4352),
        scrim = Color(0xCC05070A),
        textPrimary = Color(0xFFF2F4F8),
        textMuted = Color(0xFF97A1AE),
        textDim = Color(0xFF5A6470),
        success = Color(0xFF62C97E),
        danger = Color(0xFFF0685F),
        warning = Color(0xFFE8B65A),
        isDark = true
    )
}

fun createLightColors(accent: Color): AuricleColors {
    val bg = blendWithAccent(Color(0xFFF7F8FA), accent, 0.03f)
    val tabBarGlass = Color(0xE0FFFFFF)
    val miniPlayerGlass = Color(0xEBFFFFFF)

    return AuricleColors(
        accent = accent,
        accentStrong = darkenColor(accent, 0.18f),
        accentSoft = accent.copy(alpha = 0.12f),
        onAccent = Color.White,
        bg = bg,
        canvas = bg,
        surface = Color(0xFFFFFFFF),
        surface2 = blendWithAccent(Color(0xFFEEF1F5), accent, 0.04f),
        elevated = Color(0xFFFFFFFF),
        surfaceHover = blendWithAccent(Color(0xFFE6EAF0), accent, 0.05f),
        sheetBg = Color(0xFFFFFFFF),
        tabBarGlass = tabBarGlass,
        miniPlayerGlass = miniPlayerGlass,
        glass = miniPlayerGlass,
        frost = tabBarGlass,
        border = Color(0x0F000000),
        borderSoft = Color(0x0A000000),
        fillMed = Color(0x12000000),
        fillSoft = Color(0x0A000000),
        hoverVeil = Color(0x0D000000),
        pressVeil = Color(0x1A000000),
        primaryFill = Color(0xFF171A20),
        onPrimaryFill = Color(0xFFFFFFFF),
        segmentedSelected = Color(0xFFFFFFFF),
        scrim = Color(0x660C1016),
        textPrimary = Color(0xFF171A20),
        textMuted = Color(0xFF565D69),
        textDim = Color(0xFF838B98),
        success = Color(0xFF1D7A3A),
        danger = Color(0xFFC62828),
        warning = Color(0xFFB7791F),
        isDark = false
    )
}
