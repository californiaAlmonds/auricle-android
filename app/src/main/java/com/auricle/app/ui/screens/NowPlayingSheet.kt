package com.auricle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.LyricsData
import com.auricle.app.model.SongItem
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.PartyPill
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun NowPlayingSheet(
    playbackController: PlaybackController,
    libraryRepo: LibraryRepository,
    musicRepo: MusicRepository,
    onDismiss: () -> Unit,
    onPartyClick: () -> Unit,
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
    val libraryState by libraryRepo.state.collectAsState()

    var selectedMode by remember { mutableStateOf(0) } // 0: Player, 1: Up Next, 2: Lyrics
    var lyricsLangMode by remember { mutableStateOf("original") } // "original" or "english"
    var lyricsData by remember { mutableStateOf<LyricsData?>(null) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    var isDraggingSlider by remember { mutableStateOf(false) }
    var draggingSliderValue by remember { mutableStateOf(0f) }

    val track = currentTrack ?: return
    val isLiked = libraryState.likedSongs.any { it.videoId == track.videoId }

    // Fetch lyrics when track changes or tab opened
    LaunchedEffect(track.videoId, lyricsLangMode, selectedMode) {
        if (selectedMode == 2) {
            isLoadingLyrics = true
            val data = musicRepo.getLyrics(track.videoId, lyricsLangMode)
            lyricsData = data
            isLoadingLyrics = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.canvas)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .semantics { contentDescription = "Close player" }
                        .testTag("DismissNowPlaying")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Dismiss",
                        tint = AuricleTheme.colors.textPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Mode Selector Tabs (Player | Up Next | Lyrics)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(AuricleTheme.colors.surface)
                        .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(20.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Player", "Up Next", "Lyrics").forEachIndexed { idx, label ->
                        val isSelected = selectedMode == idx
                        Box(
                            modifier = Modifier
                                .semantics { contentDescription = label }
                                .testTag(label)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) AuricleTheme.colors.accent else Color.Transparent)
                                .clickable { selectedMode = idx }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else AuricleTheme.colors.textMuted
                            )
                        }
                    }
                }

                PartyPill(
                    partySnapshot = partySnapshot,
                    onClick = onPartyClick
                )
            }

            // Mode Views
            when (selectedMode) {
                0 -> {
                    // Player View
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Shadowed Album Artwork
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(track.displayArtworkUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(280.dp)
                                .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = AuricleTheme.colors.accent, spotColor = AuricleTheme.colors.accent)
                                .clip(RoundedCornerShape(24.dp))
                                .background(AuricleTheme.colors.surface)
                        )

                        // Title, Artist, and Like Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuricleTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = track.artist,
                                    fontSize = 15.sp,
                                    color = AuricleTheme.colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { libraryRepo.toggleLike(track) },
                                modifier = Modifier
                                    .semantics { contentDescription = if (isLiked) "Unlike" else "Like" }
                                    .testTag("NowPlayingLike")
                                    .size(44.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Like",
                                    tint = if (isLiked) AuricleTheme.colors.danger else AuricleTheme.colors.textMuted,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Progress Slider & Timestamps
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val activeProgress = if (isDraggingSlider) draggingSliderValue else {
                                if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                            }

                            Slider(
                                value = activeProgress,
                                onValueChange = {
                                    isDraggingSlider = true
                                    draggingSliderValue = it
                                },
                                onValueChangeFinished = {
                                    val targetMs = (draggingSliderValue * duration).toLong()
                                    playbackController.seekTo(targetMs)
                                    isDraggingSlider = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = AuricleTheme.colors.accent,
                                    activeTrackColor = AuricleTheme.colors.accent,
                                    inactiveTrackColor = AuricleTheme.colors.surface2
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("SeekBar")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val displayPos = if (isDraggingSlider) (draggingSliderValue * duration).toLong() else currentPosition
                                Text(
                                    text = formatTimeMs(displayPos),
                                    fontSize = 12.sp,
                                    color = AuricleTheme.colors.textDim
                                )
                                Text(
                                    text = formatTimeMs(duration),
                                    fontSize = 12.sp,
                                    color = AuricleTheme.colors.textDim
                                )
                            }
                        }

                        // Media Controls Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Shuffle
                            IconButton(
                                onClick = { playbackController.toggleShuffle() },
                                modifier = Modifier.testTag("Shuffle")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffle) AuricleTheme.colors.accent else AuricleTheme.colors.textMuted,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Previous
                            IconButton(
                                onClick = { playbackController.skipPrevious() },
                                modifier = Modifier
                                    .semantics { contentDescription = "Previous" }
                                    .testTag("Previous")
                                    .size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // Big Glowing Play / Pause Button
                            Box(
                                modifier = Modifier
                                    .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
                                    .testTag(if (isPlaying) "Pause" else "Play")
                                    .size(68.dp)
                                    .shadow(16.dp, CircleShape, ambientColor = AuricleTheme.colors.accent, spotColor = AuricleTheme.colors.accent)
                                    .clip(CircleShape)
                                    .background(AuricleTheme.colors.accent)
                                    .clickable { playbackController.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                                } else {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            // Next
                            IconButton(
                                onClick = { playbackController.skipNext() },
                                modifier = Modifier
                                    .semantics { contentDescription = "Next" }
                                    .testTag("Next")
                                    .size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Next",
                                    tint = AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // Repeat
                            IconButton(
                                onClick = { playbackController.toggleRepeat() },
                                modifier = Modifier.testTag("Repeat")
                            ) {
                                val repeatIcon = when (repeatMode) {
                                    "one" -> Icons.Rounded.RepeatOne
                                    else -> Icons.Rounded.Repeat
                                }
                                Icon(
                                    imageVector = repeatIcon,
                                    contentDescription = "Repeat: $repeatMode",
                                    tint = if (repeatMode != "off") AuricleTheme.colors.accent else AuricleTheme.colors.textMuted,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Up Next / Queue View
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (partySnapshot.isActive) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = AuricleTheme.colors.surface2
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Rounded.Sync, contentDescription = null, tint = AuricleTheme.colors.success, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "Shared Party Queue • Synced",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AuricleTheme.colors.success
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Now Playing",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuricleTheme.colors.accent,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )

                        // Current Playing Song Card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = AuricleTheme.colors.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AsyncImage(
                                    model = track.displayArtworkUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = track.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AuricleTheme.colors.textPrimary, maxLines = 1)
                                    Text(text = track.artist, fontSize = 12.sp, color = AuricleTheme.colors.textMuted, maxLines = 1)
                                }
                                Icon(imageVector = Icons.Rounded.GraphicEq, contentDescription = null, tint = AuricleTheme.colors.accent)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Upcoming in Queue",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuricleTheme.colors.textPrimary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        val upcomingTracks = if (queue.size > currentIndex + 1) {
                            queue.subList(currentIndex + 1, queue.size)
                        } else emptyList()

                        if (upcomingTracks.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(text = "End of queue • Autoplay ready", fontSize = 13.sp, color = AuricleTheme.colors.textMuted)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(upcomingTracks) { index, song ->
                                    val actualIdx = currentIndex + 1 + index
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(AuricleTheme.colors.surface.copy(alpha = 0.5f))
                                            .clickable { playbackController.playQueueIndex(actualIdx) }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AsyncImage(
                                            model = song.displayArtworkUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = song.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AuricleTheme.colors.textPrimary, maxLines = 1)
                                            val sub = if (song.addedBy.isNotBlank()) "Added by ${song.addedBy}" else song.artist
                                            Text(text = sub, fontSize = 11.sp, color = AuricleTheme.colors.textMuted, maxLines = 1)
                                        }

                                        // Play next
                                        IconButton(
                                            onClick = {
                                                playbackController.playNext(song)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Rounded.VerticalAlignTop, contentDescription = "Play Next", tint = AuricleTheme.colors.textMuted, modifier = Modifier.size(16.dp))
                                        }

                                        // Remove from queue
                                        IconButton(
                                            onClick = {
                                                playbackController.removeFromQueue(actualIdx)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Remove", tint = AuricleTheme.colors.textDim, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Lyrics View
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Original | English segmented pill bar
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(AuricleTheme.colors.surface)
                                .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(18.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Original" to "original", "English" to "english").forEach { (title, mode) ->
                                val isSelected = lyricsLangMode == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) AuricleTheme.colors.accent else Color.Transparent)
                                        .clickable { lyricsLangMode = mode }
                                        .padding(horizontal = 14.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else AuricleTheme.colors.textMuted
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isLoadingLyrics) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AuricleTheme.colors.accent, strokeWidth = 2.5.dp)
                            }
                        } else {
                            val lines = lyricsData?.lines ?: emptyList()
                            if (lines.isEmpty()) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text(text = "No lyrics found for this track", fontSize = 14.sp, color = AuricleTheme.colors.textMuted)
                                }
                            } else {
                                val listState = rememberLazyListState()

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    itemsIndexed(lines) { index, line ->
                                        if (line.isSpacer) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                        } else {
                                            val isCurrentLine = line.timeMs > 0 &&
                                                    currentPosition >= line.timeMs &&
                                                    (index == lines.size - 1 || currentPosition < lines[index + 1].timeMs)

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (line.timeMs > 0) {
                                                            playbackController.seekTo(line.timeMs)
                                                        }
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = line.primary,
                                                    fontSize = if (isCurrentLine) 20.sp else 16.sp,
                                                    fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrentLine) AuricleTheme.colors.accent else AuricleTheme.colors.textPrimary,
                                                    textAlign = TextAlign.Center
                                                )
                                                if (line.secondary.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = line.secondary,
                                                        fontSize = 13.sp,
                                                        color = AuricleTheme.colors.textMuted,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
