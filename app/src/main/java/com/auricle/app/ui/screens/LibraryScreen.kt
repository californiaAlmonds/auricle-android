package com.auricle.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleBrandGradient
import com.auricle.app.ui.theme.AuricleTheme

private fun formatRelativeTime(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "No history yet"
    val diffMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = diffMs / (60 * 1000)
    val hours = diffMs / (60 * 60 * 1000)
    val days = diffMs / (24 * 60 * 60 * 1000)
    return when {
        minutes < 2 -> "Last played just now"
        minutes < 60 -> "Last played $minutes m ago"
        hours < 24 -> "Last played $hours h ago"
        days == 1L -> "Last played yesterday"
        else -> "Last played $days d ago"
    }
}

@Composable
fun LibraryScreen(
    libraryRepo: LibraryRepository = (LocalContext.current.applicationContext as AuricleApp).libraryRepository,
    playbackController: PlaybackController,
    onSongOptionsClick: (SongItem) -> Unit,
    onUserPlaylistClick: (UserPlaylist) -> Unit,
    onLikedSongsClick: () -> Unit = {},
    onRecentlyPlayedClick: () -> Unit = {},
    onArtistClick: (ArtistItem) -> Unit = {},
    onAlbumClick: (AlbumItem) -> Unit = {},
    onBrowseHomeClick: () -> Unit = {},
    albumArtworkModifier: @Composable (String) -> Modifier = { Modifier },
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    val libraryState by libraryRepo.state.collectAsState()
    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedFilter by remember { mutableStateOf("All") }
    var filterQuery by remember { mutableStateOf("") }
    var isSearchFilterOpen by remember { mutableStateOf(false) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 72
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                newPlaylistName = ""
            },
            title = {
                Text(
                    text = "New Playlist",
                    style = AuricleTheme.typography.title2,
                    color = AuricleTheme.colors.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name", color = AuricleTheme.colors.textMuted) },
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
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            val created = libraryRepo.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreateDialog = false
                            onUserPlaylistClick(created)
                        }
                    }
                ) {
                    Text("Create", color = AuricleTheme.colors.accentStrong, style = AuricleTheme.typography.calloutSB)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        newPlaylistName = ""
                    }
                ) {
                    Text("Cancel", color = AuricleTheme.colors.textMuted, style = AuricleTheme.typography.callout)
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }

    @Composable
    fun HeaderActions() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AuricleCircleIconButton(
                icon = AuricleIcons.Search,
                contentDescription = "Filter library",
                onClick = {
                    isSearchFilterOpen = !isSearchFilterOpen
                    if (!isSearchFilterOpen) filterQuery = ""
                }
            )
            AuricleCircleIconButton(
                icon = AuricleIcons.Plus,
                contentDescription = "New Playlist",
                onClick = { showCreateDialog = true }
            )
        }
    }

    val libraryArtists = remember(libraryState) {
        val pool = libraryState.likedSongs + libraryState.recentlyPlayed.map { it.track }
        val map = mutableMapOf<String, SongItem>()
        for (s in pool) {
            if (s.artist.isNotBlank() && !map.containsKey(s.artist)) {
                map[s.artist] = s
            }
        }
        map.map { (artistName, sampleSong) ->
            ArtistItem(
                name = artistName,
                browseId = sampleSong.artistId,
                thumbnailUrl = sampleSong.displayArtworkUrl
            )
        }
    }

    val libraryAlbums = remember(libraryState) {
        val pool = libraryState.likedSongs + libraryState.recentlyPlayed.map { it.track }
        val map = mutableMapOf<String, SongItem>()
        for (s in pool) {
            val key = "${s.album}_${s.artist}"
            if (s.album.isNotBlank() && !map.containsKey(key)) {
                map[key] = s
            }
        }
        map.map { (_, sampleSong) ->
            AlbumItem(
                title = sampleSong.album,
                artist = sampleSong.artist,
                browseId = sampleSong.albumId,
                thumbnailUrl = sampleSong.displayArtworkUrl
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.bg)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Large Title Header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Library",
                            style = AuricleTheme.typography.large,
                            color = AuricleTheme.colors.textPrimary
                        )
                        HeaderActions()
                    }

                    // Optional Inline Library Filter text box
                    AnimatedVisibility(
                        visible = isSearchFilterOpen,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuricleTheme.colors.fillMed)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = AuricleIcons.Search,
                                    contentDescription = null,
                                    tint = AuricleTheme.colors.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = filterQuery,
                                    onValueChange = { filterQuery = it },
                                    singleLine = true,
                                    textStyle = AuricleTheme.typography.body.copy(color = AuricleTheme.colors.textPrimary),
                                    cursorBrush = SolidColor(AuricleTheme.colors.accent),
                                    modifier = Modifier.weight(1f)
                                )
                                if (filterQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = AuricleIcons.Close,
                                        contentDescription = "Clear",
                                        tint = AuricleTheme.colors.textDim,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { filterQuery = "" }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Filter Chips Row
            item {
                val filters = listOf("All", "Playlists", "Artists", "Albums", "Songs")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters) { filter ->
                        AuricleFilterChip(
                            label = filter,
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter }
                        )
                    }
                }
            }

            // Pinned 2 Tiles: Liked Songs & Recently Played (shown in "All" view)
            if (selectedFilter == "All") {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Liked Songs Pinned Tile
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AuricleBrandGradient)
                                .auriclePressClickable(onClick = onLikedSongsClick)
                                .padding(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = AuricleIcons.HeartFill,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Liked Songs",
                                        style = AuricleTheme.typography.headline,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${libraryState.likedSongs.size} ${if (libraryState.likedSongs.size == 1) "song" else "songs"}",
                                        style = AuricleTheme.typography.sub,
                                        color = Color.White.copy(alpha = 0.82f)
                                    )
                                }
                            }
                        }

                        // Recently Played Pinned Tile
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AuricleTheme.colors.surface2)
                                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp))
                                .auriclePressClickable(onClick = onRecentlyPlayedClick)
                                .padding(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = AuricleIcons.Clock,
                                    contentDescription = null,
                                    tint = AuricleTheme.colors.accentStrong,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Recently Played",
                                        style = AuricleTheme.typography.headline,
                                        color = AuricleTheme.colors.textPrimary
                                    )
                                    Text(
                                        text = formatRelativeTime(libraryState.recentlyPlayed.firstOrNull()?.playedAt),
                                        style = AuricleTheme.typography.sub,
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

            // Section Rendering based on Selected Filter
            when (selectedFilter) {
                "All" -> {
                    // 1. Playlists
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AuricleSectionHeader(
                                title = "Playlists",
                                actionText = "New playlist",
                                onActionClick = { showCreateDialog = true }
                            )

                            val matchingPlaylists = libraryState.playlists.filter {
                                filterQuery.isBlank() || it.name.contains(filterQuery, ignoreCase = true)
                            }

                            matchingPlaylists.forEach { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .auriclePressClickable { onUserPlaylistClick(playlist) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val artUrls = playlist.tracks.map { it.displayArtworkUrl }.filter { it.isNotBlank() }
                                    PlaylistCollageArt(
                                        artworkUrls = artUrls,
                                        size = 56.dp,
                                        cornerRadius = 8.dp
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = AuricleTheme.typography.bodyM,
                                            color = AuricleTheme.colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "Playlist · ${playlist.tracks.size} songs",
                                            style = AuricleTheme.typography.sub,
                                            color = AuricleTheme.colors.textMuted
                                        )
                                    }
                                    Icon(
                                        imageVector = AuricleIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = AuricleTheme.colors.textDim,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // "New Playlist" Action Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AuricleTheme.colors.fillSoft)
                                    .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(8.dp))
                                    .auriclePressClickable { showCreateDialog = true }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(AuricleTheme.colors.fillMed),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AuricleIcons.Plus,
                                        contentDescription = null,
                                        tint = AuricleTheme.colors.accentStrong,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = "New Playlist",
                                    style = AuricleTheme.typography.bodySB,
                                    color = AuricleTheme.colors.accentStrong
                                )
                            }
                        }
                    }

                    // 2. Artists Carousel
                    if (libraryArtists.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AuricleSectionHeader(
                                    title = "Artists",
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(libraryArtists, key = { it.name }) { artist ->
                                        ArtistCircleCard(
                                            name = artist.name,
                                            artworkUrl = artist.thumbnailUrl,
                                            onClick = { onArtistClick(artist) },
                                            size = 96.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Albums Carousel
                    if (libraryAlbums.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AuricleSectionHeader(
                                    title = "Albums",
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(libraryAlbums, key = { "${it.title}_${it.artist}" }) { album ->
                                        AlbumCard(
                                            title = album.title,
                                            subtitle = album.artist,
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

                    // Empty state check
                    if (libraryState.likedSongs.isEmpty() && libraryState.recentlyPlayed.isEmpty() && libraryState.playlists.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AuricleStateCard(
                                    icon = AuricleIcons.Heart,
                                    title = "No liked songs yet",
                                    subtitle = "Tap the heart on any song to save it here.",
                                    actionLabel = "Browse Home",
                                    onActionClick = onBrowseHomeClick
                                )
                            }
                        }
                    }
                }

                "Playlists" -> {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val playlists = libraryState.playlists.filter {
                                filterQuery.isBlank() || it.name.contains(filterQuery, ignoreCase = true)
                            }

                            if (playlists.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AuricleStateCard(
                                        icon = AuricleIcons.ListPlus,
                                        title = "No custom playlists",
                                        subtitle = "Create a playlist to organize your favorite tracks.",
                                        actionLabel = "Create Playlist",
                                        onActionClick = { showCreateDialog = true }
                                    )
                                }
                            } else {
                                playlists.forEach { playlist ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(68.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .auriclePressClickable { onUserPlaylistClick(playlist) }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val artUrls = playlist.tracks.map { it.displayArtworkUrl }.filter { it.isNotBlank() }
                                        PlaylistCollageArt(
                                            artworkUrls = artUrls,
                                            size = 56.dp,
                                            cornerRadius = 8.dp
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.name,
                                                style = AuricleTheme.typography.bodyM,
                                                color = AuricleTheme.colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "Playlist · ${playlist.tracks.size} songs",
                                                style = AuricleTheme.typography.sub,
                                                color = AuricleTheme.colors.textMuted
                                            )
                                        }
                                        Icon(
                                            imageVector = AuricleIcons.ChevronRight,
                                            contentDescription = null,
                                            tint = AuricleTheme.colors.textDim,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                "Artists" -> {
                    val matchingArtists = libraryArtists.filter {
                        filterQuery.isBlank() || it.name.contains(filterQuery, ignoreCase = true)
                    }
                    if (matchingArtists.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AuricleStateCard(
                                    icon = AuricleIcons.Artist,
                                    title = "No artists in library",
                                    subtitle = "Like songs or albums to see artists here.",
                                    actionLabel = "Browse Home",
                                    onActionClick = onBrowseHomeClick
                                )
                            }
                        }
                    } else {
                        item {
                            val rows = matchingArtists.chunked(3)
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                rows.forEach { rowArtists ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowArtists.forEach { artist ->
                                            ArtistCircleCard(
                                                name = artist.name,
                                                artworkUrl = artist.thumbnailUrl,
                                                onClick = { onArtistClick(artist) },
                                                size = 100.dp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(3 - rowArtists.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                "Albums" -> {
                    val matchingAlbums = libraryAlbums.filter {
                        filterQuery.isBlank() || it.title.contains(filterQuery, ignoreCase = true) || it.artist.contains(filterQuery, ignoreCase = true)
                    }
                    if (matchingAlbums.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AuricleStateCard(
                                    icon = AuricleIcons.Album,
                                    title = "No albums in library",
                                    subtitle = "Explore and save albums to build your collection.",
                                    actionLabel = "Browse Home",
                                    onActionClick = onBrowseHomeClick
                                )
                            }
                        }
                    } else {
                        item {
                            val rows = matchingAlbums.chunked(2)
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                rows.forEach { rowAlbums ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        rowAlbums.forEach { album ->
                                            AlbumCard(
                                                title = album.title,
                                                subtitle = album.artist,
                                                artworkUrl = album.thumbnailUrl,
                                                onClick = { onAlbumClick(album) },
                                                modifier = Modifier.weight(1f),
                                                artworkModifier = albumArtworkModifier(album.browseId.ifBlank { album.title })
                                            )
                                        }
                                        if (rowAlbums.size == 1) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                "Songs" -> {
                    val matchingSongs = libraryState.likedSongs.filter {
                        filterQuery.isBlank() || it.title.contains(filterQuery, ignoreCase = true) || it.artist.contains(filterQuery, ignoreCase = true)
                    }
                    if (matchingSongs.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AuricleStateCard(
                                    icon = AuricleIcons.Heart,
                                    title = "No liked songs yet",
                                    subtitle = "Tap the heart on any song to save it here.",
                                    actionLabel = "Browse Home",
                                    onActionClick = onBrowseHomeClick
                                )
                            }
                        }
                    } else {
                        item {
                            Column {
                                matchingSongs.forEachIndexed { idx, song ->
                                    SongRow(
                                        song = song,
                                        isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                        isActivelyPlaying = isPlaying,
                                        onClick = {
                                            playbackController.playTrack(song, matchingSongs, "Liked Songs")
                                        },
                                        onQuickAddQueue = {
                                            playbackController.addToQueue(song)
                                        },
                                        onOptionsClick = {
                                            onSongOptionsClick(song)
                                        },
                                        showDivider = idx < matchingSongs.lastIndex
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
                title = "Library",
                visible = isCollapsed,
                actions = { HeaderActions() }
            )
        }
    }
}
