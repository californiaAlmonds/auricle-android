package com.auricle.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

@Serializable
enum class AccentColorPreset(val hex: Long, val displayName: String) {
    COBALT(0xFF5B8DEF, "Cobalt"),
    VIOLET(0xFF8B5CF6, "Violet"),
    EMERALD(0xFF34D399, "Emerald"),
    ROSE(0xFFF43F5E, "Rose"),
    AMBER(0xFFF59E0B, "Amber"),
    CYAN(0xFF06B6D4, "Cyan")
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: AccentColorPreset = AccentColorPreset.COBALT,
    val dynamicAlbumArtAccent: Boolean = true,
    val userDisplayName: String = "",
    val highQualityAudio: Boolean = true
)
