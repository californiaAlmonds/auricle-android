package com.auricle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.LibraryRepository
import com.auricle.app.model.SongItem
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun SongOptionBottomSheet(
    song: SongItem,
    playbackController: PlaybackController,
    libraryRepo: LibraryRepository,
    onDismiss: () -> Unit,
    onViewAlbum: ((String) -> Unit)? = null,
    onViewArtist: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val libraryState by libraryRepo.state.collectAsState()
    val isLiked = libraryState.likedSongs.any { it.videoId == song.videoId }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.surface)
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Song Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.displayArtworkUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AuricleTheme.colors.surface2)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuricleTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    fontSize = 13.sp,
                    color = AuricleTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider(color = AuricleTheme.colors.border, thickness = 1.dp)

        if (!showPlaylistPicker) {
            // Standard Options
            OptionRow(
                icon = Icons.Rounded.VerticalAlignTop,
                label = "Play Next",
                onClick = {
                    playbackController.playNext(song)
                    onDismiss()
                }
            )

            OptionRow(
                icon = Icons.Rounded.QueueMusic,
                label = "Add to Queue",
                onClick = {
                    playbackController.addToQueue(song)
                    onDismiss()
                }
            )

            OptionRow(
                icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                iconTint = if (isLiked) AuricleTheme.colors.danger else AuricleTheme.colors.textPrimary,
                onClick = {
                    libraryRepo.toggleLike(song)
                    onDismiss()
                }
            )

            OptionRow(
                icon = Icons.Rounded.PlaylistAdd,
                label = "Add to Playlist",
                onClick = {
                    showPlaylistPicker = true
                }
            )
        } else {
            // Playlist Picker
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Add to Playlist",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuricleTheme.colors.textPrimary
                )
                TextButton(onClick = { showPlaylistPicker = false }) {
                    Text("Back", color = AuricleTheme.colors.accent)
                }
            }

            val playlists = libraryState.playlists
            if (playlists.isEmpty()) {
                Text(
                    text = "No user playlists found. Create one in Library tab first.",
                    fontSize = 13.sp,
                    color = AuricleTheme.colors.textMuted
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AuricleTheme.colors.surface2)
                                .clickable {
                                    libraryRepo.addToPlaylist(playlist.id, song)
                                    onDismiss()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(imageVector = Icons.Rounded.PlaylistPlay, contentDescription = null, tint = AuricleTheme.colors.accent)
                            Text(text = playlist.name, fontSize = 14.sp, color = AuricleTheme.colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    iconTint: androidx.compose.ui.graphics.Color = AuricleTheme.colors.textPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AuricleTheme.colors.textPrimary)
    }
}
