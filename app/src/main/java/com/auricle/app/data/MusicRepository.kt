package com.auricle.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.auricle.app.bridge.AuricleNative
import com.auricle.app.extractor.NewPipeFallbackExtractor
import com.auricle.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

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

@Keep
@Serializable
private data class ArtistsWrapper(
    val ok: Boolean = false,
    val artists: List<ArtistCredit> = emptyList(),
    val error: String? = null
)

private data class TimedCacheEntry<T>(
    val value: T,
    val fetchedAtMs: Long
)

private class LruTimedCache<T>(
    private val maxEntries: Int,
    private val ttlMs: Long
) {
    private val map = object : LinkedHashMap<String, TimedCacheEntry<T>>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedCacheEntry<T>>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(key: String): T? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAtMs > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: T) {
        map[key] = TimedCacheEntry(value, System.currentTimeMillis())
    }
}

class MusicRepository(private val context: Context) {
    companion object {
        private const val HOME_FEED_TTL_MS = 15L * 60L * 1000L
        private const val DETAIL_CACHE_TTL_MS = 5L * 60L * 1000L
        private const val DETAIL_CACHE_MAX_ENTRIES = 24
        private const val ARTISTS_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val ARTISTS_CACHE_MAX_ENTRIES = 128
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val _cachedHomeFeed = MutableStateFlow<HomeFeedData?>(null)
    val cachedHomeFeed: StateFlow<HomeFeedData?> = _cachedHomeFeed.asStateFlow()

    @Volatile
    private var homeFeedFetchedAtMs: Long = 0L
    private val homeFeedMutex = Mutex()

    private val detailCache = LruTimedCache<Any>(
        maxEntries = DETAIL_CACHE_MAX_ENTRIES,
        ttlMs = DETAIL_CACHE_TTL_MS
    )

    private val artistsCache = LruTimedCache<List<ArtistCredit>>(
        maxEntries = ARTISTS_CACHE_MAX_ENTRIES,
        ttlMs = ARTISTS_CACHE_TTL_MS
    )

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

    private fun freshCachedHomeFeed(): HomeFeedData? {
        val cached = _cachedHomeFeed.value ?: return null
        val ageMs = System.currentTimeMillis() - homeFeedFetchedAtMs
        return if (ageMs in 0L until HOME_FEED_TTL_MS) cached else null
    }

    suspend fun getHomeFeed(forceRefresh: Boolean = false): Result<HomeFeedData> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            freshCachedHomeFeed()?.let { return@withContext Result.success(it) }
        }
        homeFeedMutex.withLock {
            if (!forceRefresh) {
                freshCachedHomeFeed()?.let { return@withLock Result.success(it) }
            }
            if (!ensureNativeLoaded()) {
                return@withLock Result.failure(IllegalStateException("Native core library is not loaded"))
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
                            _cachedHomeFeed.value = feed
                            homeFeedFetchedAtMs = System.currentTimeMillis()
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

    suspend fun getAlbum(browseId: String, forceRefresh: Boolean = false): Result<AlbumItem> = withContext(Dispatchers.IO) {
        val cacheKey = "album:$browseId"
        if (!forceRefresh) {
            (detailCache.get(cacheKey) as? AlbumItem)?.let { return@withContext Result.success(it) }
        }
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
                    detailCache.put(cacheKey, album)
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

    suspend fun getArtist(browseId: String, forceRefresh: Boolean = false): Result<ArtistItem> = withContext(Dispatchers.IO) {
        val cacheKey = "artist:$browseId"
        if (!forceRefresh) {
            (detailCache.get(cacheKey) as? ArtistItem)?.let { return@withContext Result.success(it) }
        }
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
                    detailCache.put(cacheKey, artist)
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

    suspend fun getPlaylist(playlistId: String, forceRefresh: Boolean = false): Result<PlaylistItem> = withContext(Dispatchers.IO) {
        val cacheKey = "playlist:$playlistId"
        if (!forceRefresh) {
            (detailCache.get(cacheKey) as? PlaylistItem)?.let { return@withContext Result.success(it) }
        }
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
                    detailCache.put(cacheKey, playlist)
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
            decodeLyricsResponse(raw)
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

    suspend fun getLyricsSynced(song: SongItem, langMode: String = "original"): LyricsData = withContext(Dispatchers.IO) {
        if (!ensureNativeLoaded()) {
            return@withContext LyricsData(
                ok = false,
                available = false,
                error = "Native core library is not loaded"
            )
        }
        try {
            val raw = AuricleNative.nativeGetLyricsSynced(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationSecs = song.durationSecs,
                langMode = langMode
            )
            decodeLyricsResponse(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MusicRepository", "getLyricsSynced error: ${t.message}")
            LyricsData(
                ok = false,
                available = false,
                error = t.message ?: "Failed to load lyrics"
            )
        }
    }

    private fun decodeLyricsResponse(raw: String): LyricsData {
        if (raw.isBlank()) {
            return LyricsData(
                ok = false,
                available = false,
                error = "Empty lyrics response"
            )
        }
        val data = json.decodeFromString<LyricsData>(raw)
        return if (!data.ok) {
            data.copy(
                ok = false,
                available = false,
                error = data.error.ifBlank { "Lyrics unavailable" }
            )
        } else {
            data
        }
    }

    suspend fun resolveArtists(kind: String, id: String, artistText: String): List<ArtistCredit> = withContext(Dispatchers.IO) {
        val normalizedKind = kind.trim().lowercase(Locale.US)
        val trimmedId = id.trim()
        val cacheKey = "$normalizedKind|$trimmedId"
        if (trimmedId.isNotEmpty()) {
            artistsCache.get(cacheKey)?.let { return@withContext it }
        }
        try {
            if (ensureNativeLoaded() && trimmedId.isNotEmpty()) {
                val raw = AuricleNative.nativeResolveArtists(normalizedKind, trimmedId, artistText)
                if (raw.isNotBlank()) {
                    val wrapper = json.decodeFromString<ArtistsWrapper>(raw)
                    val valid = wrapper.artists.filter { it.name.isNotBlank() }
                    if (wrapper.ok && valid.isNotEmpty()) {
                        artistsCache.put(cacheKey, valid)
                        return@withContext valid
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MusicRepository", "resolveArtists error: ${t.message}")
        }
        splitArtistCredits(artistText)
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
