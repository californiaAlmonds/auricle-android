package com.auricle.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class LibraryState(
    @SerialName("liked_songs") val likedSongs: List<SongItem> = emptyList(),
    @SerialName("recently_played") val recentlyPlayed: List<RecentlyPlayedItem> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList()
)
