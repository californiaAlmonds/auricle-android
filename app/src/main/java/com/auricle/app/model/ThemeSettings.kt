package com.auricle.app.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

@Serializable(with = AccentColorPresetSerializer::class)
enum class AccentColorPreset(val hex: Long, val displayName: String) {
    DEFAULT_BLUE(0xFF6486B0, "Default Blue"),
    SOLAR_RED(0xFFE2503F, "Solar Red"),
    EMBER(0xFFF2703A, "Ember"),
    EARTH(0xFFB06A43, "Earth"),
    FOREST(0xFF3FA96A, "Forest"),
    OCEAN(0xFF1699A8, "Ocean"),
    SKY(0xFF46A6E6, "Sky"),
    TWILIGHT(0xFF8B5CF6, "Twilight"),
    ROSE(0xFFE26AA0, "Rose"),
    MONO(0xFF6B7280, "Mono");

    companion object {
        fun fromSerializedName(raw: String?): AccentColorPreset {
            return when (raw?.trim()?.uppercase()) {
                "DEFAULT_BLUE", "DEFAULT BLUE", "COBALT" -> DEFAULT_BLUE
                "SOLAR_RED", "SOLAR RED" -> SOLAR_RED
                "EMBER", "AMBER" -> EMBER
                "EARTH" -> EARTH
                "FOREST", "EMERALD" -> FOREST
                "OCEAN", "CYAN" -> OCEAN
                "SKY" -> SKY
                "TWILIGHT", "VIOLET" -> TWILIGHT
                "ROSE" -> ROSE
                "MONO" -> MONO
                else -> DEFAULT_BLUE
            }
        }
    }
}

object AccentColorPresetSerializer : KSerializer<AccentColorPreset> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AccentColorPreset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AccentColorPreset) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): AccentColorPreset {
        return runCatching {
            AccentColorPreset.fromSerializedName(decoder.decodeString())
        }.getOrDefault(AccentColorPreset.DEFAULT_BLUE)
    }
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: AccentColorPreset = AccentColorPreset.DEFAULT_BLUE,
    val dynamicAlbumArtAccent: Boolean = true,
    val userDisplayName: String = "",
    val highQualityAudio: Boolean = true,
    val autoplay: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val recentSearches: List<String> = emptyList()
)
