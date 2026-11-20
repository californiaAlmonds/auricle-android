package com.auricle.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auricle.app.AuricleApp
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.PlaylistItem
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
import com.auricle.app.ui.components.*
import com.auricle.app.ui.screens.*
import com.auricle.app.ui.theme.AuricleAppTheme
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    // Deep link state flow
    private var pendingPartyLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        handleIntent(intent)

        setContent {
            val app = application as AuricleApp
            val musicRepo = app.musicRepository
            val libraryRepo = app.libraryRepository
            val settingsRepo = app.settingsRepository
            val playbackController = app.playbackController

            val appSettings by settingsRepo.settings.collectAsState()
            val currentTrack by playbackController.currentTrack.collectAsState()
            val isPlaying by playbackController.isPlaying.collectAsState()
            val isLoading by playbackController.isLoading.collectAsState()
            val currentPosition by playbackController.currentPosition.collectAsState()
            val duration by playbackController.duration.collectAsState()
            val partySnapshot by playbackController.partySnapshot.collectAsState()
            val libraryState by libraryRepo.state.collectAsState()

            // Dynamic Album Art Accent Extraction
            var dynamicAccentColor by remember { mutableStateOf<Color?>(null) }
            val context = LocalContext.current

            LaunchedEffect(currentTrack?.videoId, appSettings.dynamicAlbumArtAccent) {
                if (appSettings.dynamicAlbumArtAccent && currentTrack != null && currentTrack!!.displayArtworkUrl.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val loader = ImageLoader(context)
                            val request = ImageRequest.Builder(context)
                                .data(currentTrack!!.displayArtworkUrl)
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

            AuricleAppTheme(
                themeMode = appSettings.themeMode,
                accentPreset = appSettings.accentPreset,
                dynamicAccentColor = dynamicAccentColor
            ) {
                // UI Navigation & Sheet States
                var selectedTab by remember { mutableStateOf(NavigationTab.HOME) }
                var activeDetailTarget by remember { mutableStateOf<DetailTarget?>(null) }
                var showNowPlayingSheet by remember { mutableStateOf(false) }
                var showPartySheet by remember { mutableStateOf(false) }
                var prefilledPartyLink by remember { mutableStateOf("") }
                var showSettingsSheet by remember { mutableStateOf(false) }
                var selectedSongForOptions by remember { mutableStateOf<SongItem?>(null) }

                // Open Party Sheet with pre-filled invite link on deep link (explicit 1-tap Join confirmation)
                LaunchedEffect(pendingPartyLink) {
                    val link = pendingPartyLink
                    if (!link.isNullOrBlank()) {
                        prefilledPartyLink = link
                        showPartySheet = true
                        pendingPartyLink = null
                    }
                }

                // Handle Hardware Back Button
                BackHandler(
                    enabled = showNowPlayingSheet || showPartySheet || showSettingsSheet || selectedSongForOptions != null || activeDetailTarget != null
                ) {
                    when {
                        selectedSongForOptions != null -> selectedSongForOptions = null
                        showPartySheet -> showPartySheet = false
                        showSettingsSheet -> showSettingsSheet = false
                        showNowPlayingSheet -> showNowPlayingSheet = false
                        activeDetailTarget != null -> activeDetailTarget = null
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AuricleTheme.colors.canvas),
                    containerColor = AuricleTheme.colors.canvas,
                    topBar = {
                        if (activeDetailTarget == null) {
                            AuricleTopBar(
                                partySnapshot = partySnapshot,
                                onPartyClick = { showPartySheet = true },
                                onSettingsClick = { showSettingsSheet = true }
                            )
                        }
                    },
                    bottomBar = {
                        Column {
                            MiniPlayer(
                                currentTrack = currentTrack,
                                isPlaying = isPlaying,
                                isLoading = isLoading,
                                currentPositionMs = currentPosition,
                                durationMs = duration,
                                isLiked = currentTrack?.let { libraryState.likedSongs.any { l -> l.videoId == it.videoId } } ?: false,
                                onLikeToggle = { currentTrack?.let { libraryRepo.toggleLike(it) } },
                                onPlayPauseClick = { playbackController.togglePlayPause() },
                                onClick = { showNowPlayingSheet = true }
                            )

                            AuricleBottomNav(
                                selectedTab = selectedTab,
                                onTabSelected = {
                                    activeDetailTarget = null
                                    selectedTab = it
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Main View Router
                        if (activeDetailTarget != null) {
                            DetailScreen(
                                target = activeDetailTarget!!,
                                musicRepo = musicRepo,
                                playbackController = playbackController,
                                onBackClick = { activeDetailTarget = null },
                                onSongOptionsClick = { selectedSongForOptions = it },
                                onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) }
                            )
                        } else {
                            when (selectedTab) {
                                NavigationTab.HOME -> {
                                    HomeScreen(
                                        musicRepo = musicRepo,
                                        playbackController = playbackController,
                                        onSongOptionsClick = { selectedSongForOptions = it },
                                        onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) }
                                    )
                                }
                                NavigationTab.SEARCH -> {
                                    SearchScreen(
                                        musicRepo = musicRepo,
                                        playbackController = playbackController,
                                        onSongOptionsClick = { selectedSongForOptions = it },
                                        onAlbumClick = { activeDetailTarget = DetailTarget.Album(it) },
                                        onArtistClick = { activeDetailTarget = DetailTarget.Artist(it) },
                                        onPlaylistClick = { activeDetailTarget = DetailTarget.Playlist(it) }
                                    )
                                }
                                NavigationTab.LIBRARY -> {
                                    LibraryScreen(
                                        libraryRepo = libraryRepo,
                                        playbackController = playbackController,
                                        onSongOptionsClick = { selectedSongForOptions = it },
                                        onUserPlaylistClick = { activeDetailTarget = DetailTarget.CustomUserPlaylist(it) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Full Screen Now Playing Overlay (above TopBar, MiniPlayer, and BottomNav)
                AnimatedVisibility(
                    visible = showNowPlayingSheet,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    NowPlayingSheet(
                        playbackController = playbackController,
                        libraryRepo = libraryRepo,
                        musicRepo = musicRepo,
                        onDismiss = { showNowPlayingSheet = false },
                        onPartyClick = { showPartySheet = true }
                    )
                }

                // Party Modal Bottom Sheet
                if (showPartySheet) {
                    ModalBottomSheet(
                        onDismissRequest = {
                            prefilledPartyLink = ""
                            showPartySheet = false
                        },
                        containerColor = AuricleTheme.colors.surface,
                        dragHandle = { BottomSheetDefaults.DragHandle(color = AuricleTheme.colors.border) }
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
                    ModalBottomSheet(
                        onDismissRequest = { showSettingsSheet = false },
                        containerColor = AuricleTheme.colors.surface,
                        dragHandle = { BottomSheetDefaults.DragHandle(color = AuricleTheme.colors.border) }
                    ) {
                        SettingsSheet(
                            settingsRepo = settingsRepo,
                            onDismiss = { showSettingsSheet = false }
                        )
                    }
                }

                // Song Options Modal Bottom Sheet
                selectedSongForOptions?.let { song ->
                    ModalBottomSheet(
                        onDismissRequest = { selectedSongForOptions = null },
                        containerColor = AuricleTheme.colors.surface,
                        dragHandle = { BottomSheetDefaults.DragHandle(color = AuricleTheme.colors.border) }
                    ) {
                        SongOptionBottomSheet(
                            song = song,
                            playbackController = playbackController,
                            libraryRepo = libraryRepo,
                            onDismiss = { selectedSongForOptions = null }
                        )
                    }
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
