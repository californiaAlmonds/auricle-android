package com.auricle.app.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.data.SettingsRepository
import com.auricle.app.model.*
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val SEARCH_PREFS_NAME = "auricle_search_prefs"
private const val KEY_DISMISSED_RECENT_SEARCHES = "dismissed_recent_searches"

private data class CategoryTile(
    val title: String,
    val startColor: Color,
    val endColor: Color
)

private val BrowseCategories = listOf(
    CategoryTile("Top Hits", Color(0xFFE2503F), Color(0xFFF2703A)),
    CategoryTile("Chill Lofi", Color(0xFF8B5CF6), Color(0xFF46A6E6)),
    CategoryTile("Bollywood", Color(0xFFF2703A), Color(0xFFB06A43)),
    CategoryTile("Punjabi", Color(0xFF3FA96A), Color(0xFF1699A8)),
    CategoryTile("Indie", Color(0xFF46A6E6), Color(0xFF1699A8)),
    CategoryTile("Romance", Color(0xFFE26AA0), Color(0xFFE2503F)),
    CategoryTile("Synthwave", Color(0xFFE26AA0), Color(0xFF8B5CF6)),
    CategoryTile("Bengali", Color(0xFFB06A43), Color(0xFFE2503F))
)

@Composable
fun SearchScreen(
    musicRepo: MusicRepository,
    libraryRepo: LibraryRepository = (LocalContext.current.applicationContext as AuricleApp).libraryRepository,
    playbackController: PlaybackController,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onPlaylistClick: (PlaylistItem) -> Unit,
    onAlbumLongClick: ((AlbumItem) -> Unit)? = null,
    onArtistLongClick: ((ArtistItem) -> Unit)? = null,
    onPlaylistLongClick: ((PlaylistItem) -> Unit)? = null,
    focusTrigger: Int = 0,
    savedQuery: String = "",
    onSavedQueryChange: (String) -> Unit = {},
    savedCategory: String = "All",
    onSavedCategoryChange: (String) -> Unit = {},
    savedResults: SearchResultData? = null,
    onSavedResultsChange: (SearchResultData?) -> Unit = {},
    albumArtworkModifier: @Composable (AlbumItem) -> Modifier = { Modifier },
    settingsRepo: SettingsRepository = (LocalContext.current.applicationContext as AuricleApp).settingsRepository,
    bottomContentPadding: Dp = 156.dp,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedLibraryRepo = libraryRepo
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    val settings by settingsRepo.settings.collectAsState()
    val currentPlayingTrack by playbackController.currentTrack.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()

    val searchPrefs = remember(context) {
        context.getSharedPreferences(SEARCH_PREFS_NAME, Context.MODE_PRIVATE)
    }
    var dismissedRecentSearches by remember {
        mutableStateOf(
            searchPrefs.getStringSet(KEY_DISMISSED_RECENT_SEARCHES, emptySet())?.toSet() ?: emptySet()
        )
    }

    var query by rememberSaveable { mutableStateOf(savedQuery) }
    var isSearchActive by rememberSaveable {
        mutableStateOf(savedQuery.isNotBlank() || savedResults != null)
    }
    var isFieldFocused by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(savedCategory) }
    var searchResults by remember { mutableStateOf(savedResults) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var searchJob by remember { mutableStateOf<Job?>(null) }
    var suggestionJob by remember { mutableStateOf<Job?>(null) }

    var lastHandledFocusTrigger by remember { mutableIntStateOf(focusTrigger) }
    var focusRequestNonce by remember { mutableIntStateOf(0) }
    var lastHandledFocusNonce by remember { mutableIntStateOf(0) }

    val collapseThresholdPx = remember(density) { with(density) { 56.dp.toPx() } }
    val listState = rememberLazyListState()
    val isCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseThresholdPx
        }
    }

    val isActiveMode = isSearchActive || query.isNotBlank() || searchResults != null

    fun updateQuery(newQuery: String) {
        query = newQuery
        onSavedQueryChange(newQuery)
    }

    fun updateCategory(newCategory: String) {
        selectedCategory = newCategory
        onSavedCategoryChange(newCategory)
    }

    fun updateResults(newResults: SearchResultData?) {
        searchResults = newResults
        onSavedResultsChange(newResults)
    }

    fun exitSearchMode() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        isLoading = false
        updateQuery("")
        updateResults(null)
        suggestions = emptyList()
        searchError = null
        updateCategory("All")
        isSearchActive = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun executeSearch(searchQuery: String, category: String = selectedCategory) {
        val trimmed = searchQuery.trim()
        if (trimmed.isBlank()) return
        searchJob?.cancel()
        suggestionJob?.cancel()
        keyboardController?.hide()
        focusManager.clearFocus()
        suggestions = emptyList()
        searchError = null

        if (dismissedRecentSearches.any { it.equals(trimmed, ignoreCase = true) }) {
            val updatedDismissed = dismissedRecentSearches
                .filterNot { it.equals(trimmed, ignoreCase = true) }
                .toSet()
            dismissedRecentSearches = updatedDismissed
            searchPrefs.edit().putStringSet(KEY_DISMISSED_RECENT_SEARCHES, updatedDismissed).apply()
        }
        settingsRepo.addRecentSearch(trimmed)

        isLoading = true
        searchJob = coroutineScope.launch {
            val catParam = when (category.lowercase()) {
                "songs" -> "songs"
                "albums" -> "albums"
                "artists" -> "artists"
                "playlists" -> "playlists"
                else -> "all"
            }
            val result = musicRepo.search(trimmed, catParam)
            if (query.trim() == trimmed) {
                result.onSuccess { data ->
                    updateResults(data)
                    searchError = null
                    isLoading = false
                }.onFailure { err ->
                    searchError = err.message?.takeIf { it.isNotBlank() } ?: "Couldn't complete search"
                    isLoading = false
                }
            }
        }
    }

    BackHandler(enabled = isActiveMode) {
        exitSearchMode()
    }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > lastHandledFocusTrigger) {
            lastHandledFocusTrigger = focusTrigger
            isSearchActive = true
            focusRequestNonce++
        }
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length >= 2 && searchResults == null) {
            suggestionJob?.cancel()
            suggestionJob = coroutineScope.launch {
                delay(200)
                val list = musicRepo.getSearchSuggestions(trimmed)
                if (searchResults == null && query.trim() == trimmed) {
                    suggestions = list
                }
            }
        } else {
            suggestionJob?.cancel()
            suggestions = emptyList()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search Input Header Bar
            if (isActiveMode) {
                LaunchedEffect(isSearchActive, focusRequestNonce) {
                    if (isSearchActive && focusRequestNonce != lastHandledFocusNonce) {
                        lastHandledFocusNonce = focusRequestNonce
                        yield()
                        runCatching { focusRequester.requestFocus() }
                        keyboardController?.show()
                    }
                }

                // Active Mode Header (Input + Cancel button)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Active Search Input Field
                    val boxInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuricleTheme.colors.fillMed)
                            .border(
                                1.5.dp,
                                if (isFieldFocused) AuricleTheme.colors.accent else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable(
                                interactionSource = boxInteractionSource,
                                indication = null
                            ) {
                                isSearchActive = true
                                runCatching { focusRequester.requestFocus() }
                                keyboardController?.show()
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = AuricleIcons.Search,
                                contentDescription = "Search",
                                tint = AuricleTheme.colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Songs, albums, artists",
                                        style = AuricleTheme.typography.body,
                                        color = AuricleTheme.colors.textMuted
                                    )
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = {
                                        searchJob?.cancel()
                                        isLoading = false
                                        updateQuery(it)
                                        updateResults(null)
                                        searchError = null
                                    },
                                    singleLine = true,
                                    textStyle = AuricleTheme.typography.body.copy(color = AuricleTheme.colors.textPrimary),
                                    cursorBrush = SolidColor(AuricleTheme.colors.accent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { executeSearch(query) }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { isFieldFocused = it.isFocused }
                                        .testTag("SearchInput")
                                )
                            }
                            if (query.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(AuricleTheme.colors.fillSoft)
                                        .clickable {
                                            searchJob?.cancel()
                                            suggestionJob?.cancel()
                                            isLoading = false
                                            updateQuery("")
                                            updateResults(null)
                                            suggestions = emptyList()
                                            searchError = null
                                            isSearchActive = true
                                            focusRequestNonce++
                                        }
                                        .testTag("ClearSearch"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AuricleIcons.Close,
                                        contentDescription = "Clear",
                                        tint = AuricleTheme.colors.textDim,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Cancel Text Button
                    Text(
                        text = "Cancel",
                        style = AuricleTheme.typography.bodySB,
                        color = AuricleTheme.colors.accentStrong,
                        modifier = Modifier
                            .clickable { exitSearchMode() }
                            .padding(vertical = 8.dp)
                    )
                }

                // Filter chips row
                val categories = listOf("All", "Songs", "Albums", "Artists", "Playlists")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    itemsIndexed(categories, key = { _, cat -> cat }) { _, cat ->
                        AuricleFilterChip(
                            label = cat,
                            selected = selectedCategory == cat,
                            onClick = {
                                updateCategory(cat)
                                if (query.isNotBlank()) {
                                    executeSearch(query, cat)
                                }
                            }
                        )
                    }
                }
            }

            // Body Area
            if (!isActiveMode) {
                // Browse Mode: Large Title + Search Input + Recent searches + Browse Categories
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
                            Text(
                                text = "Search",
                                style = AuricleTheme.typography.large,
                                color = AuricleTheme.colors.textPrimary
                            )
                            Spacer(Modifier.height(16.dp))

                            // Search Input Box
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AuricleTheme.colors.fillMed)
                                    .clickable {
                                        isSearchActive = true
                                        focusRequestNonce++
                                    }
                                    .padding(horizontal = 14.dp)
                                    .testTag("SearchInput"),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = AuricleIcons.Search,
                                        contentDescription = "Search",
                                        tint = AuricleTheme.colors.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "Songs, albums, artists",
                                        style = AuricleTheme.typography.body,
                                        color = AuricleTheme.colors.textMuted
                                    )
                                }
                            }
                        }
                    }

                    // Recent Searches
                    val recentList = settings.recentSearches.filterNot { term ->
                        dismissedRecentSearches.any { it.equals(term, ignoreCase = true) }
                    }
                    if (recentList.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Recent searches",
                                        style = AuricleTheme.typography.headline,
                                        color = AuricleTheme.colors.textPrimary
                                    )
                                    Text(
                                        text = "Clear",
                                        style = AuricleTheme.typography.subSB,
                                        color = AuricleTheme.colors.accentStrong,
                                        modifier = Modifier
                                            .clickable {
                                                settingsRepo.clearRecentSearches()
                                                dismissedRecentSearches = emptySet()
                                                searchPrefs.edit().remove(KEY_DISMISSED_RECENT_SEARCHES).apply()
                                            }
                                            .padding(4.dp)
                                    )
                                }

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(recentList, key = { idx, term -> "recent-$term-$idx" }) { _, term ->
                                        val chipShape = RoundedCornerShape(17.dp)
                                        Row(
                                            modifier = Modifier
                                                .height(34.dp)
                                                .clip(chipShape)
                                                .background(AuricleTheme.colors.fillSoft)
                                                .border(1.dp, AuricleTheme.colors.border, chipShape),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .auriclePressClickable(onClick = {
                                                        updateCategory("All")
                                                        updateQuery(term)
                                                        isSearchActive = true
                                                        executeSearch(term, "All")
                                                    })
                                                    .padding(start = 14.dp, end = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = term,
                                                    style = AuricleTheme.typography.subSB,
                                                    color = AuricleTheme.colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .padding(end = 4.dp)
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        val updatedDismissed = dismissedRecentSearches + term
                                                        dismissedRecentSearches = updatedDismissed
                                                        searchPrefs.edit()
                                                            .putStringSet(KEY_DISMISSED_RECENT_SEARCHES, updatedDismissed)
                                                            .apply()
                                                    }
                                                    .semantics { contentDescription = "Remove $term" },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = AuricleIcons.Close,
                                                    contentDescription = "Remove $term",
                                                    tint = AuricleTheme.colors.textMuted,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Browse Categories Section
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Browse categories",
                                style = AuricleTheme.typography.title2,
                                color = AuricleTheme.colors.textPrimary
                            )

                            // 2-column Category Tiles
                            val pairs = BrowseCategories.chunked(2)
                            pairs.forEach { rowPairs ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowPairs.forEach { cat ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(104.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(Brush.linearGradient(listOf(cat.startColor, cat.endColor)))
                                                .auriclePressClickable {
                                                    updateCategory("All")
                                                    updateQuery(cat.title)
                                                    isSearchActive = true
                                                    executeSearch(cat.title, "All")
                                                }
                                        ) {
                                            Text(
                                                text = cat.title,
                                                style = AuricleTheme.typography.headline,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .padding(14.dp)
                                                    .align(Alignment.TopStart)
                                            )
                                            // Decorative rotated card
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .offset(x = 10.dp, y = 10.dp)
                                                    .size(76.dp)
                                                    .graphicsLayer { rotationZ = 18f }
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.White.copy(alpha = 0.22f))
                                                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = AuricleIcons.Music,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (rowPairs.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Active Mode Body: Loading, Error, Suggestions, or Search Results
                Box(modifier = Modifier.weight(1f)) {
                    if (isLoading) {
                        FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
                    } else if (searchError != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp, vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AuricleStateCard(
                                icon = AuricleIcons.Search,
                                title = "Search failed",
                                subtitle = searchError ?: "Check your connection and try again.",
                                actionLabel = "Retry",
                                onActionClick = { executeSearch(query, selectedCategory) }
                            )
                        }
                    } else if (suggestions.isNotEmpty() && searchResults == null) {
                        // Live Autocomplete Suggestions
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            itemsIndexed(suggestions, key = { idx, item -> "suggestion-$item-$idx" }) { _, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            updateQuery(item)
                                            executeSearch(item, selectedCategory)
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = AuricleIcons.Search,
                                        contentDescription = null,
                                        tint = AuricleTheme.colors.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = item,
                                        style = AuricleTheme.typography.body,
                                        color = AuricleTheme.colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else if (searchResults != null) {
                        val res = searchResults!!
                        val hasVisibleResults = when (selectedCategory) {
                            "Songs" -> res.songs.isNotEmpty()
                            "Albums" -> res.albums.isNotEmpty()
                            "Artists" -> res.artists.isNotEmpty()
                            "Playlists" -> res.playlists.isNotEmpty()
                            else -> res.topResult != null ||
                                res.songs.isNotEmpty() ||
                                res.albums.isNotEmpty() ||
                                res.artists.isNotEmpty() ||
                                res.playlists.isNotEmpty()
                        }

                        if (!hasVisibleResults) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp, vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AuricleStateCard(
                                    icon = AuricleIcons.Search,
                                    title = "No results found",
                                    subtitle = if (selectedCategory == "All") {
                                        "Try a different song, album, or artist."
                                    } else {
                                        "No ${selectedCategory.lowercase()} matched \"${query.trim()}\"."
                                    }
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = bottomContentPadding),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                // Corrected Query Notice
                                if (!res.correctedQuery.isNullOrBlank()) {
                                    item {
                                        Text(
                                            text = "Showing results for \"${res.correctedQuery}\"",
                                            style = AuricleTheme.typography.subSB,
                                            color = AuricleTheme.colors.accentStrong,
                                            modifier = Modifier.padding(horizontal = 20.dp)
                                        )
                                    }
                                }

                                // 1. Top Result Card (in "All" category)
                                if (selectedCategory == "All" && (res.topResult != null || res.artists.isNotEmpty())) {
                                    val topArtist = res.artists.firstOrNull()
                                    val topSong = res.topResult

                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Top result",
                                                style = AuricleTheme.typography.headline,
                                                color = AuricleTheme.colors.textPrimary
                                            )

                                            if (topSong != null) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(104.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(AuricleTheme.colors.surface2)
                                                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp))
                                                        .auriclePressClickable(
                                                            onClick = {
                                                                playbackController.playTrack(
                                                                    topSong,
                                                                    listOf(topSong),
                                                                    "Search"
                                                                )
                                                            },
                                                            onLongClick = { onSongOptionsClick(topSong) },
                                                            onLongClickLabel = "More options"
                                                        )
                                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(topSong.displayArtworkUrl)
                                                            .crossfade(true)
                                                            .build(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(72.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(AuricleTheme.colors.surface)
                                                    )
                                                    Spacer(Modifier.width(14.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = topSong.title,
                                                            style = AuricleTheme.typography.title2,
                                                            color = AuricleTheme.colors.textPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(Modifier.height(2.dp))
                                                        Text(
                                                            text = "Song · ${topSong.artist}",
                                                            style = AuricleTheme.typography.sub,
                                                            color = AuricleTheme.colors.textMuted,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .minimumInteractiveComponentSize()
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .auriclePressClickable(onClick = { onSongOptionsClick(topSong) })
                                                            .semantics {
                                                                contentDescription = "More options for ${topSong.title}"
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = AuricleIcons.MoreVertical,
                                                            contentDescription = null,
                                                            tint = AuricleTheme.colors.textMuted,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                    AuricleCircleIconButton(
                                                        icon = AuricleIcons.Play,
                                                        contentDescription = "Play",
                                                        size = 44.dp,
                                                        iconSize = 18.dp,
                                                        containerColor = AuricleTheme.colors.primaryFill,
                                                        tint = AuricleTheme.colors.onPrimaryFill,
                                                        onClick = {
                                                            playbackController.playTrack(
                                                                topSong,
                                                                listOf(topSong),
                                                                "Search"
                                                            )
                                                        }
                                                    )
                                                }
                                            } else if (topArtist != null) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(104.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(AuricleTheme.colors.surface2)
                                                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp))
                                                        .auriclePressClickable(
                                                            onClick = { onArtistClick(topArtist) },
                                                            onLongClick = onArtistLongClick?.let { cb -> { cb(topArtist) } },
                                                            onLongClickLabel = "Artist options"
                                                        )
                                                        .padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(topArtist.thumbnailUrl)
                                                            .crossfade(true)
                                                            .build(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(72.dp)
                                                            .clip(CircleShape)
                                                            .background(AuricleTheme.colors.surface)
                                                    )
                                                    Spacer(Modifier.width(14.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = topArtist.name,
                                                            style = AuricleTheme.typography.title2,
                                                            color = AuricleTheme.colors.textPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(Modifier.height(2.dp))
                                                        Text(
                                                            text = "Artist",
                                                            style = AuricleTheme.typography.sub,
                                                            color = AuricleTheme.colors.textMuted
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 2. Songs Section
                                if (res.songs.isNotEmpty() && (selectedCategory == "All" || selectedCategory == "Songs")) {
                                    val songsToShow = if (selectedCategory == "All") res.songs.take(5) else res.songs
                                    item {
                                        Column {
                                            AuricleSectionHeader(
                                                title = "Songs",
                                                showChevron = false,
                                                modifier = Modifier.padding(horizontal = 20.dp)
                                            )
                                            songsToShow.forEachIndexed { idx, song ->
                                                SongRow(
                                                    song = song,
                                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                                    isActivelyPlaying = isPlaying,
                                                    onClick = {
                                                        playbackController.playTrack(song, res.songs, "Search Results")
                                                    },
                                                    onQuickAddQueue = {
                                                        playbackController.addToQueue(song)
                                                    },
                                                    onOptionsClick = {
                                                        onSongOptionsClick(song)
                                                    },
                                                    showDivider = idx < songsToShow.lastIndex
                                                )
                                            }
                                        }
                                    }
                                }

                                // 3. Albums Section
                                if (res.albums.isNotEmpty() && (selectedCategory == "All" || selectedCategory == "Albums")) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            AuricleSectionHeader(
                                                title = "Albums",
                                                showChevron = false,
                                                modifier = Modifier.padding(horizontal = 20.dp)
                                            )
                                            if (selectedCategory == "All") {
                                                LazyRow(
                                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                ) {
                                                    itemsIndexed(
                                                        res.albums,
                                                        key = { idx, album -> "search-album-${album.browseId.ifBlank { album.title }}-$idx" }
                                                    ) { _, album ->
                                                        AlbumCard(
                                                            title = album.title,
                                                            subtitle = if (album.year.isNotBlank()) "Album · ${album.artist}" else album.artist,
                                                            artworkUrl = album.thumbnailUrl,
                                                            onClick = { onAlbumClick(album) },
                                                            onLongClick = onAlbumLongClick?.let { cb -> { cb(album) } },
                                                            width = 160.dp,
                                                            artworkModifier = albumArtworkModifier(album)
                                                        )
                                                    }
                                                }
                                            } else {
                                                // 2-column grid in Albums filter
                                                val rows = res.albums.chunked(2)
                                                rows.forEach { rowAlbums ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 20.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                    ) {
                                                        rowAlbums.forEach { album ->
                                                            AlbumCard(
                                                                title = album.title,
                                                                subtitle = if (album.year.isNotBlank()) "Album · ${album.artist}" else album.artist,
                                                                artworkUrl = album.thumbnailUrl,
                                                                onClick = { onAlbumClick(album) },
                                                                onLongClick = onAlbumLongClick?.let { cb -> { cb(album) } },
                                                                modifier = Modifier.weight(1f),
                                                                artworkModifier = albumArtworkModifier(album)
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

                                // 4. Artists Section
                                if (res.artists.isNotEmpty() && (selectedCategory == "All" || selectedCategory == "Artists")) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            AuricleSectionHeader(
                                                title = "Artists",
                                                showChevron = false,
                                                modifier = Modifier.padding(horizontal = 20.dp)
                                            )
                                            if (selectedCategory == "All") {
                                                LazyRow(
                                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                ) {
                                                    itemsIndexed(
                                                        res.artists,
                                                        key = { idx, artist -> "search-artist-${artist.browseId.ifBlank { artist.name }}-$idx" }
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
                                            } else {
                                                // 2-column grid in Artists filter
                                                val rows = res.artists.chunked(2)
                                                rows.forEach { rowArtists ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 20.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                    ) {
                                                        rowArtists.forEach { artist ->
                                                            ArtistCircleCard(
                                                                name = artist.name,
                                                                artworkUrl = artist.thumbnailUrl,
                                                                onClick = { onArtistClick(artist) },
                                                                onLongClick = onArtistLongClick?.let { cb -> { cb(artist) } },
                                                                size = 128.dp,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        }
                                                        if (rowArtists.size == 1) {
                                                            Spacer(Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 5. Playlists Section
                                if (res.playlists.isNotEmpty() && (selectedCategory == "All" || selectedCategory == "Playlists")) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            AuricleSectionHeader(
                                                title = "Playlists",
                                                showChevron = false,
                                                modifier = Modifier.padding(horizontal = 20.dp)
                                            )
                                            if (selectedCategory == "All") {
                                                LazyRow(
                                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                ) {
                                                    itemsIndexed(
                                                        res.playlists,
                                                        key = { idx, playlist -> "search-playlist-${playlist.playlistId.ifBlank { playlist.title }}-$idx" }
                                                    ) { _, playlist ->
                                                        AlbumCard(
                                                            title = playlist.title,
                                                            subtitle = "Playlist · ${playlist.author}",
                                                            artworkUrl = playlist.thumbnailUrl,
                                                            onClick = { onPlaylistClick(playlist) },
                                                            onLongClick = onPlaylistLongClick?.let { cb -> { cb(playlist) } },
                                                            width = 160.dp
                                                        )
                                                    }
                                                }
                                            } else {
                                                // 2-column grid in Playlists filter
                                                val rows = res.playlists.chunked(2)
                                                rows.forEach { rowPlaylists ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 20.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                    ) {
                                                        rowPlaylists.forEach { playlist ->
                                                            AlbumCard(
                                                                title = playlist.title,
                                                                subtitle = "Playlist · ${playlist.author}",
                                                                artworkUrl = playlist.thumbnailUrl,
                                                                onClick = { onPlaylistClick(playlist) },
                                                                onLongClick = onPlaylistLongClick?.let { cb -> { cb(playlist) } },
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        }
                                                        if (rowPlaylists.size == 1) {
                                                            Spacer(Modifier.weight(1f))
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

        // Pinned Collapsed Top Bar for Browse Mode
        if (!isActiveMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                AuricleCollapsedTopBar(
                    title = "Search",
                    visible = isCollapsed,
                    actions = {}
                )
            }
        }
    }
}
