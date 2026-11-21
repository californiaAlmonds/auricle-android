package com.auricle.app.ui.screens

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.data.SettingsRepository
import com.auricle.app.model.LyricsData
import com.auricle.app.model.SongItem
import com.auricle.app.model.ytimgFallbackUrls
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NowPlayingSheet(
    playbackController: PlaybackController,
    libraryRepo: LibraryRepository,
    musicRepo: MusicRepository,
    settingsRepo: SettingsRepository? = null,
    onDismiss: () -> Unit,
    onPartyClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit = {},
    onArtistClick: ((SongItem) -> Unit)? = null,
    artworkModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val currentTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()
    val isLoading by playbackController.isLoading.collectAsState()
    val currentPosition by playbackController.currentPosition.collectAsState()
    val duration by playbackController.duration.collectAsState()
    val queue by playbackController.queue.collectAsState()
    val currentIndex by playbackController.currentIndex.collectAsState()
    val repeatMode by playbackController.repeatMode.collectAsState()
    val isShuffle by playbackController.isShuffle.collectAsState()
    val partySnapshot by playbackController.partySnapshot.collectAsState()
    val playingFrom by playbackController.playingFrom.collectAsState()
    val autoplayCandidates by playbackController.autoplayCandidates.collectAsState()
    val isLoadingAutoplay by playbackController.isLoadingAutoplay.collectAsState()
    val sleepTimerRemainingMs by playbackController.sleepTimerRemainingMs.collectAsState()
    val libraryState by libraryRepo.state.collectAsState()
    val appSettings by settingsRepo?.settings?.collectAsState() ?: remember { mutableStateOf(null) }

    var mode by remember { mutableIntStateOf(0) } // 0 = Player, 1 = Up Next, 2 = Lyrics
    var lastSubMode by remember { mutableIntStateOf(1) }
    var lyricsLangMode by remember { mutableStateOf("original") }
    val lyricsCache = remember { mutableStateMapOf<String, LyricsData>() }
    var lyricsData by remember { mutableStateOf<LyricsData?>(null) }
    var isLoadingLyrics by remember { mutableStateOf(false) }
    var lyricsRetryTrigger by remember { mutableIntStateOf(0) }

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSaveQueueDialog by remember { mutableStateOf(false) }
    var saveQueuePlaylistName by remember { mutableStateOf("") }

    var isDraggingSlider by remember { mutableStateOf(false) }
    var draggingSliderValue by remember { mutableFloatStateOf(0f) }

    val track = currentTrack ?: return
    val isLiked = libraryState.likedSongs.any { it.videoId == track.videoId }
    val partyMemberCount = if (partySnapshot.participants.isNotEmpty()) {
        partySnapshot.participants.size
    } else {
        partySnapshot.participantCount.coerceAtLeast(1)
    }

    // Smooth artwork-tinted gradient state (400ms crossfade, no flicker while Palette computes)
    val defaultTop = Color(0xFF2A3446)
    val defaultMid = Color(0xFF161C26)
    val defaultBot = Color(0xFF0A0D12)
    val defaultHighlight = Color.Transparent
    var targetTopColor by remember { mutableStateOf(defaultTop) }
    var targetMidColor by remember { mutableStateOf(defaultMid) }
    var targetBottomColor by remember { mutableStateOf(defaultBot) }
    var targetRadialHighlight by remember { mutableStateOf(defaultHighlight) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(track.videoId) {
        lyricsData = lyricsCache["${track.videoId}|$lyricsLangMode"]
        val artUrl = track.displayArtworkUrl
        if (artUrl.isBlank()) {
            targetTopColor = defaultTop
            targetMidColor = defaultMid
            targetBottomColor = defaultBot
            targetRadialHighlight = defaultHighlight
            return@LaunchedEffect
        }
        var resolvedTop: Color? = null
        var resolvedMid: Color? = null
        var resolvedHighlight: Color? = null
        withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(artUrl)
                    .size(200)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val palette = Palette.from(bitmap).generate()
                        val vibrant = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                        if (vibrant != null) {
                            var c = Color(vibrant.rgb)
                            while (c.luminance() > 0.25f) {
                                c = c.copy(red = c.red * 0.9f, green = c.green * 0.9f, blue = c.blue * 0.9f)
                            }
                            resolvedTop = c
                            resolvedMid = c.copy(red = c.red * 0.45f, green = c.green * 0.45f, blue = c.blue * 0.45f)
                            resolvedHighlight = Color(vibrant.rgb).copy(alpha = 0.16f)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (resolvedTop != null && resolvedMid != null) {
            targetTopColor = resolvedTop!!
            targetMidColor = resolvedMid!!
            targetBottomColor = defaultBot
            targetRadialHighlight = resolvedHighlight ?: defaultHighlight
        } else {
            targetTopColor = defaultTop
            targetMidColor = defaultMid
            targetBottomColor = defaultBot
            targetRadialHighlight = defaultHighlight
        }
    }

    LaunchedEffect(track.videoId, lyricsLangMode, mode, lyricsRetryTrigger) {
        if (mode == 2) {
            val cacheKey = "${track.videoId}|$lyricsLangMode"
            val cached = lyricsCache[cacheKey]
            if (cached != null) {
                lyricsData = cached
                isLoadingLyrics = false
            } else {
                isLoadingLyrics = true
                val fetched = musicRepo.getLyricsSynced(track, lyricsLangMode)
                if (fetched.ok && fetched.error.isBlank()) {
                    lyricsCache[cacheKey] = fetched
                }
                lyricsData = fetched
                isLoadingLyrics = false
            }
        }
    }

    val reducedMotion = AuricleTheme.reducedMotion
    val animSpec = if (reducedMotion) snap<Color>() else tween(400)
    val animTop by animateColorAsState(targetTopColor, animationSpec = animSpec, label = "npTop")
    val animMid by animateColorAsState(targetMidColor, animationSpec = animSpec, label = "npMid")
    val animBottom by animateColorAsState(targetBottomColor, animationSpec = animSpec, label = "npBot")
    val animRadial by animateColorAsState(targetRadialHighlight, animationSpec = animSpec, label = "npRadial")

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 110.dp.toPx() }
    val queueRowHeightPx = with(density) { 56.dp.toPx() }
    val queueListState = rememberLazyListState()

    var backProgress by remember { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animatedDragOffsetY by animateFloatAsState(
        targetValue = dragOffsetY,
        animationSpec = if (isDismissDragging || reducedMotion) snap() else tween(220, easing = FastOutSlowInEasing),
        label = "npDismissDrag"
    )
    PredictiveBackHandler { progress ->
        try {
            progress.collect { event ->
                backProgress = if (mode == 0) event.progress else 0f
            }
            if (mode != 0) {
                backProgress = 0f
                mode = 0
            } else {
                onDismiss()
                coroutineScope.launch {
                    delay(350)
                    backProgress = 0f
                }
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }

    val haptic = LocalHapticFeedback.current

    val totalOffsetY = (backProgress * 180f) + animatedDragOffsetY
    val totalScale = (1f - backProgress * 0.06f).coerceIn(0.8f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .draggable(
                state = rememberDraggableState { delta ->
                    if (mode == 0 && (delta > 0 || dragOffsetY > 0)) {
                        dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                    }
                },
                orientation = Orientation.Vertical,
                onDragStarted = { isDismissDragging = true },
                onDragStopped = {
                    isDismissDragging = false
                    if (dragOffsetY > dismissThresholdPx) {
                        onDismiss()
                        coroutineScope.launch {
                            delay(350)
                            dragOffsetY = 0f
                        }
                    } else {
                        dragOffsetY = 0f
                    }
                }
            )
            .offset { IntOffset(0, totalOffsetY.roundToInt()) }
            .scale(totalScale)
            .background(
                Brush.verticalGradient(
                    0f to animTop,
                    0.42f to animMid,
                    1f to animBottom
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(animRadial, Color.Transparent),
                        radius = 800f
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Grabber Handle (36 x 5 dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }

            AnimatedContent(
                targetState = mode == 0,
                label = "np_mode_switch",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else if (!targetState) {
                        (slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(220))) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(240)) { -it / 12 })
                    } else {
                        (slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 10 } + fadeIn(tween(220))) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(260)) { it / 5 })
                    }
                }
            ) { isPlayerMode ->
                if (isPlayerMode) {
                    // Mode 0: Full Player
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .auriclePressClickable(onClick = onDismiss)
                                    .testTag("DismissNowPlaying")
                                    .semantics { contentDescription = "Collapse player" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.ChevronDown,
                                    contentDescription = "Collapse player",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    "PLAYING FROM",
                                    style = AuricleTheme.typography.overline,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                                Text(
                                    playingFrom,
                                    style = AuricleTheme.typography.subSB,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .auriclePressClickable(onClick = { onSongOptionsClick(track) })
                                    .semantics { contentDescription = "Track options" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.More,
                                    contentDescription = "Track options",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Big Artwork (352 dp, 16 dp radius, soft scale when paused, HD + ytimg fallback)
                        val artScale by animateFloatAsState(
                            targetValue = if (isPlaying || reducedMotion) 1.0f else 0.94f,
                            animationSpec = if (reducedMotion) snap() else tween(260),
                            label = "artScale"
                        )
                        val highResCandidates = remember(track.videoId, track.artworkUrl, track.thumbnailUrl) {
                            buildList {
                                val primary = track.highResArtworkUrl
                                if (primary.isNotBlank()) add(primary)
                                if (primary.contains("i.ytimg.com", ignoreCase = true) ||
                                    primary.contains("img.youtube.com", ignoreCase = true) ||
                                    primary.isBlank()
                                ) {
                                    ytimgFallbackUrls(track.videoId, preferMaxRes = true).forEach { url ->
                                        if (url.isNotBlank() && url !in this) add(url)
                                    }
                                } else {
                                    val cardFallback = track.displayArtworkUrl
                                    if (cardFallback.isNotBlank() && cardFallback !in this) add(cardFallback)
                                    ytimgFallbackUrls(track.videoId, preferMaxRes = true).forEach { url ->
                                        if (url.isNotBlank() && url !in this) add(url)
                                    }
                                }
                            }
                        }
                        var artFallbackIdx by remember(track.videoId, track.artworkUrl, track.thumbnailUrl) {
                            mutableIntStateOf(0)
                        }
                        val activeBigArtUrl = highResCandidates.getOrNull(artFallbackIdx)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = artworkModifier
                                    .fillMaxWidth()
                                    .widthIn(max = 352.dp)
                                    .aspectRatio(1f)
                                    .scale(artScale)
                                    .shadow(18.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF1A1F2B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.Music,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.25f),
                                    modifier = Modifier.size(64.dp)
                                )
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(activeBigArtUrl)
                                        .crossfade(true)
                                        .build(),
                                    onError = {
                                        if (artFallbackIdx + 1 < highResCandidates.size) {
                                            artFallbackIdx++
                                        }
                                    },
                                    contentDescription = "Album artwork for ${track.title}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(26.dp))

                        // Title + Artist + Sleep Timer + Like Button
                        val sleepLabel = formatSleepTimerLabel(sleepTimerRemainingMs)
                        val isSleepActive = sleepLabel != null
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = AuricleTheme.typography.title1,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (reducedMotion) Modifier else Modifier.basicMarquee()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = if (onArtistClick != null) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onArtistClick(track) }
                                            .semantics { contentDescription = "Go to artist ${track.artist}" }
                                    } else {
                                        Modifier
                                    }
                                ) {
                                    Text(
                                        track.artist,
                                        style = AuricleTheme.typography.headline.copy(fontWeight = FontWeight.Medium),
                                        color = Color.White.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = if (reducedMotion) Modifier else Modifier.basicMarquee()
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Sleep Timer Pill / Circle Button
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .widthIn(min = 44.dp)
                                    .auriclePressClickable(onClick = { showSleepTimerDialog = true })
                                    .testTag("SleepTimerButton")
                                    .semantics {
                                        contentDescription = if (isSleepActive) {
                                            "Sleep timer active: $sleepLabel"
                                        } else {
                                            "Sleep timer"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(
                                            if (isSleepActive) Color.White.copy(alpha = 0.22f)
                                            else Color.White.copy(alpha = 0.12f)
                                        )
                                        .then(
                                            if (isSleepActive) {
                                                Modifier.border(
                                                    1.dp,
                                                    Color(0xFF62C97E).copy(alpha = 0.85f),
                                                    RoundedCornerShape(18.dp)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .padding(horizontal = if (isSleepActive) 10.dp else 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        AuricleIcons.Timer,
                                        contentDescription = null,
                                        tint = if (isSleepActive) Color(0xFF62C97E) else Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    if (sleepLabel != null) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            sleepLabel,
                                            style = AuricleTheme.typography.captionSB,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .auriclePressClickable(onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        libraryRepo.toggleLike(track)
                                    })
                                    .testTag("NowPlayingLike")
                                    .semantics { contentDescription = if (isLiked) "Unlike song" else "Like song" },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isLiked) AuricleIcons.HeartFill else AuricleIcons.Heart,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Sleek 4 dp Scrubber + 14 dp Circular Knob (matches Figma spec)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        ) {
                            val activeProgress = if (isDraggingSlider) draggingSliderValue else {
                                if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                            }
                            var trackWidthPx by remember { mutableFloatStateOf(1f) }
                            val thumbSize = if (isDraggingSlider) 18.dp else 14.dp

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .onSizeChanged { sz -> trackWidthPx = sz.width.coerceAtLeast(1).toFloat() }
                                    .pointerInput(duration) {
                                        detectTapGestures { offset ->
                                            if (duration > 0) {
                                                val frac = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                                playbackController.seekTo((frac * duration).toLong())
                                            }
                                        }
                                    }
                                    .pointerInput(duration) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { offset ->
                                                isDraggingSlider = true
                                                draggingSliderValue = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                            },
                                            onHorizontalDrag = { change, _ ->
                                                change.consume()
                                                draggingSliderValue = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                                            },
                                            onDragEnd = {
                                                if (duration > 0) {
                                                    playbackController.seekTo((draggingSliderValue * duration).toLong())
                                                }
                                                isDraggingSlider = false
                                            },
                                            onDragCancel = {
                                                isDraggingSlider = false
                                            }
                                        )
                                    }
                                    .testTag("SeekBar")
                                    .semantics { contentDescription = "Playback seek bar" },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.20f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(activeProgress)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White.copy(alpha = 0.92f))
                                    )
                                }
                                val thumbRadiusPx = with(density) { (thumbSize / 2).toPx() }
                                val thumbOffsetX = ((activeProgress * trackWidthPx) - thumbRadiusPx)
                                    .coerceIn(0f, (trackWidthPx - thumbRadiusPx * 2f).coerceAtLeast(0f))
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(thumbOffsetX.roundToInt(), 0) }
                                        .size(thumbSize)
                                        .shadow(4.dp, CircleShape)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val displayPos = if (isDraggingSlider) (draggingSliderValue * duration).toLong() else currentPosition
                                Text(
                                    formatTimeMs(displayPos),
                                    style = AuricleTheme.typography.caption,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                                Text(
                                    "-${formatTimeMs((duration - displayPos).coerceAtLeast(0L))}",
                                    style = AuricleTheme.typography.caption,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Transport Row: Shuffle · Prev · Play/Pause · Next · Repeat
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .auriclePressClickable { playbackController.toggleShuffle() }
                                    .testTag("Shuffle")
                                    .semantics {
                                        contentDescription = if (isShuffle) "Shuffle on" else "Shuffle off"
                                        selected = isShuffle
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(if (isShuffle) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                        .then(
                                            if (isShuffle) {
                                                Modifier.border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            AuricleIcons.Shuffle,
                                            contentDescription = null,
                                            tint = if (isShuffle) Color.White else Color.White.copy(alpha = 0.55f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        if (isShuffle) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF62C97E))
                                            )
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .auriclePressClickable { playbackController.skipPrevious() }
                                    .testTag("Previous")
                                    .semantics { contentDescription = "Previous track" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.Prev,
                                    contentDescription = "Previous track",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .auriclePressClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playbackController.togglePlayPause()
                                    }
                                    .testTag(if (isPlaying) "Pause" else "Play")
                                    .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(44.dp)
                                    )
                                } else {
                                    Icon(
                                        if (isPlaying) AuricleIcons.Pause else AuricleIcons.Play,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .auriclePressClickable { playbackController.skipNext() }
                                    .testTag("Next")
                                    .semantics { contentDescription = "Next track" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.Next,
                                    contentDescription = "Next track",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            val isRepeatActive = repeatMode != "off"
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .auriclePressClickable { playbackController.toggleRepeat() }
                                    .testTag("Repeat")
                                    .semantics {
                                        contentDescription = when (repeatMode) {
                                            "one" -> "Repeat one"
                                            "all" -> "Repeat all"
                                            else -> "Repeat off"
                                        }
                                        selected = isRepeatActive
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(if (isRepeatActive) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                        .then(
                                            if (isRepeatActive) {
                                                Modifier.border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            if (repeatMode == "one") AuricleIcons.RepeatOne else AuricleIcons.Repeat,
                                            contentDescription = null,
                                            tint = if (isRepeatActive) Color.White else Color.White.copy(alpha = 0.55f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        if (isRepeatActive) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF62C97E))
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Party Status Chip (when Party is active)
                        if (partySnapshot.isActive) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Row(
                                    modifier = Modifier
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White.copy(alpha = 0.12f))
                                        .clickable { onPartyClick() }
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF62C97E))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Listening Party · $partyMemberCount in sync",
                                        style = AuricleTheme.typography.captionSB,
                                        color = Color.White.copy(alpha = 0.90f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                } else {
                    // Mode 1 (Up Next) & Mode 2 (Lyrics): Stationary Compact Header & Transport Bar
                    val effectiveSubMode = if (mode == 1 || mode == 2) mode else lastSubMode
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Compact Header (NO shared-element artworkModifier here so AnimatedContent never collides)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1A1F2B))
                                    .clickable { mode = 0 },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.Music,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.30f),
                                    modifier = Modifier.size(22.dp)
                                )
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(track.displayArtworkUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Return to full player",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { mode = 0 }
                            ) {
                                Text(
                                    track.title,
                                    style = AuricleTheme.typography.headline,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    track.artist,
                                    style = AuricleTheme.typography.sub,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .auriclePressClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        libraryRepo.toggleLike(track)
                                    }
                                    .semantics { contentDescription = if (isLiked) "Unlike song" else "Like song" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLiked) AuricleIcons.HeartFill else AuricleIcons.Heart,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .auriclePressClickable { onSongOptionsClick(track) }
                                    .semantics { contentDescription = "Track options" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AuricleIcons.More,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        AnimatedContent(
                            targetState = effectiveSubMode,
                            label = "np_submode_switch",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            transitionSpec = {
                                if (reducedMotion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else if (targetState > initialState) {
                                    (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(200))) togetherWith
                                        (fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { -it / 5 })
                                } else {
                                    (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                                        (fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { it / 5 })
                                }
                            }
                        ) { activeSubMode ->
                            if (activeSubMode == 1) {
                                // Up Next Panel
                                val autoplay = appSettings?.autoplay ?: true
                                val upcoming = queue.drop(currentIndex + 1)
                                val keyedUpcoming = remember(upcoming) {
                                    val counts = HashMap<String, Int>(upcoming.size)
                                    upcoming.map { item ->
                                        val count = (counts[item.videoId] ?: 0) + 1
                                        counts[item.videoId] = count
                                        "upcoming_${item.videoId}_$count" to item
                                    }
                                }
                                val keyedAutoplay = remember(autoplayCandidates) {
                                    val counts = HashMap<String, Int>(autoplayCandidates.size)
                                    autoplayCandidates.map { item ->
                                        val count = (counts[item.videoId] ?: 0) + 1
                                        counts[item.videoId] = count
                                        "autoplay_${item.videoId}_$count" to item
                                    }
                                }

                                var draggedKey by remember { mutableStateOf<String?>(null) }
                                var draggedUpcomingIdx by remember { mutableStateOf<Int?>(null) }
                                var queueDragOffsetY by remember { mutableFloatStateOf(0f) }
                                var queueDragDidMove by remember { mutableStateOf(false) }

                                val latestUpcomingSize by rememberUpdatedState(keyedUpcoming.size)
                                val latestCurrentIndex by rememberUpdatedState(currentIndex)

                                fun performDragSwaps(itemKey: String) {
                                    var curUpIdx = draggedUpcomingIdx ?: return
                                    val maxUpIdx = latestUpcomingSize - 1
                                    if (maxUpIdx <= 0) return
                                    val draggedInfo = queueListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
                                    val stepHeightPx = (draggedInfo?.size?.toFloat() ?: queueRowHeightPx).coerceAtLeast(1f)
                                    val swapThresholdPx = stepHeightPx * 0.55f

                                    while (queueDragOffsetY > swapThresholdPx && curUpIdx < maxUpIdx) {
                                        val fromActual = latestCurrentIndex + 1 + curUpIdx
                                        val toActual = fromActual + 1
                                        playbackController.moveQueueItem(fromActual, toActual, syncParty = false)
                                        curUpIdx += 1
                                        draggedUpcomingIdx = curUpIdx
                                        queueDragOffsetY -= stepHeightPx
                                        queueDragDidMove = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    while (queueDragOffsetY < -swapThresholdPx && curUpIdx > 0) {
                                        val fromActual = latestCurrentIndex + 1 + curUpIdx
                                        val toActual = fromActual - 1
                                        playbackController.moveQueueItem(fromActual, toActual, syncParty = false)
                                        curUpIdx -= 1
                                        draggedUpcomingIdx = curUpIdx
                                        queueDragOffsetY += stepHeightPx
                                        queueDragDidMove = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    if (curUpIdx == 0 && queueDragOffsetY < -stepHeightPx * 0.35f) {
                                        queueDragOffsetY = -stepHeightPx * 0.35f
                                    } else if (curUpIdx == maxUpIdx && queueDragOffsetY > stepHeightPx * 0.35f) {
                                        queueDragOffsetY = stepHeightPx * 0.35f
                                    }
                                }

                                LaunchedEffect(keyedUpcoming) {
                                    val active = draggedKey
                                    if (active != null && keyedUpcoming.none { it.first == active }) {
                                        draggedKey = null
                                        draggedUpcomingIdx = null
                                        queueDragOffsetY = 0f
                                        queueDragDidMove = false
                                    }
                                }

                                LaunchedEffect(draggedKey) {
                                    val activeKey = draggedKey ?: return@LaunchedEffect
                                    val edgeZonePx = with(density) { 56.dp.toPx() }
                                    val maxScrollStepPx = with(density) { 10.dp.toPx() }
                                    while (draggedKey == activeKey) {
                                        val layoutInfo = queueListState.layoutInfo
                                        val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeKey }
                                        if (draggedInfo != null) {
                                            val draggedTop = draggedInfo.offset + queueDragOffsetY
                                            val draggedBottom = draggedTop + draggedInfo.size
                                            val viewportStart = layoutInfo.viewportStartOffset
                                            val viewportEnd = layoutInfo.viewportEndOffset
                                            val curIdx = draggedUpcomingIdx ?: 0
                                            val scrollDelta = when {
                                                draggedBottom > viewportEnd - edgeZonePx && curIdx < latestUpcomingSize - 1 -> {
                                                    val factor = ((draggedBottom - (viewportEnd - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f)
                                                    maxScrollStepPx * factor
                                                }
                                                draggedTop < viewportStart + edgeZonePx && curIdx > 0 -> {
                                                    val factor = (((viewportStart + edgeZonePx) - draggedTop) / edgeZonePx).coerceIn(0f, 1f)
                                                    -maxScrollStepPx * factor
                                                }
                                                else -> 0f
                                            }
                                            if (scrollDelta != 0f) {
                                                val consumed = queueListState.scrollBy(scrollDelta)
                                                if (consumed != 0f) {
                                                    queueDragOffsetY += consumed
                                                    performDragSwaps(activeKey)
                                                }
                                            }
                                        }
                                        delay(16L)
                                    }
                                }

                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Pinned Top 3 Mode Toggle Buttons (Carbon-Approved Option B): Shuffle · Repeat · Autoplay
                                    val repeatLabel = when (repeatMode) {
                                        "one" -> "Repeat 1"
                                        "all" -> "Repeat All"
                                        else -> "Repeat"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        QueueModeButton(
                                            label = "Shuffle",
                                            icon = AuricleIcons.Shuffle,
                                            active = isShuffle,
                                            onClick = {
                                                playbackController.toggleShuffle()
                                                coroutineScope.launch {
                                                    queueListState.scrollToItem(0)
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("QueueShufflePill")
                                        )
                                        QueueModeButton(
                                            label = repeatLabel,
                                            icon = if (repeatMode == "one") AuricleIcons.RepeatOne else AuricleIcons.Repeat,
                                            active = repeatMode != "off",
                                            onClick = { playbackController.toggleRepeat() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("QueueRepeatPill")
                                        )
                                        QueueModeButton(
                                            label = "Autoplay",
                                            icon = AuricleIcons.Infinity,
                                            active = autoplay,
                                            onClick = { settingsRepo?.updateAutoplay(!autoplay) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("QueueAutoplayPill")
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    "Playing Next",
                                                    style = AuricleTheme.typography.headline,
                                                    color = Color.White
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(
                                                            if (upcoming.isNotEmpty()) Color.White.copy(alpha = 0.16f)
                                                            else Color.White.copy(alpha = 0.10f)
                                                        )
                                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (upcoming.isNotEmpty()) "${upcoming.size}" else "Empty",
                                                        style = AuricleTheme.typography.captionSB,
                                                        color = if (upcoming.isNotEmpty()) {
                                                            Color.White.copy(alpha = 0.90f)
                                                        } else {
                                                            Color.White.copy(alpha = 0.62f)
                                                        },
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                            Text(
                                                when {
                                                    partySnapshot.isActive -> "Shared Party Queue · Synced"
                                                    upcoming.isEmpty() -> "Your manual queue"
                                                    playingFrom.startsWith("From ", ignoreCase = true) -> playingFrom
                                                    else -> "From $playingFrom"
                                                },
                                                style = AuricleTheme.typography.caption,
                                                color = Color.White.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (queue.isNotEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            saveQueuePlaylistName = ""
                                                            showSaveQueueDialog = true
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                                        .testTag("SaveQueueButton"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        "Save",
                                                        style = AuricleTheme.typography.subSB,
                                                        color = Color.White.copy(alpha = 0.85f)
                                                    )
                                                }
                                            }
                                            if (upcoming.isNotEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { playbackController.clearUpcomingQueue() }
                                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                                        .testTag("ClearQueueButton"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        "Clear",
                                                        style = AuricleTheme.typography.subSB,
                                                        color = Color.White.copy(alpha = 0.72f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    val showAutoplaySection = !(partySnapshot.isActive && !partySnapshot.isHost)
                                    LazyColumn(
                                        state = queueListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                    ) {
                                        // Static top anchor prevents Compose LazyColumn from following row-0's key down on Shuffle or drag-reorder.
                                        item(key = "queue_top_anchor") {
                                            Spacer(modifier = Modifier.height(0.dp))
                                        }
                                        if (keyedUpcoming.isNotEmpty()) {
                                            itemsIndexed(
                                                items = keyedUpcoming,
                                                key = { _, entry -> entry.first }
                                            ) { index, (itemKey, song) ->
                                                val actualIdx = currentIndex + 1 + index
                                                val latestUpcomingIdx by rememberUpdatedState(index)
                                                val isDragged = draggedKey == itemKey

                                                OnArtQueueRow(
                                                    song = song,
                                                    onClick = { playbackController.playQueueIndex(actualIdx) },
                                                    onRemove = { playbackController.removeFromQueue(actualIdx) },
                                                    onLongClick = { onSongOptionsClick(song) },
                                                    isAutoplay = false,
                                                    isDragging = isDragged,
                                                    modifier = Modifier
                                                        .then(if (!isDragged && !reducedMotion) Modifier.animateItem() else Modifier)
                                                        .zIndex(if (isDragged) 2f else 0f)
                                                        .graphicsLayer {
                                                            translationY = if (isDragged) queueDragOffsetY else 0f
                                                            scaleX = if (isDragged) 1.025f else 1f
                                                            scaleY = if (isDragged) 1.025f else 1f
                                                            shadowElevation = if (isDragged) 14f else 0f
                                                            shape = RoundedCornerShape(12.dp)
                                                        },
                                                    dragHandleModifier = Modifier.pointerInput(itemKey) {
                                                        detectDragGestures(
                                                            onDragStart = {
                                                                draggedKey = itemKey
                                                                draggedUpcomingIdx = latestUpcomingIdx
                                                                queueDragOffsetY = 0f
                                                                queueDragDidMove = false
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                if (draggedKey == itemKey) {
                                                                    queueDragOffsetY += dragAmount.y
                                                                    performDragSwaps(itemKey)
                                                                }
                                                            },
                                                            onDragEnd = {
                                                                if (draggedKey == itemKey) {
                                                                    if (queueDragDidMove) {
                                                                        playbackController.syncPartyQueuePreservingPosition()
                                                                    }
                                                                    draggedKey = null
                                                                    draggedUpcomingIdx = null
                                                                    queueDragOffsetY = 0f
                                                                    queueDragDidMove = false
                                                                }
                                                            },
                                                            onDragCancel = {
                                                                if (draggedKey == itemKey) {
                                                                    if (queueDragDidMove) {
                                                                        playbackController.syncPartyQueuePreservingPosition()
                                                                    }
                                                                    draggedKey = null
                                                                    draggedUpcomingIdx = null
                                                                    queueDragOffsetY = 0f
                                                                    queueDragDidMove = false
                                                                }
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        } else {
                                            item(key = "user_queue_empty") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 20.dp, vertical = 6.dp)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(Color.White.copy(alpha = 0.08f))
                                                        .border(
                                                            width = 1.dp,
                                                            color = Color.White.copy(alpha = 0.14f),
                                                            shape = RoundedCornerShape(14.dp)
                                                        )
                                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                                        .testTag("EmptyUserQueueCard"),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(34.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(Color.White.copy(alpha = 0.10f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = AuricleIcons.Queue,
                                                            contentDescription = null,
                                                            tint = Color.White.copy(alpha = 0.72f),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "Your queue is empty",
                                                            style = AuricleTheme.typography.subSB,
                                                            color = Color.White.copy(alpha = 0.92f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "Use Play Next or Add to Queue, or play an album or playlist.",
                                                            style = AuricleTheme.typography.caption,
                                                            color = Color.White.copy(alpha = 0.65f),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (showAutoplaySection) {
                                            item(key = "autoplay_section_header") {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 20.dp)
                                                        .padding(top = 12.dp, bottom = 8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 14.dp)
                                                            .height(1.dp)
                                                            .background(Color.White.copy(alpha = 0.14f))
                                                    )
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                AuricleIcons.Infinity,
                                                                contentDescription = null,
                                                                tint = Color.White.copy(alpha = 0.80f),
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                "Autoplay · Similar songs",
                                                                style = AuricleTheme.typography.subSB,
                                                                color = Color.White.copy(alpha = 0.90f)
                                                            )
                                                        }
                                                        if (autoplay && isLoadingAutoplay) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                CircularProgressIndicator(
                                                                    color = Color.White.copy(alpha = 0.72f),
                                                                    strokeWidth = 1.5.dp,
                                                                    modifier = Modifier.size(12.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    "Loading…",
                                                                    style = AuricleTheme.typography.caption,
                                                                    color = Color.White.copy(alpha = 0.65f)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Text(
                                                        text = if (autoplay) {
                                                            "Plays automatically after your queue"
                                                        } else {
                                                            "Autoplay is off — tap Autoplay above to keep music playing"
                                                        },
                                                        style = AuricleTheme.typography.caption,
                                                        color = Color.White.copy(alpha = 0.58f)
                                                    )
                                                }
                                            }
                                        }

                                        if (showAutoplaySection && autoplay && keyedAutoplay.isNotEmpty()) {
                                            itemsIndexed(
                                                items = keyedAutoplay,
                                                key = { _, entry -> entry.first }
                                            ) { idx, (_, candidate) ->
                                                OnArtQueueRow(
                                                    song = candidate,
                                                    alpha = 0.92f,
                                                    showDragHandle = false,
                                                    isAutoplay = true,
                                                    onClick = {
                                                        playbackController.playAutoplayAtPreservingQueues(idx)
                                                    },
                                                    onRemove = {
                                                        playbackController.removeFromAutoplay(idx)
                                                    },
                                                    onLongClick = { onSongOptionsClick(candidate) },
                                                    modifier = if (!reducedMotion) Modifier.animateItem() else Modifier
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Lyrics Panel
                                val currentLyrics = lyricsData
                                val lines = currentLyrics?.lines ?: emptyList()
                                val isSynced = remember(currentLyrics, lines) {
                                    currentLyrics?.isSynced == true || lines.count { !it.isSpacer && it.timeMs > 0L } >= 2
                                }
                                val hasError = currentLyrics != null && (!currentLyrics.ok || currentLyrics.error.isNotBlank())
                                val lyricsHeaderLabel = when {
                                    lyricsLangMode == "english" && currentLyrics?.hasRomanization == true ->
                                        if (isSynced) "Synced · Pronunciation & English" else "Pronunciation & English"
                                    lyricsLangMode == "english" ->
                                        if (isSynced) "Synced · English" else "English lyrics"
                                    isSynced -> "Synced lyrics"
                                    else -> "Lyrics"
                                }

                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            Icon(
                                                AuricleIcons.Translate,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.65f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                lyricsHeaderLabel,
                                                style = AuricleTheme.typography.caption,
                                                color = Color.White.copy(alpha = 0.72f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        AuricleSegmentedControl(
                                            options = listOf("Original", "English"),
                                            selectedIndex = if (lyricsLangMode == "english") 1 else 0,
                                            onSelect = { lyricsLangMode = if (it == 1) "english" else "original" },
                                            onArtwork = true,
                                            modifier = Modifier.width(164.dp)
                                        )
                                    }

                                    when {
                                        isLoadingLyrics -> {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
                                            }
                                        }
                                        hasError -> {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                AuricleStateCard(
                                                    icon = AuricleIcons.Lyrics,
                                                    title = "Couldn't load lyrics",
                                                    subtitle = currentLyrics?.error?.ifBlank { "Please check your connection and try again." }
                                                        ?: "Please check your connection and try again.",
                                                    actionLabel = "Retry",
                                                    onActionClick = {
                                                        lyricsCache.remove("${track.videoId}|$lyricsLangMode")
                                                        lyricsRetryTrigger++
                                                    },
                                                    onArtwork = true
                                                )
                                            }
                                        }
                                        lines.isEmpty() -> {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                AuricleStateCard(
                                                    icon = AuricleIcons.Lyrics,
                                                    title = "No lyrics found for this track",
                                                    subtitle = "Lyrics appear here when available for this track.",
                                                    onArtwork = true
                                                )
                                            }
                                        }
                                        else -> {
                                            val listState = rememberLazyListState()
                                            val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
                                            var userScrollingUntilMs by remember(track.videoId, lyricsLangMode) {
                                                mutableLongStateOf(0L)
                                            }
                                            var userHasScrolledAway by remember(track.videoId, lyricsLangMode) {
                                                mutableStateOf(false)
                                            }
                                            var viewportHeightPx by remember { mutableIntStateOf(0) }

                                            LaunchedEffect(track.videoId, lyricsLangMode) {
                                                userHasScrolledAway = false
                                                userScrollingUntilMs = 0L
                                                if (!isSynced) {
                                                    listState.scrollToItem(0)
                                                }
                                            }

                                            val activeIdx = if (isSynced) {
                                                lines.indexOfLast { !it.isSpacer && it.timeMs <= currentPosition }.coerceAtLeast(0)
                                            } else 0

                                            LaunchedEffect(isUserDragging, listState.isScrollInProgress, isSynced) {
                                                if (!isSynced) {
                                                    userHasScrolledAway = false
                                                    userScrollingUntilMs = 0L
                                                    return@LaunchedEffect
                                                }
                                                if (isUserDragging) {
                                                    userHasScrolledAway = true
                                                    userScrollingUntilMs = Long.MAX_VALUE
                                                } else if (userHasScrolledAway && !listState.isScrollInProgress) {
                                                    val resumeAt = System.currentTimeMillis() + 4000L
                                                    userScrollingUntilMs = resumeAt
                                                    delay(4000L)
                                                    if (userScrollingUntilMs == resumeAt && !listState.isScrollInProgress) {
                                                        userHasScrolledAway = false
                                                        userScrollingUntilMs = 0L
                                                    }
                                                }
                                            }

                                            LaunchedEffect(activeIdx, isSynced, userHasScrolledAway, viewportHeightPx, reducedMotion) {
                                                if (isSynced && !userHasScrolledAway && lines.isNotEmpty()) {
                                                    val viewportQuarterPx = (viewportHeightPx / 4).coerceAtLeast(0)
                                                    if (reducedMotion) {
                                                        listState.scrollToItem(activeIdx, scrollOffset = -viewportQuarterPx)
                                                    } else {
                                                        listState.animateScrollToItem(activeIdx, scrollOffset = -viewportQuarterPx)
                                                    }
                                                }
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .onSizeChanged { viewportHeightPx = it.height }
                                            ) {
                                                LazyColumn(
                                                    state = listState,
                                                    contentPadding = PaddingValues(top = 20.dp, bottom = 72.dp),
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    itemsIndexed(lines) { index, line ->
                                                        if (line.isSpacer) {
                                                            Spacer(modifier = Modifier.height(24.dp))
                                                        } else {
                                                            val dist = abs(index - activeIdx)
                                                            val alpha = when {
                                                                !isSynced -> 0.90f
                                                                dist == 0 -> 1.0f
                                                                dist == 1 -> 0.44f
                                                                else -> 0.26f
                                                            }
                                                            val blurMod = if (isSynced && dist >= 2 && Build.VERSION.SDK_INT >= 31 && !reducedMotion) {
                                                                Modifier.blur(1.2.dp)
                                                            } else Modifier

                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable(enabled = isSynced && !line.isSpacer) {
                                                                        userHasScrolledAway = false
                                                                        userScrollingUntilMs = 0L
                                                                        playbackController.seekTo(line.timeMs)
                                                                    }
                                                                    .padding(horizontal = 24.dp, vertical = 10.dp)
                                                            ) {
                                                                Text(
                                                                    line.primary,
                                                                    fontSize = if (dist == 0 && isSynced) 28.sp else 23.sp,
                                                                    lineHeight = if (dist == 0 && isSynced) 36.sp else 30.sp,
                                                                    fontWeight = if (dist == 0 && isSynced) FontWeight.Bold else FontWeight.SemiBold,
                                                                    color = Color.White.copy(alpha = alpha),
                                                                    modifier = blurMod
                                                                )
                                                                if (line.secondary.isNotBlank()) {
                                                                    Spacer(modifier = Modifier.height(4.dp))
                                                                    Text(
                                                                        line.secondary,
                                                                        fontSize = if (dist == 0 && isSynced) 18.sp else 15.sp,
                                                                        lineHeight = if (dist == 0 && isSynced) 24.sp else 20.sp,
                                                                        fontWeight = FontWeight.Medium,
                                                                        color = Color.White.copy(alpha = if (dist == 0 && isSynced) 0.72f else alpha * 0.78f),
                                                                        modifier = blurMod
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .fillMaxWidth()
                                                        .height(44.dp)
                                                        .background(
                                                            Brush.verticalGradient(
                                                                listOf(Color.Transparent, animBottom.copy(alpha = 0.85f))
                                                            )
                                                        )
                                                )
                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = isSynced && userHasScrolledAway,
                                                    enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(180)),
                                                    exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(180)),
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(end = 20.dp, bottom = 14.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .height(36.dp)
                                                            .auriclePressClickable {
                                                                userHasScrolledAway = false
                                                                userScrollingUntilMs = 0L
                                                                coroutineScope.launch {
                                                                    val viewportQuarterPx = (viewportHeightPx / 4).coerceAtLeast(0)
                                                                    if (reducedMotion) {
                                                                        listState.scrollToItem(activeIdx, scrollOffset = -viewportQuarterPx)
                                                                    } else {
                                                                        listState.animateScrollToItem(activeIdx, scrollOffset = -viewportQuarterPx)
                                                                    }
                                                                }
                                                            }
                                                            .clip(RoundedCornerShape(18.dp))
                                                            .background(Color.White.copy(alpha = 0.22f))
                                                            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                                                            .padding(horizontal = 14.dp)
                                                            .testTag("LyricsSyncPill"),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            AuricleIcons.Refresh,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            "Sync",
                                                            style = AuricleTheme.typography.captionSB,
                                                            color = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Compact Transport Bar (Modes 1 & 2)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            var compactTrackWidthPx by remember { mutableFloatStateOf(1f) }
                            val compactProgress = if (isDraggingSlider) draggingSliderValue else {
                                if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                            }
                            val compactDisplayPos = if (isDraggingSlider) (draggingSliderValue * duration).toLong() else currentPosition

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .height(24.dp)
                                    .onSizeChanged { sz -> compactTrackWidthPx = sz.width.coerceAtLeast(1).toFloat() }
                                    .pointerInput(duration) {
                                        detectTapGestures { offset ->
                                            if (duration > 0) {
                                                val frac = (offset.x / compactTrackWidthPx).coerceIn(0f, 1f)
                                                playbackController.seekTo((frac * duration).toLong())
                                            }
                                        }
                                    }
                                    .pointerInput(duration) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { offset ->
                                                isDraggingSlider = true
                                                draggingSliderValue = (offset.x / compactTrackWidthPx).coerceIn(0f, 1f)
                                            },
                                            onHorizontalDrag = { change, _ ->
                                                change.consume()
                                                draggingSliderValue = (change.position.x / compactTrackWidthPx).coerceIn(0f, 1f)
                                            },
                                            onDragEnd = {
                                                if (duration > 0) {
                                                    playbackController.seekTo((draggingSliderValue * duration).toLong())
                                                }
                                                isDraggingSlider = false
                                            },
                                            onDragCancel = {
                                                isDraggingSlider = false
                                            }
                                        )
                                    }
                                    .testTag("CompactSeekBar")
                                    .semantics { contentDescription = "Playback seek bar" },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (isDraggingSlider) 5.dp else 3.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(Color.White.copy(alpha = 0.20f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(compactProgress)
                                            .background(Color.White)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatTimeMs(compactDisplayPos), fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f))
                                Text("-${formatTimeMs((duration - compactDisplayPos).coerceAtLeast(0L))}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f))
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .auriclePressClickable { playbackController.skipPrevious() }
                                        .semantics { contentDescription = "Previous track" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(AuricleIcons.Prev, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                                Spacer(modifier = Modifier.width(20.dp))
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .auriclePressClickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            playbackController.togglePlayPause()
                                        }
                                        .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    } else {
                                        Icon(
                                            if (isPlaying) AuricleIcons.Pause else AuricleIcons.Play,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(20.dp))
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .auriclePressClickable { playbackController.skipNext() }
                                        .semantics { contentDescription = "Next track" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(AuricleIcons.Next, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Actions Row: Lyrics · Party · Queue (with micro labels & >=48dp touch area)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lyricsActive = mode == 2
                Column(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .heightIn(min = 50.dp)
                        .auriclePressClickable {
                            if (mode == 2) {
                                mode = 0
                            } else {
                                lastSubMode = 2
                                mode = 2
                            }
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (lyricsActive) Color.White.copy(alpha = 0.20f) else Color.Transparent)
                        .then(
                            if (lyricsActive) {
                                Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("Lyrics")
                        .semantics {
                            contentDescription = "Lyrics"
                            selected = lyricsActive
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        AuricleIcons.Lyrics,
                        contentDescription = null,
                        tint = if (lyricsActive) Color.White else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "Lyrics",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (lyricsActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (lyricsActive) Color.White else Color.White.copy(alpha = 0.72f)
                    )
                }

                val partyHighlight = partySnapshot.isActive && mode == 0
                Column(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .heightIn(min = 50.dp)
                        .auriclePressClickable { onPartyClick() }
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (partyHighlight) Color.White.copy(alpha = 0.20f) else Color.Transparent)
                        .then(
                            if (partyHighlight) {
                                Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("Party")
                        .semantics { contentDescription = "Listening Party" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AuricleIcons.Radio,
                            contentDescription = null,
                            tint = if (partySnapshot.isActive) Color.White else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(20.dp)
                        )
                        if (partySnapshot.isActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 5.dp, y = (-3).dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF62C97E))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "Party",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (partySnapshot.isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (partySnapshot.isActive) Color.White else Color.White.copy(alpha = 0.72f)
                    )
                }

                val queueActive = mode == 1
                Column(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .heightIn(min = 50.dp)
                        .auriclePressClickable {
                            if (mode == 1) {
                                mode = 0
                            } else {
                                lastSubMode = 1
                                mode = 1
                            }
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (queueActive) Color.White.copy(alpha = 0.20f) else Color.Transparent)
                        .then(
                            if (queueActive) {
                                Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("Up Next")
                        .semantics {
                            contentDescription = "Queue"
                            selected = queueActive
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        AuricleIcons.Queue,
                        contentDescription = null,
                        tint = if (queueActive) Color.White else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "Queue",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (queueActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (queueActive) Color.White else Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                remainingMs = sleepTimerRemainingMs,
                onSelectMinutes = { mins ->
                    playbackController.setSleepTimerMinutes(mins)
                    Toast.makeText(context, "Sleep timer set for $mins min", Toast.LENGTH_SHORT).show()
                    showSleepTimerDialog = false
                },
                onSelectEndOfTrack = {
                    playbackController.setSleepTimerEndOfTrack()
                    Toast.makeText(context, "Sleep timer: stop at end of track", Toast.LENGTH_SHORT).show()
                    showSleepTimerDialog = false
                },
                onCancelTimer = {
                    playbackController.cancelSleepTimer()
                    Toast.makeText(context, "Sleep timer turned off", Toast.LENGTH_SHORT).show()
                    showSleepTimerDialog = false
                },
                onDismiss = { showSleepTimerDialog = false }
            )
        }

        if (showSaveQueueDialog) {
            val tracksToSave = queue.drop(currentIndex.coerceAtLeast(0))
            AlertDialog(
                onDismissRequest = { showSaveQueueDialog = false },
                containerColor = AuricleTheme.colors.elevated,
                titleContentColor = AuricleTheme.colors.textPrimary,
                textContentColor = AuricleTheme.colors.textPrimary,
                title = {
                    Text(
                        "Save Queue as Playlist",
                        style = AuricleTheme.typography.headline,
                        color = AuricleTheme.colors.textPrimary
                    )
                },
                text = {
                    Column {
                        Text(
                            "Save ${tracksToSave.size} ${if (tracksToSave.size == 1) "track" else "tracks"} (current & upcoming) to a new playlist.",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = saveQueuePlaylistName,
                            onValueChange = { saveQueuePlaylistName = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    "Playlist name",
                                    color = AuricleTheme.colors.textMuted
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = saveQueuePlaylistName.trim().isNotEmpty() && tracksToSave.isNotEmpty(),
                        onClick = {
                            val name = saveQueuePlaylistName.trim()
                            if (name.isNotEmpty() && tracksToSave.isNotEmpty()) {
                                val created = libraryRepo.createPlaylist(name)
                                tracksToSave.forEach { song ->
                                    libraryRepo.addToPlaylist(created.id, song)
                                }
                                Toast.makeText(
                                    context,
                                    "Saved ${tracksToSave.size} tracks to \"$name\"",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showSaveQueueDialog = false
                            }
                        }
                    ) {
                        Text(
                            "Save",
                            color = if (saveQueuePlaylistName.trim().isNotEmpty() && tracksToSave.isNotEmpty()) {
                                AuricleTheme.colors.accentStrong
                            } else {
                                AuricleTheme.colors.textMuted
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveQueueDialog = false }) {
                        Text("Cancel", color = AuricleTheme.colors.textMuted)
                    }
                }
            )
        }
    }
}

@Composable
private fun QueueModeButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = AuricleTheme.reducedMotion
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.96f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(100),
        label = "queueModeScale"
    )
    val animatedContainerColor by animateColorAsState(
        targetValue = if (active) Color.White.copy(alpha = 0.27f) else Color.White.copy(alpha = 0.08f),
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "queueModeBg"
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = if (active) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "queueModeBorder"
    )
    val contentTint by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = 0.68f),
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "queueModeTint"
    )

    Box(
        modifier = modifier
            .scale(pressScale)
            .semantics {
                contentDescription = "$label: ${if (active) "On" else "Off"}"
                selected = active
            }
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = animatedContainerColor,
            border = BorderStroke(if (active) 1.5.dp else 1.dp, animatedBorderColor),
            interactionSource = interactionSource
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (active) 6.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (active) Color(0xFF62C97E) else Color.White.copy(alpha = 0.22f))
                )
                Spacer(modifier = Modifier.width(5.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    style = AuricleTheme.typography.captionSB.copy(
                        fontSize = 11.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(
    remainingMs: Long?,
    onSelectMinutes: (Int) -> Unit,
    onSelectEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val isOff = remainingMs == null
    val isEndOfTrack = remainingMs == PlaybackController.SLEEP_TIMER_END_OF_TRACK
    val activeCountdownLabel = when {
        isEndOfTrack -> "Stopping after current track"
        remainingMs != null && remainingMs > 0L -> "Stopping in ${formatTimeMs(remainingMs)}"
        else -> null
    }

    val options = listOf(
        Triple("Off", isOff, onCancelTimer),
        Triple("15 min", false) { onSelectMinutes(15) },
        Triple("30 min", false) { onSelectMinutes(30) },
        Triple("45 min", false) { onSelectMinutes(45) },
        Triple("60 min", false) { onSelectMinutes(60) },
        Triple("End of track", isEndOfTrack, onSelectEndOfTrack)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuricleTheme.colors.elevated,
        titleContentColor = AuricleTheme.colors.textPrimary,
        textContentColor = AuricleTheme.colors.textPrimary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AuricleIcons.Timer,
                    contentDescription = null,
                    tint = if (isOff) AuricleTheme.colors.textPrimary else Color(0xFF62C97E),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Sleep Timer",
                    style = AuricleTheme.typography.headline,
                    color = AuricleTheme.colors.textPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (activeCountdownLabel != null) {
                    Text(
                        activeCountdownLabel,
                        style = AuricleTheme.typography.captionSB,
                        color = Color(0xFF62C97E),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                options.forEach { (label, isSelected, action) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) AuricleTheme.colors.fillMed else Color.Transparent
                            )
                            .clickable(onClick = action)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = if (isSelected) AuricleTheme.typography.bodySB else AuricleTheme.typography.bodyM,
                            color = if (isSelected) Color(0xFF62C97E) else AuricleTheme.colors.textPrimary
                        )
                        if (isSelected) {
                            Icon(
                                AuricleIcons.Check,
                                contentDescription = "Selected",
                                tint = Color(0xFF62C97E),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AuricleTheme.colors.textMuted)
            }
        }
    )
}

private fun formatSleepTimerLabel(remainingMs: Long?): String? = when {
    remainingMs == null -> null
    remainingMs == PlaybackController.SLEEP_TIMER_END_OF_TRACK -> "Track end"
    remainingMs > 0L -> formatTimeMs(remainingMs)
    else -> null
}

private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
