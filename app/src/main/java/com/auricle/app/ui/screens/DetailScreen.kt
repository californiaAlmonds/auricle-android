package com.auricle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.*
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.AccentPillButton
import com.auricle.app.ui.components.SongRow
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch

sealed class DetailTarget {
    data class Album(val albumItem: AlbumItem) : DetailTarget()
    data class Artist(val artistItem: ArtistItem) : DetailTarget()
    data class Playlist(val playlistItem: PlaylistItem) : DetailTarget()
    data class CustomUserPlaylist(val playlist: UserPlaylist) : DetailTarget()
}

@Composable
fun DetailScreen(
    target: DetailTarget,
    musicRepo: MusicRepository,
    playbackController: PlaybackController,
    onBackClick: () -> Unit,
    onSongOptionsClick: (SongItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var fullAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var fullArtist by remember { mutableStateOf<ArtistItem?>(null) }
    var fullPlaylist by remember { mutableStateOf<PlaylistItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val currentPlayingTrack by playbackController.currentTrack.collectAsState()

    LaunchedEffect(target) {
        when (target) {
            is DetailTarget.Album -> {
                if (target.albumItem.tracks.isEmpty() && target.albumItem.browseId.isNotBlank()) {
                    isLoading = true
                    coroutineScope.launch {
                        val res = musicRepo.getAlbum(target.albumItem.browseId)
                        res.onSuccess { fullAlbum = it }
                        isLoading = false
                    }
                } else {
                    fullAlbum = target.albumItem
                }
            }
            is DetailTarget.Artist -> {
                if (target.artistItem.topSongs.isEmpty() && target.artistItem.browseId.isNotBlank()) {
                    isLoading = true
                    coroutineScope.launch {
                        val res = musicRepo.getArtist(target.artistItem.browseId)
                        res.onSuccess { fullArtist = it }
                        isLoading = false
                    }
                } else {
                    fullArtist = target.artistItem
                }
            }
            is DetailTarget.Playlist -> {
                if (target.playlistItem.tracks.isEmpty() && target.playlistItem.playlistId.isNotBlank()) {
                    isLoading = true
                    coroutineScope.launch {
                        val res = musicRepo.getPlaylist(target.playlistItem.playlistId)
                        res.onSuccess { fullPlaylist = it }
                        isLoading = false
                    }
                } else {
                    fullPlaylist = target.playlistItem
                }
            }
            is DetailTarget.CustomUserPlaylist -> {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuricleTheme.colors.canvas)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar with Back
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .semantics { contentDescription = "Back" }
                        .testTag("DetailBack")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = AuricleTheme.colors.textPrimary
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AuricleTheme.colors.accent, strokeWidth = 3.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (target) {
                        is DetailTarget.Album -> {
                            val album = fullAlbum ?: target.albumItem
                            item {
                                AlbumHeader(
                                    album = album,
                                    onPlayAll = {
                                        if (album.tracks.isNotEmpty()) {
                                            playbackController.playTrack(album.tracks.first(), album.tracks)
                                        }
                                    },
                                    onShuffle = {
                                        if (album.tracks.isNotEmpty()) {
                                            val shuffled = album.tracks.shuffled()
                                            playbackController.playTrack(shuffled.first(), shuffled)
                                        }
                                    }
                                )
                            }
                            items(album.tracks) { song ->
                                SongRow(
                                    song = song,
                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                    onClick = { playbackController.playTrack(song, album.tracks) },
                                    onQuickAddQueue = { playbackController.addToQueue(song) },
                                    onOptionsClick = { onSongOptionsClick(song) }
                                )
                            }
                        }
                        is DetailTarget.Artist -> {
                            val artist = fullArtist ?: target.artistItem
                            item {
                                ArtistHeader(
                                    artist = artist,
                                    onPlayAll = {
                                        if (artist.topSongs.isNotEmpty()) {
                                            playbackController.playTrack(artist.topSongs.first(), artist.topSongs)
                                        }
                                    }
                                )
                            }
                            if (artist.topSongs.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Top Songs",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AuricleTheme.colors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                                items(artist.topSongs) { song ->
                                    SongRow(
                                        song = song,
                                        isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                        onClick = { playbackController.playTrack(song, artist.topSongs) },
                                        onQuickAddQueue = { playbackController.addToQueue(song) },
                                        onOptionsClick = { onSongOptionsClick(song) }
                                    )
                                }
                            }
                            if (artist.albums.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Albums & Singles",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AuricleTheme.colors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(artist.albums + artist.singles) { alb ->
                                            Column(
                                                modifier = Modifier
                                                    .width(130.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { onAlbumClick(alb) }
                                                    .padding(4.dp)
                                            ) {
                                                AsyncImage(
                                                    model = alb.thumbnailUrl,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(122.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(AuricleTheme.colors.surface)
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = alb.title,
                                                    fontSize = 12.sp,
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
                        is DetailTarget.Playlist -> {
                            val playlist = fullPlaylist ?: target.playlistItem
                            item {
                                PlaylistHeader(
                                    title = playlist.title,
                                    subtitle = playlist.author,
                                    imageUrl = playlist.thumbnailUrl,
                                    trackCount = playlist.tracks.size,
                                    onPlayAll = {
                                        if (playlist.tracks.isNotEmpty()) {
                                            playbackController.playTrack(playlist.tracks.first(), playlist.tracks)
                                        }
                                    }
                                )
                            }
                            items(playlist.tracks) { song ->
                                SongRow(
                                    song = song,
                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                    onClick = { playbackController.playTrack(song, playlist.tracks) },
                                    onQuickAddQueue = { playbackController.addToQueue(song) },
                                    onOptionsClick = { onSongOptionsClick(song) }
                                )
                            }
                        }
                        is DetailTarget.CustomUserPlaylist -> {
                            val userPlaylist = target.playlist
                            item {
                                PlaylistHeader(
                                    title = userPlaylist.name,
                                    subtitle = "Custom Playlist",
                                    imageUrl = userPlaylist.tracks.firstOrNull()?.displayArtworkUrl ?: "",
                                    trackCount = userPlaylist.tracks.size,
                                    onPlayAll = {
                                        if (userPlaylist.tracks.isNotEmpty()) {
                                            playbackController.playTrack(userPlaylist.tracks.first(), userPlaylist.tracks)
                                        }
                                    }
                                )
                            }
                            items(userPlaylist.tracks) { song ->
                                SongRow(
                                    song = song,
                                    isPlaying = currentPlayingTrack?.videoId == song.videoId,
                                    onClick = { playbackController.playTrack(song, userPlaylist.tracks) },
                                    onQuickAddQueue = { playbackController.addToQueue(song) },
                                    onOptionsClick = { onSongOptionsClick(song) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(album: AlbumItem, onPlayAll: () -> Unit, onShuffle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = album.thumbnailUrl,
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AuricleTheme.colors.surface)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = album.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AuricleTheme.colors.textPrimary
        )
        Text(
            text = "${album.artist} • ${album.year}",
            fontSize = 13.sp,
            color = AuricleTheme.colors.textMuted
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentPillButton(onClick = onPlayAll, isPrimary = true) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play All")
            }
            AccentPillButton(onClick = onShuffle, isPrimary = false) {
                Icon(imageVector = Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Shuffle")
            }
        }
    }
}

@Composable
private fun ArtistHeader(artist: ArtistItem, onPlayAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = artist.thumbnailUrl,
            contentDescription = "Artist photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(AuricleTheme.colors.surface)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = artist.name,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AuricleTheme.colors.textPrimary
        )
        Spacer(modifier = Modifier.height(14.dp))
        AccentPillButton(onClick = onPlayAll, isPrimary = true) {
            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Play Artist")
        }
    }
}

@Composable
private fun PlaylistHeader(title: String, subtitle: String, imageUrl: String, trackCount: Int, onPlayAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Playlist cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AuricleTheme.colors.surface)
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AuricleTheme.colors.textPrimary
        )
        Text(
            text = "$subtitle • $trackCount tracks",
            fontSize = 13.sp,
            color = AuricleTheme.colors.textMuted
        )
        Spacer(modifier = Modifier.height(14.dp))
        AccentPillButton(onClick = onPlayAll, isPrimary = true) {
            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Play All")
        }
    }
}
