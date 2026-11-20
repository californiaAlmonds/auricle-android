package com.auricle.app.ui.screens

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val libraryState by libraryRepo.state.collectAsState()
    val appSettings by settingsRepo?.settings?.collectAsState() ?: remember { mutableStateOf(null) }

    var mode by remember { mutableIntStateOf(0) } // 0 = Player, 1 = Up Next, 2 = Lyrics
    var lastSubMode by remember { mutableIntStateOf(1) }
    var lyricsLangMode by remember { mutableStateOf("original") }
    val lyricsCache = remember { mutableStateMapOf<String, LyricsData>() }
    var lyricsData by remember { mutableStateOf<LyricsData?>(null) }
    var isLoadingLyrics by remember { mutableStateOf(false) }
    var lyricsRetryTrigger by remember { mutableIntStateOf(0) }

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
                val fetched = musicRepo.getLyrics(track.videoId, lyricsLangMode)
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

                        // Big Artwork (352 dp, 16 dp radius, soft scale when paused)
                        val artScale by animateFloatAsState(
                            targetValue = if (isPlaying || reducedMotion) 1.0f else 0.94f,
                            animationSpec = if (reducedMotion) snap() else tween(260),
                            label = "artScale"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = track.displayArtworkUrl,
                                contentDescription = "Album artwork for ${track.title}",
                                contentScale = ContentScale.Crop,
                                modifier = artworkModifier
                                    .fillMaxWidth()
                                    .widthIn(max = 352.dp)
                                    .aspectRatio(1f)
                                    .scale(artScale)
                                    .shadow(18.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            )
                        }

                        Spacer(modifier = Modifier.height(26.dp))

                        // Title + Artist + Like Button
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
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    track.artist,
                                    style = AuricleTheme.typography.headline.copy(fontWeight = FontWeight.Medium),
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .size(48.dp)
                                    .auriclePressClickable { playbackController.toggleShuffle() }
                                    .testTag("Shuffle")
                                    .semantics { contentDescription = "Shuffle" },
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    AuricleIcons.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffle) Color.White else Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.size(22.dp)
                                )
                                if (isShuffle) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
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

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .size(48.dp)
                                    .auriclePressClickable { playbackController.toggleRepeat() }
                                    .testTag("Repeat")
                                    .semantics { contentDescription = "Repeat mode" },
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    if (repeatMode == "one") AuricleIcons.RepeatOne else AuricleIcons.Repeat,
                                    contentDescription = "Repeat mode",
                                    tint = if (repeatMode != "off") Color.White else Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.size(22.dp)
                                )
                                if (repeatMode != "off") {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
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
                            AsyncImage(
                                model = track.displayArtworkUrl,
                                contentDescription = "Return to full player",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { mode = 0 }
                            )
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
                                var draggingFromIdx by remember { mutableStateOf<Int?>(null) }
                                var dragAccumPx by remember { mutableFloatStateOf(0f) }
                                var dragDidMove by remember { mutableStateOf(false) }

                                Column(modifier = Modifier.fillMaxSize()) {
                                    // 3 Mode Toggle Pills: Shuffle · Repeat · Autoplay
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val pills = listOf(
                                            QueuePillSpec("Shuffle", AuricleIcons.Shuffle, isShuffle) { playbackController.toggleShuffle() },
                                            QueuePillSpec(
                                                if (repeatMode == "one") "Repeat 1" else "Repeat",
                                                if (repeatMode == "one") AuricleIcons.RepeatOne else AuricleIcons.Repeat,
                                                repeatMode != "off"
                                            ) { playbackController.toggleRepeat() },
                                            QueuePillSpec("Autoplay", AuricleIcons.Infinity, autoplay) { settingsRepo?.updateAutoplay(!autoplay) }
                                        )
                                        pills.forEach { pill ->
                                            val contentTint = if (pill.active) animMid else Color.White
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(44.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (pill.active) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.12f))
                                                    .auriclePressClickable { pill.onClick() }
                                                    .padding(horizontal = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    pill.icon,
                                                    contentDescription = null,
                                                    tint = contentTint,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    pill.label,
                                                    style = AuricleTheme.typography.subSB,
                                                    color = contentTint,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    val upcoming = queue.drop(currentIndex + 1)
                                    val stableKeys = remember(upcoming) {
                                        val counts = HashMap<String, Int>(upcoming.size)
                                        upcoming.map { item ->
                                            val count = (counts[item.videoId] ?: 0) + 1
                                            counts[item.videoId] = count
                                            "upcoming_${item.videoId}_$count"
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Playing Next",
                                                style = AuricleTheme.typography.headline,
                                                color = Color.White
                                            )
                                            Text(
                                                if (partySnapshot.isActive) {
                                                    "Shared Party Queue · Synced"
                                                } else if (playingFrom.startsWith("From ", ignoreCase = true)) {
                                                    playingFrom
                                                } else {
                                                    "From $playingFrom"
                                                },
                                                style = AuricleTheme.typography.caption,
                                                color = Color.White.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (upcoming.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { playbackController.clearUpcomingQueue() }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
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

                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        itemsIndexed(upcoming, key = { idx, _ -> stableKeys[idx] }) { index, song ->
                                            val actualIdx = currentIndex + 1 + index
                                            val latestActualIdx by rememberUpdatedState(actualIdx)
                                            val latestCurrentIndex by rememberUpdatedState(currentIndex)
                                            val latestQueueLastIndex by rememberUpdatedState(queue.lastIndex)
                                            val isBeingDragged = draggingFromIdx == actualIdx
                                            OnArtQueueRow(
                                                song = song,
                                                onClick = { playbackController.playQueueIndex(actualIdx) },
                                                onRemove = { playbackController.removeFromQueue(actualIdx) },
                                                onLongClick = { onSongOptionsClick(song) },
                                                modifier = if (isBeingDragged) {
                                                    Modifier.background(Color.White.copy(alpha = 0.10f))
                                                } else {
                                                    Modifier
                                                },
                                                dragHandleModifier = Modifier.draggable(
                                                    orientation = Orientation.Vertical,
                                                    state = rememberDraggableState { delta ->
                                                        val currentDragIdx = draggingFromIdx ?: latestActualIdx
                                                        draggingFromIdx = currentDragIdx
                                                        dragAccumPx += delta
                                                        val step = (dragAccumPx / queueRowHeightPx).toInt()
                                                        if (step != 0) {
                                                            val targetIdx = (currentDragIdx + step)
                                                                .coerceIn(latestCurrentIndex + 1, latestQueueLastIndex)
                                                            if (targetIdx != currentDragIdx) {
                                                                playbackController.moveQueueItem(currentDragIdx, targetIdx, syncParty = false)
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                draggingFromIdx = targetIdx
                                                                dragAccumPx -= step * queueRowHeightPx
                                                                dragDidMove = true
                                                            }
                                                        }
                                                    },
                                                    onDragStopped = {
                                                        if (dragDidMove) {
                                                            playbackController.syncPartyQueuePreservingPosition()
                                                        }
                                                        draggingFromIdx = null
                                                        dragAccumPx = 0f
                                                        dragDidMove = false
                                                    }
                                                )
                                            )
                                        }

                                        item(key = "autoplay_section_header") {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            AuricleIcons.Infinity,
                                                            contentDescription = null,
                                                            tint = Color.White.copy(alpha = 0.78f),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            "Autoplay",
                                                            style = AuricleTheme.typography.headline,
                                                            color = Color.White
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = if (autoplay) {
                                                            "Similar songs play automatically"
                                                        } else {
                                                            "Off — turn on to keep playing similar songs"
                                                        },
                                                        style = AuricleTheme.typography.caption,
                                                        color = Color.White.copy(alpha = 0.60f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Switch(
                                                    checked = autoplay,
                                                    onCheckedChange = { settingsRepo?.updateAutoplay(it) },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = animMid,
                                                        checkedTrackColor = Color.White.copy(alpha = 0.92f),
                                                        uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                                                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                                                        uncheckedBorderColor = Color.White.copy(alpha = 0.35f)
                                                    ),
                                                    modifier = Modifier.testTag("QueueAutoplaySwitch")
                                                )
                                            }
                                        }

                                        if (autoplay && autoplayCandidates.isNotEmpty()) {
                                            itemsIndexed(
                                                items = autoplayCandidates.take(6),
                                                key = { idx, candidate -> "autoplay_${candidate.videoId}_$idx" }
                                            ) { _, candidate ->
                                                OnArtQueueRow(
                                                    song = candidate,
                                                    alpha = 0.55f,
                                                    showDragHandle = false,
                                                    onClick = {
                                                        playbackController.playAutoplayCandidate(candidate)
                                                    },
                                                    onLongClick = { onSongOptionsClick(candidate) }
                                                )
                                            }
                                        } else if (upcoming.isEmpty()) {
                                            item(key = "queue_empty_card") {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 28.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    AuricleStateCard(
                                                        icon = AuricleIcons.Queue,
                                                        title = "Queue is empty",
                                                        subtitle = "Add songs or turn on Autoplay to keep the music going.",
                                                        onArtwork = true
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Lyrics Panel
                                val currentLyrics = lyricsData
                                val lines = currentLyrics?.lines ?: emptyList()
                                val isSynced = remember(lines) { lines.any { it.timeMs > 0 } }
                                val hasError = currentLyrics != null && (!currentLyrics.ok || currentLyrics.error.isNotBlank())
                                val lyricsHeaderLabel = when {
                                    lyricsLangMode == "english" -> "English lyrics"
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                color = Color.White.copy(alpha = 0.65f)
                                            )
                                        }
                                        AuricleSegmentedControl(
                                            options = listOf("Original", "English"),
                                            selectedIndex = if (lyricsLangMode == "english") 1 else 0,
                                            onSelect = { lyricsLangMode = if (it == 1) "english" else "original" },
                                            onArtwork = true,
                                            modifier = Modifier.width(168.dp)
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
                                                    subtitle = "Lyrics appear here when YouTube Music has them.",
                                                    onArtwork = true
                                                )
                                            }
                                        }
                                        else -> {
                                            val listState = rememberLazyListState()
                                            LaunchedEffect(track.videoId, lyricsLangMode) {
                                                listState.scrollToItem(0)
                                            }
                                            val activeIdx = if (isSynced) {
                                                lines.indexOfLast { !it.isSpacer && it.timeMs <= currentPosition }.coerceAtLeast(0)
                                            } else 0

                                            LaunchedEffect(activeIdx, isSynced, reducedMotion) {
                                                if (isSynced) {
                                                    val targetIdx = (activeIdx - 1).coerceAtLeast(0)
                                                    if (reducedMotion) {
                                                        listState.scrollToItem(targetIdx)
                                                    } else {
                                                        listState.animateScrollToItem(targetIdx)
                                                    }
                                                }
                                            }

                                            Box(modifier = Modifier.fillMaxSize()) {
                                                LazyColumn(
                                                    state = listState,
                                                    contentPadding = PaddingValues(top = 20.dp, bottom = 56.dp),
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
                                                                    .clickable(enabled = line.timeMs > 0) {
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

            // Bottom Actions Row: Lyrics · Party · Up Next
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (mode == 2) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .auriclePressClickable {
                            if (mode == 2) {
                                mode = 0
                            } else {
                                lastSubMode = 2
                                mode = 2
                            }
                        }
                        .testTag("Lyrics")
                        .semantics { contentDescription = "Lyrics" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AuricleIcons.Lyrics, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (partySnapshot.isActive && mode == 0) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .auriclePressClickable { onPartyClick() }
                        .semantics { contentDescription = "Listening Party" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AuricleIcons.Radio, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    if (partySnapshot.isActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF62C97E))
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (mode == 1) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .auriclePressClickable {
                            if (mode == 1) {
                                mode = 0
                            } else {
                                lastSubMode = 1
                                mode = 1
                            }
                        }
                        .testTag("Up Next")
                        .semantics { contentDescription = "Up Next" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AuricleIcons.Queue, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

private data class QueuePillSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val active: Boolean,
    val onClick: () -> Unit
)

private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
