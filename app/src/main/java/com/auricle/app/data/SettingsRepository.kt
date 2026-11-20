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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    private val stateLock = Any()
    private val persistMutex = Mutex()

    private val _settings = MutableStateFlow(
        AppSettings(userDisplayName = "Android Listener")
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        loadSettingsSync()
    }

    private fun loadSettingsSync() {
        synchronized(stateLock) {
            try {
                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    if (content.isNotBlank()) {
                        _settings.value = decodeSettings(content)
                    }
                }
            } catch (e: Exception) {
                Log.e("SettingsRepository", "Error reading settings: ${e.message}")
            }
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                try {
                    val snapshot = synchronized(stateLock) { _settings.value }
                    val encoded = settingsJson.encodeToString(snapshot)
                    val parent = settingsFile.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    val tmpFile = File(parent, "${settingsFile.name}.tmp")
                    tmpFile.writeText(encoded)
                    try {
                        Files.move(
                            tmpFile.toPath(),
                            settingsFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Exception) {
                        try {
                            Files.move(
                                tmpFile.toPath(),
                                settingsFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Exception) {
                            if (!tmpFile.renameTo(settingsFile)) {
                                settingsFile.writeText(encoded)
                                tmpFile.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SettingsRepository", "Error saving settings: ${e.message}")
                }
            }
        }
    }

    private inline fun updateAndPersist(transform: (AppSettings) -> AppSettings) {
        synchronized(stateLock) {
            _settings.value = transform(_settings.value)
        }
        persist()
    }

    fun updateThemeMode(mode: ThemeMode) {
        updateAndPersist { it.copy(themeMode = mode) }
    }

    fun updateAccentPreset(preset: AccentColorPreset) {
        updateAndPersist { it.copy(accentPreset = preset) }
    }

    fun updateDynamicAlbumArt(enabled: Boolean) {
        updateAndPersist { it.copy(dynamicAlbumArtAccent = enabled) }
    }

    fun updateDisplayName(name: String) {
        updateAndPersist { it.copy(userDisplayName = name) }
    }

    fun updateHighQualityAudio(enabled: Boolean) {
        updateAndPersist { it.copy(highQualityAudio = enabled) }
    }

    fun updateAutoplay(enabled: Boolean) {
        updateAndPersist { it.copy(autoplay = enabled) }
    }

    fun updateAutoCheckUpdates(enabled: Boolean) {
        updateAndPersist { it.copy(autoCheckUpdates = enabled) }
    }

    fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        updateAndPersist { currentSettings ->
            val filtered = currentSettings.recentSearches.filterNot { it.equals(trimmed, ignoreCase = true) }
            val updated = (listOf(trimmed) + filtered).take(10)
            currentSettings.copy(recentSearches = updated)
        }
    }

    fun clearRecentSearches() {
        updateAndPersist { it.copy(recentSearches = emptyList()) }
    }
}
