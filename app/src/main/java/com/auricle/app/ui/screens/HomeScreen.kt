package com.auricle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.HomeFeedData
import com.auricle.app.model.PartySnapshot
import com.auricle.app.model.PlaylistItem
import com.auricle.app.model.SongItem
import com.auricle.app.model.splitArtistCredits
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleTheme
import com.auricle.app.ui.theme.BrandBlue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun artistMatchesName(trackArtist: String, targetName: String): Boolean {
    if (trackArtist.isBlank() || targetName.isBlank()) return false
    if (trackArtist.equals(targetName, ignoreCase = true)) return true
    val tokens = trackArtist.split(",", "&", "•", "feat.", "ft.", ignoreCase = true)
    return tokens.any { it.trim().equals(targetName.trim(), ignoreCase = true) }
}

private fun songMatchesArtistName(song: SongItem, targetName: String): Boolean {
    if (targetName.isBlank()) return false
    if (artistMatchesName(song.artist, targetName)) return true
    return song.parsedArtistCredits().any { it.name.trim().equals(targetName.trim(), ignoreCase = true) }
}

private data class PersonalizedHomeSection(
    val songs: List<SongItem>,
    val hasPersonalArtistMatches: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
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
    onAlbumLongClick: ((AlbumItem) -> Unit)? = null,
    onArtistLongClick: ((ArtistItem) -> Unit)? = null,
    onPlaylistClick: ((PlaylistItem) -> Unit)? = null,
    onPlaylistLongClick: ((PlaylistItem) -> Unit)? = null,
    albumArtworkModifier: @Composable (String) -> Modifier = { Modifier },
    listState: LazyListState = rememberLazyListState(),
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val cachedHomeFeed by musicRepo.cachedHomeFeed.collectAsState()
    var homeFeed by remember { mutableStateOf(cachedHomeFeed) }
    var isLoading by remember { mutableStateOf(cachedHomeFeed == null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()
    val libraryState by libraryRepo.state.collectAsState()

    val pullToRefreshState = rememberPullToRefreshState()
    val collapseThresholdPx = remember(density) { with(density) { 40.dp.toPx() } }
    val isCollapsed by remember(listState, collapseThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseThresholdPx
        }
    }

    LaunchedEffect(cachedHomeFeed) {
        val latest = cachedHomeFeed
        if (latest != null) {
            homeFeed = latest
            isLoading = false
        }
    }

    fun loadHomeFeed(isPullRefresh: Boolean = false) {
        if (isPullRefresh) {
            if (isRefreshing) return
            isRefreshing = true
        } else {
            if (homeFeed == null) {
                isLoading = true
            }
        }
        errorMessage = null
        loadJob?.cancel()
        loadJob = coroutineScope.launch {
            val result = musicRepo.getHomeFeed(forceRefresh = isPullRefresh)
            result.onSuccess { data ->
                homeFeed = data
                isLoading = false
                isRefreshing = false
            }.onFailure { err ->
                errorMessage = err.message ?: "Failed to load feed"
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (cachedHomeFeed == null) {
            loadHomeFeed(isPullRefresh = false)
        }
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadHomeFeed(isPullRefresh = true) },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                    containerColor = AuricleTheme.colors.surface2,
                    color = AuricleTheme.colors.accentStrong
                )
            },
            modifier = Modifier.fillMaxSize()
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    item(key = "home-error-header") {
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
                    }
                    item(key = "home-error-card") {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .fillParentMaxHeight(0.75f),
                            contentAlignment = Alignment.Center
                        ) {
                            AuricleStateCard(
                                icon = AuricleIcons.Refresh,
                                title = "Unable to connect to music service",
                                subtitle = errorMessage ?: "Check your connection — Auricle will retry the music service.",
                                actionLabel = "Retry",
                                onActionClick = { loadHomeFeed(isPullRefresh = false) }
                            )
                        }
                    }
                }
            } else {
                val feed = homeFeed ?: HomeFeedData()
                val heroItems = remember(feed) {
                    val raw = listOfNotNull(feed.featured) +
                        feed.trending.filter { it.videoId != feed.featured?.videoId }.take(2) +
                        feed.quickPicks.take(3)
                    val seenIds = mutableSetOf<String>()
                    val seenTitles = mutableSetOf<String>()
                    raw.filter { song ->
                        val vidOk = song.videoId.isBlank() || seenIds.add(song.videoId)
                        val titleKey = song.title.trim().lowercase()
                        val titleOk = titleKey.isBlank() || seenTitles.add(titleKey)
                        vidOk && titleOk
                    }.take(4)
                }

                val heroLead = heroItems.firstOrNull()
                val trendingDisplay = remember(feed.trending, heroLead) {
                    val leadId = heroLead?.videoId
                    val leadTitle = heroLead?.title?.trim()?.lowercase()
                    val deduped = feed.trending.filterNot { song ->
                        (!leadId.isNullOrBlank() && song.videoId == leadId) ||
                            (!leadTitle.isNullOrBlank() && song.title.trim().lowercase() == leadTitle)
                    }
                    deduped.ifEmpty { feed.trending }
                }

                val hasPersonalHistory = remember(libraryState) {
                    libraryState.followedArtists.isNotEmpty() ||
                        libraryState.likedSongs.isNotEmpty() ||
                        libraryState.recentlyPlayed.isNotEmpty() ||
                        libraryState.savedAlbums.isNotEmpty()
                }

                val personalizedSection = remember(libraryState, feed, heroItems) {
                    val heroIds = heroItems.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toSet()
                    val pool = (feed.quickPicks + feed.trending).distinctBy { it.videoId }
                    val followedArtists = libraryState.followedArtists
                    val followedIds = followedArtists
                        .mapNotNull { it.browseId.trim().takeIf(String::isNotBlank) }
                        .toSet()
                    val followedNames = followedArtists
                        .mapNotNull { it.name.trim().takeIf(String::isNotBlank) }

                    val matchedFollowed = if (followedArtists.isNotEmpty()) {
                        pool.filter { song ->
                            (song.artistId.isNotBlank() && song.artistId in followedIds) ||
                                song.parsedArtistCredits().any { it.browseId.isNotBlank() && it.browseId in followedIds } ||
                                followedNames.any { name -> songMatchesArtistName(song, name) }
                        }
                    } else emptyList()

                    val libraryArtists = (libraryState.likedSongs.map { it.artist } +
                            libraryState.recentlyPlayed.map { it.track.artist } +
                            libraryState.savedAlbums.map { it.artist })
                        .flatMap { splitArtistCredits(it).map { credit -> credit.name } }
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }

                    val matchedDerived = if (libraryArtists.isNotEmpty()) {
                        pool.filter { song ->
                            libraryArtists.any { name -> songMatchesArtistName(song, name) }
                        }
                    } else emptyList()

                    val fallback = (feed.quickPicks.drop(4) + feed.trending.drop(4))
                        .ifEmpty { feed.quickPicks + feed.trending }
                        .filterNot { it.videoId in heroIds }
                        .distinctBy { it.videoId }
                        .distinctBy { it.artist.trim().lowercase().ifBlank { it.videoId } }
                        .take(8)

                    when {
                        matchedFollowed.isNotEmpty() -> {
                            PersonalizedHomeSection(
                                songs = matchedFollowed.distinctBy { it.videoId },
                                hasPersonalArtistMatches = true
                            )
                        }
                        matchedDerived.isNotEmpty() -> {
                            PersonalizedHomeSection(
                                songs = (matchedDerived + fallback)
                                    .distinctBy { it.videoId }
                                    .take(maxOf(6, matchedDerived.size)),
                                hasPersonalArtistMatches = true
                            )
                        }
                        else -> {
                            PersonalizedHomeSection(
                                songs = fallback,
                                hasPersonalArtistMatches = followedArtists.isNotEmpty() && matchedFollowed.isNotEmpty()
                            )
                        }
                    }
                }

                val hasPersonalArtistMatches = personalizedSection.hasPersonalArtistMatches
                val recommendedTitle = if (hasPersonalArtistMatches) "From artists you follow" else "Recommended for You"
                val recommendedGlyph = if (hasPersonalArtistMatches) AuricleIcons.Heart else AuricleIcons.Sparkles
                val followedSongs = personalizedSection.songs

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding),
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // Large Title Header
                    item(key = "home-header") {
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

                    // Refresh error banner when existing feed is displayed
                    if (errorMessage != null) {
                        item(key = "home-refresh-error") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuricleTheme.colors.danger.copy(alpha = 0.12f))
                                    .border(
                                        1.dp,
                                        AuricleTheme.colors.danger.copy(alpha = 0.35f),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Couldn't refresh feed",
                                        style = AuricleTheme.typography.subSB,
                                        color = AuricleTheme.colors.textPrimary
                                    )
                                    Text(
                                        text = errorMessage ?: "Check your connection and try again.",
                                        style = AuricleTheme.typography.caption,
                                        color = AuricleTheme.colors.textMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "Retry",
                                    style = AuricleTheme.typography.subSB,
                                    color = AuricleTheme.colors.accentStrong,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { loadHomeFeed(isPullRefresh = true) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // 1. Featured Hero Pager
                    if (heroItems.isNotEmpty()) {
                        item(key = "home-hero") {
                            FeaturedHeroPager(
                                items = heroItems,
                                eyebrowText = if (hasPersonalHistory) "MADE FOR YOUR TASTE" else "FEATURED PICK",
                                onPlayClick = { song ->
                                    playbackController.playTrack(
                                        song = song,
                                        newQueue = listOf(song),
                                        sourceLabel = "Featured Pick"
                                    )
                                },
                                onAddToQueueClick = { song ->
                                    playbackController.addToQueue(song)
                                },
                                onHeroLongClick = onSongOptionsClick
                            )
                        }
                    }

                    // 2. Trending Now Section
                    if (trendingDisplay.isNotEmpty()) {
                        item(key = "home-trending") {
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
                                    itemsIndexed(
                                        items = trendingDisplay,
                                        key = { idx, item -> "trending-${item.videoId}-$idx" }
                                    ) { _, song ->
                                        AlbumCard(
                                            title = song.title.ifBlank { "Untitled Track" },
                                            subtitle = song.artist.ifBlank { "YouTube Music" },
                                            artworkUrl = song.displayArtworkUrl,
                                            onClick = {
                                                playbackController.playTrack(song, listOf(song), "Trending Now")
                                            },
                                            onLongClick = { onSongOptionsClick(song) },
                                            width = 160.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Quick Picks (Horizontally Paged 4-Row Columns)
                    if (feed.quickPicks.isNotEmpty()) {
                        item(key = "home-quickpicks") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AuricleSectionHeader(
                                    title = "Quick Picks",
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )

                                val columns = remember(feed.quickPicks) {
                                    feed.quickPicks.chunked(4)
                                }

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(
                                        items = columns,
                                        key = { idx, _ -> "quickpicks-col-$idx" }
                                    ) { _, columnSongs ->
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
                                                            listOf(song),
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

                    // 4. From artists you follow / Recommended for You
                    if (followedSongs.isNotEmpty()) {
                        item(key = "home-followed") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AuricleSectionHeader(
                                    title = recommendedTitle,
                                    glyph = recommendedGlyph,
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(
                                        items = followedSongs,
                                        key = { idx, item -> "followed-${item.videoId}-$idx" }
                                    ) { _, song ->
                                        AlbumCard(
                                            title = song.title.ifBlank { "Untitled Track" },
                                            subtitle = song.artist.ifBlank { "YouTube Music" },
                                            artworkUrl = song.displayArtworkUrl,
                                            onClick = {
                                                playbackController.playTrack(
                                                    song,
                                                    listOf(song),
                                                    recommendedTitle
                                                )
                                            },
                                            onLongClick = { onSongOptionsClick(song) },
                                            width = 160.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 5. New Releases & Albums
                    if (feed.newReleases.isNotEmpty()) {
                        item(key = "home-newreleases") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AuricleSectionHeader(
                                    title = "New Releases & Albums",
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(
                                        items = feed.newReleases,
                                        key = { idx, item -> "newrel-${item.browseId.ifBlank { item.title }}-$idx" }
                                    ) { _, album ->
                                        val isPlaylist = album.itemType.equals("playlist", ignoreCase = true)
                                        val subtitle = if (isPlaylist) {
                                            if (album.artist.isNotBlank()) "Playlist · ${album.artist}" else "Playlist"
                                        } else {
                                            if (album.artist.isNotBlank()) {
                                                if (album.year.isNotBlank()) "Album · ${album.artist}" else album.artist
                                            } else {
                                                if (album.year.isNotBlank()) "Album · ${album.year}" else "Album"
                                            }
                                        }
                                        val playlistItem = if (isPlaylist) {
                                            PlaylistItem(
                                                playlistId = album.browseId,
                                                title = album.title,
                                                author = album.artist,
                                                thumbnailUrl = album.thumbnailUrl
                                            )
                                        } else null
                                        AlbumCard(
                                            title = album.title,
                                            subtitle = subtitle,
                                            artworkUrl = album.displayThumbnailUrl,
                                            onClick = {
                                                if (playlistItem != null && onPlaylistClick != null) {
                                                    onPlaylistClick(playlistItem)
                                                } else {
                                                    onAlbumClick(album)
                                                }
                                            },
                                            onLongClick = if (playlistItem != null) {
                                                onPlaylistLongClick?.let { cb -> { cb(playlistItem) } }
                                            } else {
                                                onAlbumLongClick?.let { cb -> { cb(album) } }
                                            },
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
