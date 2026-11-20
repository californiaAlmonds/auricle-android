package com.auricle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.model.SongItem
import com.auricle.app.model.UserPlaylist
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.AccentPillButton
import com.auricle.app.ui.components.SongRow
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun LibraryScreen(
    libraryRepo: LibraryRepository,
    playbackController: PlaybackController,
    onSongOptionsClick: (SongItem) -> Unit,
    onUserPlaylistClick: (UserPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    val libraryState by libraryRepo.state.collectAsState()
    val currentPlayingTrack by playbackController.currentTrack.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0: Liked, 1: History, 2: Playlists
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text(
                    text = "New Playlist",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
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
                            libraryRepo.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create", color = AuricleTheme.colors.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = AuricleTheme.colors.textMuted)
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.canvas)
    ) {
        // Sub-Tabs Bar
        val tabTitles = listOf("Liked Songs", "Recently Played", "Playlists")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabTitles.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) AuricleTheme.colors.accent else AuricleTheme.colors.surface)
                        .border(1.dp, if (isSelected) AuricleTheme.colors.accent else AuricleTheme.colors.border, RoundedCornerShape(16.dp))
                        .clickable { selectedTab = index }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else AuricleTheme.colors.textPrimary
                    )
                }
            }
        }

        // Tab Content
        when (selectedTab) {
            0 -> {
                // Liked Songs
                val liked = libraryState.likedSongs
                if (liked.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = Icons.Rounded.FavoriteBorder, contentDescription = null, tint = AuricleTheme.colors.textDim, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = "No liked songs yet", fontSize = 15.sp, color = AuricleTheme.colors.textMuted)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${liked.size} tracks",
                                    fontSize = 13.sp,
                                    color = AuricleTheme.colors.textMuted
                                )
                                AccentPillButton(
                                    onClick = {
                                        if (liked.isNotEmpty()) {
                                            playbackController.playTrack(liked.first(), liked)
                                        }
                                    },
                                    isPrimary = true
                                ) {
                                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Play All", fontSize = 12.sp)
                                }
                            }
                        }

                        items(liked) { song ->
                            SongRow(
                                song = song,
                                isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                onClick = {
                                    playbackController.playTrack(song, liked)
                                },
                                onQuickAddQueue = { playbackController.addToQueue(song) },
                                onOptionsClick = { onSongOptionsClick(song) }
                            )
                        }
                    }
                }
            }
            1 -> {
                // Recently Played
                val history = libraryState.recentlyPlayed
                if (history.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = Icons.Rounded.History, contentDescription = null, tint = AuricleTheme.colors.textDim, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = "No playback history", fontSize = 15.sp, color = AuricleTheme.colors.textMuted)
                        }
                    }
                } else {
                    val tracks = history.map { it.track }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        items(tracks) { song ->
                            SongRow(
                                song = song,
                                isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                onClick = {
                                    playbackController.playTrack(song, tracks)
                                },
                                onQuickAddQueue = { playbackController.addToQueue(song) },
                                onOptionsClick = { onSongOptionsClick(song) }
                            )
                        }
                    }
                }
            }
            2 -> {
                // Playlists
                val playlists = libraryState.playlists
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AuricleTheme.colors.surface)
                                .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(12.dp))
                                .clickable { showCreateDialog = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(AuricleTheme.colors.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = Color.White)
                            }
                            Text(
                                text = "Create New Playlist",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuricleTheme.colors.textPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    items(playlists) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onUserPlaylistClick(playlist) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AuricleTheme.colors.surface2),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Rounded.PlaylistPlay, contentDescription = null, tint = AuricleTheme.colors.accent)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuricleTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${playlist.tracks.size} tracks",
                                    fontSize = 12.sp,
                                    color = AuricleTheme.colors.textMuted
                                )
                            }
                            IconButton(
                                onClick = { libraryRepo.deletePlaylist(playlist.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(imageVector = Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = AuricleTheme.colors.textDim)
                            }
                        }
                    }
                }
            }
        }
    }
}
