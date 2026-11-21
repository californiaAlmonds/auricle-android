package com.auricle.app.data

import android.content.Context
import android.util.Log
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.LibraryState
import com.auricle.app.model.PlaylistItem
import com.auricle.app.model.RecentlyPlayedItem
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
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

    private val stateLock = Any()
    private val persistMutex = Mutex()

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        loadLibrarySync()
    }

    private fun loadLibrarySync() {
        synchronized(stateLock) {
            try {
                if (libraryFile.exists()) {
                    val content = libraryFile.readText()
                    if (content.isNotBlank()) {
                        _state.value = json.decodeFromString<LibraryState>(content)
                    }
                }
            } catch (e: Exception) {
                Log.e("LibraryRepository", "Error reading library: ${e.message}", e)
            }
        }
    }

    private fun saveState() {
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                try {
                    val snapshot = synchronized(stateLock) { _state.value }
                    val encoded = json.encodeToString(snapshot)
                    val parent = libraryFile.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    val tmpFile = File(parent, "${libraryFile.name}.tmp")
                    tmpFile.writeText(encoded)
                    try {
                        Files.move(
                            tmpFile.toPath(),
                            libraryFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Exception) {
                        try {
                            Files.move(
                                tmpFile.toPath(),
                                libraryFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Exception) {
                            if (!tmpFile.renameTo(libraryFile)) {
                                libraryFile.writeText(encoded)
                                tmpFile.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LibraryRepository", "Error saving library: ${e.message}", e)
                }
            }
        }
    }

    fun isLiked(videoId: String): Boolean {
        return _state.value.likedSongs.any { it.videoId == videoId }
    }

    fun toggleLike(song: SongItem): Boolean {
        var nowLiked = false
        synchronized(stateLock) {
            val currentLikes = _state.value.likedSongs.toMutableList()
            val exists = currentLikes.any { it.videoId == song.videoId }
            if (exists) {
                currentLikes.removeAll { it.videoId == song.videoId }
            } else {
                currentLikes.add(0, song)
            }
            _state.value = _state.value.copy(likedSongs = currentLikes)
            nowLiked = !exists
        }
        saveState()
        return nowLiked
    }

    fun recordRecentlyPlayed(song: SongItem) {
        synchronized(stateLock) {
            val currentHistory = _state.value.recentlyPlayed.toMutableList()
            currentHistory.removeAll { it.track.videoId == song.videoId }
            currentHistory.add(0, RecentlyPlayedItem(track = song, playedAt = System.currentTimeMillis()))
            if (currentHistory.size > 100) {
                currentHistory.subList(100, currentHistory.size).clear()
            }
            _state.value = _state.value.copy(recentlyPlayed = currentHistory)
        }
        saveState()
    }

    fun removeFromHistory(videoId: String) {
        synchronized(stateLock) {
            val updated = _state.value.recentlyPlayed.filterNot { it.track.videoId == videoId }
            _state.value = _state.value.copy(recentlyPlayed = updated)
        }
        saveState()
    }

    fun clearHistory() {
        synchronized(stateLock) {
            _state.value = _state.value.copy(recentlyPlayed = emptyList())
        }
        saveState()
    }

    fun createPlaylist(name: String): UserPlaylist {
        val newPlaylist = UserPlaylist(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            tracks = emptyList()
        )
        synchronized(stateLock) {
            val list = _state.value.playlists.toMutableList()
            list.add(0, newPlaylist)
            _state.value = _state.value.copy(playlists = list)
        }
        saveState()
        return newPlaylist
    }

    fun addToPlaylist(playlistId: String, song: SongItem) {
        synchronized(stateLock) {
            val list = _state.value.playlists.map { playlist ->
                if (playlist.id == playlistId) {
                    if (!playlist.tracks.any { it.videoId == song.videoId }) {
                        playlist.copy(tracks = playlist.tracks + song)
                    } else playlist
                } else playlist
            }
            _state.value = _state.value.copy(playlists = list)
        }
        saveState()
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        synchronized(stateLock) {
            val list = _state.value.playlists.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(tracks = playlist.tracks.filterNot { it.videoId == videoId })
                } else playlist
            }
            _state.value = _state.value.copy(playlists = list)
        }
        saveState()
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        synchronized(stateLock) {
            val list = _state.value.playlists.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(name = trimmed)
                } else playlist
            }
            _state.value = _state.value.copy(playlists = list)
        }
        saveState()
    }

    fun deletePlaylist(playlistId: String) {
        synchronized(stateLock) {
            val list = _state.value.playlists.filterNot { it.id == playlistId }
            _state.value = _state.value.copy(playlists = list)
        }
        saveState()
    }

    private fun matchesArtist(existing: ArtistItem, browseId: String, name: String): Boolean {
        return if (browseId.isNotBlank()) {
            existing.browseId == browseId ||
                (existing.browseId.isBlank() && name.isNotBlank() && existing.name.equals(name, ignoreCase = true))
        } else {
            name.isNotBlank() && existing.name.equals(name, ignoreCase = true)
        }
    }

    fun isArtistFollowed(browseId: String, name: String = ""): Boolean {
        if (browseId.isBlank() && name.isBlank()) return false
        return _state.value.followedArtists.any { matchesArtist(it, browseId, name) }
    }

    fun toggleFollowArtist(artist: ArtistItem) {
        synchronized(stateLock) {
            val current = _state.value.followedArtists.toMutableList()
            val exists = current.any { matchesArtist(it, artist.browseId, artist.name) }
            if (exists) {
                current.removeAll { matchesArtist(it, artist.browseId, artist.name) }
            } else {
                current.add(0, artist)
            }
            _state.value = _state.value.copy(followedArtists = current)
        }
        saveState()
    }

    private fun matchesAlbum(existing: AlbumItem, browseId: String, title: String, artist: String? = null): Boolean {
        return if (browseId.isNotBlank()) {
            existing.browseId == browseId ||
                (existing.browseId.isBlank() && title.isNotBlank() && existing.title.equals(title, ignoreCase = true) &&
                    (artist.isNullOrBlank() || existing.artist.equals(artist, ignoreCase = true)))
        } else {
            title.isNotBlank() && existing.title.equals(title, ignoreCase = true) &&
                (artist.isNullOrBlank() || existing.artist.equals(artist, ignoreCase = true))
        }
    }

    fun isAlbumSaved(browseId: String, title: String = "", artist: String? = null): Boolean {
        if (browseId.isBlank() && title.isBlank()) return false
        return _state.value.savedAlbums.any { matchesAlbum(it, browseId, title, artist) }
    }

    fun toggleSaveAlbum(album: AlbumItem) {
        synchronized(stateLock) {
            val current = _state.value.savedAlbums.toMutableList()
            val exists = current.any { matchesAlbum(it, album.browseId, album.title, album.artist) }
            if (exists) {
                current.removeAll { matchesAlbum(it, album.browseId, album.title, album.artist) }
            } else {
                current.add(0, album)
            }
            _state.value = _state.value.copy(savedAlbums = current)
        }
        saveState()
    }

    private fun matchesPlaylist(existing: PlaylistItem, browseId: String, title: String): Boolean {
        return if (browseId.isNotBlank()) {
            existing.playlistId == browseId ||
                (existing.playlistId.isBlank() && title.isNotBlank() && existing.title.equals(title, ignoreCase = true))
        } else {
            title.isNotBlank() && existing.title.equals(title, ignoreCase = true)
        }
    }

    fun isPlaylistSaved(browseId: String, title: String = ""): Boolean {
        if (browseId.isBlank() && title.isBlank()) return false
        return _state.value.savedPlaylists.any { matchesPlaylist(it, browseId, title) }
    }

    fun toggleSavePlaylist(playlist: PlaylistItem) {
        synchronized(stateLock) {
            val current = _state.value.savedPlaylists.toMutableList()
            val exists = current.any { matchesPlaylist(it, playlist.playlistId, playlist.title) }
            if (exists) {
                current.removeAll { matchesPlaylist(it, playlist.playlistId, playlist.title) }
            } else {
                current.add(0, playlist)
            }
            _state.value = _state.value.copy(savedPlaylists = current)
        }
        saveState()
    }
}
