package com.auricle.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.PlaylistItem
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleBrandGradient
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch

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
private fun UserPlaylistRow(
    playlist: UserPlaylist,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .auriclePressClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Playlist options"
            )
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
            val count = playlist.tracks.size
            Text(
                text = "Playlist · $count ${if (count == 1) "song" else "songs"}",
                style = AuricleTheme.typography.sub,
                color = AuricleTheme.colors.textMuted
            )
        }
        if (onLongClick != null) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .semantics { contentDescription = "More options for ${playlist.name}" }
                    .auriclePressClickable(onClick = onLongClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.MoreVertical,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                imageVector = AuricleIcons.ChevronRight,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SavedPlaylistRow(
    playlist: PlaylistItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .auriclePressClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Playlist options"
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val artUrls = if (playlist.tracks.isNotEmpty()) {
            playlist.tracks.map { it.displayArtworkUrl }.filter { it.isNotBlank() }
        } else {
            listOfNotNull(playlist.thumbnailUrl.takeIf { it.isNotBlank() })
        }
        PlaylistCollageArt(
            artworkUrls = artUrls,
            size = 56.dp,
            cornerRadius = 8.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = AuricleTheme.typography.bodyM,
                color = AuricleTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = buildString {
                append("Saved playlist")
                if (playlist.author.isNotBlank()) append(" · ${playlist.author}")
                val count = if (playlist.trackCount > 0) playlist.trackCount else playlist.tracks.size
                if (count > 0) append(" · $count ${if (count == 1) "song" else "songs"}")
            }
            Text(
                text = subtitle,
                style = AuricleTheme.typography.sub,
                color = AuricleTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onLongClick != null) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .semantics { contentDescription = "More options for ${playlist.title}" }
                    .auriclePressClickable(onClick = onLongClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.MoreVertical,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                imageVector = AuricleIcons.ChevronRight,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim,
                modifier = Modifier.size(18.dp)
            )
        }
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
    onAlbumLongClick: ((AlbumItem) -> Unit)? = null,
    onArtistLongClick: ((ArtistItem) -> Unit)? = null,
    onPlaylistLongClick: ((UserPlaylist) -> Unit)? = null,
    onRemotePlaylistClick: ((PlaylistItem) -> Unit)? = null,
    onRemotePlaylistLongClick: ((PlaylistItem) -> Unit)? = null,
    onBrowseHomeClick: () -> Unit = {},
    albumArtworkModifier: @Composable (String) -> Modifier = { Modifier },
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    val libraryState by libraryRepo.state.collectAsState()
    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val filterFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    var selectedFilter by remember { mutableStateOf("All") }
    var filterQuery by remember { mutableStateOf("") }
    var isSearchFilterOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchFilterOpen || filterQuery.isNotBlank()) {
        filterQuery = ""
        isSearchFilterOpen = false
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 72
        }
    }

    LaunchedEffect(isSearchFilterOpen) {
        if (!isSearchFilterOpen) {
            focusManager.clearFocus()
            keyboardController?.hide()
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
                    val opening = !isSearchFilterOpen
                    isSearchFilterOpen = opening
                    if (opening) {
                        coroutineScope.launch { listState.animateScrollToItem(0) }
                    } else {
                        filterQuery = ""
                    }
                }
            )
            AuricleCircleIconButton(
                icon = AuricleIcons.Plus,
                contentDescription = "New Playlist",
                onClick = { showCreateDialog = true }
            )
        }
    }

    // Merge real persisted followedArtists first + derived artists from likedSongs/recentlyPlayed
    val libraryArtists = remember(libraryState) {
        val result = mutableListOf<ArtistItem>()
        val seenBrowseIds = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()

        for (artist in libraryState.followedArtists) {
            val normId = artist.browseId.trim()
            val normName = artist.name.trim().lowercase()
            if (normId.isBlank() && normName.isBlank()) continue
            if ((normId.isNotBlank() && normId in seenBrowseIds) ||
                (normName.isNotBlank() && normName in seenNames)
            ) {
                continue
            }
            result.add(artist)
            if (normId.isNotBlank()) seenBrowseIds.add(normId)
            if (normName.isNotBlank()) seenNames.add(normName)
        }

        val pool = libraryState.likedSongs + libraryState.recentlyPlayed.map { it.track }
        for (s in pool) {
            val trimmedName = s.artist.trim()
            if (trimmedName.isBlank()) continue
            val normId = s.artistId.trim()
            val normName = trimmedName.lowercase()
            if ((normId.isNotBlank() && normId in seenBrowseIds) || normName in seenNames) {
                continue
            }
            result.add(
                ArtistItem(
                    name = trimmedName,
                    browseId = normId,
                    thumbnailUrl = s.displayArtworkUrl
                )
            )
            if (normId.isNotBlank()) seenBrowseIds.add(normId)
            seenNames.add(normName)
        }
        result
    }

    // Merge real persisted savedAlbums first + derived albums from likedSongs/recentlyPlayed
    val libraryAlbums = remember(libraryState) {
        val result = mutableListOf<AlbumItem>()
        val seenBrowseIds = mutableSetOf<String>()
        val seenTitleArtists = mutableSetOf<String>()

        for (album in libraryState.savedAlbums) {
            val normId = album.browseId.trim()
            val titleArtistKey = "${album.title.trim().lowercase()}||${album.artist.trim().lowercase()}"
            if (normId.isBlank() && album.title.isBlank()) continue
            if ((normId.isNotBlank() && normId in seenBrowseIds) ||
                (album.title.isNotBlank() && titleArtistKey in seenTitleArtists)
            ) {
                continue
            }
            result.add(album)
            if (normId.isNotBlank()) seenBrowseIds.add(normId)
            if (album.title.isNotBlank()) seenTitleArtists.add(titleArtistKey)
        }

        val pool = libraryState.likedSongs + libraryState.recentlyPlayed.map { it.track }
        for (s in pool) {
            val trimmedAlbum = s.album.trim()
            if (trimmedAlbum.isBlank()) continue
            val trimmedArtist = s.artist.trim()
            val normId = s.albumId.trim()
            val titleArtistKey = "${trimmedAlbum.lowercase()}||${trimmedArtist.lowercase()}"
            if ((normId.isNotBlank() && normId in seenBrowseIds) || titleArtistKey in seenTitleArtists) {
                continue
            }
            result.add(
                AlbumItem(
                    title = trimmedAlbum,
                    artist = trimmedArtist,
                    browseId = normId,
                    thumbnailUrl = s.displayArtworkUrl
                )
            )
            if (normId.isNotBlank()) seenBrowseIds.add(normId)
            seenTitleArtists.add(titleArtistKey)
        }
        result
    }

    val matchingPlaylists = remember(libraryState.playlists, filterQuery) {
        if (filterQuery.isBlank()) libraryState.playlists
        else libraryState.playlists.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }
    val matchingSavedPlaylists = remember(libraryState.savedPlaylists, filterQuery) {
        if (filterQuery.isBlank()) libraryState.savedPlaylists
        else libraryState.savedPlaylists.filter {
            it.title.contains(filterQuery, ignoreCase = true) ||
                it.author.contains(filterQuery, ignoreCase = true)
        }
    }
    val matchingArtists = remember(libraryArtists, filterQuery) {
        if (filterQuery.isBlank()) libraryArtists
        else libraryArtists.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }
    val matchingAlbums = remember(libraryAlbums, filterQuery) {
        if (filterQuery.isBlank()) libraryAlbums
        else libraryAlbums.filter {
            it.title.contains(filterQuery, ignoreCase = true) ||
                it.artist.contains(filterQuery, ignoreCase = true)
        }
    }
    val matchingSongs = remember(libraryState.likedSongs, filterQuery) {
        if (filterQuery.isBlank()) libraryState.likedSongs
        else libraryState.likedSongs.filter {
            it.title.contains(filterQuery, ignoreCase = true) ||
                it.artist.contains(filterQuery, ignoreCase = true) ||
                it.album.contains(filterQuery, ignoreCase = true)
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
            contentPadding = PaddingValues(bottom = bottomContentPadding)
        ) {
            // Large Title Header
            item(key = "library-header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)
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
                        LaunchedEffect(Unit) {
                            runCatching { filterFocusRequester.requestFocus() }
                        }
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = AuricleIcons.Search,
                                    contentDescription = null,
                                    tint = AuricleTheme.colors.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (filterQuery.isEmpty()) {
                                        Text(
                                            text = "Filter your library",
                                            style = AuricleTheme.typography.body,
                                            color = AuricleTheme.colors.textMuted
                                        )
                                    }
                                    BasicTextField(
                                        value = filterQuery,
                                        onValueChange = { filterQuery = it },
                                        singleLine = true,
                                        textStyle = AuricleTheme.typography.body.copy(color = AuricleTheme.colors.textPrimary),
                                        cursorBrush = SolidColor(AuricleTheme.colors.accent),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(filterFocusRequester)
                                    )
                                }
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
            item(key = "library-filter-chips") {
                val filters = listOf("All", "Playlists", "Artists", "Albums", "Songs")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    items(filters, key = { it }) { filter ->
                        AuricleFilterChip(
                            label = filter,
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter }
                        )
                    }
                }
            }

            // Pinned 2 Tiles: Liked Songs & Recently Played (shown in "All" view when not filtering)
            if (selectedFilter == "All" && filterQuery.isBlank()) {
                item(key = "library-pinned-tiles") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp),
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
                    val hasAnyPlaylists = matchingPlaylists.isNotEmpty() || matchingSavedPlaylists.isNotEmpty()
                    if (hasAnyPlaylists || filterQuery.isBlank()) {
                        // 1. Playlists (custom + saved remote playlists, virtualized)
                        item(key = "all-playlists-header") {
                            AuricleSectionHeader(
                                title = "Playlists",
                                actionText = "New playlist",
                                onActionClick = { showCreateDialog = true },
                                modifier = Modifier
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 4.dp)
                            )
                        }

                        itemsIndexed(
                            items = matchingPlaylists,
                            key = { idx, pl -> "all-pl-${pl.id}-$idx" }
                        ) { _, playlist ->
                            UserPlaylistRow(
                                playlist = playlist,
                                onClick = { onUserPlaylistClick(playlist) },
                                onLongClick = onPlaylistLongClick?.let { cb -> { cb(playlist) } },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }

                        itemsIndexed(
                            items = matchingSavedPlaylists,
                            key = { idx, pl -> "all-spl-${pl.playlistId.ifBlank { pl.title }}-$idx" }
                        ) { _, playlist ->
                            SavedPlaylistRow(
                                playlist = playlist,
                                onClick = { onRemotePlaylistClick?.invoke(playlist) },
                                onLongClick = onRemotePlaylistLongClick?.let { cb -> { cb(playlist) } },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }

                        if (filterQuery.isBlank()) {
                            item(key = "all-new-playlist-row") {
                                // "New Playlist" Action Row
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .padding(top = 6.dp, bottom = 24.dp)
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
                        } else {
                            item(key = "all-playlists-spacer") {
                                Spacer(Modifier.height(20.dp))
                            }
                        }
                    }

                    // 2. Artists Carousel
                    if (matchingArtists.isNotEmpty()) {
                        item(key = "all-artists-carousel") {
                            Column(
                                modifier = Modifier.padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AuricleSectionHeader(
                                    title = "Artists",
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(
                                        items = matchingArtists,
                                        key = { idx, artist -> "all-art-${artist.browseId.ifBlank { artist.name }}-$idx" }
                                    ) { _, artist ->
                                        ArtistCircleCard(
                                            name = artist.name,
                                            artworkUrl = artist.thumbnailUrl,
                                            onClick = { onArtistClick(artist) },
                                            onLongClick = onArtistLongClick?.let { cb -> { cb(artist) } },
                                            size = 96.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Albums Carousel
                    if (matchingAlbums.isNotEmpty()) {
                        item(key = "all-albums-carousel") {
                            Column(
                                modifier = Modifier.padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AuricleSectionHeader(
                                    title = "Albums",
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(
                                        items = matchingAlbums,
                                        key = { idx, album ->
                                            "all-alb-${album.browseId.ifBlank { "${album.title}_${album.artist}" }}-$idx"
                                        }
                                    ) { _, album ->
                                        AlbumCard(
                                            title = album.title,
                                            subtitle = album.artist,
                                            artworkUrl = album.thumbnailUrl,
                                            onClick = { onAlbumClick(album) },
                                            onLongClick = onAlbumLongClick?.let { cb -> { cb(album) } },
                                            width = 160.dp,
                                            artworkModifier = albumArtworkModifier(album.browseId.ifBlank { album.title })
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Matching Songs (shown in "All" tab when inline filter is active)
                    if (filterQuery.isNotBlank() && matchingSongs.isNotEmpty()) {
                        item(key = "all-songs-header") {
                            AuricleSectionHeader(
                                title = "Songs",
                                showChevron = false,
                                modifier = Modifier
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 4.dp)
                            )
                        }
                        itemsIndexed(
                            items = matchingSongs,
                            key = { idx, song -> "all-song-${song.videoId}-$idx" }
                        ) { idx, song ->
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

                    // Empty state check
                    val isLibraryCompletelyEmpty = libraryState.likedSongs.isEmpty() &&
                        libraryState.recentlyPlayed.isEmpty() &&
                        libraryState.playlists.isEmpty() &&
                        libraryState.followedArtists.isEmpty() &&
                        libraryState.savedAlbums.isEmpty() &&
                        libraryState.savedPlaylists.isEmpty()

                    val isFilterEmpty = filterQuery.isNotBlank() &&
                        matchingPlaylists.isEmpty() &&
                        matchingSavedPlaylists.isEmpty() &&
                        matchingArtists.isEmpty() &&
                        matchingAlbums.isEmpty() &&
                        matchingSongs.isEmpty()

                    if (isFilterEmpty) {
                        item(key = "all-filter-empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AuricleStateCard(
                                    icon = AuricleIcons.Search,
                                    title = "No matching items",
                                    subtitle = "Try a different keyword or clear your filter.",
                                    actionLabel = "Clear filter",
                                    onActionClick = { filterQuery = "" }
                                )
                            }
                        }
                    } else if (filterQuery.isBlank() && isLibraryCompletelyEmpty) {
                        item(key = "all-empty") {
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
                    if (matchingPlaylists.isEmpty() && matchingSavedPlaylists.isEmpty()) {
                        item(key = "tab-playlists-empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (filterQuery.isNotBlank()) {
                                    AuricleStateCard(
                                        icon = AuricleIcons.Search,
                                        title = "No matching playlists",
                                        subtitle = "No playlists matched \"$filterQuery\".",
                                        actionLabel = "Clear filter",
                                        onActionClick = { filterQuery = "" }
                                    )
                                } else {
                                    AuricleStateCard(
                                        icon = AuricleIcons.ListPlus,
                                        title = "No playlists yet",
                                        subtitle = "Create a playlist or save one to organize your favorite tracks.",
                                        actionLabel = "Create Playlist",
                                        onActionClick = { showCreateDialog = true }
                                    )
                                }
                            }
                        }
                    } else {
                        if (matchingPlaylists.isNotEmpty()) {
                            if (matchingSavedPlaylists.isNotEmpty()) {
                                item(key = "tab-pl-header") {
                                    AuricleSectionHeader(
                                        title = "Your Playlists",
                                        showChevron = false,
                                        modifier = Modifier
                                            .padding(horizontal = 20.dp)
                                            .padding(bottom = 4.dp)
                                    )
                                }
                            }
                            itemsIndexed(
                                items = matchingPlaylists,
                                key = { idx, pl -> "tab-pl-${pl.id}-$idx" }
                            ) { _, playlist ->
                                UserPlaylistRow(
                                    playlist = playlist,
                                    onClick = { onUserPlaylistClick(playlist) },
                                    onLongClick = onPlaylistLongClick?.let { cb -> { cb(playlist) } },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (matchingSavedPlaylists.isNotEmpty()) {
                            item(key = "tab-spl-header") {
                                AuricleSectionHeader(
                                    title = "Saved Playlists",
                                    showChevron = false,
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .padding(
                                            top = if (matchingPlaylists.isNotEmpty()) 16.dp else 0.dp,
                                            bottom = 4.dp
                                        )
                                )
                            }
                            itemsIndexed(
                                items = matchingSavedPlaylists,
                                key = { idx, pl -> "tab-spl-${pl.playlistId.ifBlank { pl.title }}-$idx" }
                            ) { _, playlist ->
                                SavedPlaylistRow(
                                    playlist = playlist,
                                    onClick = { onRemotePlaylistClick?.invoke(playlist) },
                                    onLongClick = onRemotePlaylistLongClick?.let { cb -> { cb(playlist) } },
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                "Artists" -> {
                    if (matchingArtists.isEmpty()) {
                        item(key = "tab-artists-empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (filterQuery.isNotBlank()) {
                                    AuricleStateCard(
                                        icon = AuricleIcons.Search,
                                        title = "No matching artists",
                                        subtitle = "No artists matched \"$filterQuery\".",
                                        actionLabel = "Clear filter",
                                        onActionClick = { filterQuery = "" }
                                    )
                                } else {
                                    AuricleStateCard(
                                        icon = AuricleIcons.Artist,
                                        title = "No artists in library",
                                        subtitle = "Follow artists or like songs to see artists here.",
                                        actionLabel = "Browse Home",
                                        onActionClick = onBrowseHomeClick
                                    )
                                }
                            }
                        }
                    } else {
                        val rows = matchingArtists.chunked(3)
                        itemsIndexed(
                            items = rows,
                            key = { idx, _ -> "tab-art-row-$idx" }
                        ) { _, rowArtists ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowArtists.forEach { artist ->
                                    ArtistCircleCard(
                                        name = artist.name,
                                        artworkUrl = artist.thumbnailUrl,
                                        onClick = { onArtistClick(artist) },
                                        onLongClick = onArtistLongClick?.let { cb -> { cb(artist) } },
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

                "Albums" -> {
                    if (matchingAlbums.isEmpty()) {
                        item(key = "tab-albums-empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (filterQuery.isNotBlank()) {
                                    AuricleStateCard(
                                        icon = AuricleIcons.Search,
                                        title = "No matching albums",
                                        subtitle = "No albums matched \"$filterQuery\".",
                                        actionLabel = "Clear filter",
                                        onActionClick = { filterQuery = "" }
                                    )
                                } else {
                                    AuricleStateCard(
                                        icon = AuricleIcons.Album,
                                        title = "No albums in library",
                                        subtitle = "Explore and save albums to build your collection.",
                                        actionLabel = "Browse Home",
                                        onActionClick = onBrowseHomeClick
                                    )
                                }
                            }
                        }
                    } else {
                        val rows = matchingAlbums.chunked(2)
                        itemsIndexed(
                            items = rows,
                            key = { idx, _ -> "tab-alb-row-$idx" }
                        ) { _, rowAlbums ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                rowAlbums.forEach { album ->
                                    AlbumCard(
                                        title = album.title,
                                        subtitle = album.artist,
                                        artworkUrl = album.thumbnailUrl,
                                        onClick = { onAlbumClick(album) },
                                        onLongClick = onAlbumLongClick?.let { cb -> { cb(album) } },
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

                "Songs" -> {
                    if (matchingSongs.isEmpty()) {
                        item(key = "tab-songs-empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (filterQuery.isNotBlank()) {
                                    AuricleStateCard(
                                        icon = AuricleIcons.Search,
                                        title = "No matching songs",
                                        subtitle = "No liked songs matched \"$filterQuery\".",
                                        actionLabel = "Clear filter",
                                        onActionClick = { filterQuery = "" }
                                    )
                                } else {
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
                    } else {
                        itemsIndexed(
                            items = matchingSongs,
                            key = { idx, song -> "tab-song-${song.videoId}-$idx" }
                        ) { idx, song ->
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
