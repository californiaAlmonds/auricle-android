package com.auricle.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

// Base Raw Auricle Colors
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

@Immutable
data class AuricleColors(
    val accent: Color,
    val canvas: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val elevated: Color,
    val glass: Color,
    val frost: Color,
    val border: Color,
    val borderSoft: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,
    val success: Color,
    val danger: Color,
    val isDark: Boolean
)

fun createDarkColors(accent: Color): AuricleColors {
    return AuricleColors(
        accent = accent,
        canvas = blendWithAccent(RawCanvas, accent, 0.04f),
        bg = blendWithAccent(RawBg, accent, 0.06f),
        surface = blendWithAccent(RawSurface, accent, 0.06f),
        surface2 = blendWithAccent(RawSurface2, accent, 0.06f),
        elevated = blendWithAccent(RawElevated, accent, 0.06f),
        glass = Color(0xEB141923),
        frost = Color(0xE6131822),
        border = Color(0x17FFFFFF),
        borderSoft = Color(0x0DFFFFFF),
        textPrimary = RawTextPrimary,
        textMuted = RawTextMuted,
        textDim = RawTextDim,
        success = AuricleSuccess,
        danger = AuricleDanger,
        isDark = true
    )
}

fun createLightColors(accent: Color): AuricleColors {
    return AuricleColors(
        accent = accent,
        canvas = blendWithAccent(Color(0xFFF6F8FB), accent, 0.03f),
        bg = blendWithAccent(Color(0xFFFFFFFF), accent, 0.02f),
        surface = blendWithAccent(Color(0xFFF0F4F9), accent, 0.04f),
        surface2 = blendWithAccent(Color(0xFFE6ECF4), accent, 0.04f),
        elevated = Color(0xFFFFFFFF),
        glass = Color(0xF2FFFFFF),
        frost = Color(0xF7F4F7FB),
        border = Color(0x1F000000),
        borderSoft = Color(0x0F000000),
        textPrimary = Color(0xFF131720),
        textMuted = Color(0xFF5E6773),
        textDim = Color(0xFF8B96A5),
        success = Color(0xFF2E854B),
        danger = Color(0xFFDC3545),
        isDark = false
    )
}
