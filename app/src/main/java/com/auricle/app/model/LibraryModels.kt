package com.auricle.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
    val tracks: List<SongItem> = emptyList()
)

@Serializable
data class RecentlyPlayedItem(
    val track: SongItem,
    @SerialName("played_at") val playedAt: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LibraryState(
    @SerialName("liked_songs") val likedSongs: List<SongItem> = emptyList(),
    @SerialName("recently_played") val recentlyPlayed: List<RecentlyPlayedItem> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    @SerialName("followed_artists") @JsonNames("followedArtists", "followed_artists") val followedArtists: List<ArtistItem> = emptyList(),
    @SerialName("saved_albums") @JsonNames("savedAlbums", "saved_albums") val savedAlbums: List<AlbumItem> = emptyList(),
    @SerialName("saved_playlists") @JsonNames("savedPlaylists", "saved_playlists") val savedPlaylists: List<PlaylistItem> = emptyList()
)
