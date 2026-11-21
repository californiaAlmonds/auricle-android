package com.auricle.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.auricle.app.bridge.AuricleNative
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.data.SettingsRepository
import com.auricle.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Keep
@Serializable
data class PersistedPlaybackState(
    val currentTrack: SongItem,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playingFrom: String = "Queue",
    val queue: List<SongItem> = emptyList(),
    val currentIndex: Int = 0
)

class PlaybackController(
    private val context: Context,
    private val musicRepo: MusicRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository? = null,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val AUTOPLAY_TARGET = 15
        private const val AUTOPLAY_MAX = 25
        private const val AUTOPLAY_REFILL_THRESHOLD = 6
        private const val SESSION_HISTORY_MAX = 50
        private const val TASTE_AFFINITY_BIAS = 2.5f
        private const val RECENTLY_PLAYED_DEDUP_LIMIT = 30
        const val SLEEP_TIMER_END_OF_TRACK = -1L
    }

    private val TAG = "PlaybackController"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var exoPlayer: ExoPlayer? = null

    // Playback State
    private val _currentTrack = MutableStateFlow<SongItem?>(null)
    val currentTrack: StateFlow<SongItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // Strictly User Queue (currentTrack + upcoming user queue; NEVER polluted with autoplay items)
    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _repeatMode = MutableStateFlow("off") // "off", "all", "one"
    val repeatMode: StateFlow<String> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _playingFrom = MutableStateFlow("Queue")
    val playingFrom: StateFlow<String> = _playingFrom.asStateFlow()

    // Dedicated Persistent Autoplay Queue (Windows Auricle parity)
    private val _autoplayQueue = MutableStateFlow<List<SongItem>>(emptyList())
    val autoplayCandidates: StateFlow<List<SongItem>> = _autoplayQueue.asStateFlow()
    val autoplayQueue: StateFlow<List<SongItem>> = _autoplayQueue.asStateFlow()

    private val _isLoadingAutoplay = MutableStateFlow(false)
    val isLoadingAutoplay: StateFlow<Boolean> = _isLoadingAutoplay.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    // Sleep Timer: null = off, > 0 = countdown ms, -1L = End of current track
    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    // Party State
    private val _partySnapshot = MutableStateFlow(PartySnapshot())
    val partySnapshot: StateFlow<PartySnapshot> = _partySnapshot.asStateFlow()

    private var isApplyingRemoteParty = false
    private var isSwitchingTrack = false
    private var lastLoadedVideoId: String? = null
    private var retriedErrorVideoId: String? = null
    private var consecutiveErrorCount: Int = 0
    private var unshuffledQueue: List<SongItem> = emptyList()
    private var streamResolveJob: Job? = null
    private var autoplayFetchJob: Job? = null
    private var autoplayRefillJob: Job? = null
    private var autoplayAdvanceJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val partyActionDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Autoplay & Session History State
    private var autoplaySeed: String = ""
    private var autoplayGeneration: Long = 0L
    private var isRefillingAutoplay: Boolean = false
    private val sessionHistory = ArrayDeque<SongItem>()

    private data class PendingPlayback(
        val song: SongItem,
        val streamResult: StreamResolutionResult,
        val startPositionMs: Long,
        val autoPlay: Boolean
    )
    private var pendingPlayback: PendingPlayback? = null

    private val streamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val playbackStateFile: File by lazy {
        File(context.filesDir, "playback_state.json")
    }
    private val playbackPersistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val playbackPersistMutex = Mutex()

    init {
        ensureMediaServiceConnected()
        observeAutoplaySetting()
        restorePlaybackStateOnLaunch()
        startPartySyncLoop()
        startPositionTrackingLoop()
    }

    // ---- Playback State Persistence ----

    private fun restorePlaybackStateOnLaunch() {
        coroutineScope.launch(playbackPersistDispatcher) {
            val loaded: PersistedPlaybackState? = playbackPersistMutex.withLock {
                runCatching {
                    if (playbackStateFile.exists()) {
                        val raw = playbackStateFile.readText()
                        if (raw.isNotBlank()) {
                            json.decodeFromString<PersistedPlaybackState>(raw)
                                .takeIf { it.currentTrack.videoId.isNotBlank() }
                        } else null
                    } else null
                }.onFailure { err ->
                    Log.w(TAG, "Failed to restore playback_state.json: ${err.message}")
                }.getOrNull()
            }

            withContext(Dispatchers.Main) {
                if (_currentTrack.value != null || _partySnapshot.value.isActive) {
                    return@withContext
                }

                val stateToApply: PersistedPlaybackState = loaded ?: run {
                    val recentTrack = libraryRepo.state.value.recentlyPlayed
                        .firstOrNull()
                        ?.track
                        ?.takeIf { it.videoId.isNotBlank() }
                        ?: return@withContext
                    PersistedPlaybackState(
                        currentTrack = recentTrack,
                        positionMs = 0L,
                        durationMs = if (recentTrack.durationSecs > 0L) recentTrack.durationSecs * 1000L else 0L,
                        playingFrom = "Recently Played",
                        queue = listOf(recentTrack),
                        currentIndex = 0
                    )
                }

                val restoredTrack = stateToApply.currentTrack
                val validQueue = stateToApply.queue.filter { it.videoId.isNotBlank() }
                val normalizedQueue = if (validQueue.none { it.videoId == restoredTrack.videoId }) {
                    listOf(restoredTrack) + validQueue
                } else {
                    validQueue
                }
                val restoredIndex = stateToApply.currentIndex.let { idx ->
                    if (idx in normalizedQueue.indices && normalizedQueue[idx].videoId == restoredTrack.videoId) {
                        idx
                    } else {
                        normalizedQueue.indexOfFirst { it.videoId == restoredTrack.videoId }.coerceAtLeast(0)
                    }
                }
                val restoredDuration = when {
                    stateToApply.durationMs > 0L -> stateToApply.durationMs
                    restoredTrack.durationSecs > 0L -> restoredTrack.durationSecs * 1000L
                    else -> 0L
                }
                val restoredPosition = if (restoredDuration > 0L) {
                    stateToApply.positionMs.coerceIn(0L, restoredDuration)
                } else {
                    stateToApply.positionMs.coerceAtLeast(0L)
                }

                _queue.value = normalizedQueue
                _currentIndex.value = restoredIndex
                _currentTrack.value = restoredTrack
                _duration.value = restoredDuration
                _currentPosition.value = restoredPosition
                _playingFrom.value = stateToApply.playingFrom.ifBlank { "Queue" }
                _isPlaying.value = false
                _isLoading.value = false
                lastLoadedVideoId = null

                if (settingsRepo?.settings?.value?.autoplay != false &&
                    _autoplayQueue.value.isEmpty() &&
                    autoplayFetchJob?.isActive != true
                ) {
                    resetAndFetchAutoplay(restoredTrack.videoId)
                }
            }
        }
    }

    fun persistPlaybackStateNow() {
        persistPlaybackState(preferLivePlayerPosition = true)
    }

    private fun persistPlaybackState(preferLivePlayerPosition: Boolean = true) {
        val snapshot = capturePersistedPlaybackState(preferLivePlayerPosition) ?: return
        coroutineScope.launch(playbackPersistDispatcher) {
            writePersistedPlaybackState(snapshot)
        }
    }

    private fun capturePersistedPlaybackState(preferLivePlayerPosition: Boolean): PersistedPlaybackState? {
        val current = _currentTrack.value ?: return null
        if (current.videoId.isBlank()) return null

        val isMain = Looper.myLooper() == Looper.getMainLooper()
        val player = if (isMain && preferLivePlayerPosition) exoPlayer else null
        val playerActive = player != null &&
            !_isLoading.value &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) &&
            player.currentMediaItem?.mediaId == current.videoId

        val liveDuration = if (playerActive && player.duration > 0L) player.duration else 0L
        val durationMs = when {
            liveDuration > 0L -> liveDuration
            _duration.value > 0L -> _duration.value
            current.durationSecs > 0L -> current.durationSecs * 1000L
            else -> 0L
        }

        val rawPos = if (playerActive) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            _currentPosition.value.coerceAtLeast(0L)
        }
        val positionMs = if (durationMs > 0L) rawPos.coerceIn(0L, durationMs) else rawPos

        val rawQueue = _queue.value
        val normalizedQueue = if (rawQueue.isEmpty() || rawQueue.none { it.videoId == current.videoId }) {
            listOf(current) + rawQueue.filterNot { it.videoId == current.videoId }
        } else {
            rawQueue
        }
        val currentIdx = _currentIndex.value.let { idx ->
            if (idx in normalizedQueue.indices && normalizedQueue[idx].videoId == current.videoId) {
                idx
            } else {
                normalizedQueue.indexOfFirst { it.videoId == current.videoId }.coerceAtLeast(0)
            }
        }

        return PersistedPlaybackState(
            currentTrack = current,
            positionMs = positionMs,
            durationMs = durationMs,
            playingFrom = _playingFrom.value.ifBlank { "Queue" },
            queue = normalizedQueue,
            currentIndex = currentIdx
        )
    }

    private suspend fun writePersistedPlaybackState(snapshot: PersistedPlaybackState) {
        playbackPersistMutex.withLock {
            try {
                val encoded = json.encodeToString(snapshot)
                val parent = playbackStateFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val tmpFile = File(parent, "${playbackStateFile.name}.tmp")
                tmpFile.writeText(encoded)
                try {
                    Files.move(
                        tmpFile.toPath(),
                        playbackStateFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    try {
                        Files.move(
                            tmpFile.toPath(),
                            playbackStateFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Exception) {
                        if (!tmpFile.renameTo(playbackStateFile)) {
                            playbackStateFile.writeText(encoded)
                            tmpFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving playback state: ${e.message}")
            }
        }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    // ---- Sleep Timer ----

    fun setSleepTimerMinutes(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        sleepTimerJob?.cancel()
        val durationMs = minutes.toLong() * 60_000L
        val endAtElapsedMs = SystemClock.elapsedRealtime() + durationMs
        _sleepTimerRemainingMs.value = durationMs
        sleepTimerJob = coroutineScope.launch(Dispatchers.Main) {
            while (isActive) {
                val remaining = endAtElapsedMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    _sleepTimerRemainingMs.value = null
                    sleepTimerJob = null
                    setPlaying(false)
                    break
                }
                _sleepTimerRemainingMs.value = remaining
                delay(minOf(1000L, remaining))
            }
        }
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = SLEEP_TIMER_END_OF_TRACK
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
    }

    // ---- Taste Affinity & Autoplay Queue Management (Windows Auricle Parity) ----

    private fun pushSessionHistory(song: SongItem?) {
        if (song == null || song.videoId.isBlank()) return
        if (sessionHistory.lastOrNull()?.videoId == song.videoId) return
        sessionHistory.addLast(song)
        while (sessionHistory.size > SESSION_HISTORY_MAX) {
            sessionHistory.removeFirst()
        }
    }

    private fun normalizeArtistKey(name: String): String =
        name.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    private fun extractArtistKeys(artistText: String, credits: List<ArtistCredit> = emptyList()): List<String> {
        val keys = LinkedHashSet<String>()
        for (credit in credits) {
            val normalized = normalizeArtistKey(credit.name)
            if (normalized.isNotEmpty()) keys.add(normalized)
        }
        if (artistText.isNotBlank()) {
            val full = normalizeArtistKey(artistText)
            if (full.isNotEmpty()) keys.add(full)
            for (credit in splitArtistCredits(artistText)) {
                val normalized = normalizeArtistKey(credit.name)
                if (normalized.isNotEmpty()) keys.add(normalized)
            }
        }
        return keys.toList()
    }

    private fun buildArtistAffinityMap(): Map<String, Float> {
        val lib = libraryRepo.state.value
        val affinity = HashMap<String, Float>()
        fun record(key: String, weight: Float) {
            if (key.isEmpty()) return
            val existing = affinity[key]
            if (existing == null || weight > existing) {
                affinity[key] = weight
            }
        }
        for (album in lib.savedAlbums) {
            for (k in extractArtistKeys(album.artist, album.artists)) {
                record(k, 0.6f)
            }
        }
        for (song in lib.likedSongs) {
            for (k in extractArtistKeys(song.artist, song.artists)) {
                record(k, 0.8f)
            }
        }
        for (artist in lib.followedArtists) {
            record(normalizeArtistKey(artist.name), 1.0f)
        }
        return affinity
    }

    private fun applyTasteAffinityBias(tracks: List<SongItem>): List<SongItem> {
        if (tracks.size <= 1) return tracks
        val affinity = buildArtistAffinityMap()
        if (affinity.isEmpty()) return tracks

        return tracks.withIndex()
            .sortedWith(
                compareBy<IndexedValue<SongItem>> { (idx, song) ->
                    val score = extractArtistKeys(song.artist, song.artists)
                        .maxOfOrNull { affinity[it] ?: 0f } ?: 0f
                    idx.toFloat() - TASTE_AFFINITY_BIAS * score
                }.thenBy { it.index }
            )
            .map { it.value }
    }

    private fun filterAndRankAutoplayBatch(
        rawTracks: List<SongItem>,
        currentVideoId: String?,
        queueIds: Set<String>,
        existingAutoplayIds: Set<String>
    ): List<SongItem> {
        val distinct = rawTracks
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
        if (distinct.isEmpty()) return emptyList()

        val recentIds = libraryRepo.state.value.recentlyPlayed
            .take(RECENTLY_PLAYED_DEDUP_LIMIT)
            .map { it.track.videoId }
            .toSet()

        val strictExcluded = HashSet<String>(queueIds.size + existingAutoplayIds.size + recentIds.size + 1)
        if (!currentVideoId.isNullOrBlank()) strictExcluded.add(currentVideoId)
        strictExcluded.addAll(queueIds)
        strictExcluded.addAll(existingAutoplayIds)
        strictExcluded.addAll(recentIds)

        var filtered = distinct.filterNot { strictExcluded.contains(it.videoId) }

        if (filtered.isEmpty()) {
            val softExcluded = HashSet<String>(queueIds.size + existingAutoplayIds.size + 1)
            if (!currentVideoId.isNullOrBlank()) softExcluded.add(currentVideoId)
            softExcluded.addAll(queueIds)
            softExcluded.addAll(existingAutoplayIds)
            filtered = distinct.filterNot { softExcluded.contains(it.videoId) }
        }

        if (filtered.isEmpty()) {
            filtered = distinct.filterNot {
                it.videoId == currentVideoId || existingAutoplayIds.contains(it.videoId)
            }
        }

        return applyTasteAffinityBias(filtered)
    }

    private fun clearAutoplayState() {
        autoplayGeneration++
        autoplayFetchJob?.cancel()
        autoplayFetchJob = null
        autoplayRefillJob?.cancel()
        autoplayRefillJob = null
        autoplayAdvanceJob?.cancel()
        autoplayAdvanceJob = null
        isRefillingAutoplay = false
        _isLoadingAutoplay.value = false
        _autoplayQueue.value = emptyList()
        autoplaySeed = ""
    }

    private fun observeAutoplaySetting() {
        val repo = settingsRepo ?: return
        coroutineScope.launch(Dispatchers.Main) {
            repo.settings
                .map { it.autoplay }
                .distinctUntilChanged()
                .collect { autoplayEnabled ->
                    if (!autoplayEnabled) {
                        clearAutoplayState()
                    } else {
                        val current = _currentTrack.value
                        val isPartyGuest = _partySnapshot.value.isActive && !_partySnapshot.value.isHost
                        if (current != null &&
                            !isPartyGuest &&
                            _autoplayQueue.value.isEmpty() &&
                            autoplayFetchJob?.isActive != true
                        ) {
                            resetAndFetchAutoplay(current.videoId)
                        }
                    }
                }
        }
    }

    private fun resetAndFetchAutoplay(seedVideoId: String, forceIgnoreSetting: Boolean = false) {
        autoplayGeneration++
        val gen = autoplayGeneration
        autoplaySeed = seedVideoId
        autoplayFetchJob?.cancel()
        autoplayFetchJob = null
        autoplayRefillJob?.cancel()
        autoplayRefillJob = null
        autoplayAdvanceJob?.cancel()
        autoplayAdvanceJob = null
        isRefillingAutoplay = false
        _autoplayQueue.value = emptyList()

        val isPartyGuest = _partySnapshot.value.isActive && !_partySnapshot.value.isHost
        if (seedVideoId.isBlank() || isPartyGuest || (!forceIgnoreSetting && settingsRepo?.settings?.value?.autoplay == false)) {
            _isLoadingAutoplay.value = false
            return
        }

        _isLoadingAutoplay.value = true
        autoplayFetchJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val related = musicRepo.getWatchPlaylist(seedVideoId)
                withContext(Dispatchers.Main) {
                    if (autoplayGeneration != gen) return@withContext
                    if (!forceIgnoreSetting && settingsRepo?.settings?.value?.autoplay == false) {
                        _autoplayQueue.value = emptyList()
                        _isLoadingAutoplay.value = false
                        return@withContext
                    }
                    val queueIds = _queue.value.map { it.videoId }.toSet()
                    val ranked = filterAndRankAutoplayBatch(
                        rawTracks = related,
                        currentVideoId = _currentTrack.value?.videoId,
                        queueIds = queueIds,
                        existingAutoplayIds = emptySet()
                    ).take(AUTOPLAY_TARGET)
                    _autoplayQueue.value = ranked
                    _isLoadingAutoplay.value = false
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (autoplayGeneration == gen) {
                        _isLoadingAutoplay.value = false
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay fetch error: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (autoplayGeneration == gen) {
                        _isLoadingAutoplay.value = false
                    }
                }
            }
        }
    }

    private fun ensureAutoplayPopulatedIfEmpty(seedVideoId: String) {
        if (seedVideoId.isBlank()) return
        if (settingsRepo?.settings?.value?.autoplay == false) return
        if (_autoplayQueue.value.isNotEmpty()) return
        if (autoplayFetchJob?.isActive == true || isRefillingAutoplay) return
        resetAndFetchAutoplay(seedVideoId)
    }

    private fun refillAutoplayQueue() {
        if (isRefillingAutoplay) return
        if (settingsRepo?.settings?.value?.autoplay == false) return
        val currentAutoplay = _autoplayQueue.value
        if (currentAutoplay.size >= AUTOPLAY_MAX) return

        val seed = currentAutoplay.lastOrNull()?.videoId?.takeIf { it.isNotBlank() }
            ?: _currentTrack.value?.videoId?.takeIf { it.isNotBlank() }
            ?: autoplaySeed
        if (seed.isBlank()) return

        val gen = autoplayGeneration
        isRefillingAutoplay = true
        _isLoadingAutoplay.value = true
        autoplayRefillJob?.cancel()
        autoplayRefillJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val related = musicRepo.getWatchPlaylist(seed)
                withContext(Dispatchers.Main) {
                    if (autoplayGeneration != gen) return@withContext
                    isRefillingAutoplay = false
                    _isLoadingAutoplay.value = false
                    if (settingsRepo?.settings?.value?.autoplay == false) return@withContext

                    val latestAutoplay = _autoplayQueue.value
                    val room = (AUTOPLAY_MAX - latestAutoplay.size).coerceAtLeast(0)
                    if (room == 0) return@withContext

                    val queueIds = _queue.value.map { it.videoId }.toSet()
                    val existingAutoplayIds = latestAutoplay.map { it.videoId }.toSet()
                    val additions = filterAndRankAutoplayBatch(
                        rawTracks = related,
                        currentVideoId = _currentTrack.value?.videoId,
                        queueIds = queueIds,
                        existingAutoplayIds = existingAutoplayIds
                    ).take(room)

                    if (additions.isNotEmpty()) {
                        _autoplayQueue.value = latestAutoplay + additions
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (autoplayGeneration == gen) {
                        isRefillingAutoplay = false
                        _isLoadingAutoplay.value = false
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay refill error: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (autoplayGeneration == gen) {
                        isRefillingAutoplay = false
                        _isLoadingAutoplay.value = false
                    }
                }
            }
        }
    }

    private fun removeVideoFromAutoplay(videoId: String) {
        if (videoId.isBlank()) return
        val current = _autoplayQueue.value
        if (current.any { it.videoId == videoId }) {
            val updated = current.filterNot { it.videoId == videoId }
            _autoplayQueue.value = updated
            if (updated.size < AUTOPLAY_REFILL_THRESHOLD) {
                refillAutoplayQueue()
            }
        }
    }

    fun removeFromAutoplay(index: Int) {
        val current = _autoplayQueue.value
        if (index !in current.indices) return
        val updated = current.toMutableList().apply { removeAt(index) }
        _autoplayQueue.value = updated
        if (updated.size < AUTOPLAY_REFILL_THRESHOLD) {
            refillAutoplayQueue()
        }
    }

    fun startRadio(song: SongItem) {
        consecutiveErrorCount = 0
        settingsRepo?.let { repo ->
            if (!repo.settings.value.autoplay) {
                repo.updateAutoplay(true)
            }
        }
        playTrackInternal(
            song = song,
            newQueue = listOf(song),
            sourceLabel = "Radio · ${song.title}",
            resetShuffle = true,
            forceAutoplayFetch = true
        )
    }

    // ---- MediaSessionService & ExoPlayer ----

    fun ensureMediaServiceConnected() {
        coroutineScope.launch(Dispatchers.Main) {
            val existing = mediaControllerFuture
            if (existing != null && existing.isDone && exoPlayer == null) {
                MediaController.releaseFuture(existing)
                mediaControllerFuture = null
            }
            if (mediaControllerFuture == null) {
                try {
                    val token = SessionToken(
                        context,
                        ComponentName(context, AuriclePlaybackService::class.java)
                    )
                    val future = MediaController.Builder(context, token).buildAsync()
                    mediaControllerFuture = future
                    future.addListener({
                        try {
                            future.get()
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaController connection failed: ${e.message}", e)
                            if (mediaControllerFuture === future) {
                                mediaControllerFuture = null
                            }
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind MediaSessionService: ${e.message}", e)
                }
            }
        }
    }

    fun attachPlayer(player: ExoPlayer) {
        this.exoPlayer = player
        setupPlayerListener(player)
        pendingPlayback?.let { pending ->
            pendingPlayback = null
            if (_currentTrack.value?.videoId == pending.song.videoId) {
                playStream(pending.song, pending.streamResult, pending.startPositionMs, pending.autoPlay)
            }
        }
    }

    fun detachPlayer() {
        persistPlaybackStateNow()
        this.exoPlayer = null
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        this.mediaControllerFuture = null
        _isPlaying.value = false
        _isLoading.value = false
    }

    private fun setupPlayerListener(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // Keep local UI state in sync WITHOUT firing automatic remote party broadcast
                _isPlaying.value = playing
                if (!playing && !isSwitchingTrack) {
                    persistPlaybackStateNow()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isLoading.value = true
                    }
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        consecutiveErrorCount = 0
                        if (player.duration > 0L) {
                            val hadZeroDuration = _duration.value <= 0L
                            _duration.value = player.duration
                            if (hadZeroDuration) {
                                persistPlaybackStateNow()
                            }
                        }

                        // If active in a party, snap immediately to live target timestamp upon buffer completion
                        if (_partySnapshot.value.isActive) {
                            val track = _currentTrack.value
                            if (track != null && track.videoId.isNotBlank()) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val targetMs = AuricleNative.nativePartyGetTargetPositionMs(track.videoId)
                                    if (targetMs >= 0) {
                                        withContext(Dispatchers.Main) {
                                            if (abs(player.currentPosition - targetMs) > 350) {
                                                player.seekTo(targetMs)
                                                Log.d(TAG, "Buffer ready snap to live party target: $targetMs ms")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        _isLoading.value = false
                        handleTrackEnded()
                    }
                    Player.STATE_IDLE -> {
                        _isLoading.value = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}")
                _isLoading.value = false
                val current = _currentTrack.value ?: return
                if (retriedErrorVideoId == current.videoId) {
                    handleTrackPlaybackFailure(current)
                    return
                }
                retriedErrorVideoId = current.videoId
                val resumePos = player.currentPosition.coerceAtLeast(0L)
                val highQuality = settingsRepo?.settings?.value?.highQualityAudio ?: true

                streamResolveJob?.cancel()
                streamResolveJob = coroutineScope.launch {
                    val fallback = musicRepo.resolveStream(
                        videoId = current.videoId,
                        forceRefresh = true,
                        highQuality = highQuality
                    )
                    withContext(Dispatchers.Main) {
                        if (_currentTrack.value?.videoId != current.videoId) return@withContext
                        fallback.onSuccess { streamRes ->
                            if (streamRes.durationSecs > 0L && _duration.value == 0L) {
                                _duration.value = streamRes.durationSecs * 1000L
                            }
                            playStream(current, streamRes, resumePos, autoPlay = true)
                        }.onFailure { err ->
                            Log.e(TAG, "Fallback stream resolution error: ${err.message}")
                            handleTrackPlaybackFailure(current)
                        }
                    }
                }
            }
        })
    }

    private fun startPositionTrackingLoop() {
        coroutineScope.launch(Dispatchers.Main) {
            var lastPersistElapsedMs = SystemClock.elapsedRealtime()
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                        _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                        if (player.duration > 0) {
                            _duration.value = player.duration
                        }
                    }
                }
                if (_isPlaying.value && _currentTrack.value != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPersistElapsedMs >= 5_000L) {
                        lastPersistElapsedMs = now
                        persistPlaybackStateNow()
                    }
                }
                delay(if (_isPlaying.value) 120L else 300L)
            }
        }
    }

    private fun startPartySyncLoop() {
        coroutineScope.launch(Dispatchers.IO) {
            var driftTickCounter = 0
            while (isActive) {
                delay(500)
                try {
                    // 1. Poll Party Remote Updates
                    val pollRaw = AuricleNative.nativePartyPollRemoteUpdate()
                    if (pollRaw.isNotBlank() && !pollRaw.contains("\"ok\":false")) {
                        val pollResult = json.decodeFromString<PartyPollResult>(pollRaw)
                        if (pollResult.hasUpdate && pollResult.state != null) {
                            withContext(Dispatchers.Main) {
                                applyRemotePartyState(pollResult.state, pollResult.targetPositionMs)
                            }
                        }
                    }

                    // 2. Refresh UI snapshot
                    val snapRaw = AuricleNative.nativePartyGetSnapshot()
                    if (snapRaw.isNotBlank() && !snapRaw.contains("\"ok\":false")) {
                        val wasActive = _partySnapshot.value.isActive
                        val snapshot = json.decodeFromString<PartySnapshot>(snapRaw)
                        _partySnapshot.value = snapshot
                        if (wasActive && !snapshot.isActive) {
                            withContext(Dispatchers.Main) {
                                exoPlayer?.playbackParameters = PlaybackParameters(1.0f)
                            }
                        }
                    }

                    // 3. Drift Correction (every 1000ms while active party is playing)
                    driftTickCounter++
                    if (driftTickCounter % 2 == 0 && _partySnapshot.value.isActive && _isPlaying.value) {
                        val currentVid = _currentTrack.value?.videoId
                        if (!currentVid.isNullOrBlank()) {
                            val targetMs = AuricleNative.nativePartyGetTargetPositionMs(currentVid)
                            if (targetMs >= 0) {
                                withContext(Dispatchers.Main) {
                                    applyDriftCorrection(targetMs)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Party sync loop error: ${e.message}")
                }
            }
        }
    }

    private fun applyDriftCorrection(targetMs: Long) {
        val player = exoPlayer ?: return
        if (player.playbackState != Player.STATE_READY) return

        val localMs = player.currentPosition
        val driftMs = localMs - targetMs // positive = local ahead, negative = local behind

        if (abs(driftMs) > 1500) {
            player.seekTo(targetMs)
            player.playbackParameters = PlaybackParameters(1.0f)
            Log.d(TAG, "Party hard seek drift=$driftMs ms -> target=$targetMs ms")
        } else if (driftMs > 240) {
            player.playbackParameters = PlaybackParameters(0.97f)
        } else if (driftMs < -240) {
            player.playbackParameters = PlaybackParameters(1.03f)
        } else if (abs(driftMs) < 80 && player.playbackParameters.speed != 1.0f) {
            player.playbackParameters = PlaybackParameters(1.0f)
        }
    }

    private fun applyRemotePartyState(state: RemotePartyState, targetPos: Long?) {
        isApplyingRemoteParty = true
        try {
            val remoteTrack = state.currentTrack
            if (state.queue.isEmpty() && remoteTrack == null) {
                streamResolveJob?.cancel()
                clearAutoplayState()
                sessionHistory.clear()
                pendingPlayback = null
                exoPlayer?.apply {
                    stop()
                    clearMediaItems()
                }
                _queue.value = emptyList()
                _currentTrack.value = null
                _currentIndex.value = -1
                _isPlaying.value = false
                _isLoading.value = false
                _currentPosition.value = 0L
                _duration.value = 0L
                _isShuffle.value = false
                unshuffledQueue = emptyList()
                _repeatMode.value = state.repeatMode
                return
            }

            if (state.queue.isNotEmpty()) {
                if (_queue.value != state.queue) {
                    _isShuffle.value = false
                    unshuffledQueue = emptyList()
                }
                _queue.value = state.queue
                _currentIndex.value = state.currentIndex.coerceIn(0, state.queue.size - 1)
            }
            _repeatMode.value = state.repeatMode

            val effectivePos = targetPos ?: state.positionMs
            if (remoteTrack != null) {
                if (_currentTrack.value?.videoId != remoteTrack.videoId) {
                    playSongInternal(
                        song = remoteTrack,
                        startPositionMs = effectivePos,
                        autoPlay = state.isPlaying,
                        resetAutoplay = false
                    )
                } else {
                    val player = exoPlayer
                    if (player != null) {
                        if (state.isPlaying && !player.isPlaying) {
                            player.play()
                        } else if (!state.isPlaying && player.isPlaying) {
                            player.pause()
                        }
                        if (abs(player.currentPosition - effectivePos) > 350) {
                            player.seekTo(effectivePos)
                        }
                    }
                }
            }
        } finally {
            isApplyingRemoteParty = false
        }
    }

    private fun sendPartyAction(action: PartyAction) {
        sendPartyActionsSequential(action)
    }

    private fun sendPartyActionsSequential(vararg actions: PartyAction) {
        if (isApplyingRemoteParty || actions.isEmpty()) return
        if (!_partySnapshot.value.isActive) return

        coroutineScope.launch(partyActionDispatcher) {
            for (action in actions) {
                try {
                    val actionJson = json.encodeToString(action)
                    AuricleNative.nativePartySendAction(actionJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending party action: ${e.message}")
                }
            }
        }
    }

    fun syncPartyQueuePreservingPosition() {
        if (!_partySnapshot.value.isActive || isApplyingRemoteParty) return
        val q = _queue.value
        val preservedPosMs = if (q.isEmpty()) {
            0L
        } else {
            (exoPlayer?.currentPosition ?: _currentPosition.value).coerceAtLeast(0L)
        }
        val replaceAction = PartyAction(
            type = "replace_queue",
            tracks = q,
            startIndex = _currentIndex.value.coerceAtLeast(0),
            isPlaying = if (q.isEmpty()) false else _isPlaying.value
        )
        if (q.isNotEmpty() && preservedPosMs > 0L) {
            sendPartyActionsSequential(
                replaceAction,
                PartyAction(type = "seek", positionMs = preservedPosMs)
            )
        } else {
            sendPartyActionsSequential(replaceAction)
        }
    }

    fun playTrack(song: SongItem, newQueue: List<SongItem> = listOf(song), sourceLabel: String? = null) {
        consecutiveErrorCount = 0
        playTrackInternal(song, newQueue, sourceLabel, resetShuffle = true)
    }

    fun playShuffled(tracks: List<SongItem>, playingFrom: String) {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        unshuffledQueue = tracks
        _isShuffle.value = true
        consecutiveErrorCount = 0
        playTrackInternal(shuffled.first(), shuffled, playingFrom, resetShuffle = false)
    }

    fun playTracksShuffled(tracks: List<SongItem>, sourceLabel: String? = null) {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        unshuffledQueue = tracks
        _isShuffle.value = true
        consecutiveErrorCount = 0
        playTrackInternal(shuffled.first(), shuffled, sourceLabel, resetShuffle = false)
    }

    private fun playTrackInternal(
        song: SongItem,
        newQueue: List<SongItem>,
        sourceLabel: String?,
        resetShuffle: Boolean,
        forceAutoplayFetch: Boolean = false
    ) {
        autoplayAdvanceJob?.cancel()
        sessionHistory.clear()
        if (resetShuffle) {
            _isShuffle.value = false
            unshuffledQueue = emptyList()
        }
        if (sourceLabel != null) {
            _playingFrom.value = sourceLabel
        }
        val targetQueue = if (newQueue.none { it.videoId == song.videoId }) {
            listOf(song) + newQueue
        } else newQueue

        _queue.value = targetQueue
        val idx = targetQueue.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
        _currentIndex.value = idx

        playSongInternal(
            song = song,
            startPositionMs = 0L,
            autoPlay = true,
            resetAutoplay = true,
            forceAutoplayFetch = forceAutoplayFetch
        )

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            if (targetQueue.size > 1) {
                sendPartyAction(
                    PartyAction(
                        type = "replace_queue",
                        tracks = targetQueue,
                        startIndex = idx,
                        isPlaying = true
                    )
                )
            } else {
                sendPartyAction(
                    PartyAction(
                        type = "play_track_now",
                        track = song
                    )
                )
            }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int, syncParty: Boolean = true) {
        val currentQ = _queue.value.toMutableList()
        if (fromIndex !in currentQ.indices || toIndex !in currentQ.indices || fromIndex == toIndex) return

        val item = currentQ.removeAt(fromIndex)
        currentQ.add(toIndex, item)
        _queue.value = currentQ

        val currentIdx = _currentIndex.value
        if (fromIndex == currentIdx) {
            _currentIndex.value = toIndex
        } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
            _currentIndex.value = currentIdx - 1
        } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
            _currentIndex.value = currentIdx + 1
        }

        if (syncParty) {
            syncPartyQueuePreservingPosition()
        }
        persistPlaybackStateNow()
    }

    fun clearUpcomingQueue() {
        val current = _currentTrack.value
        val items = listOfNotNull(current)
        _queue.value = items
        _currentIndex.value = if (items.isNotEmpty()) 0 else -1
        if (_isShuffle.value) {
            unshuffledQueue = items
        }

        syncPartyQueuePreservingPosition()
        persistPlaybackStateNow()
    }

    private fun playSongInternal(
        song: SongItem,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        clearError: Boolean = true,
        resetAutoplay: Boolean = false,
        forceAutoplayFetch: Boolean = false
    ) {
        autoplayAdvanceJob?.cancel()
        if (clearError) {
            _playbackError.value = null
        }
        // Pause immediately so the previous track stops sounding while the next stream resolves,
        // without calling stop()/clearMediaItems() which drops MediaSession to STATE_IDLE and
        // triggers an asynchronous Android 15 SystemUI MediaSessionRecord:stop race.
        isSwitchingTrack = true
        try {
            exoPlayer?.pause()
        } finally {
            isSwitchingTrack = false
        }
        val previousTrackId = _currentTrack.value?.videoId
        val previousDuration = _duration.value
        _currentTrack.value = song
        _isLoading.value = true
        _currentPosition.value = startPositionMs.coerceAtLeast(0L)
        _duration.value = when {
            song.durationSecs > 0L -> song.durationSecs * 1000L
            previousTrackId == song.videoId && previousDuration > 0L -> previousDuration
            else -> 0L
        }
        lastLoadedVideoId = song.videoId
        retriedErrorVideoId = null
        libraryRepo.recordRecentlyPlayed(song)
        persistPlaybackState(preferLivePlayerPosition = false)

        if (resetAutoplay) {
            resetAndFetchAutoplay(song.videoId, forceIgnoreSetting = forceAutoplayFetch)
        }

        val highQuality = settingsRepo?.settings?.value?.highQualityAudio ?: true
        streamResolveJob?.cancel()
        streamResolveJob = coroutineScope.launch {
            val res = musicRepo.resolveStream(song.videoId, highQuality = highQuality)
            withContext(Dispatchers.Main) {
                if (_currentTrack.value?.videoId != song.videoId) return@withContext
                res.onSuccess { streamResult ->
                    if (streamResult.durationSecs > 0L && _duration.value == 0L) {
                        _duration.value = streamResult.durationSecs * 1000L
                    }
                    playStream(song, streamResult, startPositionMs, autoPlay)
                }.onFailure { err ->
                    Log.e(TAG, "Stream resolution error: ${err.message}")
                    handleTrackPlaybackFailure(song)
                }
            }
        }
    }

    private fun handleTrackPlaybackFailure(failedSong: SongItem) {
        if (_currentTrack.value?.videoId != failedSong.videoId) return
        exoPlayer?.pause()
        _isLoading.value = false
        _isPlaying.value = false
        consecutiveErrorCount++
        _playbackError.value = "Couldn't play ${failedSong.title}"

        val isPartyGuest = _partySnapshot.value.isActive && !_partySnapshot.value.isHost
        val q = _queue.value
        val hasNextInQueue = _currentIndex.value + 1 < q.size || (_repeatMode.value == "all" && q.size > 1)
        val hasAutoplayNext = (settingsRepo?.settings?.value?.autoplay ?: true) && _autoplayQueue.value.isNotEmpty()

        if (consecutiveErrorCount < 3 && !isPartyGuest && (hasNextInQueue || hasAutoplayNext)) {
            skipNextInternal(clearError = false)
        }
    }

    private fun playStream(
        song: SongItem,
        streamResult: StreamResolutionResult,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true
    ) {
        val player = exoPlayer
        if (player == null) {
            pendingPlayback = PendingPlayback(song, streamResult, startPositionMs, autoPlay)
            ensureMediaServiceConnected()
            return
        }

        val dataSourceFactory = OkHttpDataSource.Factory(streamHttpClient)
        streamResult.userAgent?.let { ua ->
            dataSourceFactory.setUserAgent(ua)
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
        val art = song.displayArtworkUrl
        if (art.isNotBlank()) {
            metadataBuilder.setArtworkUri(Uri.parse(art))
        }
        val mediaMetadata = metadataBuilder.build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamResult.url))
            .setMediaId(song.videoId)
            .setMediaMetadata(mediaMetadata)
            .build()

        val mediaSource = if (streamResult.isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs)
        }
        if (autoPlay) {
            player.play()
        }
    }

    fun setPlaying(targetPlaying: Boolean) {
        autoplayAdvanceJob?.cancel()
        val player = exoPlayer
        val current = _currentTrack.value
        if (player == null) {
            if (targetPlaying) {
                if (current != null) {
                    val startPos = if (_duration.value > 0L && _currentPosition.value >= _duration.value) {
                        0L
                    } else {
                        _currentPosition.value.coerceAtLeast(0L)
                    }
                    playSongInternal(
                        song = current,
                        startPositionMs = startPos,
                        autoPlay = true,
                        resetAutoplay = false
                    )
                    if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                        sendPartyAction(
                            PartyAction(
                                type = "play_pause",
                                isPlaying = true,
                                positionMs = startPos
                            )
                        )
                    }
                }
            } else {
                _isPlaying.value = false
                persistPlaybackStateNow()
            }
            return
        }

        if (targetPlaying) {
            if (current != null && (
                    player.playbackState == Player.STATE_IDLE ||
                        player.currentMediaItem == null ||
                        player.currentMediaItem?.mediaId != current.videoId ||
                        lastLoadedVideoId != current.videoId
                    )
            ) {
                val startPos = if (_duration.value > 0L && _currentPosition.value >= _duration.value) {
                    0L
                } else {
                    _currentPosition.value.coerceAtLeast(0L)
                }
                playSongInternal(
                    song = current,
                    startPositionMs = startPos,
                    autoPlay = true,
                    resetAutoplay = false
                )
                if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                    sendPartyAction(
                        PartyAction(
                            type = "play_pause",
                            isPlaying = true,
                            positionMs = startPos
                        )
                    )
                }
                return
            }

            when (player.playbackState) {
                Player.STATE_ENDED -> {
                    player.seekTo(0L)
                    player.play()
                    _currentPosition.value = 0L
                }
                else -> {
                    player.play()
                }
            }
        } else {
            player.pause()
            persistPlaybackStateNow()
        }

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "play_pause",
                    isPlaying = targetPlaying,
                    positionMs = player.currentPosition
                )
            )
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer
        val current = _currentTrack.value
        if (player == null) {
            setPlaying(!_isPlaying.value)
            return
        }
        val needsLoad = current != null && (
            player.playbackState == Player.STATE_IDLE ||
                player.currentMediaItem == null ||
                player.currentMediaItem?.mediaId != current.videoId ||
                lastLoadedVideoId != current.videoId
            )
        if (needsLoad) {
            setPlaying(true)
            return
        }
        setPlaying(!player.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        if (positionMs == C.TIME_UNSET) return
        autoplayAdvanceJob?.cancel()
        val player = exoPlayer
        val current = _currentTrack.value
        val playerHasCurrentTrack = player != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.currentMediaItem != null &&
            (current == null ||
                (player.currentMediaItem?.mediaId == current.videoId && lastLoadedVideoId == current.videoId))
        val knownDur = _duration.value.takeIf { it > 0L }
            ?: if (playerHasCurrentTrack) player?.duration?.takeIf { it > 0L } else null
        val clamped = if (knownDur != null) positionMs.coerceIn(0L, knownDur) else positionMs.coerceAtLeast(0L)
        if (playerHasCurrentTrack) {
            player?.seekTo(clamped)
        }
        _currentPosition.value = clamped
        persistPlaybackState(preferLivePlayerPosition = false)

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "seek",
                    positionMs = clamped
                )
            )
        }
    }

    fun canSkipNext(): Boolean {
        val q = _queue.value
        if (q.isEmpty()) return false
        if (_currentIndex.value + 1 < q.size) return true
        if (_repeatMode.value == "all") return true
        val isPartyGuest = _partySnapshot.value.isActive && !_partySnapshot.value.isHost
        if (isPartyGuest) return false
        val autoplayAllowed = settingsRepo?.settings?.value?.autoplay ?: true
        return autoplayAllowed && (_autoplayQueue.value.isNotEmpty() || _currentTrack.value != null)
    }

    fun skipNext() {
        consecutiveErrorCount = 0
        skipNextInternal(clearError = true)
    }

    private fun skipNextInternal(clearError: Boolean) {
        val q = _queue.value
        if (q.isEmpty()) return

        val isPartyActive = _partySnapshot.value.isActive
        val isHost = _partySnapshot.value.isHost
        val nextIdx = _currentIndex.value + 1

        if (nextIdx < q.size) {
            _currentIndex.value = nextIdx
            val nextSong = q[nextIdx]
            playSongInternal(
                song = nextSong,
                startPositionMs = 0L,
                autoPlay = true,
                clearError = clearError,
                resetAutoplay = false
            )
            ensureAutoplayPopulatedIfEmpty(nextSong.videoId)

            if (isPartyActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else if (_repeatMode.value == "all" && q.isNotEmpty()) {
            _currentIndex.value = 0
            val firstSong = q[0]
            playSongInternal(
                song = firstSong,
                startPositionMs = 0L,
                autoPlay = true,
                clearError = clearError,
                resetAutoplay = false
            )
            ensureAutoplayPopulatedIfEmpty(firstSong.videoId)

            if (isPartyActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else {
            val current = _currentTrack.value
            val autoplayAllowed = settingsRepo?.settings?.value?.autoplay ?: true
            if (current != null && (!isPartyActive || isHost) && autoplayAllowed) {
                if (_autoplayQueue.value.isNotEmpty()) {
                    playAutoplayAtPreservingQueues(0, clearError = clearError)
                } else {
                    advanceIntoAutoplay(current, clearError = clearError)
                }
            } else {
                val player = exoPlayer
                val isActivelyPlaying = player != null &&
                    (player.isPlaying || player.playbackState == Player.STATE_BUFFERING)
                if (!isActivelyPlaying) {
                    _isPlaying.value = false
                    _isLoading.value = false
                }
            }
        }
    }

    fun skipPrevious() {
        if (_currentPosition.value > 3000L) {
            seekTo(0L)
            return
        }

        val q = _queue.value
        if (q.isEmpty() && sessionHistory.isEmpty()) return

        val currentIdx = _currentIndex.value
        if (currentIdx > 0 && q.isNotEmpty()) {
            val prevIdx = currentIdx - 1
            _currentIndex.value = prevIdx
            playSongInternal(
                song = q[prevIdx],
                startPositionMs = 0L,
                autoPlay = true,
                resetAutoplay = false
            )

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "prev"))
            }
        } else if (sessionHistory.isNotEmpty()) {
            val prevSong = sessionHistory.removeLast()
            val updatedQ = q.toMutableList()
            val targetIdx: Int
            if (updatedQ.isEmpty()) {
                updatedQ.add(prevSong)
                targetIdx = 0
            } else {
                val curIdx = currentIdx.coerceIn(0, updatedQ.lastIndex)
                updatedQ[curIdx] = prevSong
                targetIdx = curIdx
            }
            _queue.value = updatedQ
            _currentIndex.value = targetIdx
            playSongInternal(
                song = prevSong,
                startPositionMs = 0L,
                autoPlay = true,
                resetAutoplay = false
            )
            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                sendPartyAction(
                    PartyAction(
                        type = "replace_queue",
                        tracks = updatedQ,
                        startIndex = targetIdx,
                        isPlaying = true
                    )
                )
            }
        } else if (_repeatMode.value == "all" && q.isNotEmpty()) {
            val lastIdx = q.lastIndex
            _currentIndex.value = lastIdx
            playSongInternal(
                song = q[lastIdx],
                startPositionMs = 0L,
                autoPlay = true,
                resetAutoplay = false
            )

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "play_queue_index", index = lastIdx))
            }
        } else {
            seekTo(0L)
        }
    }

    fun playQueueIndex(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        autoplayAdvanceJob?.cancel()
        consecutiveErrorCount = 0
        _currentIndex.value = index
        val song = q[index]
        playSongInternal(
            song = song,
            startPositionMs = 0L,
            autoPlay = true,
            resetAutoplay = false
        )
        ensureAutoplayPopulatedIfEmpty(song.videoId)

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "play_queue_index",
                    index = index
                )
            )
        }
    }

    fun addToQueue(song: SongItem) {
        if (_currentTrack.value == null || _queue.value.isEmpty()) {
            playTrack(song, listOf(song), "Queue")
            return
        }
        removeVideoFromAutoplay(song.videoId)
        val currentQ = _queue.value
        val alreadyInQueue = currentQ.any { it.videoId == song.videoId }
        val updated = currentQ + song
        _queue.value = updated
        if (_isShuffle.value) {
            unshuffledQueue = unshuffledQueue + song
        }

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            if (alreadyInQueue) {
                syncPartyQueuePreservingPosition()
            } else {
                sendPartyAction(
                    PartyAction(
                        type = "add_to_queue",
                        track = song,
                        playNext = false
                    )
                )
            }
        }
        persistPlaybackStateNow()
    }

    fun playNext(song: SongItem) {
        if (_currentTrack.value == null || _queue.value.isEmpty()) {
            playTrack(song, listOf(song), "Queue")
            return
        }
        removeVideoFromAutoplay(song.videoId)
        val currentQ = _queue.value.toMutableList()
        val currentIdx = _currentIndex.value.coerceIn(0, currentQ.lastIndex)
        val existingUpcomingIndex = (currentIdx + 1 until currentQ.size)
            .firstOrNull { currentQ[it].videoId == song.videoId }

        if (existingUpcomingIndex != null) {
            // Already in upcoming queue: move it next
            val upcomingIdx = existingUpcomingIndex - currentIdx - 1
            currentQ.removeAt(existingUpcomingIndex)
            val insertPos = currentIdx + 1
            currentQ.add(insertPos, song)
            _queue.value = currentQ

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                sendPartyAction(
                    PartyAction(
                        type = "move_queue_next",
                        upcomingIndex = upcomingIdx
                    )
                )
            }
        } else {
            val existsAtOrBeforeCurrent = currentQ.take(currentIdx + 1).any { it.videoId == song.videoId }
            // Insert newly into queue right after current
            val insertPos = (currentIdx + 1).coerceAtMost(currentQ.size)
            currentQ.add(insertPos, song)
            _queue.value = currentQ
            if (_isShuffle.value) {
                unshuffledQueue = unshuffledQueue + song
            }

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                if (existsAtOrBeforeCurrent) {
                    syncPartyQueuePreservingPosition()
                } else {
                    sendPartyAction(
                        PartyAction(
                            type = "add_to_queue",
                            track = song,
                            playNext = true
                        )
                    )
                }
            }
        }
        persistPlaybackStateNow()
    }

    fun removeFromQueue(index: Int) {
        val currentQ = _queue.value.toMutableList()
        if (index !in currentQ.indices) return

        autoplayAdvanceJob?.cancel()
        val currentIdx = _currentIndex.value
        val upcomingIdx = if (index > currentIdx) index - currentIdx - 1 else null
        val removedSong = currentQ.removeAt(index)

        if (currentQ.isEmpty()) {
            streamResolveJob?.cancel()
            clearAutoplayState()
            sessionHistory.clear()
            pendingPlayback = null
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            _queue.value = emptyList()
            _currentTrack.value = null
            _currentIndex.value = -1
            _isPlaying.value = false
            _isLoading.value = false
            _currentPosition.value = 0L
            _duration.value = 0L
            _playbackError.value = null
            unshuffledQueue = emptyList()
            syncPartyQueuePreservingPosition()
            return
        }

        if (_isShuffle.value) {
            val idxInUnshuffled = unshuffledQueue.indexOfFirst { it.videoId == removedSong.videoId }
            if (idxInUnshuffled >= 0) {
                unshuffledQueue = unshuffledQueue.toMutableList().apply { removeAt(idxInUnshuffled) }
            }
        }

        _queue.value = currentQ
        when {
            index == currentIdx -> {
                val nextIdx = index.coerceIn(0, currentQ.lastIndex)
                _currentIndex.value = nextIdx
                playSongInternal(
                    song = currentQ[nextIdx],
                    startPositionMs = 0L,
                    autoPlay = true,
                    resetAutoplay = false
                )
                if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                    sendPartyActionsSequential(
                        PartyAction(
                            type = "replace_queue",
                            tracks = currentQ,
                            startIndex = nextIdx,
                            isPlaying = true
                        ),
                        PartyAction(type = "seek", positionMs = 0L)
                    )
                }
            }
            index < currentIdx -> {
                _currentIndex.value = (currentIdx - 1).coerceAtLeast(0)
                syncPartyQueuePreservingPosition()
                persistPlaybackStateNow()
            }
            else -> {
                if (_partySnapshot.value.isActive && !isApplyingRemoteParty && upcomingIdx != null) {
                    sendPartyAction(
                        PartyAction(
                            type = "remove_from_queue",
                            upcomingIndex = upcomingIdx
                        )
                    )
                }
                persistPlaybackStateNow()
            }
        }
    }

    fun playAutoplayAtPreservingQueues(index: Int, clearError: Boolean = true) {
        val autoplayList = _autoplayQueue.value
        if (index !in autoplayList.indices) return
        autoplayAdvanceJob?.cancel()

        val song = autoplayList[index]
        val remainingAutoplay = autoplayList.toMutableList().apply { removeAt(index) }
        _autoplayQueue.value = remainingAutoplay

        val previousTrack = _currentTrack.value
        pushSessionHistory(previousTrack)
        autoplaySeed = song.videoId

        val currentQ = _queue.value.toMutableList()
        val targetIdx: Int
        if (currentQ.isEmpty()) {
            currentQ.add(song)
            targetIdx = 0
        } else {
            val curIdx = _currentIndex.value.coerceIn(0, currentQ.lastIndex)
            currentQ[curIdx] = song
            targetIdx = curIdx
        }
        _queue.value = currentQ
        _currentIndex.value = targetIdx

        if (_isShuffle.value && previousTrack != null) {
            val unshuf = unshuffledQueue.toMutableList()
            val match = unshuf.indexOfFirst { it.videoId == previousTrack.videoId }
            if (match >= 0) {
                unshuf[match] = song
                unshuffledQueue = unshuf
            }
        }

        playSongInternal(
            song = song,
            startPositionMs = 0L,
            autoPlay = true,
            clearError = clearError,
            resetAutoplay = false
        )

        if (remainingAutoplay.size < AUTOPLAY_REFILL_THRESHOLD) {
            refillAutoplayQueue()
        }

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "replace_queue",
                    tracks = currentQ,
                    startIndex = targetIdx,
                    isPlaying = true
                )
            )
        }
    }

    fun playAutoplayCandidate(candidate: SongItem) {
        consecutiveErrorCount = 0
        val idx = _autoplayQueue.value.indexOfFirst { it.videoId == candidate.videoId }
        if (idx >= 0) {
            playAutoplayAtPreservingQueues(idx, clearError = true)
            return
        }

        // Fallback if candidate is not currently in _autoplayQueue: still preserve user queue & autoplay
        autoplayAdvanceJob?.cancel()
        val previousTrack = _currentTrack.value
        pushSessionHistory(previousTrack)
        autoplaySeed = candidate.videoId

        val currentQ = _queue.value.toMutableList()
        val targetIdx: Int
        if (currentQ.isEmpty()) {
            currentQ.add(candidate)
            targetIdx = 0
        } else {
            val curIdx = _currentIndex.value.coerceIn(0, currentQ.lastIndex)
            currentQ[curIdx] = candidate
            targetIdx = curIdx
        }
        _queue.value = currentQ
        _currentIndex.value = targetIdx

        playSongInternal(
            song = candidate,
            startPositionMs = 0L,
            autoPlay = true,
            clearError = true,
            resetAutoplay = false
        )

        if (_autoplayQueue.value.size < AUTOPLAY_REFILL_THRESHOLD) {
            refillAutoplayQueue()
        }

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "replace_queue",
                    tracks = currentQ,
                    startIndex = targetIdx,
                    isPlaying = true
                )
            )
        }
    }

    fun toggleRepeat() {
        val next = when (_repeatMode.value) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        _repeatMode.value = next

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "set_repeat_mode",
                    mode = next
                )
            )
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        val q = _queue.value
        if (newShuffle) {
            unshuffledQueue = q
            if (q.isNotEmpty()) {
                val currentIdx = _currentIndex.value.coerceIn(0, q.lastIndex)
                val prefix = q.subList(0, currentIdx + 1).toList()
                val upcoming = q.drop(currentIdx + 1)
                _queue.value = prefix + upcoming.shuffled()
                _currentIndex.value = currentIdx
            }
        } else if (unshuffledQueue.isNotEmpty()) {
            val restored = unshuffledQueue
            val currentTrack = _currentTrack.value
            val currentVid = currentTrack?.videoId
            val matchIdx = if (!currentVid.isNullOrBlank()) {
                restored.indexOfFirst { it.videoId == currentVid }
            } else -1

            if (matchIdx >= 0) {
                _queue.value = restored
                _currentIndex.value = matchIdx
            } else if (currentTrack != null) {
                _queue.value = listOf(currentTrack) + restored.filterNot { it.videoId == currentVid }
                _currentIndex.value = 0
            } else {
                _queue.value = restored
                _currentIndex.value = if (restored.isNotEmpty()) 0 else -1
            }
            unshuffledQueue = emptyList()
        }

        syncPartyQueuePreservingPosition()
        persistPlaybackStateNow()
    }

    private fun handleTrackEnded() {
        val current = _currentTrack.value ?: return
        val isPartyActive = _partySnapshot.value.isActive
        val isHost = _partySnapshot.value.isHost

        // Sleep Timer: "End of current track" pauses cleanly and clears timer
        if (_sleepTimerRemainingMs.value == SLEEP_TIMER_END_OF_TRACK) {
            cancelSleepTimer()
            exoPlayer?.pause()
            _isPlaying.value = false
            _isLoading.value = false
            persistPlaybackStateNow()
            if (isPartyActive && isHost && !isApplyingRemoteParty) {
                sendPartyAction(
                    PartyAction(
                        type = "play_pause",
                        isPlaying = false,
                        positionMs = _duration.value.coerceAtLeast(0L)
                    )
                )
            }
            return
        }

        // In a Party, only the Host drives natural track-end transitions to prevent split-brain
        if (isPartyActive && !isHost) {
            return
        }

        if (_repeatMode.value == "one") {
            val player = exoPlayer
            if (player != null) {
                player.seekTo(0L)
                player.play()
                _currentPosition.value = 0L
            } else {
                playSongInternal(
                    song = current,
                    startPositionMs = 0L,
                    autoPlay = true,
                    resetAutoplay = false
                )
            }
            if (isPartyActive && isHost) {
                sendPartyAction(PartyAction(type = "seek", positionMs = 0L))
            }
            return
        }

        val q = _queue.value
        val nextIdx = _currentIndex.value + 1

        if (nextIdx < q.size) {
            _currentIndex.value = nextIdx
            val nextSong = q[nextIdx]
            playSongInternal(
                song = nextSong,
                startPositionMs = 0L,
                autoPlay = true,
                resetAutoplay = false
            )
            ensureAutoplayPopulatedIfEmpty(nextSong.videoId)
            if (isPartyActive && isHost) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else if (_repeatMode.value == "all" && q.isNotEmpty()) {
            _currentIndex.value = 0
            val firstSong = q[0]
            playSongInternal(
                song = firstSong,
                startPositionMs = 0L,
                autoPlay = true,
                resetAutoplay = false
            )
            ensureAutoplayPopulatedIfEmpty(firstSong.videoId)
            if (isPartyActive && isHost) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else {
            val autoplayAllowed = settingsRepo?.settings?.value?.autoplay ?: true
            if (autoplayAllowed) {
                if (_autoplayQueue.value.isNotEmpty()) {
                    playAutoplayAtPreservingQueues(0, clearError = true)
                } else {
                    advanceIntoAutoplay(current, clearError = true)
                }
            } else {
                _isPlaying.value = false
                _isLoading.value = false
                persistPlaybackStateNow()
            }
        }
    }

    private fun advanceIntoAutoplay(current: SongItem, clearError: Boolean) {
        val isPartyActive = _partySnapshot.value.isActive
        val isHost = _partySnapshot.value.isHost
        if (isPartyActive && !isHost) return

        if (_autoplayQueue.value.isNotEmpty()) {
            playAutoplayAtPreservingQueues(0, clearError = clearError)
            return
        }

        val gen = autoplayGeneration
        autoplayAdvanceJob?.cancel()
        _isLoadingAutoplay.value = true
        autoplayAdvanceJob = coroutineScope.launch {
            val fetchedTracks = try {
                withContext(Dispatchers.IO) { musicRepo.getWatchPlaylist(current.videoId) }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (autoplayGeneration == gen) {
                        _isLoadingAutoplay.value = false
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay advance fetch error: ${e.message}")
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (autoplayGeneration != gen) return@withContext
                _isLoadingAutoplay.value = false
                if (_currentTrack.value?.videoId != current.videoId) return@withContext
                if (settingsRepo?.settings?.value?.autoplay == false) return@withContext
                if (_partySnapshot.value.isActive && !_partySnapshot.value.isHost) return@withContext

                val latestQueue = _queue.value
                val latestIdx = _currentIndex.value

                // If user appended to the queue while watch playlist was loading, advance into it
                if (latestIdx + 1 < latestQueue.size) {
                    val nextIdx = latestIdx + 1
                    _currentIndex.value = nextIdx
                    playSongInternal(
                        song = latestQueue[nextIdx],
                        startPositionMs = 0L,
                        autoPlay = true,
                        clearError = clearError,
                        resetAutoplay = false
                    )
                    if (_partySnapshot.value.isActive && _partySnapshot.value.isHost) {
                        sendPartyAction(PartyAction(type = "next"))
                    }
                    return@withContext
                }

                val queueIds = latestQueue.map { it.videoId }.toSet()
                val existingAutoplayIds = _autoplayQueue.value.map { it.videoId }.toSet()
                val ranked = filterAndRankAutoplayBatch(
                    rawTracks = fetchedTracks,
                    currentVideoId = current.videoId,
                    queueIds = queueIds,
                    existingAutoplayIds = existingAutoplayIds
                ).take(AUTOPLAY_TARGET)

                if (ranked.isNotEmpty()) {
                    _autoplayQueue.value = _autoplayQueue.value + ranked
                    playAutoplayAtPreservingQueues(0, clearError = clearError)
                } else {
                    _isPlaying.value = false
                    _isLoading.value = false
                }
            }
        }
    }

    // Party Management Methods
    suspend fun startParty(displayName: String): Result<PartySnapshot> = withContext(Dispatchers.IO) {
        try {
            val seed = PartySeedState(
                queue = _queue.value,
                currentIndex = _currentIndex.value,
                currentTrack = _currentTrack.value,
                isPlaying = _isPlaying.value,
                positionMs = _currentPosition.value,
                repeatMode = _repeatMode.value
            )
            val seedJson = json.encodeToString(seed)
            val raw = AuricleNative.nativePartyStart(displayName, seedJson)
            val snapshot = json.decodeFromString<PartySnapshot>(raw)
            _partySnapshot.value = snapshot
            Result.success(snapshot)
        } catch (e: Exception) {
            Log.e(TAG, "startParty error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun joinParty(inviteInput: String, displayName: String): Result<PartySnapshot> = withContext(Dispatchers.IO) {
        try {
            val raw = AuricleNative.nativePartyJoin(inviteInput.trim(), displayName)
            val snapshot = json.decodeFromString<PartySnapshot>(raw)
            _partySnapshot.value = snapshot
            withContext(Dispatchers.Main) {
                _isShuffle.value = false
                unshuffledQueue = emptyList()
                clearAutoplayState()
            }
            Result.success(snapshot)
        } catch (e: Exception) {
            Log.e(TAG, "joinParty error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun leaveParty(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AuricleNative.nativePartyLeave()
            _partySnapshot.value = PartySnapshot()
            withContext(Dispatchers.Main) {
                exoPlayer?.playbackParameters = PlaybackParameters(1.0f)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "leaveParty error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
