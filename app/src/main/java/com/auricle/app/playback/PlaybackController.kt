package com.auricle.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
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
import com.auricle.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlaybackController(
    private val context: Context,
    private val musicRepo: MusicRepository,
    private val libraryRepo: LibraryRepository,
    private val coroutineScope: CoroutineScope
) {
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

    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _repeatMode = MutableStateFlow("off") // "off", "all", "one"
    val repeatMode: StateFlow<String> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    // Party State
    private val _partySnapshot = MutableStateFlow(PartySnapshot())
    val partySnapshot: StateFlow<PartySnapshot> = _partySnapshot.asStateFlow()

    private var isApplyingRemoteParty = false
    private var lastLoadedVideoId: String? = null
    private var retriedErrorVideoId: String? = null
    private var streamResolveJob: Job? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null

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

    init {
        ensureMediaServiceConnected()
        startPartySyncLoop()
        startPositionTrackingLoop()
    }

    fun ensureMediaServiceConnected() {
        coroutineScope.launch(Dispatchers.Main) {
            if (mediaControllerFuture == null) {
                try {
                    val token = SessionToken(
                        context,
                        ComponentName(context, AuriclePlaybackService::class.java)
                    )
                    mediaControllerFuture = MediaController.Builder(context, token).buildAsync()
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
            playStream(pending.song, pending.streamResult, pending.startPositionMs, pending.autoPlay)
        }
    }

    fun detachPlayer() {
        this.exoPlayer = null
    }

    private fun setupPlayerListener(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // Keep local UI state in sync WITHOUT firing automatic remote party broadcast
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isLoading.value = true
                    }
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        _duration.value = player.duration.coerceAtLeast(0L)

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
                    return
                }
                retriedErrorVideoId = current.videoId
                val resumePos = player.currentPosition.coerceAtLeast(0L)

                // Switch immediately to NewPipeExtractor stream on HTTP 403 / source error
                coroutineScope.launch {
                    val npFallback = withContext(Dispatchers.IO) {
                        com.auricle.app.extractor.NewPipeFallbackExtractor.extractAudioStreamUrl(current.videoId)
                    }
                    if (npFallback != null) {
                        withContext(Dispatchers.Main) {
                            playStream(
                                current,
                                StreamResolutionResult(
                                    url = npFallback.first,
                                    userAgent = npFallback.second,
                                    isHls = npFallback.first.contains(".m3u8")
                                ),
                                resumePos
                            )
                        }
                    } else {
                        val fallback = musicRepo.resolveStream(current.videoId, forceRefresh = true)
                        fallback.onSuccess { streamRes ->
                            withContext(Dispatchers.Main) {
                                playStream(current, streamRes, resumePos)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun startPositionTrackingLoop() {
        coroutineScope.launch(Dispatchers.Main) {
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                        _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                        if (player.duration > 0) {
                            _duration.value = player.duration
                        }
                    }
                }
                delay(250)
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
                        val snapshot = json.decodeFromString<PartySnapshot>(snapRaw)
                        _partySnapshot.value = snapshot
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
            if (state.queue.isNotEmpty()) {
                _queue.value = state.queue
                _currentIndex.value = state.currentIndex.coerceIn(0, state.queue.size - 1)
            }
            _repeatMode.value = state.repeatMode

            val effectivePos = targetPos ?: state.positionMs
            val remoteTrack = state.currentTrack
            if (remoteTrack != null) {
                if (_currentTrack.value?.videoId != remoteTrack.videoId) {
                    playSongInternal(remoteTrack, effectivePos, autoPlay = state.isPlaying)
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
        if (isApplyingRemoteParty) return
        if (!_partySnapshot.value.isActive) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val actionJson = json.encodeToString(action)
                AuricleNative.nativePartySendAction(actionJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending party action: ${e.message}")
            }
        }
    }

    fun playTrack(song: SongItem, newQueue: List<SongItem> = listOf(song)) {
        val targetQueue = if (newQueue.none { it.videoId == song.videoId }) {
            listOf(song) + newQueue
        } else newQueue

        _queue.value = targetQueue
        val idx = targetQueue.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
        _currentIndex.value = idx

        playSongInternal(song, startPositionMs = 0L, autoPlay = true)

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

    private fun playSongInternal(song: SongItem, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        _currentTrack.value = song
        _isLoading.value = true
        lastLoadedVideoId = song.videoId
        retriedErrorVideoId = null
        libraryRepo.recordRecentlyPlayed(song)

        streamResolveJob?.cancel()
        streamResolveJob = coroutineScope.launch {
            val res = musicRepo.resolveStream(song.videoId)
            res.onSuccess { streamResult ->
                withContext(Dispatchers.Main) {
                    playStream(song, streamResult, startPositionMs, autoPlay)
                }
            }.onFailure { err ->
                Log.e(TAG, "Stream resolution error: ${err.message}")
                _isLoading.value = false
            }
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

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(Uri.parse(song.displayArtworkUrl))
            .build()

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
        val player = exoPlayer ?: return
        if (targetPlaying) {
            player.play()
        } else {
            player.pause()
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
        val player = exoPlayer ?: return
        setPlaying(!player.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo(positionMs)
        _currentPosition.value = positionMs

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "seek",
                    positionMs = positionMs
                )
            )
        }
    }

    fun skipNext() {
        val q = _queue.value
        if (q.isEmpty()) return

        val nextIdx = _currentIndex.value + 1
        if (nextIdx < q.size) {
            _currentIndex.value = nextIdx
            val nextSong = q[nextIdx]
            playSongInternal(nextSong, startPositionMs = 0L, autoPlay = true)

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                sendPartyAction(
                    PartyAction(
                        type = "next"
                    )
                )
            }
        } else {
            handleTrackEnded()
        }
    }

    fun skipPrevious() {
        val player = exoPlayer
        if (player != null && player.currentPosition > 3000) {
            seekTo(0L)
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return

        val prevIdx = (_currentIndex.value - 1).coerceAtLeast(0)
        _currentIndex.value = prevIdx
        val prevSong = q[prevIdx]
        playSongInternal(prevSong, startPositionMs = 0L, autoPlay = true)

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "prev"
                )
            )
        }
    }

    fun playQueueIndex(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        _currentIndex.value = index
        val song = q[index]
        playSongInternal(song, startPositionMs = 0L, autoPlay = true)

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
        val updated = _queue.value + song
        _queue.value = updated

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "add_to_queue",
                    track = song,
                    playNext = false
                )
            )
        }
    }

    fun playNext(song: SongItem) {
        val currentQ = _queue.value.toMutableList()
        val existingIndex = currentQ.indexOfFirst { it.videoId == song.videoId }

        if (existingIndex > _currentIndex.value) {
            // Already in upcoming queue: move it next
            val upcomingIdx = existingIndex - _currentIndex.value - 1
            currentQ.removeAt(existingIndex)
            val insertPos = _currentIndex.value + 1
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
            // Insert newly into queue right after current
            val insertPos = (_currentIndex.value + 1).coerceAtMost(currentQ.size)
            currentQ.add(insertPos, song)
            _queue.value = currentQ

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
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

    fun removeFromQueue(index: Int) {
        val currentQ = _queue.value.toMutableList()
        if (index in currentQ.indices) {
            val upcomingIdx = if (index > _currentIndex.value) index - _currentIndex.value - 1 else null
            currentQ.removeAt(index)
            _queue.value = currentQ
            if (index < _currentIndex.value) {
                _currentIndex.value = (_currentIndex.value - 1).coerceAtLeast(0)
            }

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty && upcomingIdx != null) {
                sendPartyAction(
                    PartyAction(
                        type = "remove_from_queue",
                        upcomingIndex = upcomingIdx
                    )
                )
            }
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
        if (newShuffle) {
            val q = _queue.value.toMutableList()
            val current = _currentTrack.value
            if (current != null) {
                q.remove(current)
                q.shuffle()
                _queue.value = listOf(current) + q
                _currentIndex.value = 0
            } else {
                q.shuffle()
                _queue.value = q
            }
        }

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "replace_queue",
                    tracks = _queue.value,
                    startIndex = _currentIndex.value,
                    isPlaying = _isPlaying.value
                )
            )
        }
    }

    private fun handleTrackEnded() {
        val current = _currentTrack.value ?: return
        val isPartyActive = _partySnapshot.value.isActive
        val isHost = _partySnapshot.value.isHost

        // In a Party, only the Host drives natural track-end transitions to prevent split-brain
        if (isPartyActive && !isHost) {
            return
        }

        if (_repeatMode.value == "one") {
            playSongInternal(current, startPositionMs = 0L, autoPlay = true)
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
            playSongInternal(nextSong, startPositionMs = 0L, autoPlay = true)
            if (isPartyActive && isHost) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else if (_repeatMode.value == "all" && q.isNotEmpty()) {
            _currentIndex.value = 0
            playSongInternal(q[0], startPositionMs = 0L, autoPlay = true)
            if (isPartyActive && isHost) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else {

            if (!isPartyActive || isHost) {
                coroutineScope.launch {
                    val related = musicRepo.getWatchPlaylist(current.videoId)
                    val existingIds = q.map { it.videoId }.toSet()
                    val freshTracks = related.filterNot { existingIds.contains(it.videoId) }
                    if (freshTracks.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val updatedQ = q + freshTracks
                            _queue.value = updatedQ
                            _currentIndex.value = nextIdx
                            val nextSong = updatedQ[nextIdx]
                            playSongInternal(nextSong, startPositionMs = 0L, autoPlay = true)

                            if (isPartyActive && isHost) {
                                sendPartyAction(
                                    PartyAction(
                                        type = "replace_queue",
                                        tracks = updatedQ,
                                        startIndex = nextIdx,
                                        isPlaying = true
                                    )
                                )
                            }
                        }
                    }
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
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "leaveParty error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
