package com.auricle.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auricle.app.AuricleApp
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.SearchResultData
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
import com.auricle.app.ui.components.AuricleBottomNav
import com.auricle.app.ui.components.BottomChromeFade
import com.auricle.app.ui.components.MiniPlayer
import com.auricle.app.ui.components.NavigationTab
import com.auricle.app.ui.screens.ContextMenuItem
import com.auricle.app.ui.screens.DetailScreen
import com.auricle.app.ui.screens.DetailTarget
import com.auricle.app.ui.screens.HomeScreen
import com.auricle.app.ui.screens.ItemOptionBottomSheet
import com.auricle.app.ui.screens.LibraryScreen
import com.auricle.app.ui.screens.NowPlayingSheet
import com.auricle.app.ui.screens.PartySheet
import com.auricle.app.ui.screens.SearchScreen
import com.auricle.app.ui.screens.SettingsSheet
import com.auricle.app.ui.screens.SongOptionBottomSheet
import com.auricle.app.ui.screens.UpdaterSheet
import com.auricle.app.ui.theme.AuricleAppTheme
import com.auricle.app.ui.theme.AuricleTheme
import com.auricle.app.ui.theme.LocalBackdropLayer
import com.auricle.app.updater.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    private var pendingPartyLink by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        handleIntent(intent)

        setContent {
            val app = application as AuricleApp
            val musicRepo = app.musicRepository
            val libraryRepo = app.libraryRepository
            val settingsRepo = app.settingsRepository
            val playbackController = app.playbackController
            val updateRepo = app.updateRepository

            val appSettings by settingsRepo.settings.collectAsState()
            val currentTrack by playbackController.currentTrack.collectAsState()
            val isPlaying by playbackController.isPlaying.collectAsState()
            val isLoading by playbackController.isLoading.collectAsState()
            val currentPosition by playbackController.currentPosition.collectAsState()
            val duration by playbackController.duration.collectAsState()
            val partySnapshot by playbackController.partySnapshot.collectAsState()
            val playbackError by playbackController.playbackError.collectAsState()
            val libraryState by libraryRepo.state.collectAsState()
            val updateState by updateRepo.state.collectAsState()
            val hasPendingUpdate by updateRepo.hasPendingUpdate.collectAsState()

            var selectedTab by remember { mutableStateOf(NavigationTab.HOME) }
            val detailBackStack = remember { mutableStateListOf<DetailTarget>() }
            val activeDetailTarget = detailBackStack.lastOrNull()
            var searchFocusTrigger by remember { mutableIntStateOf(0) }
            var searchQuery by remember { mutableStateOf("") }
            var searchCategory by remember { mutableStateOf("All") }
            var searchResults by remember { mutableStateOf<SearchResultData?>(null) }

            var showNowPlayingSheet by remember { mutableStateOf(false) }
            var showPartySheet by remember { mutableStateOf(false) }
            var prefilledPartyLink by remember { mutableStateOf("") }
            var showSettingsSheet by remember { mutableStateOf(false) }
            var showUpdaterSheet by remember { mutableStateOf(false) }
            var selectedSongForOptions by remember { mutableStateOf<SongItem?>(null) }
            var songOptionsPlaylistContext by remember { mutableStateOf<UserPlaylist?>(null) }
            var selectedItemForOptions by remember { mutableStateOf<ContextMenuItem?>(null) }

            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            fun isSameDetailTarget(a: DetailTarget?, b: DetailTarget?): Boolean {
                if (a == null || b == null) return false
                return when {
                    a is DetailTarget.Album && b is DetailTarget.Album -> {
                        val idA = a.albumItem.browseId
                        val idB = b.albumItem.browseId
                        if (idA.isNotBlank() && idB.isNotBlank()) {
                            idA == idB
                        } else {
                            a.albumItem.title.isNotBlank() &&
                                a.albumItem.title.equals(b.albumItem.title, ignoreCase = true) &&
                                (a.albumItem.artist.isBlank() || b.albumItem.artist.isBlank() ||
                                    a.albumItem.artist.equals(b.albumItem.artist, ignoreCase = true))
                        }
                    }
                    a is DetailTarget.Artist && b is DetailTarget.Artist -> {
                        val idA = a.artistItem.browseId
                        val idB = b.artistItem.browseId
                        if (idA.isNotBlank() && idB.isNotBlank()) {
                            idA == idB
                        } else {
                            a.artistItem.name.isNotBlank() &&
                                a.artistItem.name.equals(b.artistItem.name, ignoreCase = true)
                        }
                    }
                    a is DetailTarget.Playlist && b is DetailTarget.Playlist -> {
                        val idA = a.playlistItem.playlistId
                        val idB = b.playlistItem.playlistId
                        if (idA.isNotBlank() && idB.isNotBlank()) {
                            idA == idB
                        } else {
                            a.playlistItem.title.isNotBlank() &&
                                a.playlistItem.title.equals(b.playlistItem.title, ignoreCase = true)
                        }
                    }
                    a is DetailTarget.CustomUserPlaylist && b is DetailTarget.CustomUserPlaylist -> {
                        a.playlistId == b.playlistId
                    }
                    a is DetailTarget.LikedSongs && b is DetailTarget.LikedSongs -> true
                    a is DetailTarget.RecentlyPlayed && b is DetailTarget.RecentlyPlayed -> true
                    else -> false
                }
            }

            fun menuItemToDetailTarget(menuItem: ContextMenuItem): DetailTarget = when (menuItem) {
                is ContextMenuItem.Album -> DetailTarget.Album(menuItem.album)
                is ContextMenuItem.Artist -> DetailTarget.Artist(menuItem.artist)
                is ContextMenuItem.RemotePlaylist -> DetailTarget.Playlist(menuItem.playlist)
                is ContextMenuItem.LocalPlaylist -> DetailTarget.CustomUserPlaylist(menuItem.playlist)
            }

            fun pushDetail(target: DetailTarget) {
                if (!isSameDetailTarget(detailBackStack.lastOrNull(), target)) {
                    detailBackStack.add(target)
                }
            }

            fun popDetail() {
                if (detailBackStack.isNotEmpty()) {
                    detailBackStack.removeAt(detailBackStack.lastIndex)
                }
            }

            LaunchedEffect(currentTrack) {
                if (currentTrack == null) {
                    showNowPlayingSheet = false
                }
            }

            LaunchedEffect(playbackError) {
                val err = playbackError
                if (!err.isNullOrBlank()) {
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    playbackController.clearPlaybackError()
                }
            }

            LaunchedEffect(updateState) {
                val isUpdaterVisibleState = updateState is UpdateState.Available ||
                    updateState is UpdateState.Downloading ||
                    updateState is UpdateState.Ready ||
                    (updateState is UpdateState.Error && (updateState as UpdateState.Error).info != null)
                if (!isUpdaterVisibleState) {
                    showUpdaterSheet = false
                }
            }

            // Dynamic Album Art Accent Extraction
            var dynamicAccentColor by remember { mutableStateOf<Color?>(null) }

            LaunchedEffect(currentTrack?.videoId, appSettings.dynamicAlbumArtAccent) {
                val track = currentTrack
                if (appSettings.dynamicAlbumArtAccent && track != null && track.displayArtworkUrl.isNotBlank()) {
                    val extracted = withContext(Dispatchers.IO) {
                        try {
                            val request = ImageRequest.Builder(context)
                                .data(track.displayArtworkUrl)
                                .size(200)
                                .allowHardware(false)
                                .build()
                            val result = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                            val bitmap = (result as? BitmapDrawable)?.bitmap
                            if (bitmap != null) {
                                val palette = Palette.from(bitmap).generate()
                                val vibrant = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb
                                vibrant?.let { Color(it) }
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Palette extraction error: ${e.message}")
                            null
                        }
                    }
                    dynamicAccentColor = extracted
                } else {
                    dynamicAccentColor = null
                }
            }

            // Open Party Sheet with pre-filled invite link on deep link
            LaunchedEffect(pendingPartyLink) {
                val link = pendingPartyLink
                if (!link.isNullOrBlank()) {
                    prefilledPartyLink = link
                    showPartySheet = true
                    pendingPartyLink = null
                }
            }

            AuricleAppTheme(
                themeMode = appSettings.themeMode,
                accentPreset = appSettings.accentPreset,
                dynamicAccentColor = dynamicAccentColor,
                forceLightStatusBarIcons = showNowPlayingSheet
            ) {
                val reducedMotion = AuricleTheme.reducedMotion
                val backdropLayer = rememberGraphicsLayer()

                BackHandler(
                    enabled = showUpdaterSheet ||
                        selectedItemForOptions != null ||
                        selectedSongForOptions != null ||
                        showPartySheet ||
                        showSettingsSheet ||
                        showNowPlayingSheet ||
                        detailBackStack.isNotEmpty()
                ) {
                    when {
                        showUpdaterSheet -> showUpdaterSheet = false
                        selectedItemForOptions != null -> selectedItemForOptions = null
                        selectedSongForOptions != null -> {
                            selectedSongForOptions = null
                            songOptionsPlaylistContext = null
                        }
                        showPartySheet -> {
                            prefilledPartyLink = ""
                            showPartySheet = false
                        }
                        showSettingsSheet -> showSettingsSheet = false
                        showNowPlayingSheet -> showNowPlayingSheet = false
                        detailBackStack.isNotEmpty() -> popDetail()
                    }
                }

                val bottomContentPadding = if (currentTrack != null) 168.dp else 100.dp

                SharedTransitionLayout(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AuricleTheme.colors.bg)
                ) {
                    val sharedLayoutScope = this

                    CompositionLocalProvider(LocalBackdropLayer provides backdropLayer) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(AuricleTheme.colors.bg)
                        ) {
                            // Scrollable screen content recorded into backdropLayer for API 31+ Glass Blur
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        backdropLayer.record {
                                            this@drawWithContent.drawContent()
                                        }
                                        drawContent()
                                    }
                            ) {
                                AnimatedContent(
                                    targetState = activeDetailTarget,
                                    transitionSpec = {
                                        if (reducedMotion) {
                                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                        } else {
                                            fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                                        }
                                    },
                                    label = "detailNav"
                                ) { detailTarget ->
                                    if (detailTarget != null) {
                                        DetailScreen(
                                            target = detailTarget,
                                            musicRepo = musicRepo,
                                            libraryRepo = libraryRepo,
                                            playbackController = playbackController,
                                            onBackClick = { popDetail() },
                                            onSongOptionsClick = {
                                                songOptionsPlaylistContext = null
                                                selectedSongForOptions = it
                                            },
                                            onAlbumClick = { pushDetail(DetailTarget.Album(it)) },
                                            onArtistClick = { pushDetail(DetailTarget.Artist(it)) },
                                            onBrowseHomeClick = {
                                                detailBackStack.clear()
                                                selectedTab = NavigationTab.HOME
                                            },
                                            onAlbumLongClick = { selectedItemForOptions = ContextMenuItem.Album(it) },
                                            onPlaylistSongOptionsClick = { song, playlist ->
                                                songOptionsPlaylistContext = playlist
                                                selectedSongForOptions = song
                                            },
                                            onItemOptionsClick = { selectedItemForOptions = it },
                                            bottomContentPadding = bottomContentPadding
                                        )
                                    } else {
                                        when (selectedTab) {
                                            NavigationTab.HOME -> {
                                                HomeScreen(
                                                    musicRepo = musicRepo,
                                                    libraryRepo = libraryRepo,
                                                    playbackController = playbackController,
                                                    partySnapshot = partySnapshot,
                                                    hasUpdateBadge = hasPendingUpdate,
                                                    onPartyClick = { showPartySheet = true },
                                                    onSettingsClick = { showSettingsSheet = true },
                                                    onSongOptionsClick = {
                                                        songOptionsPlaylistContext = null
                                                        selectedSongForOptions = it
                                                    },
                                                    onAlbumClick = { pushDetail(DetailTarget.Album(it)) },
                                                    onAlbumLongClick = { selectedItemForOptions = ContextMenuItem.Album(it) },
                                                    onArtistLongClick = { selectedItemForOptions = ContextMenuItem.Artist(it) },
                                                    onPlaylistClick = { pushDetail(DetailTarget.Playlist(it)) },
                                                    onPlaylistLongClick = { selectedItemForOptions = ContextMenuItem.RemotePlaylist(it) },
                                                    bottomContentPadding = bottomContentPadding
                                                )
                                            }
                                            NavigationTab.SEARCH -> {
                                                SearchScreen(
                                                    musicRepo = musicRepo,
                                                    libraryRepo = libraryRepo,
                                                    playbackController = playbackController,
                                                    onSongOptionsClick = {
                                                        songOptionsPlaylistContext = null
                                                        selectedSongForOptions = it
                                                    },
                                                    onAlbumClick = { pushDetail(DetailTarget.Album(it)) },
                                                    onArtistClick = { pushDetail(DetailTarget.Artist(it)) },
                                                    onPlaylistClick = { pushDetail(DetailTarget.Playlist(it)) },
                                                    onAlbumLongClick = { selectedItemForOptions = ContextMenuItem.Album(it) },
                                                    onArtistLongClick = { selectedItemForOptions = ContextMenuItem.Artist(it) },
                                                    onPlaylistLongClick = { selectedItemForOptions = ContextMenuItem.RemotePlaylist(it) },
                                                    focusTrigger = searchFocusTrigger,
                                                    savedQuery = searchQuery,
                                                    onSavedQueryChange = { searchQuery = it },
                                                    savedCategory = searchCategory,
                                                    onSavedCategoryChange = { searchCategory = it },
                                                    savedResults = searchResults,
                                                    onSavedResultsChange = { searchResults = it },
                                                    settingsRepo = settingsRepo,
                                                    bottomContentPadding = bottomContentPadding
                                                )
                                            }
                                            NavigationTab.LIBRARY -> {
                                                LibraryScreen(
                                                    libraryRepo = libraryRepo,
                                                    playbackController = playbackController,
                                                    onSongOptionsClick = {
                                                        songOptionsPlaylistContext = null
                                                        selectedSongForOptions = it
                                                    },
                                                    onUserPlaylistClick = { pushDetail(DetailTarget.CustomUserPlaylist(it)) },
                                                    onLikedSongsClick = { pushDetail(DetailTarget.LikedSongs) },
                                                    onRecentlyPlayedClick = { pushDetail(DetailTarget.RecentlyPlayed) },
                                                    onArtistClick = { pushDetail(DetailTarget.Artist(it)) },
                                                    onAlbumClick = { pushDetail(DetailTarget.Album(it)) },
                                                    onAlbumLongClick = { selectedItemForOptions = ContextMenuItem.Album(it) },
                                                    onArtistLongClick = { selectedItemForOptions = ContextMenuItem.Artist(it) },
                                                    onPlaylistLongClick = { selectedItemForOptions = ContextMenuItem.LocalPlaylist(it) },
                                                    onRemotePlaylistClick = { pushDetail(DetailTarget.Playlist(it)) },
                                                    onRemotePlaylistLongClick = { selectedItemForOptions = ContextMenuItem.RemotePlaylist(it) },
                                                    onBrowseHomeClick = { selectedTab = NavigationTab.HOME },
                                                    bottomContentPadding = bottomContentPadding
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Floating Bottom Chrome: Gradient Fade + Glass MiniPlayer + Glass BottomNav
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                BottomChromeFade(
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AnimatedVisibility(
                                        visible = currentTrack != null && !showNowPlayingSheet,
                                        enter = if (reducedMotion) fadeIn(tween(0)) else slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                                        exit = if (reducedMotion) fadeOut(tween(0)) else slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                                    ) {
                                        val miniScope = this
                                        val miniArtMod = if (!reducedMotion && currentTrack != null) {
                                            with(sharedLayoutScope) {
                                                Modifier.sharedElement(
                                                    state = rememberSharedContentState(key = "np-art-${currentTrack!!.videoId}"),
                                                    animatedVisibilityScope = miniScope,
                                                    boundsTransform = { _, _ ->
                                                        tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }

                                        MiniPlayer(
                                            currentTrack = currentTrack,
                                            isPlaying = isPlaying,
                                            isLoading = isLoading,
                                            currentPositionMs = currentPosition,
                                            durationMs = duration,
                                            isLiked = currentTrack?.let { t -> libraryState.likedSongs.any { it.videoId == t.videoId } } ?: false,
                                            onLikeToggle = { currentTrack?.let { libraryRepo.toggleLike(it) } },
                                            onPlayPauseClick = { playbackController.togglePlayPause() },
                                            onNextClick = { playbackController.skipNext() },
                                            onPreviousClick = { playbackController.skipPrevious() },
                                            isInParty = partySnapshot.isActive,
                                            artworkModifier = miniArtMod,
                                            onClick = { showNowPlayingSheet = true },
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = !showNowPlayingSheet,
                                        enter = if (reducedMotion) fadeIn(tween(0)) else slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                                        exit = if (reducedMotion) fadeOut(tween(0)) else slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                                    ) {
                                        AuricleBottomNav(
                                            selectedTab = selectedTab,
                                            onTabSelected = { tab ->
                                                if (tab == NavigationTab.SEARCH &&
                                                    selectedTab == NavigationTab.SEARCH &&
                                                    detailBackStack.isEmpty()
                                                ) {
                                                    searchFocusTrigger++
                                                } else {
                                                    detailBackStack.clear()
                                                    selectedTab = tab
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Full-Screen Now Playing Overlay (with Shared Element Artwork)
                            AnimatedVisibility(
                                visible = showNowPlayingSheet && currentTrack != null,
                                enter = if (reducedMotion) fadeIn(tween(0)) else slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                ) + fadeIn(tween(220)),
                                exit = if (reducedMotion) fadeOut(tween(0)) else slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                                ) + fadeOut(tween(180))
                            ) {
                                val npScope = this
                                val npArtMod = if (!reducedMotion && currentTrack != null) {
                                    with(sharedLayoutScope) {
                                        Modifier.sharedElement(
                                            state = rememberSharedContentState(key = "np-art-${currentTrack!!.videoId}"),
                                            animatedVisibilityScope = npScope,
                                            boundsTransform = { _, _ ->
                                                tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                            }
                                        )
                                    }
                                } else {
                                    Modifier
                                }

                                NowPlayingSheet(
                                    playbackController = playbackController,
                                    libraryRepo = libraryRepo,
                                    musicRepo = musicRepo,
                                    settingsRepo = settingsRepo,
                                    onDismiss = { showNowPlayingSheet = false },
                                    onPartyClick = { showPartySheet = true },
                                    onSongOptionsClick = {
                                        songOptionsPlaylistContext = null
                                        selectedSongForOptions = it
                                    },
                                    artworkModifier = npArtMod
                                )
                            }
                        }
                    }
                }

                // Party Modal Bottom Sheet
                if (showPartySheet) {
                    val partySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = {
                            prefilledPartyLink = ""
                            showPartySheet = false
                        },
                        sheetState = partySheetState,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        containerColor = AuricleTheme.colors.sheetBg,
                        scrimColor = AuricleTheme.colors.scrim,
                        dragHandle = { AuricleSheetGrabber() }
                    ) {
                        PartySheet(
                            playbackController = playbackController,
                            settingsRepo = settingsRepo,
                            initialInviteLink = prefilledPartyLink,
                            onDismiss = {
                                prefilledPartyLink = ""
                                showPartySheet = false
                            }
                        )
                    }
                }

                // Settings Modal Bottom Sheet
                if (showSettingsSheet) {
                    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val availableVer = when (val st = updateState) {
                        is UpdateState.Available -> st.info.version
                        is UpdateState.Downloading -> st.info.version
                        is UpdateState.Ready -> st.info.version
                        is UpdateState.Error -> st.info?.version
                        else -> null
                    }
                    val statusText = when (val st = updateState) {
                        is UpdateState.Checking -> "Checking GitHub releases…"
                        is UpdateState.UpToDate -> "Up to date (v${st.checkedVersion})"
                        is UpdateState.Available -> "v${st.info.version} available"
                        is UpdateState.Downloading -> "Downloading v${st.info.version}…"
                        is UpdateState.Ready -> "v${st.info.version} verified & ready"
                        is UpdateState.Error -> st.message
                        UpdateState.Idle -> null
                    }

                    ModalBottomSheet(
                        onDismissRequest = { showSettingsSheet = false },
                        sheetState = settingsSheetState,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        containerColor = AuricleTheme.colors.sheetBg,
                        scrimColor = AuricleTheme.colors.scrim,
                        dragHandle = { AuricleSheetGrabber() }
                    ) {
                        SettingsSheet(
                            settingsRepo = settingsRepo,
                            availableUpdateVersion = availableVer,
                            isUpdateReady = updateState is UpdateState.Ready,
                            isCheckingForUpdate = updateState is UpdateState.Checking,
                            updateStatusText = statusText,
                            onOpenUpdater = {
                                showSettingsSheet = false
                                showUpdaterSheet = true
                            },
                            onCheckForUpdates = {
                                scope.launch {
                                    updateRepo.checkForUpdates(manual = true)
                                    val after = updateRepo.state.value
                                    if (after is UpdateState.Available || after is UpdateState.Ready) {
                                        showSettingsSheet = false
                                        showUpdaterSheet = true
                                    }
                                }
                            },
                            onDismiss = { showSettingsSheet = false }
                        )
                    }
                }

                // Song Options Modal Bottom Sheet
                selectedSongForOptions?.let { song ->
                    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val activePlaylistCtx = songOptionsPlaylistContext
                    ModalBottomSheet(
                        onDismissRequest = {
                            selectedSongForOptions = null
                            songOptionsPlaylistContext = null
                        },
                        sheetState = optionsSheetState,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        containerColor = AuricleTheme.colors.sheetBg,
                        scrimColor = AuricleTheme.colors.scrim,
                        dragHandle = { AuricleSheetGrabber() }
                    ) {
                        SongOptionBottomSheet(
                            song = song,
                            playbackController = playbackController,
                            libraryRepo = libraryRepo,
                            playlistContextName = activePlaylistCtx?.name,
                            onRemoveFromPlaylist = activePlaylistCtx?.let { pl ->
                                { libraryRepo.removeFromPlaylist(pl.id, song.videoId) }
                            },
                            onDismiss = {
                                selectedSongForOptions = null
                                songOptionsPlaylistContext = null
                            },
                            onGoToAlbum = { targetSong ->
                                selectedSongForOptions = null
                                songOptionsPlaylistContext = null
                                if (targetSong.album.isNotBlank() || targetSong.albumId.isNotBlank()) {
                                    showNowPlayingSheet = false
                                    pushDetail(
                                        DetailTarget.Album(
                                            AlbumItem(
                                                browseId = targetSong.albumId,
                                                title = targetSong.album,
                                                artist = targetSong.artist,
                                                thumbnailUrl = targetSong.displayArtworkUrl
                                            )
                                        )
                                    )
                                }
                            },
                            onGoToArtist = { targetSong ->
                                selectedSongForOptions = null
                                songOptionsPlaylistContext = null
                                if (targetSong.artist.isNotBlank() || targetSong.artistId.isNotBlank()) {
                                    showNowPlayingSheet = false
                                    pushDetail(
                                        DetailTarget.Artist(
                                            ArtistItem(
                                                browseId = targetSong.artistId,
                                                name = targetSong.artist,
                                                thumbnailUrl = targetSong.displayArtworkUrl
                                            )
                                        )
                                    )
                                }
                            }
                        )
                    }
                }

                // Item Options Modal Bottom Sheet (Album, Artist, RemotePlaylist, LocalPlaylist)
                selectedItemForOptions?.let { item ->
                    val liveLocalPlaylist = if (item is ContextMenuItem.LocalPlaylist) {
                        libraryState.playlists.find { it.id == item.playlist.id } ?: item.playlist
                    } else null
                    val effectiveItem = liveLocalPlaylist?.let { ContextMenuItem.LocalPlaylist(it) } ?: item

                    val isSavedOrFollowed = when (effectiveItem) {
                        is ContextMenuItem.Album -> libraryRepo.isAlbumSaved(
                            effectiveItem.album.browseId,
                            effectiveItem.album.title,
                            effectiveItem.album.artist
                        )
                        is ContextMenuItem.Artist -> libraryRepo.isArtistFollowed(effectiveItem.artist.browseId, effectiveItem.artist.name)
                        is ContextMenuItem.RemotePlaylist -> libraryRepo.isPlaylistSaved(
                            effectiveItem.playlist.playlistId,
                            effectiveItem.playlist.title
                        )
                        is ContextMenuItem.LocalPlaylist -> false
                    }
                    val targetDetailForItem = menuItemToDetailTarget(effectiveItem)
                    val alreadyOnTargetDetail = isSameDetailTarget(activeDetailTarget, targetDetailForItem)

                    suspend fun resolveTracksForItem(menuItem: ContextMenuItem): Pair<List<SongItem>, String> {
                        return when (menuItem) {
                            is ContextMenuItem.Album -> {
                                val alb = menuItem.album
                                val tracks = if (alb.tracks.isNotEmpty()) {
                                    alb.tracks
                                } else {
                                    var bid = alb.browseId
                                    if (bid.isBlank() && (alb.title.isNotBlank() || alb.artist.isNotBlank())) {
                                        val q = "${alb.title} ${alb.artist}".trim()
                                        musicRepo.search(q, "albums").onSuccess { sRes ->
                                            bid = sRes.albums.firstOrNull()?.browseId.orEmpty()
                                        }
                                    }
                                    if (bid.isNotBlank()) {
                                        musicRepo.getAlbum(bid).getOrNull()?.tracks.orEmpty()
                                    } else {
                                        emptyList()
                                    }
                                }
                                tracks to alb.title.ifBlank { "Album" }
                            }
                            is ContextMenuItem.Artist -> {
                                val art = menuItem.artist
                                val songs = if (art.topSongs.isNotEmpty()) {
                                    art.topSongs
                                } else {
                                    var bid = art.browseId
                                    if (bid.isBlank() && art.name.isNotBlank()) {
                                        musicRepo.search(art.name, "artists").onSuccess { sRes ->
                                            bid = sRes.artists.firstOrNull()?.browseId.orEmpty()
                                        }
                                    }
                                    if (bid.isNotBlank()) {
                                        musicRepo.getArtist(bid).getOrNull()?.topSongs.orEmpty()
                                    } else {
                                        emptyList()
                                    }
                                }
                                songs to art.name.ifBlank { "Artist" }
                            }
                            is ContextMenuItem.RemotePlaylist -> {
                                val pl = menuItem.playlist
                                val tracks = if (pl.tracks.isNotEmpty()) {
                                    pl.tracks
                                } else if (pl.playlistId.isNotBlank()) {
                                    musicRepo.getPlaylist(pl.playlistId).getOrNull()?.tracks.orEmpty()
                                } else {
                                    emptyList()
                                }
                                tracks to pl.title.ifBlank { "Playlist" }
                            }
                            is ContextMenuItem.LocalPlaylist -> {
                                menuItem.playlist.tracks to menuItem.playlist.name.ifBlank { "Playlist" }
                            }
                        }
                    }

                    fun launchShareIntent(subject: String, text: String) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        try {
                            context.startActivity(Intent.createChooser(shareIntent, "Share"))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }

                    ItemOptionBottomSheet(
                        item = effectiveItem,
                        isSavedOrFollowed = isSavedOrFollowed,
                        onDismiss = { selectedItemForOptions = null },
                        onPlay = {
                            scope.launch {
                                val (tracks, label) = resolveTracksForItem(effectiveItem)
                                if (tracks.isNotEmpty()) {
                                    playbackController.playTrack(tracks.first(), tracks, label)
                                } else {
                                    Toast.makeText(context, "No tracks available to play", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onShuffle = {
                            scope.launch {
                                val (tracks, label) = resolveTracksForItem(effectiveItem)
                                if (tracks.isNotEmpty()) {
                                    playbackController.playShuffled(tracks, label)
                                } else {
                                    Toast.makeText(context, "No tracks available to shuffle", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onAddToQueue = {
                            scope.launch {
                                val (tracks, _) = resolveTracksForItem(effectiveItem)
                                if (tracks.isNotEmpty()) {
                                    tracks.forEach { playbackController.addToQueue(it) }
                                    Toast.makeText(context, "Added ${tracks.size} to queue", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No tracks available to queue", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onToggleSaveOrFollow = when (effectiveItem) {
                            is ContextMenuItem.Album -> {
                                { libraryRepo.toggleSaveAlbum(effectiveItem.album) }
                            }
                            is ContextMenuItem.Artist -> {
                                { libraryRepo.toggleFollowArtist(effectiveItem.artist) }
                            }
                            is ContextMenuItem.RemotePlaylist -> {
                                { libraryRepo.toggleSavePlaylist(effectiveItem.playlist) }
                            }
                            is ContextMenuItem.LocalPlaylist -> null
                        },
                        onOpenDetail = if (alreadyOnTargetDetail) null else {
                            { pushDetail(targetDetailForItem) }
                        },
                        onRenamePlaylist = if (effectiveItem is ContextMenuItem.LocalPlaylist) {
                            { newName -> libraryRepo.renamePlaylist(effectiveItem.playlist.id, newName) }
                        } else null,
                        onDeletePlaylist = if (effectiveItem is ContextMenuItem.LocalPlaylist) {
                            {
                                libraryRepo.deletePlaylist(effectiveItem.playlist.id)
                                detailBackStack.removeAll {
                                    it is DetailTarget.CustomUserPlaylist && it.playlistId == effectiveItem.playlist.id
                                }
                            }
                        } else null,
                        onShare = {
                            when (effectiveItem) {
                                is ContextMenuItem.Album -> {
                                    val alb = effectiveItem.album
                                    val header = listOf(alb.title, alb.artist).filter { it.isNotBlank() }.joinToString(" — ")
                                    val body = if (alb.browseId.isNotBlank()) {
                                        "$header\nhttps://music.youtube.com/browse/${alb.browseId}"
                                    } else header
                                    if (body.isNotBlank()) launchShareIntent(header.ifBlank { "Album" }, body)
                                }
                                is ContextMenuItem.Artist -> {
                                    val art = effectiveItem.artist
                                    val body = if (art.browseId.isNotBlank()) {
                                        "${art.name}\nhttps://music.youtube.com/channel/${art.browseId}"
                                    } else art.name
                                    if (body.isNotBlank()) launchShareIntent(art.name.ifBlank { "Artist" }, body)
                                }
                                is ContextMenuItem.RemotePlaylist -> {
                                    val pl = effectiveItem.playlist
                                    val listId = pl.playlistId.removePrefix("VL")
                                    val header = listOf(pl.title, pl.author).filter { it.isNotBlank() }.joinToString(" — ")
                                    val body = if (listId.isNotBlank()) {
                                        "$header\nhttps://music.youtube.com/playlist?list=$listId"
                                    } else header
                                    if (body.isNotBlank()) launchShareIntent(header.ifBlank { "Playlist" }, body)
                                }
                                is ContextMenuItem.LocalPlaylist -> {
                                    val pl = effectiveItem.playlist
                                    val trackList = pl.tracks.take(15).joinToString("\n") { s ->
                                        listOf(s.title, s.artist).filter { it.isNotBlank() }.joinToString(" — ")
                                    }
                                    val body = if (trackList.isNotBlank()) "${pl.name}\n$trackList" else pl.name
                                    if (body.isNotBlank()) launchShareIntent(pl.name.ifBlank { "Playlist" }, body)
                                }
                            }
                        }
                    )
                }

                // In-App Updater Modal Bottom Sheet
                if (showUpdaterSheet && (
                        updateState is UpdateState.Available ||
                        updateState is UpdateState.Downloading ||
                        updateState is UpdateState.Ready ||
                        (updateState is UpdateState.Error && (updateState as UpdateState.Error).info != null)
                    )
                ) {
                    val updaterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = { showUpdaterSheet = false },
                        sheetState = updaterSheetState,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        containerColor = AuricleTheme.colors.sheetBg,
                        scrimColor = AuricleTheme.colors.scrim,
                        dragHandle = { AuricleSheetGrabber() }
                    ) {
                        UpdaterSheet(
                            updateState = updateState,
                            onDownloadClick = { updateRepo.downloadUpdate() },
                            onCancelDownloadClick = { updateRepo.cancelDownload() },
                            onInstallClick = {
                                if (!updateRepo.installUpdate(this@MainActivity) &&
                                    updateRepo.state.value is UpdateState.Ready
                                ) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Enable \"Install unknown apps\" for Auricle, then tap Install again.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onDismiss = { showUpdaterSheet = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val data: Uri? = intent.data

        if (Intent.ACTION_VIEW == action && data != null) {
            val scheme = data.scheme
            val host = data.host
            val path = data.path ?: ""

            Log.d(TAG, "Deep link received: $data (scheme=$scheme, host=$host, path=$path)")
            if (scheme == "auricle" || path.contains("party")) {
                pendingPartyLink = data.toString()
            }
        } else if (Intent.ACTION_SEND == action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                pendingPartyLink = sharedText.trim()
            }
        }
    }
}

@Composable
private fun AuricleSheetGrabber() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AuricleTheme.colors.textDim.copy(alpha = 0.55f))
        )
    }
}
