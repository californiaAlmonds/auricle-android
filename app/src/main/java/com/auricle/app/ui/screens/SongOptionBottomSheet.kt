package com.auricle.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.AuricleApp
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.ArtistCredit
import com.auricle.app.model.SongItem
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.AuricleCircleIconButton
import com.auricle.app.ui.components.AuricleIcons
import com.auricle.app.ui.components.auriclePressClickable
import com.auricle.app.ui.theme.AuricleTheme

private enum class SongOptionsSubmenu {
    MAIN,
    PLAYLISTS,
    ARTISTS
}

@Composable
fun SongOptionBottomSheet(
    song: SongItem,
    playbackController: PlaybackController,
    libraryRepo: LibraryRepository,
    onDismiss: () -> Unit,
    onGoToAlbum: ((SongItem) -> Unit)? = null,
    onGoToArtist: ((SongItem) -> Unit)? = null,
    playlistContextName: String? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    musicRepo: MusicRepository = (LocalContext.current.applicationContext as AuricleApp).musicRepository,
    onGoToArtistCredit: ((ArtistCredit) -> Unit)? = null,
    startInArtistPicker: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val libraryState by libraryRepo.state.collectAsState()
    val isLiked = libraryState.likedSongs.any { it.videoId == song.videoId }

    var submenu by remember(song.videoId, startInArtistPicker) {
        mutableStateOf(if (startInArtistPicker) SongOptionsSubmenu.ARTISTS else SongOptionsSubmenu.MAIN)
    }
    var newPlaylistName by remember(song.videoId) { mutableStateOf("") }
    var resolvedArtists by remember(song.videoId, song.artist) {
        mutableStateOf(song.parsedArtistCredits())
    }
    var isResolvingArtists by remember(song.videoId, song.artist) {
        mutableStateOf(false)
    }

    LaunchedEffect(song.videoId, song.artist) {
        if (song.videoId.isNotBlank() && (song.artists.isEmpty() || song.artists.any { it.browseId.isBlank() })) {
            isResolvingArtists = true
            try {
                val fetched = musicRepo.resolveArtists("song", song.videoId, song.artist)
                if (fetched.isNotEmpty()) {
                    resolvedArtists = fetched
                }
            } finally {
                isResolvingArtists = false
            }
        }
    }

    BackHandler(
        enabled = submenu == SongOptionsSubmenu.PLAYLISTS ||
            (submenu == SongOptionsSubmenu.ARTISTS && !startInArtistPicker)
    ) {
        submenu = SongOptionsSubmenu.MAIN
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.sheetBg)
            .navigationBarsPadding()
            .imePadding()
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
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AuricleTheme.colors.surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.Music,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textDim,
                    modifier = Modifier.size(22.dp)
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

        when (submenu) {
            SongOptionsSubmenu.MAIN -> {
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
                                Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
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
                    val canShare = song.videoId.isNotBlank() || song.title.isNotBlank()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .alpha(if (canShare) 1f else 0.45f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AuricleTheme.colors.fillMed)
                            .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
                            .auriclePressClickable(enabled = canShare) {
                                val headerText = listOf(song.title, song.artist)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" — ")
                                val shareText = if (song.videoId.isNotBlank()) {
                                    "$headerText\nhttps://music.youtube.com/watch?v=${song.videoId}"
                                } else {
                                    headerText
                                }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, headerText)
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                                } catch (_: ActivityNotFoundException) {
                                }
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

                // Action Rows
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1. Start Radio
                    SongActionRow(
                        icon = AuricleIcons.Radio,
                        label = "Start Radio",
                        onClick = {
                            playbackController.startRadio(song)
                            Toast.makeText(context, "Starting radio…", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )

                    // 2. Add to Playlist
                    SongActionRow(
                        icon = AuricleIcons.Plus,
                        label = "Add to Playlist",
                        trailingIcon = AuricleIcons.ChevronRight,
                        onClick = { submenu = SongOptionsSubmenu.PLAYLISTS }
                    )

                    // Optional: Remove from Playlist
                    if (onRemoveFromPlaylist != null) {
                        SongActionRow(
                            icon = AuricleIcons.Trash,
                            label = "Remove from ${playlistContextName ?: "playlist"}",
                            iconTint = AuricleTheme.colors.danger,
                            labelColor = AuricleTheme.colors.danger,
                            onClick = {
                                onRemoveFromPlaylist()
                                onDismiss()
                            }
                        )
                    }

                    // 3. Go to Album
                    if (onGoToAlbum != null && (song.album.isNotBlank() || song.albumId.isNotBlank())) {
                        SongActionRow(
                            icon = AuricleIcons.Album,
                            label = "Go to Album",
                            onClick = {
                                onGoToAlbum.invoke(song)
                                onDismiss()
                            }
                        )
                    }

                    // 4. Go to Artist
                    val canGoToArtist = (onGoToArtistCredit != null || onGoToArtist != null) &&
                        (song.artist.isNotBlank() || song.artistId.isNotBlank() || resolvedArtists.isNotEmpty())
                    if (canGoToArtist) {
                        val hasMultipleArtists = resolvedArtists.size > 1
                        val opensArtistSubmenu = hasMultipleArtists || (isResolvingArtists && resolvedArtists.isEmpty())
                        SongActionRow(
                            icon = AuricleIcons.Artist,
                            label = "Go to Artist",
                            trailingLabel = if (hasMultipleArtists) "${resolvedArtists.size}" else null,
                            trailingIcon = if (opensArtistSubmenu) AuricleIcons.ChevronRight else null,
                            onClick = {
                                when {
                                    opensArtistSubmenu -> {
                                        submenu = SongOptionsSubmenu.ARTISTS
                                    }
                                    resolvedArtists.size == 1 -> {
                                        val singleCredit = resolvedArtists.first().let { credit ->
                                            if (credit.browseId.isBlank() && song.artistId.isNotBlank()) {
                                                credit.copy(browseId = song.artistId)
                                            } else {
                                                credit
                                            }
                                        }
                                        if (onGoToArtistCredit != null) {
                                            onGoToArtistCredit.invoke(singleCredit)
                                        } else {
                                            onGoToArtist?.invoke(song)
                                        }
                                        onDismiss()
                                    }
                                    else -> {
                                        if (onGoToArtist != null) {
                                            onGoToArtist.invoke(song)
                                        } else {
                                            onGoToArtistCredit?.invoke(
                                                ArtistCredit(name = song.artist, browseId = song.artistId)
                                            )
                                        }
                                        onDismiss()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            SongOptionsSubmenu.PLAYLISTS -> {
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
                            .auriclePressClickable { submenu = SongOptionsSubmenu.MAIN }
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
                val createAndAdd: () -> Unit = {
                    if (newPlaylistName.isNotBlank()) {
                        val created = libraryRepo.createPlaylist(newPlaylistName.trim())
                        libraryRepo.addToPlaylist(created.id, song)
                        onDismiss()
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { createAndAdd() }),
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
                            .auriclePressClickable(enabled = newPlaylistName.isNotBlank(), onClick = createAndAdd)
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
                        items(playlists, key = { it.id }) { playlist ->
                            val isAlreadyAdded = song.videoId.isNotBlank() &&
                                playlist.tracks.any { it.videoId == song.videoId }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AuricleTheme.colors.surface2)
                                    .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(12.dp))
                                    .auriclePressClickable {
                                        if (!isAlreadyAdded) {
                                            libraryRepo.addToPlaylist(playlist.id, song)
                                        }
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
                                if (isAlreadyAdded) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(AuricleTheme.colors.accentSoft)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = AuricleIcons.Check,
                                            contentDescription = "Added",
                                            tint = AuricleTheme.colors.accentStrong,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "Added",
                                            style = AuricleTheme.typography.captionSB,
                                            color = AuricleTheme.colors.accentStrong
                                        )
                                    }
                                } else {
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

            SongOptionsSubmenu.ARTISTS -> {
                // Individual Artist Picker Submenu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Go to Artist",
                            style = AuricleTheme.typography.headline,
                            color = AuricleTheme.colors.textPrimary
                        )
                        if (isResolvingArtists && resolvedArtists.isNotEmpty()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = AuricleTheme.colors.accentStrong
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .auriclePressClickable {
                                if (startInArtistPicker) {
                                    onDismiss()
                                } else {
                                    submenu = SongOptionsSubmenu.MAIN
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (startInArtistPicker) "Close" else "Back",
                            style = AuricleTheme.typography.bodySB,
                            color = AuricleTheme.colors.accentStrong
                        )
                    }
                }

                if (isResolvingArtists && resolvedArtists.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = AuricleTheme.colors.accentStrong
                        )
                        Text(
                            text = "Loading artists…",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                } else if (resolvedArtists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No artist information available.",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = resolvedArtists,
                            key = { index, credit -> "$index:${credit.browseId}:${credit.name}" }
                        ) { _, credit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 58.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AuricleTheme.colors.surface2)
                                    .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(12.dp))
                                    .auriclePressClickable {
                                        if (onGoToArtistCredit != null) {
                                            onGoToArtistCredit.invoke(credit)
                                        } else {
                                            onGoToArtist?.invoke(
                                                song.copy(artist = credit.name, artistId = credit.browseId)
                                            )
                                        }
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(AuricleTheme.colors.accentSoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AuricleIcons.Artist,
                                        contentDescription = null,
                                        tint = AuricleTheme.colors.accentStrong,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = credit.name,
                                        style = AuricleTheme.typography.bodyM,
                                        color = AuricleTheme.colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Artist",
                                        style = AuricleTheme.typography.caption,
                                        color = AuricleTheme.colors.textMuted
                                    )
                                }
                                Icon(
                                    imageVector = AuricleIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = AuricleTheme.colors.textMuted,
                                    modifier = Modifier.size(18.dp)
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
private fun SongActionRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = AuricleTheme.colors.textPrimary,
    labelColor: Color = AuricleTheme.colors.textPrimary,
    trailingLabel: String? = null,
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
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailingLabel != null || trailingIcon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (trailingLabel != null) {
                    Text(
                        text = trailingLabel,
                        style = AuricleTheme.typography.captionSB,
                        color = AuricleTheme.colors.textMuted
                    )
                }
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
    }
}
