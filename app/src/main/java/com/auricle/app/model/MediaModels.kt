package com.auricle.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.Locale

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SongItem(
    @SerialName("video_id") @JsonNames("video_id", "id") val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("album_id") val albumId: String = "",
    @SerialName("artist_id") val artistId: String = "",
    val duration: String = "",
    @SerialName("duration_secs") val durationSecs: Int = 0,
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("artwork_url") val artworkUrl: String = "",
    @SerialName("added_by") val addedBy: String = ""
) {
    val displayArtworkUrl: String
        get() = artworkUrl.ifBlank { thumbnailUrl }

    val displayDuration: String
        get() = when {
            duration.isNotBlank() -> duration
            durationSecs > 0 -> String.format(Locale.US, "%d:%02d", durationSecs / 60, durationSecs % 60)
            else -> ""
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AlbumItem(
    @SerialName("id") @JsonNames("browse_id", "id") val browseId: String = "",
    val title: String = "",
    @SerialName("artist") @JsonNames("artist", "subtitle") val artist: String = "",
    val year: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("item_type") @JsonNames("kind") val itemType: String = "album",
    val description: String? = null,
    val tracks: List<SongItem> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ArtistItem(
    @SerialName("id") @JsonNames("browse_id", "id") val browseId: String = "",
    val name: String = "",
    val subscribers: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val description: String? = null,
    @SerialName("top_songs") val topSongs: List<SongItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val singles: List<AlbumItem> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlaylistItem(
    @SerialName("id") @JsonNames("playlist_id", "id") val playlistId: String = "",
    val title: String = "",
    @SerialName("author") @JsonNames("author", "subtitle") val author: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("track_count") val trackCount: Int = 0,
    val description: String? = null,
    val tracks: List<SongItem> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HomeFeedData(
    val ok: Boolean = true,
    @SerialName("hero") @JsonNames("hero", "featured") val featured: SongItem? = null,
    val trending: List<SongItem> = emptyList(),
    @SerialName("quick_picks") val quickPicks: List<SongItem> = emptyList(),
    @SerialName("new_releases") val newReleases: List<AlbumItem> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SearchResultData(
    val ok: Boolean = true,
    @SerialName("corrected_query") val correctedQuery: String? = null,
    @SerialName("top") @JsonNames("top", "top_result") val topResult: SongItem? = null,
    val songs: List<SongItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val playlists: List<PlaylistItem> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LyricsLine(
    @SerialName("primary") @JsonNames("primary", "text") val primary: String = "",
    @SerialName("secondary") @JsonNames("secondary", "translation") val secondary: String = "",
    @SerialName("is_spacer") val isSpacer: Boolean = false,
    @SerialName("time_ms") val timeMs: Long = 0L
)

@Serializable
data class LyricsData(
    val ok: Boolean = false,
    val available: Boolean = false,
    val source: String = "",
    @SerialName("is_already_en") val isAlreadyEn: Boolean = false,
    val lines: List<LyricsLine> = emptyList(),
    val error: String = ""
)

@Serializable
data class StreamResolutionResult(
    val url: String = "",
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("is_hls") val isHls: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("format_id") val formatId: String? = null,
    @SerialName("duration_secs") val durationSecs: Long = 0L,
    val error: String? = null
)
