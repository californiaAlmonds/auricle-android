package com.auricle.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.auricle.app.bridge.AuricleNative
import com.auricle.app.extractor.NewPipeFallbackExtractor
import com.auricle.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Keep
@Serializable
private data class SuggestionsWrapper(
    val ok: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val error: String? = null
)

@Keep
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

    private fun extractOkFalseError(raw: String): String? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
        if (ok == false) {
            return obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "Request returned ok=false"
        }
        return null
    }

    private fun ensureNativeLoaded(): Boolean = AuricleNative.isNativeLoaded()

    suspend fun getHomeFeed(): Result<HomeFeedData> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext Result.failure(IllegalStateException("Native core library is not loaded"))
        }
        try {
            val raw = AuricleNative.nativeGetHomeFeed()
            if (raw.isBlank()) {
                Result.failure(Exception("Empty response from nativeGetHomeFeed"))
            } else {
                val errMsg = extractOkFalseError(raw)
                if (errMsg != null) {
                    Result.failure(Exception(errMsg))
                } else {
                    val feed = json.decodeFromString<HomeFeedData>(raw)
                    if (!feed.ok) {
                        Result.failure(Exception("Home feed request returned ok=false"))
                    } else {
                        Result.success(feed)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("MusicRepository", "getHomeFeed error: ${t.message}", t)
            Result.failure(if (t is Exception) t else Exception(t.message ?: "Native error", t))
        }
    }

    suspend fun search(query: String, category: String = "all"): Result<SearchResultData> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext Result.failure(IllegalStateException("Native core library is not loaded"))
        }
        try {
            val raw = AuricleNative.nativeSearch(query, category)
            if (raw.isBlank()) {
                Result.failure(Exception("Empty response from nativeSearch"))
            } else {
                val errMsg = extractOkFalseError(raw)
                if (errMsg != null) {
                    Result.failure(Exception(errMsg))
                } else {
                    val res = json.decodeFromString<SearchResultData>(raw)
                    if (!res.ok) {
                        Result.failure(Exception("Search request returned ok=false"))
                    } else {
                        Result.success(res)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("MusicRepository", "search error: ${t.message}", t)
            Result.failure(if (t is Exception) t else Exception(t.message ?: "Native error", t))
        }
    }

    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) return@withContext emptyList()
        try {
            val raw = AuricleNative.nativeSearchSuggestions(query)
            if (raw.isBlank()) {
                emptyList()
            } else {
                val wrapper = json.decodeFromString<SuggestionsWrapper>(raw)
                if (wrapper.ok) {
                    wrapper.suggestions
                } else {
                    Log.w("MusicRepository", "getSearchSuggestions ok=false: ${wrapper.error}")
                    emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MusicRepository", "getSearchSuggestions error: ${t.message}")
            emptyList()
        }
    }

    suspend fun getWatchPlaylist(videoId: String): List<SongItem> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) return@withContext emptyList()
        try {
            val raw = AuricleNative.nativeGetWatchPlaylist(videoId)
            if (raw.isBlank()) {
                emptyList()
            } else {
                val wrapper = json.decodeFromString<WatchPlaylistWrapper>(raw)
                if (wrapper.ok) wrapper.tracks else emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MusicRepository", "getWatchPlaylist error: ${t.message}")
            emptyList()
        }
    }

    suspend fun getAlbum(browseId: String): Result<AlbumItem> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext Result.failure(IllegalStateException("Native core library is not loaded"))
        }
        try {
            val raw = AuricleNative.nativeGetAlbum(browseId)
            if (raw.isBlank()) {
                Result.failure(Exception("Empty album response"))
            } else {
                val errMsg = extractOkFalseError(raw)
                if (errMsg != null) {
                    Result.failure(Exception(errMsg))
                } else {
                    val album = json.decodeFromString<AlbumItem>(raw)
                    Result.success(album)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("MusicRepository", "getAlbum error: ${t.message}", t)
            Result.failure(if (t is Exception) t else Exception(t.message ?: "Native error", t))
        }
    }

    suspend fun getArtist(browseId: String): Result<ArtistItem> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext Result.failure(IllegalStateException("Native core library is not loaded"))
        }
        try {
            val raw = AuricleNative.nativeGetArtist(browseId)
            if (raw.isBlank()) {
                Result.failure(Exception("Empty artist response"))
            } else {
                val errMsg = extractOkFalseError(raw)
                if (errMsg != null) {
                    Result.failure(Exception(errMsg))
                } else {
                    val artist = json.decodeFromString<ArtistItem>(raw)
                    Result.success(artist)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("MusicRepository", "getArtist error: ${t.message}", t)
            Result.failure(if (t is Exception) t else Exception(t.message ?: "Native error", t))
        }
    }

    suspend fun getPlaylist(playlistId: String): Result<PlaylistItem> = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext Result.failure(IllegalStateException("Native core library is not loaded"))
        }
        try {
            val raw = AuricleNative.nativeGetPlaylist(playlistId)
            if (raw.isBlank()) {
                Result.failure(Exception("Empty playlist response"))
            } else {
                val errMsg = extractOkFalseError(raw)
                if (errMsg != null) {
                    Result.failure(Exception(errMsg))
                } else {
                    val playlist = json.decodeFromString<PlaylistItem>(raw)
                    Result.success(playlist)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("MusicRepository", "getPlaylist error: ${t.message}", t)
            Result.failure(if (t is Exception) t else Exception(t.message ?: "Native error", t))
        }
    }

    suspend fun getLyrics(videoId: String, english: Boolean): LyricsData =
        getLyrics(videoId, if (english) "english" else "original")

    suspend fun getLyrics(videoId: String, langMode: String = "original"): LyricsData = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext LyricsData(
                ok = false,
                available = false,
                error = "Native core library is not loaded"
            )
        }
        try {
            val raw = AuricleNative.nativeGetLyrics(videoId, langMode)
            if (raw.isBlank()) {
                LyricsData(
                    ok = false,
                    available = false,
                    error = "Empty lyrics response"
                )
            } else {
                val data = json.decodeFromString<LyricsData>(raw)
                if (!data.ok) {
                    data.copy(
                        ok = false,
                        available = false,
                        error = data.error.ifBlank { "Lyrics unavailable" }
                    )
                } else {
                    data
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MusicRepository", "getLyrics error: ${t.message}")
            LyricsData(
                ok = false,
                available = false,
                error = t.message ?: "Failed to load lyrics"
            )
        }
    }

    fun clearStreamCache() {
        if (AuricleNative.isNativeLoaded()) {
            runCatching { AuricleNative.nativeClearStreamCache() }
        }
    }

    suspend fun resolveStream(
        videoId: String,
        forceRefresh: Boolean = false,
        highQuality: Boolean = true
    ): Result<StreamResolutionResult> = withContext(Dispatchers.IO) {
        try {
            if (ensureNativeLoaded()) {
                val raw = AuricleNative.nativeResolveStreamQuality(videoId, forceRefresh, highQuality)
                if (raw.isNotBlank()) {
                    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    val isOkFalse = obj?.get("ok")?.jsonPrimitive?.booleanOrNull == false
                    val topError = obj?.get("error")?.jsonPrimitive?.contentOrNull
                    val hasUrl = !obj?.get("url")?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                    if (!isOkFalse && (topError.isNullOrBlank() || hasUrl)) {
                        val res = json.decodeFromString<StreamResolutionResult>(raw)
                        if (res.url.isNotBlank()) {
                            return@withContext Result.success(res)
                        }
                    }
                }
            }

            // Fallback to NewPipeExtractor
            val fallback = NewPipeFallbackExtractor.extractAudioStreamUrl(videoId, highQuality)
            if (fallback != null) {
                return@withContext Result.success(
                    StreamResolutionResult(
                        url = fallback.url,
                        userAgent = fallback.userAgent,
                        isHls = fallback.isHls,
                        durationSecs = fallback.durationSecs
                    )
                )
            }

            Result.failure(Exception("Unable to resolve audio stream for $videoId"))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("MusicRepository", "resolveStream failed: ${t.message}", t)
            val fallback = NewPipeFallbackExtractor.extractAudioStreamUrl(videoId, highQuality)
            if (fallback != null) {
                Result.success(
                    StreamResolutionResult(
                        url = fallback.url,
                        userAgent = fallback.userAgent,
                        isHls = fallback.isHls,
                        durationSecs = fallback.durationSecs
                    )
                )
            } else {
                Result.failure(if (t is Exception) t else Exception(t.message ?: "Stream resolution failed", t))
            }
        }
    }
}
