package com.auricle.app.model

import com.auricle.app.data.SettingsRepository
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsMigrationTest {

    @Test
    fun testDecodeV020Settings() {
        val json = """{"themeMode":"DARK","accentPreset":"COBALT","dynamicAlbumArtAccent":true,"userDisplayName":"Deepak","highQualityAudio":true}"""
        val settings = SettingsRepository.decodeSettings(json)

        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(AccentColorPreset.DEFAULT_BLUE, settings.accentPreset)
        assertTrue(settings.dynamicAlbumArtAccent)
        assertEquals("Deepak", settings.userDisplayName)
        assertTrue(settings.highQualityAudio)
        assertTrue(settings.autoplay)
        assertTrue(settings.autoCheckUpdates)
        assertTrue(settings.recentSearches.isEmpty())
    }

    @Test
    fun testLegacyPresetMigration() {
        val legacyMappings = listOf(
            "COBALT" to AccentColorPreset.DEFAULT_BLUE,
            "VIOLET" to AccentColorPreset.TWILIGHT,
            "EMERALD" to AccentColorPreset.FOREST,
            "ROSE" to AccentColorPreset.ROSE,
            "AMBER" to AccentColorPreset.EMBER,
            "CYAN" to AccentColorPreset.OCEAN
        )

        for ((legacy, expected) in legacyMappings) {
            val json = """{"accentPreset":"$legacy"}"""
            val settings = SettingsRepository.decodeSettings(json)
            assertEquals("Failed to map $legacy", expected, settings.accentPreset)
        }
    }

    @Test
    fun testUnknownEnumValuesFallback() {
        val json = """{"accentPreset":"UNKNOWN_NEON","themeMode":"UNKNOWN_MODE"}"""
        val settings = SettingsRepository.decodeSettings(json)

        assertEquals(AccentColorPreset.DEFAULT_BLUE, settings.accentPreset)
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun testRoundTripAllNewPresets() {
        for (preset in AccentColorPreset.entries) {
            val initial = AppSettings(accentPreset = preset)
            val encoded = SettingsRepository.settingsJson.encodeToString(initial)
            val decoded = SettingsRepository.decodeSettings(encoded)
            assertEquals("Roundtrip mismatch for ${preset.name}", preset, decoded.accentPreset)
        }
    }
}
