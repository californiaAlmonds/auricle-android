package com.auricle.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    coverArtworkModifier: @Composable (String) -> Modifier = { Modifier },
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val libraryState by libraryRepo.state.collectAsState()
    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()

    var fullAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var fullArtist by remember { mutableStateOf<ArtistItem?>(null) }
    var fullPlaylist by remember { mutableStateOf<PlaylistItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 90
        }
    }

    LaunchedEffect(target) {
        when (target) {
            is DetailTarget.Album -> {
                if (target.albumItem.tracks.isEmpty()) {
                    isLoading = true
                    coroutineScope.launch {
                        var browseId = target.albumItem.browseId
                        if (browseId.isBlank() && (target.albumItem.title.isNotBlank() || target.albumItem.artist.isNotBlank())) {
                            val q = "${target.albumItem.title} ${target.albumItem.artist}".trim()
                            musicRepo.search(q, "albums").onSuccess { sRes ->
                                browseId = sRes.albums.firstOrNull()?.browseId.orEmpty()
                            }
                        }
                        if (browseId.isNotBlank()) {
                            musicRepo.getAlbum(browseId).onSuccess { fullAlbum = it }
                        } else {
                            fullAlbum = target.albumItem
                        }
                        isLoading = false
                    }
                } else {
                    fullAlbum = target.albumItem
                }
            }
            is DetailTarget.Artist -> {
                if (target.artistItem.topSongs.isEmpty()) {
                    isLoading = true
                    coroutineScope.launch {
                        var browseId = target.artistItem.browseId
                        if (browseId.isBlank() && target.artistItem.name.isNotBlank()) {
                            musicRepo.search(target.artistItem.name, "artists").onSuccess { sRes ->
                                browseId = sRes.artists.firstOrNull()?.browseId.orEmpty()
                            }
                        }
                        if (browseId.isNotBlank()) {
                            musicRepo.getArtist(browseId).onSuccess { fetched ->
                                fullArtist = if (fetched.thumbnailUrl.isBlank()) {
                                    fetched.copy(thumbnailUrl = target.artistItem.thumbnailUrl)
                                } else {
                                    fetched
                                }
                            }
                        } else {
                            fullArtist = target.artistItem
                        }
                        isLoading = false
                    }
                } else {
                    fullArtist = target.artistItem
                }
            }
            is DetailTarget.Playlist -> {
                if (target.playlistItem.tracks.isEmpty() && target.playlistItem.playlistId.isNotBlank()) {
                    isLoading = true
                    coroutineScope.launch {
                        val res = musicRepo.getPlaylist(target.playlistItem.playlistId)
                        res.onSuccess { fullPlaylist = it }
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
                    artist = artist,
                    isLoading = isLoading,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    listState = listState,
                    isCollapsed = isCollapsed,
                    playbackController = playbackController,
                    onBackClick = onBackClick,
                    onSongOptionsClick = onSongOptionsClick,
                    onAlbumClick = onAlbumClick,
                    bottomContentPadding = bottomContentPadding
                )
            }
            else -> {
                StandardDetailContent(
                    target = target,
                    fullAlbum = fullAlbum,
                    fullPlaylist = fullPlaylist,
                    isLoading = isLoading,
                    libraryState = libraryState,
                    libraryRepo = libraryRepo,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    listState = listState,
                    isCollapsed = isCollapsed,
                    playbackController = playbackController,
                    onBackClick = onBackClick,
                    onSongOptionsClick = onSongOptionsClick,
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
    artist: ArtistItem,
    isLoading: Boolean,
    currentPlayingTrack: SongItem?,
    isPlaying: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isCollapsed: Boolean,
    playbackController: PlaybackController,
    onBackClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    bottomContentPadding: Dp
) {
    var isFollowing by remember { mutableStateOf(false) }

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
                    if (artist.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(artist.thumbnailUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Artist Hero Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
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
                                )
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
                            onClick = {}
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
                    AuriclePrimaryButton(
                        text = "Play",
                        icon = AuricleIcons.Play,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                        onClick = {
                            if (artist.topSongs.isNotEmpty()) {
                                playbackController.playTrack(artist.topSongs.first(), artist.topSongs, artist.name)
                            }
                        }
                    )
                    AuricleSecondaryButton(
                        text = "Shuffle",
                        icon = AuricleIcons.Shuffle,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                        onClick = {
                            if (artist.topSongs.isNotEmpty()) {
                                playbackController.playTracksShuffled(artist.topSongs, artist.name)
                            }
                        }
                    )
                    AuricleOutlineButton(
                        text = if (isFollowing) "Following" else "Follow",
                        icon = if (isFollowing) AuricleIcons.Check else AuricleIcons.Plus,
                        height = 44.dp,
                        onClick = { isFollowing = !isFollowing }
                    )
                }
            }

            if (isLoading) {
                item {
                    FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
                }
            } else {
                // Top Songs Section
                if (artist.topSongs.isNotEmpty()) {
                    item {
                        Column {
                            AuricleSectionHeader(
                                title = "Top Songs",
                                showChevron = false,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            artist.topSongs.take(5).forEachIndexed { idx, song ->
                                SongRow(
                                    song = song,
                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                    isActivelyPlaying = isPlaying,
                                    onClick = {
                                        playbackController.playTrack(song, artist.topSongs, artist.name)
                                    },
                                    onQuickAddQueue = {
                                        playbackController.addToQueue(song)
                                    },
                                    onOptionsClick = {
                                        onSongOptionsClick(song)
                                    },
                                    showDivider = idx < artist.topSongs.take(5).lastIndex
                                )
                            }
                        }
                    }
                }

                // Albums & Singles Section
                val artistReleases = artist.albums + artist.singles
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
                                items(artistReleases) { alb ->
                                    AlbumCard(
                                        title = alb.title,
                                        subtitle = if (alb.year.isNotBlank()) "Album · ${alb.year}" else "Album",
                                        artworkUrl = alb.thumbnailUrl,
                                        onClick = { onAlbumClick(alb) },
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
                actions = {
                    AuricleCircleIconButton(
                        icon = AuricleIcons.ChevronLeft,
                        contentDescription = "Back",
                        onClick = onBackClick,
                        modifier = Modifier.testTag("DetailBack")
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
    libraryState: LibraryState,
    libraryRepo: LibraryRepository,
    currentPlayingTrack: SongItem?,
    isPlaying: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isCollapsed: Boolean,
    playbackController: PlaybackController,
    onBackClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onBrowseHomeClick: () -> Unit,
    coverArtworkModifier: @Composable (String) -> Modifier,
    bottomContentPadding: Dp
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var glowColor by remember { mutableStateOf<Color?>(null) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val cfg = remember(target, fullAlbum, fullPlaylist, libraryState) {
        when (target) {
            is DetailTarget.Album -> {
                val alb = fullAlbum ?: target.albumItem
                DetailConfig(
                    title = alb.title,
                    subtitle = alb.artist,
                    yearOrCount = if (alb.year.isNotBlank()) "Album · ${alb.year}" else "Album",
                    artworkUrl = alb.thumbnailUrl,
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
                    artworkUrl = pl.thumbnailUrl,
                    tracks = pl.tracks,
                    isAlbum = false,
                    coverKey = pl.playlistId
                )
            }
            is DetailTarget.CustomUserPlaylist -> {
                val pl = libraryState.playlists.find { it.id == target.playlistId } ?: target.fallbackPlaylist
                DetailConfig(
                    title = pl.name,
                    subtitle = "Custom Playlist",
                    yearOrCount = "${pl.tracks.size} songs",
                    artworkUrl = pl.tracks.firstOrNull()?.displayArtworkUrl ?: "",
                    tracks = pl.tracks,
                    isAlbum = false,
                    coverKey = pl.id,
                    customPlaylistId = pl.id
                )
            }
            DetailTarget.LikedSongs -> {
                DetailConfig(
                    title = "Liked Songs",
                    subtitle = "Auto Playlist",
                    yearOrCount = "${libraryState.likedSongs.size} songs",
                    artworkUrl = "",
                    tracks = libraryState.likedSongs,
                    isAlbum = false,
                    coverKey = "liked_songs",
                    isLikedSongs = true
                )
            }
            DetailTarget.RecentlyPlayed -> {
                val recentSongs = libraryState.recentlyPlayed.map { it.track }
                DetailConfig(
                    title = "Recently Played",
                    subtitle = "Playback History",
                    yearOrCount = "${recentSongs.size} songs",
                    artworkUrl = recentSongs.firstOrNull()?.displayArtworkUrl ?: "",
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

    // Glow color extraction
    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNotBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val loader = ImageLoader(context)
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
                        if (swatch != null) {
                            glowColor = Color(swatch.rgb)
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
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
                        if (customPlaylistId != null) {
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
                        } else {
                            AuricleCircleIconButton(
                                icon = AuricleIcons.Heart,
                                contentDescription = "Like",
                                onClick = {}
                            )
                            AuricleCircleIconButton(
                                icon = AuricleIcons.More,
                                contentDescription = "More",
                                onClick = {}
                            )
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
                            PlaylistCollageArt(
                                artworkUrls = tracks.map { it.displayArtworkUrl }.filter { it.isNotBlank() },
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
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(artworkUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Cover Artwork",
                                contentScale = ContentScale.Crop,
                                modifier = coverArtworkModifier(coverKey)
                                    .size(248.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
                                    .background(AuricleTheme.colors.surface2)
                            )
                        }
                    }
                }
            }

            // Metadata: Title, Artist, Year/Count
            item {
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = AuricleTheme.typography.headline,
                        color = AuricleTheme.colors.accentStrong,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.clickable {
                            if (target is DetailTarget.Album && fullAlbum?.artist != null) {
                                onArtistClick(ArtistItem(name = fullAlbum!!.artist, browseId = "", thumbnailUrl = ""))
                            }
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = yearOrCount,
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Play & Shuffle Action Buttons
            if (tracks.isNotEmpty()) {
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

            // Loading state
            if (isLoading) {
                item {
                    FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
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
                    itemsIndexed(tracks) { idx, song ->
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
                    itemsIndexed(tracks) { idx, song ->
                        SongRow(
                            song = song,
                            isPlaying = currentPlayingTrack?.videoId == song.videoId,
                            isActivelyPlaying = isPlaying,
                            onClick = { playbackController.playTrack(song, tracks, title) },
                            onQuickAddQueue = { playbackController.addToQueue(song) },
                            onOptionsClick = { onSongOptionsClick(song) },
                            showDivider = idx < tracks.lastIndex
                        )
                    }
                }

                // Footer track count
                item {
                    Text(
                        text = "${tracks.size} songs",
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
                actions = {
                    AuricleCircleIconButton(
                        icon = AuricleIcons.ChevronLeft,
                        contentDescription = "Back",
                        onClick = onBackClick,
                        modifier = Modifier.testTag("DetailBack")
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
