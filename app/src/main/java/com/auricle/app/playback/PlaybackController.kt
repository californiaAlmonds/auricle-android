package com.auricle.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlaybackController(
    private val context: Context,
    private val musicRepo: MusicRepository,
    private val libraryRepo: LibraryRepository,
    private val settingsRepo: SettingsRepository? = null,
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

    private val _playingFrom = MutableStateFlow("Queue")
    val playingFrom: StateFlow<String> = _playingFrom.asStateFlow()

    private val _autoplayCandidates = MutableStateFlow<List<SongItem>>(emptyList())
    val autoplayCandidates: StateFlow<List<SongItem>> = _autoplayCandidates.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    // Party State
    private val _partySnapshot = MutableStateFlow(PartySnapshot())
    val partySnapshot: StateFlow<PartySnapshot> = _partySnapshot.asStateFlow()

    private var isApplyingRemoteParty = false
    private var lastLoadedVideoId: String? = null
    private var retriedErrorVideoId: String? = null
    private var consecutiveErrorCount: Int = 0
    private var unshuffledQueue: List<SongItem> = emptyList()
    private var streamResolveJob: Job? = null
    private var autoplayFetchJob: Job? = null
    private var autoplayAdvanceJob: Job? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val partyActionDispatcher = Dispatchers.IO.limitedParallelism(1)

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
        observeAutoplaySetting()
        startPartySyncLoop()
        startPositionTrackingLoop()
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    private fun observeAutoplaySetting() {
        val repo = settingsRepo ?: return
        coroutineScope.launch {
            repo.settings
                .map { it.autoplay }
                .distinctUntilChanged()
                .collect { autoplayEnabled ->
                    if (!autoplayEnabled) {
                        autoplayFetchJob?.cancel()
                        autoplayAdvanceJob?.cancel()
                        _autoplayCandidates.value = emptyList()
                    } else {
                        val current = _currentTrack.value
                        if (current != null && _autoplayCandidates.value.isEmpty()) {
                            fetchAutoplayCandidates(current.videoId)
                        }
                    }
                }
        }
    }

    private fun fetchAutoplayCandidates(videoId: String) {
        if (videoId.isBlank()) return
        if (settingsRepo?.settings?.value?.autoplay == false) {
            _autoplayCandidates.value = emptyList()
            return
        }
        autoplayFetchJob?.cancel()
        autoplayFetchJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val related = musicRepo.getWatchPlaylist(videoId)
                withContext(Dispatchers.Main) {
                    if (_currentTrack.value?.videoId != videoId) return@withContext
                    if (settingsRepo?.settings?.value?.autoplay == false) {
                        _autoplayCandidates.value = emptyList()
                        return@withContext
                    }
                    val existingIds = _queue.value.map { it.videoId }.toSet()
                    _autoplayCandidates.value = related.filterNot { existingIds.contains(it.videoId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay fetch error: ${e.message}")
            }
        }
    }

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
                            _duration.value = player.duration
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
                autoplayFetchJob?.cancel()
                autoplayAdvanceJob?.cancel()
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
                _autoplayCandidates.value = emptyList()
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
        resetShuffle: Boolean
    ) {
        autoplayAdvanceJob?.cancel()
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
    }

    private fun playSongInternal(
        song: SongItem,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        clearError: Boolean = true
    ) {
        autoplayAdvanceJob?.cancel()
        if (clearError) {
            _playbackError.value = null
        }
        // Pause immediately so the previous track stops sounding while the next stream resolves,
        // without calling stop()/clearMediaItems() which drops MediaSession to STATE_IDLE and
        // triggers an asynchronous Android 15 SystemUI MediaSessionRecord:stop race.
        exoPlayer?.pause()
        _currentTrack.value = song
        _isLoading.value = true
        _currentPosition.value = startPositionMs.coerceAtLeast(0L)
        _duration.value = if (song.durationSecs > 0) song.durationSecs * 1000L else 0L
        lastLoadedVideoId = song.videoId
        retriedErrorVideoId = null
        libraryRepo.recordRecentlyPlayed(song)

        autoplayFetchJob?.cancel()
        _autoplayCandidates.value = emptyList()
        if (settingsRepo?.settings?.value?.autoplay ?: true) {
            fetchAutoplayCandidates(song.videoId)
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
        val hasAutoplayNext = (settingsRepo?.settings?.value?.autoplay ?: true) && _autoplayCandidates.value.isNotEmpty()

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
        if (player == null) {
            if (targetPlaying) {
                val current = _currentTrack.value
                if (current != null) {
                    playSongInternal(current, startPositionMs = _currentPosition.value, autoPlay = true)
                }
            } else {
                _isPlaying.value = false
            }
            return
        }

        if (targetPlaying) {
            when (player.playbackState) {
                Player.STATE_ENDED -> {
                    player.seekTo(0L)
                    player.play()
                    _currentPosition.value = 0L
                }
                Player.STATE_IDLE -> {
                    val current = _currentTrack.value
                    if (current != null) {
                        playSongInternal(current, startPositionMs = _currentPosition.value, autoPlay = true)
                        return
                    }
                }
                else -> {
                    player.play()
                }
            }
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
        val player = exoPlayer
        if (player == null) {
            setPlaying(!_isPlaying.value)
            return
        }
        setPlaying(!player.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        if (positionMs == C.TIME_UNSET) return
        autoplayAdvanceJob?.cancel()
        val player = exoPlayer ?: return
        val knownDur = _duration.value.takeIf { it > 0L } ?: player.duration.takeIf { it > 0L }
        val clamped = if (knownDur != null) positionMs.coerceIn(0L, knownDur) else positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        _currentPosition.value = clamped

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
        val autoplayAllowed = settingsRepo?.settings?.value?.autoplay ?: true
        return autoplayAllowed && (_autoplayCandidates.value.isNotEmpty() || _currentTrack.value != null)
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
            playSongInternal(nextSong, startPositionMs = 0L, autoPlay = true, clearError = clearError)

            if (isPartyActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else if (_repeatMode.value == "all" && q.isNotEmpty()) {
            _currentIndex.value = 0
            playSongInternal(q[0], startPositionMs = 0L, autoPlay = true, clearError = clearError)

            if (isPartyActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "next"))
            }
        } else {
            val current = _currentTrack.value
            val autoplayAllowed = settingsRepo?.settings?.value?.autoplay ?: true
            if (current != null && (!isPartyActive || isHost) && autoplayAllowed) {
                advanceIntoAutoplay(current, clearError = clearError)
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
        if (q.isEmpty()) return

        val currentIdx = _currentIndex.value
        if (currentIdx > 0) {
            val prevIdx = currentIdx - 1
            _currentIndex.value = prevIdx
            playSongInternal(q[prevIdx], startPositionMs = 0L, autoPlay = true)

            if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
                sendPartyAction(PartyAction(type = "prev"))
            }
        } else if (_repeatMode.value == "all" && q.isNotEmpty()) {
            val lastIdx = q.lastIndex
            _currentIndex.value = lastIdx
            playSongInternal(q[lastIdx], startPositionMs = 0L, autoPlay = true)

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
        if (_currentTrack.value == null || _queue.value.isEmpty()) {
            playTrack(song, listOf(song), "Queue")
            return
        }
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
    }

    fun playNext(song: SongItem) {
        if (_currentTrack.value == null || _queue.value.isEmpty()) {
            playTrack(song, listOf(song), "Queue")
            return
        }
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
            autoplayFetchJob?.cancel()
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
            _autoplayCandidates.value = emptyList()
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
                playSongInternal(currentQ[nextIdx], startPositionMs = 0L, autoPlay = true)
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
            }
        }
    }

    fun playAutoplayCandidate(candidate: SongItem) {
        autoplayAdvanceJob?.cancel()
        val currentQ = _queue.value
        val existingIds = currentQ.map { it.videoId }.toSet()
        val remainingUnseen = _autoplayCandidates.value
            .filterNot { it.videoId == candidate.videoId || existingIds.contains(it.videoId) }

        val existingIndex = currentQ.indexOfFirst { it.videoId == candidate.videoId }
        val updatedQ: List<SongItem>
        val targetIndex: Int
        val appendedTracks: List<SongItem>

        if (existingIndex >= 0) {
            updatedQ = currentQ + remainingUnseen
            targetIndex = existingIndex
            appendedTracks = remainingUnseen
        } else {
            val toAppend = buildList {
                if (!existingIds.contains(candidate.videoId)) {
                    add(candidate)
                }
                addAll(remainingUnseen)
            }
            if (toAppend.isEmpty()) {
                return
            }
            updatedQ = currentQ + toAppend
            targetIndex = currentQ.size
            appendedTracks = toAppend
        }

        if (_isShuffle.value && appendedTracks.isNotEmpty()) {
            unshuffledQueue = unshuffledQueue + appendedTracks
        }

        consecutiveErrorCount = 0
        _queue.value = updatedQ
        _currentIndex.value = targetIndex
        val trackToPlay = updatedQ[targetIndex]
        playSongInternal(trackToPlay, startPositionMs = 0L, autoPlay = true)

        if (_partySnapshot.value.isActive && !isApplyingRemoteParty) {
            sendPartyAction(
                PartyAction(
                    type = "replace_queue",
                    tracks = updatedQ,
                    startIndex = targetIndex,
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
            val player = exoPlayer
            if (player != null) {
                player.seekTo(0L)
                player.play()
                _currentPosition.value = 0L
            } else {
                playSongInternal(current, startPositionMs = 0L, autoPlay = true)
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
            val autoplayAllowed = settingsRepo?.settings?.value?.autoplay ?: true
            if (autoplayAllowed) {
                advanceIntoAutoplay(current, clearError = true)
            } else {
                _isPlaying.value = false
                _isLoading.value = false
            }
        }
    }

    private fun advanceIntoAutoplay(current: SongItem, clearError: Boolean) {
        val isPartyActive = _partySnapshot.value.isActive
        val isHost = _partySnapshot.value.isHost

        autoplayAdvanceJob?.cancel()
        autoplayAdvanceJob = coroutineScope.launch {
            val cachedCandidates = _autoplayCandidates.value
            val fetchedTracks = if (cachedCandidates.isNotEmpty()) {
                cachedCandidates
            } else {
                try {
                    withContext(Dispatchers.IO) { musicRepo.getWatchPlaylist(current.videoId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }

            withContext(Dispatchers.Main) {
                if (_currentTrack.value?.videoId != current.videoId) return@withContext
                if (settingsRepo?.settings?.value?.autoplay == false) return@withContext

                val latestQueue = _queue.value
                val latestIdx = _currentIndex.value

                // If user appended to the queue while watch playlist was loading, advance into it
                if (latestIdx + 1 < latestQueue.size) {
                    val nextIdx = latestIdx + 1
                    _currentIndex.value = nextIdx
                    playSongInternal(latestQueue[nextIdx], startPositionMs = 0L, autoPlay = true, clearError = clearError)
                    if (isPartyActive && isHost) {
                        sendPartyAction(PartyAction(type = "next"))
                    }
                    return@withContext
                }

                val existingIds = latestQueue.map { it.videoId }.toSet()
                val freshTracks = fetchedTracks
                    .filterNot { existingIds.contains(it.videoId) }
                    .distinctBy { it.videoId }

                if (freshTracks.isNotEmpty()) {
                    val targetIdx = latestQueue.size
                    val updatedQ = latestQueue + freshTracks
                    _queue.value = updatedQ
                    if (_isShuffle.value) {
                        unshuffledQueue = unshuffledQueue + freshTracks
                    }
                    _currentIndex.value = targetIdx
                    val nextSong = updatedQ[targetIdx]
                    playSongInternal(nextSong, startPositionMs = 0L, autoPlay = true, clearError = clearError)

                    if (isPartyActive && isHost) {
                        sendPartyAction(
                            PartyAction(
                                type = "replace_queue",
                                tracks = updatedQ,
                                startIndex = targetIdx,
                                isPlaying = true
                            )
                        )
                    }
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
