package com.auricle.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.*
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.SongRow
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    musicRepo: MusicRepository,
    playbackController: PlaybackController,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onPlaylistClick: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var searchResults by remember { mutableStateOf<SearchResultData?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentSearches by remember { mutableStateOf(listOf("Top Hits", "Chill Lofi", "Synthwave")) }
    var isLoading by remember { mutableStateOf(false) }

    val currentPlayingTrack by playbackController.currentTrack.collectAsState()

    var suggestionJob by remember { mutableStateOf<Job?>(null) }

    fun executeSearch(searchQuery: String, category: String = selectedCategory) {
        if (searchQuery.isBlank()) return
        keyboardController?.hide()
        suggestions = emptyList()

        if (!recentSearches.contains(searchQuery.trim())) {
            recentSearches = listOf(searchQuery.trim()) + recentSearches.take(9)
        }

        isLoading = true
        coroutineScope.launch {
            val catParam = when (category.lowercase()) {
                "songs" -> "songs"
                "albums" -> "albums"
                "artists" -> "artists"
                "playlists" -> "playlists"
                else -> "all"
            }
            val result = musicRepo.search(searchQuery.trim(), catParam)
            result.onSuccess { data ->
                searchResults = data
                isLoading = false
            }.onFailure {
                isLoading = false
            }
        }
    }

    LaunchedEffect(query) {
        if (query.trim().length >= 2 && searchResults == null) {
            suggestionJob?.cancel()
            suggestionJob = coroutineScope.launch {
                delay(200)
                val list = musicRepo.getSearchSuggestions(query.trim())
                suggestions = list
            }
        } else {
            suggestions = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.canvas)
    ) {
        // Search Input Bar
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isBlank()) {
                        searchResults = null
                    }
                },
                placeholder = {
                    Text(
                        text = "Search songs, albums, artists...",
                        fontSize = 14.sp,
                        color = AuricleTheme.colors.textMuted
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search icon",
                        tint = AuricleTheme.colors.textMuted
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(
                            onClick = {
                                query = ""
                                searchResults = null
                                suggestions = emptyList()
                            },
                            modifier = Modifier.semantics { contentDescription = "Clear search" }.testTag("ClearSearch")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = AuricleTheme.colors.textMuted
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AuricleTheme.colors.surface2,
                    unfocusedContainerColor = AuricleTheme.colors.surface,
                    focusedBorderColor = AuricleTheme.colors.accent,
                    unfocusedBorderColor = AuricleTheme.colors.border,
                    focusedTextColor = AuricleTheme.colors.textPrimary,
                    unfocusedTextColor = AuricleTheme.colors.textPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch(query) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SearchInput")
            )
        }

        // Category Filter Chips
        val categories = listOf("All", "Songs", "Albums", "Artists", "Playlists")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(categories) { category ->
                val isSelected = selectedCategory == category
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) AuricleTheme.colors.accent else AuricleTheme.colors.surface)
                        .border(1.dp, if (isSelected) AuricleTheme.colors.accent else AuricleTheme.colors.border, RoundedCornerShape(16.dp))
                        .clickable {
                            selectedCategory = category
                            if (query.isNotBlank()) {
                                executeSearch(query, category)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = category,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else AuricleTheme.colors.textPrimary
                    )
                }
            }
        }

        // Content Area
        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuricleTheme.colors.accent, strokeWidth = 3.dp)
                }
            } else if (suggestions.isNotEmpty() && searchResults == null) {
                // Live Autocomplete Suggestions
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(suggestions) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    query = item
                                    executeSearch(item)
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = AuricleTheme.colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = item,
                                fontSize = 14.sp,
                                color = AuricleTheme.colors.textPrimary
                            )
                        }
                    }
                }
            } else if (searchResults != null) {
                val res = searchResults!!

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Corrected Query Notice
                    if (!res.correctedQuery.isNullOrBlank()) {
                        item {
                            Text(
                                text = "Showing results for \"${res.correctedQuery}\"",
                                fontSize = 13.sp,
                                color = AuricleTheme.colors.accent,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    // Top Result
                    if (res.topResult != null) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text(
                                    text = "Top Result",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuricleTheme.colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SongRow(
                                    song = res.topResult,
                                    isPlaying = currentPlayingTrack?.videoId == res.topResult.videoId,
                                    onClick = {
                                        playbackController.playTrack(res.topResult, res.songs.ifEmpty { listOf(res.topResult) })
                                    },
                                    onQuickAddQueue = { playbackController.addToQueue(res.topResult) },
                                    onOptionsClick = { onSongOptionsClick(res.topResult) }
                                )
                            }
                        }
                    }

                    // Songs Results
                    if (res.songs.isNotEmpty()) {
                        item {
                            Text(
                                text = "Songs",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AuricleTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        items(res.songs) { song ->
                            SongRow(
                                song = song,
                                isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                onClick = {
                                    playbackController.playTrack(song, res.songs)
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

                    // Albums Results
                    if (res.albums.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Albums",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(res.albums) { album ->
                                        Column(
                                            modifier = Modifier
                                                .width(140.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onAlbumClick(album) }
                                                .padding(4.dp)
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(album.thumbnailUrl)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(132.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(AuricleTheme.colors.surface)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = album.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AuricleTheme.colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = album.artist,
                                                fontSize = 11.sp,
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

                    // Artists Results
                    if (res.artists.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Artists",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(res.artists) { artist ->
                                        Column(
                                            modifier = Modifier
                                                .width(120.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onArtistClick(artist) }
                                                .padding(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(artist.thumbnailUrl)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(100.dp)
                                                    .clip(CircleShape)
                                                    .background(AuricleTheme.colors.surface)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = artist.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AuricleTheme.colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Playlists Results
                    if (res.playlists.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Playlists",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(res.playlists) { playlist ->
                                        Column(
                                            modifier = Modifier
                                                .width(140.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onPlaylistClick(playlist) }
                                                .padding(4.dp)
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(playlist.thumbnailUrl)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(132.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(AuricleTheme.colors.surface)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = playlist.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AuricleTheme.colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = playlist.author,
                                                fontSize = 11.sp,
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
            } else {
                // Default: Recent Search History
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (recentSearches.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent Searches",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuricleTheme.colors.textPrimary
                                )
                                Text(
                                    text = "Clear All",
                                    fontSize = 12.sp,
                                    color = AuricleTheme.colors.accent,
                                    modifier = Modifier.clickable { recentSearches = emptyList() }
                                )
                            }
                        }

                        items(recentSearches) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        query = item
                                        executeSearch(item)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = AuricleTheme.colors.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = item,
                                        fontSize = 14.sp,
                                        color = AuricleTheme.colors.textPrimary
                                    )
                                }

                                IconButton(
                                    onClick = { recentSearches = recentSearches.filterNot { it == item } },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove",
                                        tint = AuricleTheme.colors.textDim,
                                        modifier = Modifier.size(16.dp)
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
