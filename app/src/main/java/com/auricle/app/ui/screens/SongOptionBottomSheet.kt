package com.auricle.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.LibraryRepository
import com.auricle.app.model.SongItem
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.AuricleCircleIconButton
import com.auricle.app.ui.components.AuricleIcons
import com.auricle.app.ui.components.auriclePressClickable
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun SongOptionBottomSheet(
    song: SongItem,
    playbackController: PlaybackController,
    libraryRepo: LibraryRepository,
    onDismiss: () -> Unit,
    onGoToAlbum: ((SongItem) -> Unit)? = null,
    onGoToArtist: ((SongItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val libraryState by libraryRepo.state.collectAsState()
    val isLiked = libraryState.likedSongs.any { it.videoId == song.videoId }

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.sheetBg)
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Song Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
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
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AuricleTheme.colors.surface2)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = AuricleTheme.typography.headline,
                    color = AuricleTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val subtitle = buildString {
                    append(song.artist)
                    if (song.displayDuration.isNotBlank()) {
                        append(" · ${song.displayDuration}")
                    }
                }
                Text(
                    text = subtitle,
                    style = AuricleTheme.typography.sub,
                    color = AuricleTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AuricleCircleIconButton(
                icon = if (isLiked) AuricleIcons.HeartFill else AuricleIcons.Heart,
                contentDescription = if (isLiked) "Unlike" else "Like",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    libraryRepo.toggleLike(song)
                },
                size = 36.dp,
                iconSize = 20.dp,
                containerColor = AuricleTheme.colors.fillMed,
                tint = if (isLiked) AuricleTheme.colors.danger else AuricleTheme.colors.textPrimary
            )
        }

        // Horizontal Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AuricleTheme.colors.border)
        )

        if (!showPlaylistPicker) {
            // Quick Actions 3-Tile Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Play Next
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuricleTheme.colors.fillMed)
                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
                        .auriclePressClickable {
                            playbackController.playNext(song)
                            onDismiss()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AuricleIcons.ListStart,
                        contentDescription = null,
                        tint = AuricleTheme.colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Play Next",
                        style = AuricleTheme.typography.captionSB,
                        color = AuricleTheme.colors.textPrimary
                    )
                }

                // Add to Queue
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuricleTheme.colors.fillMed)
                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
                        .auriclePressClickable {
                            playbackController.addToQueue(song)
                            onDismiss()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AuricleIcons.ListPlus,
                        contentDescription = null,
                        tint = AuricleTheme.colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Add to Queue",
                        style = AuricleTheme.typography.captionSB,
                        color = AuricleTheme.colors.textPrimary
                    )
                }

                // Share
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuricleTheme.colors.fillMed)
                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
                        .auriclePressClickable {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${song.title} — ${song.artist}")
                                putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}\nhttps://music.youtube.com/watch?v=${song.videoId}")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                            onDismiss()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AuricleIcons.Share,
                        contentDescription = null,
                        tint = AuricleTheme.colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Share",
                        style = AuricleTheme.typography.captionSB,
                        color = AuricleTheme.colors.textPrimary
                    )
                }
            }

            // 4 Action Rows
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 1. Liked Songs Toggle
                SongActionRow(
                    icon = if (isLiked) AuricleIcons.HeartFill else AuricleIcons.Heart,
                    label = if (isLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                    iconTint = if (isLiked) AuricleTheme.colors.danger else AuricleTheme.colors.textPrimary,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        libraryRepo.toggleLike(song)
                        onDismiss()
                    }
                )

                // 2. Add to Playlist
                SongActionRow(
                    icon = AuricleIcons.Plus,
                    label = "Add to Playlist",
                    trailingIcon = AuricleIcons.ChevronRight,
                    onClick = { showPlaylistPicker = true }
                )

                // 3. Go to Album
                SongActionRow(
                    icon = AuricleIcons.Album,
                    label = "Go to Album",
                    onClick = {
                        onGoToAlbum?.invoke(song)
                        onDismiss()
                    }
                )

                // 4. Go to Artist
                SongActionRow(
                    icon = AuricleIcons.Artist,
                    label = "Go to Artist",
                    onClick = {
                        onGoToArtist?.invoke(song)
                        onDismiss()
                    }
                )
            }
        } else {
            // Playlist Picker View
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Add to Playlist",
                    style = AuricleTheme.typography.headline,
                    color = AuricleTheme.colors.textPrimary
                )
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .auriclePressClickable { showPlaylistPicker = false }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Back",
                        style = AuricleTheme.typography.bodySB,
                        color = AuricleTheme.colors.accentStrong
                    )
                }
            }

            // Inline Create New Playlist Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    textStyle = AuricleTheme.typography.body.copy(color = AuricleTheme.colors.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(AuricleTheme.colors.accent),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuricleTheme.colors.fillMed)
                                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (newPlaylistName.isEmpty()) {
                                Text(
                                    text = "New playlist name",
                                    style = AuricleTheme.typography.body.copy(fontSize = 14.sp),
                                    color = AuricleTheme.colors.textDim
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (newPlaylistName.isNotBlank()) AuricleTheme.colors.primaryFill else AuricleTheme.colors.fillMed)
                        .auriclePressClickable(enabled = newPlaylistName.isNotBlank()) {
                            if (newPlaylistName.isNotBlank()) {
                                val created = libraryRepo.createPlaylist(newPlaylistName.trim())
                                libraryRepo.addToPlaylist(created.id, song)
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Create",
                        style = AuricleTheme.typography.subSB.copy(fontSize = 13.sp),
                        color = if (newPlaylistName.isNotBlank()) AuricleTheme.colors.onPrimaryFill else AuricleTheme.colors.textDim
                    )
                }
            }

            val playlists = libraryState.playlists
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No playlists yet. Create one above to save this track.",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AuricleTheme.colors.surface2)
                                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(12.dp))
                                .auriclePressClickable {
                                    libraryRepo.addToPlaylist(playlist.id, song)
                                    onDismiss()
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = AuricleIcons.Queue,
                                contentDescription = null,
                                tint = AuricleTheme.colors.accentStrong,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = AuricleTheme.typography.bodyM,
                                    color = AuricleTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${playlist.tracks.size} ${if (playlist.tracks.size == 1) "track" else "tracks"}",
                                    style = AuricleTheme.typography.caption,
                                    color = AuricleTheme.colors.textMuted
                                )
                            }
                            Icon(
                                imageVector = AuricleIcons.Plus,
                                contentDescription = null,
                                tint = AuricleTheme.colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongActionRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = AuricleTheme.colors.textPrimary,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .auriclePressClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = AuricleTheme.typography.bodyM,
            color = AuricleTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = AuricleTheme.colors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
