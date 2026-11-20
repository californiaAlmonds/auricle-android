package com.auricle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.HomeFeedData
import com.auricle.app.model.SongItem
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.HeroCard
import com.auricle.app.ui.components.SongCard
import com.auricle.app.ui.components.SongRow
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    musicRepo: MusicRepository,
    playbackController: PlaybackController,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var homeFeed by remember { mutableStateOf<HomeFeedData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentPlayingTrack by playbackController.currentTrack.collectAsState()

    fun loadHomeFeed() {
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            val result = musicRepo.getHomeFeed()
            result.onSuccess { data ->
                homeFeed = data
                isLoading = false
            }.onFailure { err ->
                errorMessage = err.message ?: "Failed to load feed"
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadHomeFeed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.canvas)
    ) {
        if (isLoading && homeFeed == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = AuricleTheme.colors.accent,
                    strokeWidth = 3.dp
                )
            }
        } else if (errorMessage != null && homeFeed == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Unable to connect to music service",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuricleTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "",
                    fontSize = 13.sp,
                    color = AuricleTheme.colors.textMuted
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { loadHomeFeed() },
                    colors = ButtonDefaults.buttonColors(containerColor = AuricleTheme.colors.accent)
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Retry")
                }
            }
        } else {
            val feed = homeFeed ?: HomeFeedData()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Featured Hero Section
                val featuredSong = feed.featured ?: feed.trending.firstOrNull()
                if (featuredSong != null) {
                    item {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            HeroCard(
                                song = featuredSong,
                                onPlayClick = {
                                    playbackController.playTrack(featuredSong, feed.trending.ifEmpty { listOf(featuredSong) })
                                },
                                onQueueClick = {
                                    playbackController.addToQueue(featuredSong)
                                },
                                onRefreshPick = {
                                    loadHomeFeed()
                                }
                            )
                        }
                    }
                }

                // Trending Now Section
                if (feed.trending.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Trending Now",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AuricleTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(feed.trending) { song ->
                                    SongCard(
                                        song = song,
                                        onClick = {
                                            playbackController.playTrack(song, feed.trending)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Quick Picks Section
                if (feed.quickPicks.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Quick Picks",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AuricleTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            feed.quickPicks.forEach { song ->
                                SongRow(
                                    song = song,
                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                    onClick = {
                                        playbackController.playTrack(song, feed.quickPicks)
                                    },
                                    onQuickAddQueue = {
                                        playbackController.addToQueue(song)
                                    },
                                    onOptionsClick = {
                                        onSongOptionsClick(song)
                                    }
                                )
                            }
                        }
                    }
                }

                // New Releases / Albums Section
                if (feed.newReleases.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "New Releases & Albums",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AuricleTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(feed.newReleases) { album ->
                                    Column(
                                        modifier = Modifier
                                            .width(148.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { onAlbumClick(album) }
                                            .padding(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(140.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(AuricleTheme.colors.surface)
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(album.thumbnailUrl)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = album.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AuricleTheme.colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Text(
                                            text = album.artist,
                                            fontSize = 12.sp,
                                            color = AuricleTheme.colors.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
