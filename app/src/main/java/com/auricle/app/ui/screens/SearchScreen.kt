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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
    val endColor: Color,
    val icon: ImageVector
)

private val BrowseCategories = listOf(
    CategoryTile("Top Hits", Color(0xFFD93829), Color(0xFFF26430), AuricleIcons.Flame),
    CategoryTile("Chill Lofi", Color(0xFF312E81), Color(0xFF4F46E5), AuricleIcons.Moon),
    CategoryTile("Bollywood", Color(0xFFA16207), Color(0xFFEAB308), AuricleIcons.Sparkles),
    CategoryTile("Punjabi", Color(0xFF047857), Color(0xFF10B981), AuricleIcons.Music),
    CategoryTile("Indie", Color(0xFF0369A1), Color(0xFF0EA5E9), AuricleIcons.Explore),
    CategoryTile("Romance", Color(0xFFBE185D), Color(0xFFF43F5E), AuricleIcons.Heart),
    CategoryTile("Synthwave", Color(0xFF7E22CE), Color(0xFFEC4899), AuricleIcons.Radio),
    CategoryTile("Bengali", Color(0xFF115E59), Color(0xFF06B6D4), AuricleIcons.Album)
)

private fun categoryToParam(category: String): String = when (category.lowercase()) {
    "songs" -> "songs"
    "albums" -> "albums"
    "artists" -> "artists"
    "playlists" -> "playlists"
    else -> "all"
}

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
    var textFieldSelection by remember { mutableStateOf(TextRange(query.length)) }
    var isSearchActive by rememberSaveable {
        mutableStateOf(savedQuery.isNotBlank() || savedResults != null)
    }
    var hasSubmittedExactQuery by rememberSaveable {
        mutableStateOf(savedResults != null && savedQuery.isNotBlank())
    }
    var isFieldFocused by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(savedCategory) }
    var searchResults by remember { mutableStateOf(savedResults) }
    var lastResultsQuery by remember { mutableStateOf(if (savedResults != null) savedQuery.trim() else null) }
    var lastResultsCategory by remember { mutableStateOf(if (savedResults != null) savedCategory else null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isLiveSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var liveRequestGen by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var liveSearchJob by remember { mutableStateOf<Job?>(null) }
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

    fun updateResults(newResults: SearchResultData?, forQuery: String? = null, forCategory: String? = null) {
        searchResults = newResults
        lastResultsQuery = if (newResults != null) forQuery else null
        lastResultsCategory = if (newResults != null) forCategory else null
        onSavedResultsChange(newResults)
    }

    fun unDismissRecentSearch(term: String) {
        if (dismissedRecentSearches.any { it.equals(term, ignoreCase = true) }) {
            val updatedDismissed = dismissedRecentSearches
                .filterNot { it.equals(term, ignoreCase = true) }
                .toSet()
            dismissedRecentSearches = updatedDismissed
            searchPrefs.edit().putStringSet(KEY_DISMISSED_RECENT_SEARCHES, updatedDismissed).apply()
        }
    }

    fun commitSearchInteraction() {
        focusManager.clearFocus()
        keyboardController?.hide()
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            unDismissRecentSearch(trimmed)
            settingsRepo.addRecentSearch(trimmed)
            hasSubmittedExactQuery = true
            suggestions = emptyList()
            suggestionJob?.cancel()
        }
    }

    fun exitSearchMode() {
        liveRequestGen++
        searchJob?.cancel()
        liveSearchJob?.cancel()
        suggestionJob?.cancel()
        isLoading = false
        isLiveSearching = false
        hasSubmittedExactQuery = false
        textFieldSelection = TextRange.Zero
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
        liveRequestGen++
        searchJob?.cancel()
        liveSearchJob?.cancel()
        suggestionJob?.cancel()
        isLiveSearching = false
        keyboardController?.hide()
        focusManager.clearFocus()
        hasSubmittedExactQuery = true
        suggestions = emptyList()
        searchError = null

        unDismissRecentSearch(trimmed)
        settingsRepo.addRecentSearch(trimmed)

        // Reuse already-fetched live results if exact query & category already loaded cleanly
        if (searchResults != null &&
            lastResultsQuery.equals(trimmed, ignoreCase = true) &&
            lastResultsCategory == category
        ) {
            isLoading = false
            return
        }

        val showFullSkeleton = searchResults == null ||
            !lastResultsQuery.equals(trimmed, ignoreCase = true) ||
            lastResultsCategory != category
        if (showFullSkeleton) {
            isLoading = true
        }
        val requestGen = liveRequestGen
        searchJob = coroutineScope.launch {
            val catParam = categoryToParam(category)
            val result = musicRepo.search(trimmed, catParam)
            if (liveRequestGen == requestGen && query.trim() == trimmed) {
                result.onSuccess { data ->
                    updateResults(data, forQuery = trimmed, forCategory = category)
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

    // Parallel debounced suggestions (180ms) + live search results (320ms) while typing
    LaunchedEffect(query, hasSubmittedExactQuery) {
        if (hasSubmittedExactQuery) {
            suggestionJob?.cancel()
            liveSearchJob?.cancel()
            isLiveSearching = false
            return@LaunchedEffect
        }

        val trimmed = query.trim()
        suggestionJob?.cancel()
        liveSearchJob?.cancel()

        if (trimmed.length < 2) {
            liveRequestGen++
            isLiveSearching = false
            suggestions = emptyList()
            return@LaunchedEffect
        }

        val gen = ++liveRequestGen
        val activeCategory = selectedCategory
        val catParam = categoryToParam(activeCategory)
        isLiveSearching = true

        suggestionJob = coroutineScope.launch {
            delay(180)
            val list = musicRepo.getSearchSuggestions(trimmed)
            if (liveRequestGen == gen && !hasSubmittedExactQuery && query.trim() == trimmed) {
                suggestions = list.take(4)
            }
        }

        liveSearchJob = coroutineScope.launch {
            delay(320)
            val result = musicRepo.search(trimmed, catParam)
            if (liveRequestGen == gen && !hasSubmittedExactQuery && query.trim() == trimmed) {
                result.onSuccess { data ->
                    updateResults(data, forQuery = trimmed, forCategory = activeCategory)
                    searchError = null
                }
                isLiveSearching = false
            }
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
                                val textFieldValue = TextFieldValue(
                                    text = query,
                                    selection = TextRange(
                                        textFieldSelection.start.coerceIn(0, query.length),
                                        textFieldSelection.end.coerceIn(0, query.length)
                                    )
                                )
                                BasicTextField(
                                    value = textFieldValue,
                                    onValueChange = { newValue ->
                                        textFieldSelection = newValue.selection
                                        if (newValue.text != query) {
                                            searchJob?.cancel()
                                            liveSearchJob?.cancel()
                                            suggestionJob?.cancel()
                                            isLoading = false
                                            hasSubmittedExactQuery = false
                                            searchError = null
                                            updateQuery(newValue.text)
                                            if (newValue.text.isBlank()) {
                                                liveRequestGen++
                                                isLiveSearching = false
                                                suggestions = emptyList()
                                                updateResults(null)
                                            }
                                        }
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
                                            liveRequestGen++
                                            searchJob?.cancel()
                                            liveSearchJob?.cancel()
                                            suggestionJob?.cancel()
                                            isLoading = false
                                            isLiveSearching = false
                                            hasSubmittedExactQuery = false
                                            textFieldSelection = TextRange.Zero
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

            // Recent searches shared filter
            val recentList = settings.recentSearches.filterNot { term ->
                dismissedRecentSearches.any { it.equals(term, ignoreCase = true) }
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
                                                        textFieldSelection = TextRange(term.length)
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
                                                    textFieldSelection = TextRange(cat.title.length)
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
                                            // Decorative rotated card with distinct category glyph
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .offset(x = 10.dp, y = 10.dp)
                                                    .size(76.dp)
                                                    .graphicsLayer { rotationZ = 18f }
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color.White.copy(alpha = 0.22f))
                                                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = cat.icon,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.90f),
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
                // Active Mode Body: Loading, Error, Live Suggestions + Live/Submitted Search Results
                Box(modifier = Modifier.weight(1f)) {
                    val showSuggestions = !hasSubmittedExactQuery && query.trim().length >= 2 && suggestions.isNotEmpty()
                    val res = searchResults

                    if (isLoading && res == null) {
                        FeedLoadingSkeleton(modifier = Modifier.fillMaxWidth())
                    } else if (searchError != null && res == null) {
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
                    } else if (query.isBlank() && res == null && recentList.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = bottomContentPadding),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item(key = "active-recent-header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 4.dp),
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
                            }
                            itemsIndexed(recentList, key = { idx, term -> "active-recent-$term-$idx" }) { _, term ->
                                SearchSuggestionRow(
                                    suggestion = term,
                                    leadingIcon = AuricleIcons.Clock,
                                    onSelect = {
                                        textFieldSelection = TextRange(term.length)
                                        updateQuery(term)
                                        executeSearch(term, selectedCategory)
                                    },
                                    onFillQuery = {
                                        hasSubmittedExactQuery = false
                                        textFieldSelection = TextRange(term.length)
                                        updateQuery(term)
                                        isSearchActive = true
                                        runCatching { focusRequester.requestFocus() }
                                        keyboardController?.show()
                                    }
                                )
                            }
                        }
                    } else {
                        val hasVisibleResults = if (res == null) false else when (selectedCategory) {
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

                        if (res != null && !hasVisibleResults && !showSuggestions && !isLiveSearching) {
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
                                contentPadding = PaddingValues(top = 4.dp, bottom = bottomContentPadding),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Subtle inline progress row during incremental typing or background category refresh
                                if (isLiveSearching || isLoading) {
                                    item(key = "live-searching-indicator") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = AuricleTheme.colors.accentStrong
                                            )
                                            Text(
                                                text = "Searching…",
                                                style = AuricleTheme.typography.sub,
                                                color = AuricleTheme.colors.textMuted
                                            )
                                        }
                                    }
                                }

                                // Top 4 compact autocomplete suggestion rows while typing
                                if (showSuggestions) {
                                    item(key = "suggestions-block") {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            suggestions.take(4).forEachIndexed { idx, item ->
                                                key("suggestion-$idx-$item") {
                                                    SearchSuggestionRow(
                                                        suggestion = item,
                                                        leadingIcon = AuricleIcons.Search,
                                                        onSelect = {
                                                            textFieldSelection = TextRange(item.length)
                                                            updateQuery(item)
                                                            executeSearch(item, selectedCategory)
                                                        },
                                                        onFillQuery = {
                                                            hasSubmittedExactQuery = false
                                                            textFieldSelection = TextRange(item.length)
                                                            updateQuery(item)
                                                            isSearchActive = true
                                                            runCatching { focusRequester.requestFocus() }
                                                            keyboardController?.show()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (res != null) {
                                    if (!hasVisibleResults) {
                                        if (!isLiveSearching && !isLoading) {
                                            item(key = "no-results-inline") {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
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
                                            }
                                        }
                                    } else {
                                        // Corrected Query Notice
                                        if (!res.correctedQuery.isNullOrBlank()) {
                                            item(key = "corrected-query") {
                                                Text(
                                                    text = "Showing results for \"${res.correctedQuery}\"",
                                                    style = AuricleTheme.typography.subSB,
                                                    color = AuricleTheme.colors.accentStrong,
                                                    modifier = Modifier.padding(horizontal = 20.dp)
                                                )
                                            }
                                        }

                                        // 1. Typed Top Result Card (in "All" category)
                                        if (selectedCategory == "All" && (res.topResult != null || res.artists.isNotEmpty())) {
                                            val top = res.topResult
                                            val fallbackArtist = if (top == null) res.artists.firstOrNull() else null

                                            item(key = "top-result-section") {
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

                                                    if (top != null) {
                                                        when (top.kind.lowercase()) {
                                                            "artist" -> {
                                                                val artistItem = top.toArtistItem()
                                                                val subtitle = "Artist" + if (top.subtitle.isNotBlank()) " · ${top.subtitle}" else ""
                                                                TopEntityResultCard(
                                                                    title = artistItem.name.ifBlank { top.title },
                                                                    subtitle = subtitle,
                                                                    artworkUrl = top.displayArtworkUrl,
                                                                    isCircular = true,
                                                                    placeholderIcon = AuricleIcons.Artist,
                                                                    onClick = {
                                                                        commitSearchInteraction()
                                                                        onArtistClick(artistItem)
                                                                    },
                                                                    onLongClick = onArtistLongClick?.let { cb ->
                                                                        {
                                                                            commitSearchInteraction()
                                                                            cb(artistItem)
                                                                        }
                                                                    },
                                                                    onLongClickLabel = "Artist options"
                                                                )
                                                            }
                                                            "album" -> {
                                                                val albumItem = top.toAlbumItem()
                                                                val artistLabel = albumItem.artist.ifBlank { top.subtitle }
                                                                val subtitle = if (artistLabel.isNotBlank()) "Album · $artistLabel" else "Album"
                                                                TopEntityResultCard(
                                                                    title = albumItem.title,
                                                                    subtitle = subtitle,
                                                                    artworkUrl = top.displayArtworkUrl,
                                                                    isCircular = false,
                                                                    placeholderIcon = AuricleIcons.Music,
                                                                    onClick = {
                                                                        commitSearchInteraction()
                                                                        onAlbumClick(albumItem)
                                                                    },
                                                                    onLongClick = onAlbumLongClick?.let { cb ->
                                                                        {
                                                                            commitSearchInteraction()
                                                                            cb(albumItem)
                                                                        }
                                                                    },
                                                                    onLongClickLabel = "Album options"
                                                                )
                                                            }
                                                            "playlist" -> {
                                                                val playlistItem = top.toPlaylistItem()
                                                                val subLabel = top.subtitle.ifBlank { playlistItem.author }
                                                                val subtitle = if (subLabel.isNotBlank()) "Playlist · $subLabel" else "Playlist"
                                                                TopEntityResultCard(
                                                                    title = playlistItem.title,
                                                                    subtitle = subtitle,
                                                                    artworkUrl = top.displayArtworkUrl,
                                                                    isCircular = false,
                                                                    placeholderIcon = AuricleIcons.Music,
                                                                    onClick = {
                                                                        commitSearchInteraction()
                                                                        onPlaylistClick(playlistItem)
                                                                    },
                                                                    onLongClick = onPlaylistLongClick?.let { cb ->
                                                                        {
                                                                            commitSearchInteraction()
                                                                            cb(playlistItem)
                                                                        }
                                                                    },
                                                                    onLongClickLabel = "Playlist options"
                                                                )
                                                            }
                                                            else -> {
                                                                val topSong = top.toSongItem()
                                                                TopSongResultCard(
                                                                    song = topSong,
                                                                    onPlay = {
                                                                        commitSearchInteraction()
                                                                        val playQueue = if (res.songs.any { it.videoId == topSong.videoId }) {
                                                                            res.songs
                                                                        } else {
                                                                            listOf(topSong) + res.songs
                                                                        }
                                                                        playbackController.playTrack(
                                                                            topSong,
                                                                            playQueue,
                                                                            "Search"
                                                                        )
                                                                    },
                                                                    onMoreOptions = {
                                                                        commitSearchInteraction()
                                                                        onSongOptionsClick(topSong)
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    } else if (fallbackArtist != null) {
                                                        val subtitle = "Artist" + if (fallbackArtist.subscribers.isNotBlank()) " · ${fallbackArtist.subscribers}" else ""
                                                        TopEntityResultCard(
                                                            title = fallbackArtist.name,
                                                            subtitle = subtitle,
                                                            artworkUrl = fallbackArtist.displayThumbnailUrl,
                                                            isCircular = true,
                                                            placeholderIcon = AuricleIcons.Artist,
                                                            onClick = {
                                                                commitSearchInteraction()
                                                                onArtistClick(fallbackArtist)
                                                            },
                                                            onLongClick = onArtistLongClick?.let { cb ->
                                                                {
                                                                    commitSearchInteraction()
                                                                    cb(fallbackArtist)
                                                                }
                                                            },
                                                            onLongClickLabel = "Artist options"
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // 2. Songs Section (filter out top song if top.kind == "song" and > 1 songs)
                                        val topSongId = if (res.topResult?.kind?.equals("song", ignoreCase = true) == true) {
                                            res.topResult.videoId.ifBlank { res.topResult.id }
                                        } else ""
                                        val dedupedSongs = if (
                                            selectedCategory == "All" &&
                                            topSongId.isNotBlank() &&
                                            res.songs.size > 1
                                        ) {
                                            res.songs.filterNot { it.videoId == topSongId }
                                        } else {
                                            res.songs
                                        }

                                        if (dedupedSongs.isNotEmpty() && (selectedCategory == "All" || selectedCategory == "Songs")) {
                                            val songsToShow = if (selectedCategory == "All") dedupedSongs.take(5) else dedupedSongs
                                            item(key = "songs-section") {
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
                                                                commitSearchInteraction()
                                                                playbackController.playTrack(song, res.songs, "Search Results")
                                                            },
                                                            onQuickAddQueue = {
                                                                commitSearchInteraction()
                                                                playbackController.addToQueue(song)
                                                            },
                                                            onOptionsClick = {
                                                                commitSearchInteraction()
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
                                            item(key = "albums-section") {
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
                                                                    artworkUrl = album.displayThumbnailUrl,
                                                                    onClick = {
                                                                        commitSearchInteraction()
                                                                        onAlbumClick(album)
                                                                    },
                                                                    onLongClick = onAlbumLongClick?.let { cb ->
                                                                        {
                                                                            commitSearchInteraction()
                                                                            cb(album)
                                                                        }
                                                                    },
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
                                                                        artworkUrl = album.displayThumbnailUrl,
                                                                        onClick = {
                                                                            commitSearchInteraction()
                                                                            onAlbumClick(album)
                                                                        },
                                                                        onLongClick = onAlbumLongClick?.let { cb ->
                                                                            {
                                                                                commitSearchInteraction()
                                                                                cb(album)
                                                                            }
                                                                        },
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
                                            item(key = "artists-section") {
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
                                                                    artworkUrl = artist.displayThumbnailUrl,
                                                                    onClick = {
                                                                        commitSearchInteraction()
                                                                        onArtistClick(artist)
                                                                    },
                                                                    onLongClick = onArtistLongClick?.let { cb ->
                                                                        {
                                                                            commitSearchInteraction()
                                                                            cb(artist)
                                                                        }
                                                                    },
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
                                                                        artworkUrl = artist.displayThumbnailUrl,
                                                                        onClick = {
                                                                            commitSearchInteraction()
                                                                            onArtistClick(artist)
                                                                        },
                                                                        onLongClick = onArtistLongClick?.let { cb ->
                                                                            {
                                                                                commitSearchInteraction()
                                                                                cb(artist)
                                                                            }
                                                                        },
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
                                            item(key = "playlists-section") {
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
                                                                    subtitle = if (playlist.author.isNotBlank()) "Playlist · ${playlist.author}" else "Playlist",
                                                                    artworkUrl = playlist.displayThumbnailUrl,
                                                                    onClick = {
                                                                        commitSearchInteraction()
                                                                        onPlaylistClick(playlist)
                                                                    },
                                                                    onLongClick = onPlaylistLongClick?.let { cb ->
                                                                        {
                                                                            commitSearchInteraction()
                                                                            cb(playlist)
                                                                        }
                                                                    },
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
                                                                        subtitle = if (playlist.author.isNotBlank()) "Playlist · ${playlist.author}" else "Playlist",
                                                                        artworkUrl = playlist.displayThumbnailUrl,
                                                                        onClick = {
                                                                            commitSearchInteraction()
                                                                            onPlaylistClick(playlist)
                                                                        },
                                                                        onLongClick = onPlaylistLongClick?.let { cb ->
                                                                            {
                                                                                commitSearchInteraction()
                                                                                cb(playlist)
                                                                            }
                                                                        },
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

@Composable
private fun SearchSuggestionRow(
    suggestion: String,
    leadingIcon: ImageVector,
    onSelect: () -> Unit,
    onFillQuery: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clickable(onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = AuricleTheme.colors.textMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = suggestion,
                style = AuricleTheme.typography.body,
                color = AuricleTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onFillQuery)
                .semantics { contentDescription = "Fill search query" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AuricleIcons.ArrowUpLeft,
                contentDescription = "Fill search query",
                tint = AuricleTheme.colors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TopEntityResultCard(
    title: String,
    subtitle: String,
    artworkUrl: String,
    isCircular: Boolean,
    placeholderIcon: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String
) {
    val context = LocalContext.current
    val artShape = if (isCircular) CircleShape else RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AuricleTheme.colors.surface2)
            .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp))
            .auriclePressClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(artShape)
                .background(AuricleTheme.colors.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim.copy(alpha = 0.5f),
                modifier = Modifier.size(28.dp)
            )
            if (artworkUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(artworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AuricleTheme.typography.title2,
                color = AuricleTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = AuricleTheme.typography.sub,
                color = AuricleTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AuricleTheme.colors.fillSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AuricleIcons.ChevronRight,
                contentDescription = null,
                tint = AuricleTheme.colors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TopSongResultCard(
    song: SongItem,
    onPlay: () -> Unit,
    onMoreOptions: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AuricleTheme.colors.surface2)
            .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp))
            .auriclePressClickable(
                onClick = onPlay,
                onLongClick = onMoreOptions,
                onLongClickLabel = "More options"
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AuricleTheme.colors.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AuricleIcons.Music,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim.copy(alpha = 0.45f),
                modifier = Modifier.size(28.dp)
            )
            if (song.displayArtworkUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(song.displayArtworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = AuricleTheme.typography.title2,
                color = AuricleTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val sub = buildString {
                append("Song")
                if (song.artist.isNotBlank()) append(" · ${song.artist}")
                if (song.displayDuration.isNotBlank()) append(" · ${song.displayDuration}")
            }
            Text(
                text = sub,
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
                .auriclePressClickable(onClick = onMoreOptions)
                .semantics {
                    contentDescription = "More options for ${song.title}"
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
            onClick = onPlay
        )
    }
}
