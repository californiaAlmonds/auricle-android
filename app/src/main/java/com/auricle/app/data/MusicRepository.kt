package com.auricle.app.data

import android.content.Context
import android.util.Log
import com.auricle.app.bridge.AuricleNative
import com.auricle.app.extractor.NewPipeFallbackExtractor
import com.auricle.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SuggestionsWrapper(
    val ok: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val error: String? = null
)

@Serializable
private data class WatchPlaylistWrapper(
    val ok: Boolean = false,
    val tracks: List<SongItem> = emptyList(),
    val error: String? = null
)

class MusicRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun getHomeFeed(): Result<HomeFeedData> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeGetHomeFeed()
            if (raw.isBlank()) {
                Result.failure(Exception("Empty response from nativeGetHomeFeed"))
            } else {
                val feed = json.decodeFromString<HomeFeedData>(raw)
                if (!feed.ok) {
                    Result.failure(Exception("Home feed request returned ok=false"))
                } else {
                    Result.success(feed)
                }
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "getHomeFeed error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun search(query: String, category: String = "all"): Result<SearchResultData> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeSearch(query, category)
            if (raw.isBlank()) {
                Result.failure(Exception("Empty response from nativeSearch"))
            } else {
                val res = json.decodeFromString<SearchResultData>(raw)
                if (!res.ok) {
                    Result.failure(Exception("Search request returned ok=false"))
                } else {
                    Result.success(res)
                }
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "search error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeSearchSuggestions(query)
            if (raw.isBlank()) {
                emptyList()
            } else {
                val wrapper = json.decodeFromString<SuggestionsWrapper>(raw)
                if (wrapper.ok) wrapper.suggestions else emptyList()
            }
        } catch (e: Exception) {
            Log.w("MusicRepository", "getSearchSuggestions error: ${e.message}")
            emptyList()
        }
    }

    suspend fun getWatchPlaylist(videoId: String): List<SongItem> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeGetWatchPlaylist(videoId)
            if (raw.isBlank()) {
                emptyList()
            } else {
                val wrapper = json.decodeFromString<WatchPlaylistWrapper>(raw)
                if (wrapper.ok) wrapper.tracks else emptyList()
            }
        } catch (e: Exception) {
            Log.w("MusicRepository", "getWatchPlaylist error: ${e.message}")
            emptyList()
        }
    }

    suspend fun getAlbum(browseId: String): Result<AlbumItem> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeGetAlbum(browseId)
            if (raw.isBlank() || raw.contains("\"ok\":false")) {
                Result.failure(Exception(raw.ifBlank { "Empty album response" }))
            } else {
                val album = json.decodeFromString<AlbumItem>(raw)
                Result.success(album)
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "getAlbum error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getArtist(browseId: String): Result<ArtistItem> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeGetArtist(browseId)
            if (raw.isBlank() || raw.contains("\"ok\":false")) {
                Result.failure(Exception(raw.ifBlank { "Empty artist response" }))
            } else {
                val artist = json.decodeFromString<ArtistItem>(raw)
                Result.success(artist)
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "getArtist error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getPlaylist(playlistId: String): Result<PlaylistItem> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeGetPlaylist(playlistId)
            if (raw.isBlank() || raw.contains("\"ok\":false")) {
                Result.failure(Exception(raw.ifBlank { "Empty playlist response" }))
            } else {
                val playlist = json.decodeFromString<PlaylistItem>(raw)
                Result.success(playlist)
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "getPlaylist error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getLyrics(videoId: String, langMode: String = "original"): LyricsData = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeGetLyrics(videoId, langMode)
            if (raw.isBlank()) {
                LyricsData()
            } else {
                json.decodeFromString<LyricsData>(raw)
            }
        } catch (e: Exception) {
            Log.w("MusicRepository", "getLyrics error: ${e.message}")
            LyricsData()
        }
    }

    suspend fun resolveStream(videoId: String, forceRefresh: Boolean = false): Result<StreamResolutionResult> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativeResolveStream(videoId, forceRefresh)
            if (raw.isNotBlank() && !raw.contains("\"ok\":false") && !raw.startsWith("{\"error\"")) {
                val res = json.decodeFromString<StreamResolutionResult>(raw)
                if (res.url.isNotBlank()) {
                    return@withContext Result.success(res)
                }
            }

            // Fallback to NewPipeExtractor
            val fallback = NewPipeFallbackExtractor.extractAudioStreamUrl(videoId)
            if (fallback != null) {
                return@withContext Result.success(
                    StreamResolutionResult(
                        url = fallback.first,
                        userAgent = fallback.second,
                        isHls = fallback.first.contains(".m3u8")
                    )
                )
            }

            Result.failure(Exception("Unable to resolve audio stream for $videoId"))
        } catch (e: Exception) {
            Log.e("MusicRepository", "resolveStream failed: ${e.message}", e)
            val fallback = NewPipeFallbackExtractor.extractAudioStreamUrl(videoId)
            if (fallback != null) {
                Result.success(
                    StreamResolutionResult(
                        url = fallback.first,
                        userAgent = fallback.second,
                        isHls = fallback.first.contains(".m3u8")
                    )
                )
            } else {
                Result.failure(e)
            }
        }
    }
}
