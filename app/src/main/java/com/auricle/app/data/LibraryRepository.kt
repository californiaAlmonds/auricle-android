package com.auricle.app.data

import android.content.Context
import android.util.Log
import com.auricle.app.model.LibraryState
import com.auricle.app.model.RecentlyPlayedItem
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class LibraryRepository(private val context: Context, private val scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        prettyPrint = true
    }

    private val libraryFile: File by lazy {
        File(context.filesDir, "library_state.json")
    }

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        loadLibrary()
    }

    private fun loadLibrary() {
        scope.launch(Dispatchers.IO) {
            try {
                if (libraryFile.exists()) {
                    val content = libraryFile.readText()
                    if (content.isNotBlank()) {
                        val loaded = json.decodeFromString<LibraryState>(content)
                        _state.value = loaded
                    }
                }
            } catch (e: Exception) {
                Log.e("LibraryRepository", "Error reading library: ${e.message}", e)
            }
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            try {
                val encoded = json.encodeToString(_state.value)
                libraryFile.writeText(encoded)
            } catch (e: Exception) {
                Log.e("LibraryRepository", "Error saving library: ${e.message}", e)
            }
        }
    }

    fun isLiked(videoId: String): Boolean {
        return _state.value.likedSongs.any { it.videoId == videoId }
    }

    fun toggleLike(song: SongItem): Boolean {
        val currentLikes = _state.value.likedSongs.toMutableList()
        val exists = currentLikes.any { it.videoId == song.videoId }
        if (exists) {
            currentLikes.removeAll { it.videoId == song.videoId }
        } else {
            currentLikes.add(0, song)
        }
        _state.value = _state.value.copy(likedSongs = currentLikes)
        persist()
        return !exists
    }

    fun recordRecentlyPlayed(song: SongItem) {
        val currentHistory = _state.value.recentlyPlayed.toMutableList()
        currentHistory.removeAll { it.track.videoId == song.videoId }
        currentHistory.add(0, RecentlyPlayedItem(track = song, playedAt = System.currentTimeMillis()))
        if (currentHistory.size > 100) {
            currentHistory.subList(100, currentHistory.size).clear()
        }
        _state.value = _state.value.copy(recentlyPlayed = currentHistory)
        persist()
    }

    fun createPlaylist(name: String): UserPlaylist {
        val newPlaylist = UserPlaylist(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            tracks = emptyList()
        )
        val list = _state.value.playlists.toMutableList()
        list.add(0, newPlaylist)
        _state.value = _state.value.copy(playlists = list)
        persist()
        return newPlaylist
    }

    fun addToPlaylist(playlistId: String, song: SongItem) {
        val list = _state.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                if (!playlist.tracks.any { it.videoId == song.videoId }) {
                    playlist.copy(tracks = playlist.tracks + song)
                } else playlist
            } else playlist
        }
        _state.value = _state.value.copy(playlists = list)
        persist()
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        val list = _state.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(tracks = playlist.tracks.filterNot { it.videoId == videoId })
            } else playlist
        }
        _state.value = _state.value.copy(playlists = list)
        persist()
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val list = _state.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(name = trimmed)
            } else playlist
        }
        _state.value = _state.value.copy(playlists = list)
        persist()
    }

    fun deletePlaylist(playlistId: String) {
        val list = _state.value.playlists.filterNot { it.id == playlistId }
        _state.value = _state.value.copy(playlists = list)
        persist()
    }
}
