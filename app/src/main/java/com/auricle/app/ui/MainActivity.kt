package com.auricle.app.ui

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auricle.app.AuricleApp
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.SongItem
import com.auricle.app.ui.components.AuricleBottomNav
import com.auricle.app.ui.components.BottomChromeFade
import com.auricle.app.ui.components.MiniPlayer
import com.auricle.app.ui.components.NavigationTab
import com.auricle.app.ui.screens.DetailScreen
import com.auricle.app.ui.screens.DetailTarget
import com.auricle.app.ui.screens.HomeScreen
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
            val libraryState by libraryRepo.state.collectAsState()
            val updateState by updateRepo.state.collectAsState()
            val hasPendingUpdate by updateRepo.hasPendingUpdate.collectAsState()

            var selectedTab by remember { mutableStateOf(NavigationTab.HOME) }
            var activeDetailTarget by remember { mutableStateOf<DetailTarget?>(null) }
            var showNowPlayingSheet by remember { mutableStateOf(false) }
            var showPartySheet by remember { mutableStateOf(false) }
            var prefilledPartyLink by remember { mutableStateOf("") }
            var showSettingsSheet by remember { mutableStateOf(false) }
            var showUpdaterSheet by remember { mutableStateOf(false) }
            var selectedSongForOptions by remember { mutableStateOf<SongItem?>(null) }

            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            // Dynamic Album Art Accent Extraction
            var dynamicAccentColor by remember { mutableStateOf<Color?>(null) }

            LaunchedEffect(currentTrack?.videoId, appSettings.dynamicAlbumArtAccent) {
                val track = currentTrack
                if (appSettings.dynamicAlbumArtAccent && track != null && track.displayArtworkUrl.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val loader = ImageLoader(context)
                            val request = ImageRequest.Builder(context)
                                .data(track.displayArtworkUrl)
                                .size(200)
                                .allowHardware(false)
                                .build()
                            val result = (loader.execute(request) as? SuccessResult)?.drawable
                            val bitmap = (result as? BitmapDrawable)?.bitmap
                            if (bitmap != null) {
                                val palette = Palette.from(bitmap).generate()
                                val vibrant = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb
                                if (vibrant != null) {
                                    dynamicAccentColor = Color(vibrant)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Palette extraction error: ${e.message}")
                        }
                    }
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
                        selectedSongForOptions != null ||
                        showPartySheet ||
                        showSettingsSheet ||
                        showNowPlayingSheet ||
                        activeDetailTarget != null
                ) {
                    when {
                        showUpdaterSheet -> showUpdaterSheet = false
                        selectedSongForOptions != null -> selectedSongForOptions = null
                        showPartySheet -> {
                            prefilledPartyLink = ""
                            showPartySheet = false
                        }
                        showSettingsSheet -> showSettingsSheet = false
                        showNowPlayingSheet -> showNowPlayingSheet = false
                        activeDetailTarget != null -> activeDetailTarget = null
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
                                    val animVisibilityScope = this
                                    val coverSharedMod: @Composable (String) -> Modifier = { key ->
                                        if (!reducedMotion && key.isNotBlank()) {
                                            with(sharedLayoutScope) {
                                                Modifier.sharedElement(
                                                    state = rememberSharedContentState(key = "album-cover-$key"),
                                                    animatedVisibilityScope = animVisibilityScope,
                                                    boundsTransform = { _, _ ->
                                                        tween(durationMillis = 320, easing = FastOutSlowInEasing)
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }
                                    }

                                    if (detailTarget != null) {
                                        DetailScreen(
                                            target = detailTarget,
                                            musicRepo = musicRepo,
                                            libraryRepo = libraryRepo,
                                            playbackController = playbackController,
                                            onBackClick = { activeDetailTarget = null },
                                            onSongOptionsClick = { selectedSongForOptions = it },
                                            onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) },
                                            onArtistClick = { activeDetailTarget = DetailTarget.Artist(it) },
                                            onBrowseHomeClick = {
                                                activeDetailTarget = null
                                                selectedTab = NavigationTab.HOME
                                            },
                                            coverArtworkModifier = coverSharedMod,
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
                                                    onSongOptionsClick = { selectedSongForOptions = it },
                                                    onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) },
                                                    albumArtworkModifier = coverSharedMod,
                                                    bottomContentPadding = bottomContentPadding
                                                )
                                            }
                                            NavigationTab.SEARCH -> {
                                                SearchScreen(
                                                    musicRepo = musicRepo,
                                                    settingsRepo = settingsRepo,
                                                    libraryRepo = libraryRepo,
                                                    playbackController = playbackController,
                                                    onSongOptionsClick = { selectedSongForOptions = it },
                                                    onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) },
                                                    onArtistClick = { activeDetailTarget = DetailTarget.Artist(it) },
                                                    onPlaylistClick = { activeDetailTarget = DetailTarget.Playlist(it) },
                                                    albumArtworkModifier = coverSharedMod,
                                                    bottomContentPadding = bottomContentPadding
                                                )
                                            }
                                            NavigationTab.LIBRARY -> {
                                                LibraryScreen(
                                                    libraryRepo = libraryRepo,
                                                    playbackController = playbackController,
                                                    onSongOptionsClick = { selectedSongForOptions = it },
                                                    onUserPlaylistClick = { activeDetailTarget = DetailTarget.CustomUserPlaylist(it) },
                                                    onLikedSongsClick = { activeDetailTarget = DetailTarget.LikedSongs },
                                                    onRecentlyPlayedClick = { activeDetailTarget = DetailTarget.RecentlyPlayed },
                                                    onArtistClick = { activeDetailTarget = DetailTarget.Artist(it) },
                                                    onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) },
                                                    onBrowseHomeClick = { selectedTab = NavigationTab.HOME },
                                                    albumArtworkModifier = coverSharedMod,
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

                                    AuricleBottomNav(
                                        selectedTab = selectedTab,
                                        onTabSelected = { tab ->
                                            activeDetailTarget = null
                                            selectedTab = tab
                                        }
                                    )
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
                                    onSongOptionsClick = { selectedSongForOptions = it },
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
                    ModalBottomSheet(
                        onDismissRequest = { selectedSongForOptions = null },
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
                            onDismiss = { selectedSongForOptions = null },
                            onGoToAlbum = { targetSong ->
                                showNowPlayingSheet = false
                                activeDetailTarget = DetailTarget.Album(
                                    AlbumItem(
                                        browseId = targetSong.albumId,
                                        title = targetSong.album.ifBlank { targetSong.title },
                                        artist = targetSong.artist,
                                        thumbnailUrl = targetSong.displayArtworkUrl
                                    )
                                )
                            },
                            onGoToArtist = { targetSong ->
                                showNowPlayingSheet = false
                                activeDetailTarget = DetailTarget.Artist(
                                    ArtistItem(
                                        browseId = targetSong.artistId,
                                        name = targetSong.artist,
                                        thumbnailUrl = targetSong.displayArtworkUrl
                                    )
                                )
                            }
                        )
                    }
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
                            onInstallClick = { updateRepo.installUpdate(this@MainActivity) },
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
