package com.auricle.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.*
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleBrandGradient
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class DetailTarget {
    data class Album(val albumItem: AlbumItem) : DetailTarget()
    data class Artist(val artistItem: ArtistItem) : DetailTarget()
    data class Playlist(val playlistItem: PlaylistItem) : DetailTarget()
    data class CustomUserPlaylist(val playlistId: String, val fallbackPlaylist: UserPlaylist) : DetailTarget() {
        constructor(playlist: UserPlaylist) : this(playlist.id, playlist)
    }
    data object LikedSongs : DetailTarget()
    data object RecentlyPlayed : DetailTarget()
}

@Composable
fun DetailScreen(
    target: DetailTarget,
    musicRepo: MusicRepository,
    libraryRepo: LibraryRepository = (LocalContext.current.applicationContext as AuricleApp).libraryRepository,
    playbackController: PlaybackController,
    onBackClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit = {},
    onBrowseHomeClick: () -> Unit = {},
    onAlbumLongClick: ((AlbumItem) -> Unit)? = null,
    onPlaylistSongOptionsClick: ((SongItem, UserPlaylist) -> Unit)? = null,
    onItemOptionsClick: ((ContextMenuItem) -> Unit)? = null,
    coverArtworkModifier: @Composable (String) -> Modifier = { Modifier },
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val libraryState by libraryRepo.state.collectAsState()
    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()

    var fullAlbum by remember(target) { mutableStateOf<AlbumItem?>(null) }
    var fullArtist by remember(target) { mutableStateOf<ArtistItem?>(null) }
    var fullPlaylist by remember(target) { mutableStateOf<PlaylistItem?>(null) }
    var isLoading by remember(target) { mutableStateOf(false) }
    var loadError by remember(target) { mutableStateOf<String?>(null) }
    var retryTrigger by remember(target) { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 90
        }
    }

    LaunchedEffect(target, retryTrigger) {
        loadError = null
        when (target) {
            is DetailTarget.Album -> {
                if (target.albumItem.tracks.isEmpty()) {
                    isLoading = true
                    try {
                        var browseId = target.albumItem.browseId
                        if (browseId.isBlank() && (target.albumItem.title.isNotBlank() || target.albumItem.artist.isNotBlank())) {
                            val q = "${target.albumItem.title} ${target.albumItem.artist}".trim()
                            musicRepo.search(q, "albums")
                                .onSuccess { sRes ->
                                    browseId = sRes.albums.firstOrNull()?.browseId.orEmpty()
                                }
                                .onFailure { err ->
                                    loadError = err.message ?: "Failed to load album"
                                }
                        }
                        if (browseId.isNotBlank()) {
                            loadError = null
                            musicRepo.getAlbum(browseId)
                                .onSuccess { fetched ->
                                    fullAlbum = fetched.copy(
                                        artist = fetched.artist.ifBlank { target.albumItem.artist },
                                        thumbnailUrl = fetched.thumbnailUrl.ifBlank { target.albumItem.thumbnailUrl }
                                    )
                                }
                                .onFailure { err ->
                                    loadError = err.message ?: "Failed to load album"
                                }
                        } else if (loadError == null) {
                            fullAlbum = target.albumItem
                        }
                    } finally {
                        isLoading = false
                    }
                } else {
                    fullAlbum = target.albumItem
                }
            }
            is DetailTarget.Artist -> {
                if (target.artistItem.topSongs.isEmpty()) {
                    isLoading = true
                    try {
                        var browseId = target.artistItem.browseId
                        var matchedArtistThumb = ""
                        if (browseId.isBlank() && target.artistItem.name.isNotBlank()) {
                            musicRepo.search(target.artistItem.name, "artists")
                                .onSuccess { sRes ->
                                    val targetName = target.artistItem.name.trim()
                                    val matched = sRes.artists.firstOrNull {
                                        it.name.trim().equals(targetName, ignoreCase = true)
                                    } ?: sRes.artists.firstOrNull()
                                    browseId = matched?.browseId.orEmpty()
                                    matchedArtistThumb = matched?.thumbnailUrl.orEmpty()
                                }
                                .onFailure { err ->
                                    loadError = err.message ?: "Failed to load artist"
                                }
                        }
                        if (browseId.isNotBlank()) {
                            loadError = null
                            musicRepo.getArtist(browseId)
                                .onSuccess { fetched ->
                                    fullArtist = if (fetched.thumbnailUrl.isBlank()) {
                                        fetched.copy(
                                            thumbnailUrl = matchedArtistThumb.ifBlank { target.artistItem.thumbnailUrl }
                                        )
                                    } else {
                                        fetched
                                    }
                                }
                                .onFailure { err ->
                                    loadError = err.message ?: "Failed to load artist"
                                }
                        } else if (loadError == null) {
                            fullArtist = target.artistItem
                        }
                    } finally {
                        isLoading = false
                    }
                } else {
                    fullArtist = target.artistItem
                }
            }
            is DetailTarget.Playlist -> {
                if (target.playlistItem.tracks.isEmpty() && target.playlistItem.playlistId.isNotBlank()) {
                    isLoading = true
                    try {
                        musicRepo.getPlaylist(target.playlistItem.playlistId)
                            .onSuccess { fetched ->
                                fullPlaylist = fetched.copy(
                                    author = fetched.author.ifBlank { target.playlistItem.author },
                                    thumbnailUrl = fetched.thumbnailUrl.ifBlank { target.playlistItem.thumbnailUrl }
                                )
                            }
                            .onFailure { err ->
                                loadError = err.message ?: "Failed to load playlist"
                            }
                    } finally {
                        isLoading = false
                    }
                } else {
                    fullPlaylist = target.playlistItem
                }
            }
            is DetailTarget.CustomUserPlaylist,
            DetailTarget.LikedSongs,
            DetailTarget.RecentlyPlayed -> {
                // Local state is already up to date
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.bg)
    ) {
        when (target) {
            is DetailTarget.Artist -> {
                val artist = fullArtist ?: target.artistItem
                ArtistDetailContent(
                    target = target,
                    artist = artist,
                    isLoading = isLoading,
                    loadError = loadError,
                    onRetry = { retryTrigger++ },
                    libraryState = libraryState,
                    libraryRepo = libraryRepo,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    listState = listState,
                    isCollapsed = isCollapsed,
                    playbackController = playbackController,
                    onBackClick = onBackClick,
                    onSongOptionsClick = onSongOptionsClick,
                    onAlbumClick = onAlbumClick,
                    onAlbumLongClick = onAlbumLongClick,
                    onItemOptionsClick = onItemOptionsClick,
                    bottomContentPadding = bottomContentPadding
                )
            }
            else -> {
                StandardDetailContent(
                    target = target,
                    fullAlbum = fullAlbum,
                    fullPlaylist = fullPlaylist,
                    isLoading = isLoading,
                    loadError = loadError,
                    onRetry = { retryTrigger++ },
                    libraryState = libraryState,
                    libraryRepo = libraryRepo,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    listState = listState,
                    isCollapsed = isCollapsed,
                    playbackController = playbackController,
                    onBackClick = onBackClick,
                    onSongOptionsClick = onSongOptionsClick,
                    onPlaylistSongOptionsClick = onPlaylistSongOptionsClick,
                    onItemOptionsClick = onItemOptionsClick,
                    onArtistClick = onArtistClick,
                    onBrowseHomeClick = onBrowseHomeClick,
                    coverArtworkModifier = coverArtworkModifier,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(
    target: DetailTarget.Artist,
    artist: ArtistItem,
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    libraryState: LibraryState,
    libraryRepo: LibraryRepository,
    currentPlayingTrack: SongItem?,
    isPlaying: Boolean,
    listState: LazyListState,
    isCollapsed: Boolean,
    playbackController: PlaybackController,
    onBackClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onAlbumLongClick: ((AlbumItem) -> Unit)?,
    onItemOptionsClick: ((ContextMenuItem) -> Unit)?,
    bottomContentPadding: Dp
) {
    val isFollowing = remember(libraryState, artist.browseId, artist.name) {
        libraryRepo.isArtistFollowed(artist.browseId, artist.name)
    }
    var showAllTopSongs by remember(target) { mutableStateOf(false) }
    val artistReleases = remember(artist) {
        artist.albums.map { it.copy(artist = artist.name) } +
            artist.singles.map { it.copy(itemType = "single", artist = artist.name) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Hero Photo Section (420 dp with 4-stop gradient fade)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    val heroPhotoUrl = artist.highResThumbnailUrl
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        AuricleTheme.colors.accent.copy(alpha = 0.45f),
                                        AuricleTheme.colors.bg
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (heroPhotoUrl.isBlank()) {
                            Icon(
                                imageVector = AuricleIcons.Artist,
                                contentDescription = null,
                                tint = AuricleTheme.colors.textDim.copy(alpha = 0.4f),
                                modifier = Modifier.size(72.dp)
                            )
                        }
                    }
                    if (heroPhotoUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(heroPhotoUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Artist Hero Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 4-stop Figma vertical gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color(0x66000000),
                                    0.22f to Color.Transparent,
                                    0.55f to AuricleTheme.colors.bg.copy(alpha = 0.2f),
                                    1.0f to AuricleTheme.colors.bg
                                )
                            )
                    )

                    // Overlaid top nav buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AuricleCircleIconButton(
                            icon = AuricleIcons.ChevronLeft,
                            contentDescription = "Back",
                            containerColor = Color.Black.copy(alpha = 0.36f),
                            tint = Color.White,
                            onClick = onBackClick,
                            modifier = Modifier.testTag("DetailBack")
                        )
                        AuricleCircleIconButton(
                            icon = AuricleIcons.More,
                            contentDescription = "More",
                            containerColor = Color.Black.copy(alpha = 0.36f),
                            tint = Color.White,
                            onClick = { onItemOptionsClick?.invoke(ContextMenuItem.Artist(artist)) }
                        )
                    }

                    // Overlapping Title & Subtitle at bottom of hero
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = artist.name,
                            style = AuricleTheme.typography.display,
                            color = AuricleTheme.colors.textPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (artist.subscribers.isNotBlank()) "Artist · ${artist.subscribers}" else "Artist",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                }
            }

            // 3-Button Action Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (artist.topSongs.isNotEmpty() && loadError == null) {
                        AuriclePrimaryButton(
                            text = "Play",
                            icon = AuricleIcons.Play,
                            modifier = Modifier.weight(1f),
                            height = 44.dp,
                            onClick = {
                                playbackController.playTrack(artist.topSongs.first(), artist.topSongs, artist.name)
                            }
                        )
                        AuricleSecondaryButton(
                            text = "Shuffle",
                            icon = AuricleIcons.Shuffle,
                            modifier = Modifier.weight(1f),
                            height = 44.dp,
                            onClick = {
                                playbackController.playTracksShuffled(artist.topSongs, artist.name)
                            }
                        )
                    }
                    AuricleOutlineButton(
                        text = if (isFollowing) "Following" else "Follow",
                        icon = if (isFollowing) AuricleIcons.Check else AuricleIcons.Plus,
                        height = 44.dp,
                        onClick = {
                            libraryRepo.toggleFollowArtist(
                                ArtistItem(
                                    browseId = artist.browseId,
                                    name = artist.name,
                                    subscribers = artist.subscribers,
                                    thumbnailUrl = artist.thumbnailUrl
                                )
                            )
                        }
                    )
                }
            }

            if (isLoading) {
                item {
                    FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
                }
            } else if (loadError != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AuricleStateCard(
                            icon = AuricleIcons.Refresh,
                            title = "Couldn't load artist",
                            subtitle = loadError,
                            actionLabel = "Retry",
                            onActionClick = onRetry
                        )
                    }
                }
            } else {
                // Top Songs Section
                if (artist.topSongs.isNotEmpty()) {
                    val displayedSongs = if (showAllTopSongs) artist.topSongs else artist.topSongs.take(5)
                    item {
                        Column {
                            AuricleSectionHeader(
                                title = "Top Songs",
                                showChevron = false,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            displayedSongs.forEachIndexed { idx, song ->
                                SongRow(
                                    song = song,
                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                    isActivelyPlaying = isPlaying,
                                    onClick = {
                                        playbackController.playTrack(song, listOf(song), artist.name)
                                    },
                                    onQuickAddQueue = {
                                        playbackController.addToQueue(song)
                                    },
                                    onOptionsClick = {
                                        onSongOptionsClick(song)
                                    },
                                    showDivider = idx < displayedSongs.lastIndex
                                )
                            }
                            if (artist.topSongs.size > 5) {
                                TextButton(
                                    onClick = { showAllTopSongs = !showAllTopSongs },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (showAllTopSongs) "Show less" else "Show all (${artist.topSongs.size})",
                                        style = AuricleTheme.typography.subSB,
                                        color = AuricleTheme.colors.accentStrong
                                    )
                                }
                            }
                        }
                    }
                }

                // Albums & Singles Section
                if (artistReleases.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AuricleSectionHeader(
                                title = "Albums & Singles",
                                showChevron = false,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                itemsIndexed(
                                    items = artistReleases,
                                    key = { idx, item -> "artist-rel-${item.browseId.ifBlank { item.title }}-$idx" }
                                ) { _, alb ->
                                    val typeLabel = alb.itemType.ifBlank { "album" }
                                        .replaceFirstChar { it.uppercase() }
                                    AlbumCard(
                                        title = alb.title,
                                        subtitle = if (alb.year.isNotBlank()) "$typeLabel · ${alb.year}" else typeLabel,
                                        artworkUrl = alb.displayThumbnailUrl,
                                        onClick = { onAlbumClick(alb) },
                                        onLongClick = onAlbumLongClick?.let { cb -> { cb(alb) } },
                                        width = 160.dp
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
                title = artist.name,
                visible = isCollapsed,
                navigationIcon = {
                    AuricleCircleIconButton(
                        icon = AuricleIcons.ChevronLeft,
                        contentDescription = "Back",
                        onClick = onBackClick
                    )
                }
            )
        }
    }
}

@Composable
private fun StandardDetailContent(
    target: DetailTarget,
    fullAlbum: AlbumItem?,
    fullPlaylist: PlaylistItem?,
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    libraryState: LibraryState,
    libraryRepo: LibraryRepository,
    currentPlayingTrack: SongItem?,
    isPlaying: Boolean,
    listState: LazyListState,
    isCollapsed: Boolean,
    playbackController: PlaybackController,
    onBackClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit,
    onPlaylistSongOptionsClick: ((SongItem, UserPlaylist) -> Unit)?,
    onItemOptionsClick: ((ContextMenuItem) -> Unit)?,
    onArtistClick: (ArtistItem) -> Unit,
    onBrowseHomeClick: () -> Unit,
    coverArtworkModifier: @Composable (String) -> Modifier,
    bottomContentPadding: Dp
) {
    val context = LocalContext.current
    var glowColor by remember(target) { mutableStateOf<Color?>(null) }

    var showRenameDialog by remember(target) { mutableStateOf(false) }
    var renameText by remember(target) { mutableStateOf("") }
    var showDeleteConfirm by remember(target) { mutableStateOf(false) }
    var showClearHistoryConfirm by remember(target) { mutableStateOf(false) }

    val currentCustomPlaylist = remember(target, libraryState) {
        if (target is DetailTarget.CustomUserPlaylist) {
            libraryState.playlists.find { it.id == target.playlistId } ?: target.fallbackPlaylist
        } else {
            null
        }
    }

    val cfg = remember(target, fullAlbum, fullPlaylist, libraryState, currentCustomPlaylist) {
        when (target) {
            is DetailTarget.Album -> {
                val alb = fullAlbum ?: target.albumItem
                DetailConfig(
                    title = alb.title,
                    subtitle = alb.artist,
                    yearOrCount = if (alb.year.isNotBlank()) "Album · ${alb.year}" else "Album",
                    artworkUrl = alb.highResThumbnailUrl,
                    tracks = alb.tracks,
                    isAlbum = true,
                    coverKey = alb.browseId.ifBlank { alb.title }
                )
            }
            is DetailTarget.Playlist -> {
                val pl = fullPlaylist ?: target.playlistItem
                DetailConfig(
                    title = pl.title,
                    subtitle = pl.author,
                    yearOrCount = "Playlist · ${pl.tracks.size} songs",
                    artworkUrl = pl.highResThumbnailUrl,
                    tracks = pl.tracks,
                    isAlbum = false,
                    coverKey = pl.playlistId
                )
            }
            is DetailTarget.CustomUserPlaylist -> {
                val pl = currentCustomPlaylist ?: target.fallbackPlaylist
                val countLabel = if (pl.tracks.size == 1) "1 song" else "${pl.tracks.size} songs"
                DetailConfig(
                    title = pl.name,
                    subtitle = "Custom Playlist",
                    yearOrCount = countLabel,
                    artworkUrl = pl.tracks.firstOrNull { it.highResArtworkUrl.isNotBlank() }?.highResArtworkUrl ?: "",
                    tracks = pl.tracks,
                    isAlbum = false,
                    coverKey = pl.id,
                    customPlaylistId = pl.id
                )
            }
            DetailTarget.LikedSongs -> {
                val countLabel = if (libraryState.likedSongs.size == 1) "1 song" else "${libraryState.likedSongs.size} songs"
                DetailConfig(
                    title = "Liked Songs",
                    subtitle = "Auto Playlist",
                    yearOrCount = countLabel,
                    artworkUrl = "",
                    tracks = libraryState.likedSongs,
                    isAlbum = false,
                    coverKey = "liked_songs",
                    isLikedSongs = true
                )
            }
            DetailTarget.RecentlyPlayed -> {
                val recentSongs = libraryState.recentlyPlayed.map { it.track }
                val countLabel = if (recentSongs.size == 1) "1 song" else "${recentSongs.size} songs"
                DetailConfig(
                    title = "Recently Played",
                    subtitle = "Playback History",
                    yearOrCount = countLabel,
                    artworkUrl = recentSongs.firstOrNull { it.highResArtworkUrl.isNotBlank() }?.highResArtworkUrl ?: "",
                    tracks = recentSongs,
                    isAlbum = false,
                    coverKey = "recently_played",
                    isRecentlyPlayed = true
                )
            }
            is DetailTarget.Artist -> error("Artist handled separately")
        }
    }
    val title = cfg.title
    val subtitle = cfg.subtitle
    val yearOrCount = cfg.yearOrCount
    val artworkUrl = cfg.artworkUrl
    val tracks = cfg.tracks
    val isAlbumType = cfg.isAlbum
    val coverKey = cfg.coverKey
    val customPlaylistId = cfg.customPlaylistId

    // Glow color extraction (structured within LaunchedEffect so target change cancels previous work)
    LaunchedEffect(target, artworkUrl) {
        if (artworkUrl.isBlank()) {
            glowColor = null
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                val req = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(false)
                    .size(150)
                    .build()
                val res = loader.execute(req)
                if (res is SuccessResult) {
                    val bitmap = res.drawable.toBitmap()
                    val palette = Palette.from(bitmap).generate()
                    val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                    swatch?.let { Color(it.rgb) }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (extracted != null) {
            glowColor = extracted
        }
    }

    // Custom playlist rename & delete dialogs
    if (showRenameDialog && customPlaylistId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist", style = AuricleTheme.typography.title2, color = AuricleTheme.colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AuricleTheme.colors.accent,
                        unfocusedBorderColor = AuricleTheme.colors.border,
                        focusedTextColor = AuricleTheme.colors.textPrimary,
                        unfocusedTextColor = AuricleTheme.colors.textPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        libraryRepo.renamePlaylist(customPlaylistId, renameText.trim())
                        showRenameDialog = false
                    }
                }) {
                    Text("Save", color = AuricleTheme.colors.accentStrong, style = AuricleTheme.typography.calloutSB)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = AuricleTheme.colors.textMuted)
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }

    if (showDeleteConfirm && customPlaylistId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Playlist", style = AuricleTheme.typography.title2, color = AuricleTheme.colors.textPrimary) },
            text = { Text("Are you sure you want to delete \"$title\"?", style = AuricleTheme.typography.body, color = AuricleTheme.colors.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    libraryRepo.deletePlaylist(customPlaylistId)
                    showDeleteConfirm = false
                    onBackClick()
                }) {
                    Text("Delete", color = AuricleTheme.colors.danger, style = AuricleTheme.typography.calloutSB)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = AuricleTheme.colors.textMuted)
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Clear Recently Played", style = AuricleTheme.typography.title2, color = AuricleTheme.colors.textPrimary) },
            text = { Text("Remove all songs from your playback history?", style = AuricleTheme.typography.body, color = AuricleTheme.colors.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    libraryRepo.clearHistory()
                    showClearHistoryConfirm = false
                }) {
                    Text("Clear", color = AuricleTheme.colors.danger, style = AuricleTheme.typography.calloutSB)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text("Cancel", color = AuricleTheme.colors.textMuted)
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }

    val glowAlpha = if (AuricleTheme.colors.isDark) 0.45f else 0.25f
    val resolvedGlow = glowColor ?: AuricleTheme.colors.accentStrong

    Box(modifier = Modifier.fillMaxSize()) {
        // Top 480 dp vertical Artwork Glow background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            resolvedGlow.copy(alpha = glowAlpha),
                            resolvedGlow.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent
                        )
                    )
                )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Nav Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuricleCircleIconButton(
                        icon = AuricleIcons.ChevronLeft,
                        contentDescription = "Back",
                        onClick = onBackClick,
                        modifier = Modifier.testTag("DetailBack")
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (target) {
                            is DetailTarget.CustomUserPlaylist -> {
                                val playlist = currentCustomPlaylist ?: target.fallbackPlaylist
                                AuricleCircleIconButton(
                                    icon = AuricleIcons.Edit,
                                    contentDescription = "Rename Playlist",
                                    onClick = {
                                        renameText = title
                                        showRenameDialog = true
                                    }
                                )
                                AuricleCircleIconButton(
                                    icon = AuricleIcons.Trash,
                                    contentDescription = "Delete Playlist",
                                    tint = AuricleTheme.colors.danger,
                                    onClick = { showDeleteConfirm = true }
                                )
                                AuricleCircleIconButton(
                                    icon = AuricleIcons.More,
                                    contentDescription = "More",
                                    onClick = { onItemOptionsClick?.invoke(ContextMenuItem.LocalPlaylist(playlist)) }
                                )
                            }
                            is DetailTarget.Album -> {
                                val currentAlbum = fullAlbum ?: target.albumItem
                                val isSaved = remember(libraryState, currentAlbum.browseId, currentAlbum.title, currentAlbum.artist) {
                                    libraryRepo.isAlbumSaved(currentAlbum.browseId, currentAlbum.title, currentAlbum.artist)
                                }
                                AuricleCircleIconButton(
                                    icon = if (isSaved) AuricleIcons.HeartFill else AuricleIcons.Heart,
                                    contentDescription = if (isSaved) "Remove album from library" else "Save album",
                                    tint = if (isSaved) AuricleTheme.colors.accent else AuricleTheme.colors.textPrimary,
                                    onClick = { libraryRepo.toggleSaveAlbum(currentAlbum) }
                                )
                                AuricleCircleIconButton(
                                    icon = AuricleIcons.More,
                                    contentDescription = "More",
                                    onClick = { onItemOptionsClick?.invoke(ContextMenuItem.Album(currentAlbum)) }
                                )
                            }
                            is DetailTarget.Playlist -> {
                                val currentPlaylist = fullPlaylist ?: target.playlistItem
                                val isSaved = remember(libraryState, currentPlaylist.playlistId, currentPlaylist.title) {
                                    libraryRepo.isPlaylistSaved(currentPlaylist.playlistId, currentPlaylist.title)
                                }
                                AuricleCircleIconButton(
                                    icon = if (isSaved) AuricleIcons.HeartFill else AuricleIcons.Heart,
                                    contentDescription = if (isSaved) "Remove playlist from library" else "Save playlist",
                                    tint = if (isSaved) AuricleTheme.colors.accent else AuricleTheme.colors.textPrimary,
                                    onClick = { libraryRepo.toggleSavePlaylist(currentPlaylist) }
                                )
                                AuricleCircleIconButton(
                                    icon = AuricleIcons.More,
                                    contentDescription = "More",
                                    onClick = { onItemOptionsClick?.invoke(ContextMenuItem.RemotePlaylist(currentPlaylist)) }
                                )
                            }
                            DetailTarget.RecentlyPlayed -> {
                                if (tracks.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(AuricleTheme.colors.fillMed)
                                            .auriclePressClickable { showClearHistoryConfirm = true }
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Clear",
                                            style = AuricleTheme.typography.subSB,
                                            color = AuricleTheme.colors.danger
                                        )
                                    }
                                }
                            }
                            DetailTarget.LikedSongs,
                            is DetailTarget.Artist -> Unit
                        }
                    }
                }
            }

            // Cover Artwork (248 dp)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        target is DetailTarget.LikedSongs -> {
                            Box(
                                modifier = Modifier
                                    .size(248.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuricleBrandGradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AuricleIcons.HeartFill,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(72.dp)
                                )
                            }
                        }
                        customPlaylistId != null -> {
                            val collageUrls = if (tracks.size < 4) {
                                tracks.map { it.highResArtworkUrl }.filter { it.isNotBlank() }
                            } else {
                                tracks.map { it.displayArtworkUrl }.filter { it.isNotBlank() }
                            }
                            PlaylistCollageArt(
                                artworkUrls = collageUrls,
                                size = 248.dp,
                                cornerRadius = 14.dp
                            )
                        }
                        target is DetailTarget.RecentlyPlayed -> {
                            Box(
                                modifier = Modifier
                                    .size(248.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuricleTheme.colors.surface2)
                                    .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AuricleIcons.Clock,
                                    contentDescription = null,
                                    tint = AuricleTheme.colors.accentStrong,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }
                        else -> {
                            Box(
                                modifier = coverArtworkModifier(coverKey)
                                    .size(248.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuricleTheme.colors.surface2)
                                    .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AuricleIcons.Music,
                                    contentDescription = null,
                                    tint = AuricleTheme.colors.textDim.copy(alpha = 0.55f),
                                    modifier = Modifier.size(64.dp)
                                )
                                if (artworkUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(artworkUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Cover Artwork",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Metadata: Title, Artist, Year/Count
            item {
                val albumForArtist = if (target is DetailTarget.Album) (fullAlbum ?: target.albumItem) else null
                val clickableArtistName = albumForArtist
                    ?.let { alb -> alb.artist.takeIf { it.isNotBlank() } ?: target.let { (it as DetailTarget.Album).albumItem.artist } }
                    ?.takeIf { it.isNotBlank() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = AuricleTheme.typography.title1,
                        color = AuricleTheme.colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = AuricleTheme.typography.headline,
                        color = if (clickableArtistName != null) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = if (clickableArtistName != null) {
                            Modifier.clickable {
                                val singleCredit = albumForArtist?.parsedArtistCredits()?.singleOrNull()
                                onArtistClick(
                                    ArtistItem(
                                        name = singleCredit?.name?.ifBlank { clickableArtistName } ?: clickableArtistName,
                                        browseId = singleCredit?.browseId.orEmpty(),
                                        thumbnailUrl = ""
                                    )
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = yearOrCount,
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Play & Shuffle Action Buttons
            if (tracks.isNotEmpty() && loadError == null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AuriclePrimaryButton(
                            text = "Play",
                            icon = AuricleIcons.Play,
                            height = 48.dp,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                playbackController.playTrack(tracks.first(), tracks, title)
                            }
                        )
                        AuricleSecondaryButton(
                            text = "Shuffle",
                            icon = AuricleIcons.Shuffle,
                            height = 48.dp,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                playbackController.playTracksShuffled(tracks, title)
                            }
                        )
                    }
                }
            }

            // Loading / Error / Empty / Tracks state
            if (isLoading) {
                item {
                    FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
                }
            } else if (loadError != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AuricleStateCard(
                            icon = AuricleIcons.Refresh,
                            title = "Couldn't load",
                            subtitle = loadError,
                            actionLabel = "Retry",
                            onActionClick = onRetry
                        )
                    }
                }
            } else if (tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AuricleStateCard(
                            icon = AuricleIcons.Heart,
                            title = "No songs here",
                            subtitle = "Explore tracks and add them to this collection.",
                            actionLabel = "Browse Home",
                            onActionClick = onBrowseHomeClick
                        )
                    }
                }
            } else {
                // Numbered Track Rows (for Album) or Song Rows (for Playlists)
                if (isAlbumType) {
                    itemsIndexed(
                        items = tracks,
                        key = { idx, song -> "album-track-${song.videoId.ifBlank { song.title }}-$idx" }
                    ) { idx, song ->
                        TrackRow(
                            index = idx + 1,
                            song = song,
                            isPlaying = currentPlayingTrack?.videoId == song.videoId,
                            isActivelyPlaying = isPlaying,
                            onClick = { playbackController.playTrack(song, tracks, title) },
                            onOptionsClick = { onSongOptionsClick(song) },
                            showDivider = idx < tracks.lastIndex
                        )
                    }
                } else {
                    itemsIndexed(
                        items = tracks,
                        key = { idx, song -> "detail-song-${song.videoId.ifBlank { song.title }}-$idx" }
                    ) { idx, song ->
                        SongRow(
                            song = song,
                            isPlaying = currentPlayingTrack?.videoId == song.videoId,
                            isActivelyPlaying = isPlaying,
                            onClick = { playbackController.playTrack(song, tracks, title) },
                            onQuickAddQueue = { playbackController.addToQueue(song) },
                            onOptionsClick = {
                                if (currentCustomPlaylist != null) {
                                    onPlaylistSongOptionsClick?.invoke(song, currentCustomPlaylist)
                                        ?: onSongOptionsClick(song)
                                } else {
                                    onSongOptionsClick(song)
                                }
                            },
                            showDivider = idx < tracks.lastIndex
                        )
                    }
                }

                // Footer track count
                item {
                    Text(
                        text = if (tracks.size == 1) "1 song" else "${tracks.size} songs",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
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
                title = title,
                visible = isCollapsed,
                navigationIcon = {
                    AuricleCircleIconButton(
                        icon = AuricleIcons.ChevronLeft,
                        contentDescription = "Back",
                        onClick = onBackClick
                    )
                }
            )
        }
    }
}

private data class DetailConfig(
    val title: String,
    val subtitle: String,
    val yearOrCount: String,
    val artworkUrl: String,
    val tracks: List<SongItem>,
    val isAlbum: Boolean,
    val coverKey: String = "",
    val customPlaylistId: String? = null,
    val isLikedSongs: Boolean = false,
    val isRecentlyPlayed: Boolean = false
)
