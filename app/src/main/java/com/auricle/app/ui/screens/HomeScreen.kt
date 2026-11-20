package com.auricle.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.HomeFeedData
import com.auricle.app.model.PartySnapshot
import com.auricle.app.model.SongItem
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleTheme
import com.auricle.app.ui.theme.BrandBlue
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    musicRepo: MusicRepository,
    libraryRepo: LibraryRepository = (LocalContext.current.applicationContext as AuricleApp).libraryRepository,
    playbackController: PlaybackController,
    partySnapshot: PartySnapshot = PartySnapshot(),
    hasUpdateBadge: Boolean = false,
    onPartyClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    albumArtworkModifier: @Composable (String) -> Modifier = { Modifier },
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var homeFeed by remember { mutableStateOf<HomeFeedData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()
    val libraryState by libraryRepo.state.collectAsState()

    val listState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 72
        }
    }

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

    @Composable
    fun HeaderActions() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PartyPill(
                partySnapshot = partySnapshot,
                onClick = onPartyClick
            )
            Box(contentAlignment = Alignment.TopEnd) {
                AuricleCircleIconButton(
                    icon = AuricleIcons.Settings,
                    contentDescription = "Settings",
                    onClick = onSettingsClick,
                    modifier = Modifier.testTag("Settings")
                )
                if (hasUpdateBadge) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp, end = 2.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(BrandBlue)
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.bg)
    ) {
        if (isLoading && homeFeed == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Large title header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Home",
                        style = AuricleTheme.typography.large,
                        color = AuricleTheme.colors.textPrimary
                    )
                    HeaderActions()
                }
                Spacer(Modifier.height(12.dp))
                FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
            }
        } else if (errorMessage != null && homeFeed == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Home",
                        style = AuricleTheme.typography.large,
                        color = AuricleTheme.colors.textPrimary
                    )
                    HeaderActions()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AuricleStateCard(
                        icon = AuricleIcons.Refresh,
                        title = "Unable to connect to music service",
                        subtitle = "Check your connection — Auricle will retry the music service.",
                        actionLabel = "Retry",
                        onActionClick = { loadHomeFeed() }
                    )
                }
            }
        } else {
            val feed = homeFeed ?: HomeFeedData()
            val heroItems = remember(feed) {
                val raw = listOfNotNull(feed.featured) + feed.trending.take(4)
                val seen = mutableSetOf<String>()
                raw.filter { seen.add(it.videoId) }.take(4)
            }
            val followedSongs = remember(libraryState, feed) {
                val libraryArtists = (libraryState.likedSongs.map { it.artist } +
                        libraryState.recentlyPlayed.map { it.track.artist })
                    .filter { it.isNotBlank() }
                    .toSet()

                val pool = feed.quickPicks + feed.trending
                val matched = if (libraryArtists.isNotEmpty()) {
                    pool.filter { libraryArtists.contains(it.artist) }
                } else emptyList()

                if (matched.isNotEmpty()) {
                    matched.distinctBy { it.videoId }
                } else {
                    (feed.quickPicks.drop(4) + feed.trending.drop(4))
                        .distinctBy { it.artist }
                        .take(6)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // Large Title Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 0.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Home",
                            style = AuricleTheme.typography.large,
                            color = AuricleTheme.colors.textPrimary
                        )
                        HeaderActions()
                    }
                }

                // 1. Featured Hero Pager
                if (heroItems.isNotEmpty()) {
                    item {
                        FeaturedHeroPager(
                            items = heroItems,
                            onPlayClick = { song ->
                                playbackController.playTrack(
                                    song = song,
                                    newQueue = feed.trending.ifEmpty { listOf(song) },
                                    sourceLabel = "Featured Pick"
                                )
                            },
                            onAddToQueueClick = { song ->
                                playbackController.addToQueue(song)
                            }
                        )
                    }
                }

                // 2. Trending Now Section
                if (feed.trending.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AuricleSectionHeader(
                                title = "Trending Now",
                                glyph = AuricleIcons.Flame,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(feed.trending, key = { it.videoId }) { song ->
                                    AlbumCard(
                                        title = song.title,
                                        subtitle = song.artist,
                                        artworkUrl = song.displayArtworkUrl,
                                        onClick = {
                                            playbackController.playTrack(song, feed.trending, "Trending Now")
                                        },
                                        width = 160.dp
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Quick Picks (Horizontally Paged 4-Row Columns)
                if (feed.quickPicks.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AuricleSectionHeader(
                                title = "Quick Picks",
                                actionText = "Play all",
                                onActionClick = {
                                    playbackController.playTrack(
                                        feed.quickPicks.first(),
                                        feed.quickPicks,
                                        "Quick Picks"
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )

                            val columns = remember(feed.quickPicks) {
                                feed.quickPicks.chunked(4)
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(columns) { columnSongs ->
                                    Column(
                                        modifier = Modifier.width(320.dp)
                                    ) {
                                        columnSongs.forEachIndexed { rowIdx, song ->
                                            SongRow(
                                                song = song,
                                                isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                                isActivelyPlaying = isPlaying,
                                                onClick = {
                                                    playbackController.playTrack(
                                                        song,
                                                        feed.quickPicks,
                                                        "Quick Picks"
                                                    )
                                                },
                                                onQuickAddQueue = {
                                                    playbackController.addToQueue(song)
                                                },
                                                onOptionsClick = {
                                                    onSongOptionsClick(song)
                                                },
                                                showDivider = rowIdx < columnSongs.lastIndex
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. From artists you follow
                if (followedSongs.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AuricleSectionHeader(
                                title = "From artists you follow",
                                glyph = AuricleIcons.Heart,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(followedSongs, key = { it.videoId }) { song ->
                                    AlbumCard(
                                        title = song.title,
                                        subtitle = song.artist,
                                        artworkUrl = song.displayArtworkUrl,
                                        onClick = {
                                            playbackController.playTrack(
                                                song,
                                                followedSongs,
                                                "From artists you follow"
                                            )
                                        },
                                        width = 160.dp
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. New Releases & Albums
                if (feed.newReleases.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AuricleSectionHeader(
                                title = "New Releases & Albums",
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(feed.newReleases, key = { it.browseId.ifBlank { it.title } }) { album ->
                                    AlbumCard(
                                        title = album.title,
                                        subtitle = if (album.year.isNotBlank()) "Album · ${album.artist}" else album.artist,
                                        artworkUrl = album.thumbnailUrl,
                                        onClick = { onAlbumClick(album) },
                                        width = 160.dp,
                                        artworkModifier = albumArtworkModifier(album.browseId.ifBlank { album.title })
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pinned Collapsed Top Bar
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            AuricleCollapsedTopBar(
                title = "Home",
                visible = isCollapsed,
                actions = { HeaderActions() }
            )
        }
    }
}
