package com.auricle.app.data

import android.content.Context
import android.util.Log
import com.auricle.app.model.AccentColorPreset
import com.auricle.app.model.AppSettings
import com.auricle.app.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SettingsRepository(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        val settingsJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            prettyPrint = true
        }

        fun decodeSettings(raw: String): AppSettings =
            runCatching { settingsJson.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    private val settingsFile: File by lazy {
        File(context.filesDir, "settings.json")
    }

    private val _settings = MutableStateFlow(
        AppSettings(userDisplayName = "Android Listener")
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        scope.launch(Dispatchers.IO) {
            try {
                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    if (content.isNotBlank()) {
                        val loaded = decodeSettings(content)
                        _settings.value = loaded
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsRepository", "Error reading settings: ${e.message}")
            }
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            try {
                val encoded = settingsJson.encodeToString(_settings.value)
                settingsFile.writeText(encoded)
            } catch (e: Exception) {
                Log.e("SettingsRepository", "Error saving settings: ${e.message}")
            }
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        _settings.value = _settings.value.copy(themeMode = mode)
        persist()
    }

    fun updateAccentPreset(preset: AccentColorPreset) {
        _settings.value = _settings.value.copy(accentPreset = preset)
        persist()
    }

    fun updateDynamicAlbumArt(enabled: Boolean) {
        _settings.value = _settings.value.copy(dynamicAlbumArtAccent = enabled)
        persist()
    }

    fun updateDisplayName(name: String) {
        _settings.value = _settings.value.copy(userDisplayName = name)
        persist()
    }

    fun updateHighQualityAudio(enabled: Boolean) {
        _settings.value = _settings.value.copy(highQualityAudio = enabled)
        persist()
    }

    fun updateAutoplay(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoplay = enabled)
        persist()
    }

    fun updateAutoCheckUpdates(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoCheckUpdates = enabled)
        persist()
    }

    fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = _settings.value.recentSearches
        val filtered = current.filterNot { it.equals(trimmed, ignoreCase = true) }
        val updated = listOf(trimmed) + filtered
        val capped = updated.take(10)
        _settings.value = _settings.value.copy(recentSearches = capped)
        persist()
    }

    fun clearRecentSearches() {
        _settings.value = _settings.value.copy(recentSearches = emptyList())
        persist()
    }
}
