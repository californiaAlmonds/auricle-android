package com.auricle.app.model

import androidx.annotation.Keep
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.Locale

private val ARTIST_SPLIT_REGEX = Regex(
    "\\s*(?:,\\s+|\\s+&\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+)",
    RegexOption.IGNORE_CASE
)

@Keep
@Serializable
data class ArtistCredit(
    @SerialName("name") val name: String = "",
    @SerialName("browse_id") val browseId: String = ""
)

fun splitArtistCredits(artistText: String, fallbackArtistId: String = ""): List<ArtistCredit> {
    val trimmed = artistText.trim()
    if (trimmed.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    val names = trimmed
        .split(ARTIST_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.US)) }
    if (names.isEmpty()) return emptyList()
    return if (names.size == 1) {
        listOf(ArtistCredit(name = names[0], browseId = fallbackArtistId))
    } else {
        names.map { ArtistCredit(name = it, browseId = "") }
    }
}

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
    @SerialName("added_by") val addedBy: String = "",
    val artists: List<ArtistCredit> = emptyList()
) {
    val displayArtworkUrl: String
        get() = resolveCardArtwork(artworkUrl, thumbnailUrl, videoId)

    val highResArtworkUrl: String
        get() = resolveHighResArtwork(artworkUrl, thumbnailUrl, videoId)

    val displayDuration: String
        get() = when {
            duration.isNotBlank() -> duration
            durationSecs > 0 -> String.format(Locale.US, "%d:%02d", durationSecs / 60, durationSecs % 60)
            else -> ""
        }

    fun parsedArtistCredits(): List<ArtistCredit> {
        val structured = artists.filter { it.name.isNotBlank() }
        if (structured.isNotEmpty()) return structured
        return splitArtistCredits(artist, artistId)
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
    val tracks: List<SongItem> = emptyList(),
    val artists: List<ArtistCredit> = emptyList()
) {
    val displayThumbnailUrl: String
        get() = upgradeArtworkUrl(thumbnailUrl, 544)

    val highResThumbnailUrl: String
        get() = upgradeArtworkUrl(thumbnailUrl, 1080)

    fun parsedArtistCredits(): List<ArtistCredit> {
        val structured = artists.filter { it.name.isNotBlank() }
        if (structured.isNotEmpty()) return structured
        return splitArtistCredits(artist)
    }
}

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
) {
    val displayThumbnailUrl: String
        get() = upgradeArtworkUrl(thumbnailUrl, 544)

    val highResThumbnailUrl: String
        get() = upgradeArtworkUrl(thumbnailUrl, 1080)
}

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
) {
    val displayThumbnailUrl: String
        get() = upgradeArtworkUrl(thumbnailUrl, 544)

    val highResThumbnailUrl: String
        get() = upgradeArtworkUrl(thumbnailUrl, 1080)
}

@OptIn(ExperimentalSerializationApi::class)
@Keep
@Serializable
data class TopSearchResult(
    val kind: String = "song",
    @SerialName("id") @JsonNames("id", "browse_id") val id: String = "",
    @SerialName("video_id") val videoId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("album_id") val albumId: String = "",
    @SerialName("artist_id") val artistId: String = "",
    val duration: String = "",
    @SerialName("duration_secs") val durationSecs: Int = 0,
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("artwork_url") val artworkUrl: String = "",
    val year: String = "",
    val artists: List<ArtistCredit> = emptyList()
) {
    val displayArtworkUrl: String
        get() = resolveCardArtwork(artworkUrl, thumbnailUrl, videoId)

    val highResArtworkUrl: String
        get() = resolveHighResArtwork(artworkUrl, thumbnailUrl, videoId)

    val displayDuration: String
        get() = when {
            duration.isNotBlank() -> duration
            durationSecs > 0 -> String.format(Locale.US, "%d:%02d", durationSecs / 60, durationSecs % 60)
            else -> ""
        }

    fun toSongItem(): SongItem = SongItem(
        videoId = videoId.ifBlank { id },
        title = title,
        artist = artist.ifBlank { subtitle },
        album = album,
        albumId = albumId,
        artistId = artistId,
        duration = duration,
        durationSecs = durationSecs,
        thumbnailUrl = thumbnailUrl,
        artworkUrl = artworkUrl,
        artists = artists
    )

    fun toArtistItem(): ArtistItem = ArtistItem(
        browseId = id.ifBlank { artistId },
        name = title.ifBlank { artist },
        subscribers = subtitle,
        thumbnailUrl = thumbnailUrl.ifBlank { artworkUrl }
    )

    fun toAlbumItem(): AlbumItem = AlbumItem(
        browseId = id.ifBlank { albumId },
        title = title,
        artist = artist.ifBlank { subtitle },
        year = year,
        thumbnailUrl = thumbnailUrl.ifBlank { artworkUrl },
        itemType = "album",
        artists = artists
    )

    fun toPlaylistItem(): PlaylistItem = PlaylistItem(
        playlistId = id,
        title = title,
        author = artist.ifBlank { subtitle },
        thumbnailUrl = thumbnailUrl.ifBlank { artworkUrl }
    )
}

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
    @SerialName("top") @JsonNames("top", "top_result") val topResult: TopSearchResult? = null,
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
    val error: String = "",
    @SerialName("is_synced") val isSynced: Boolean = false,
    @SerialName("has_romanization") val hasRomanization: Boolean = false
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
