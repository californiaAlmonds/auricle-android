package com.auricle.app.ui.screens

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.AuricleApp
import com.auricle.app.data.MusicRepository
import com.auricle.app.model.AlbumItem
import com.auricle.app.model.ArtistCredit
import com.auricle.app.model.ArtistItem
import com.auricle.app.model.PlaylistItem
import com.auricle.app.model.UserPlaylist
import com.auricle.app.ui.components.AuricleIcons
import com.auricle.app.ui.components.auriclePressClickable
import com.auricle.app.ui.theme.AuricleBrandGradient
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch

sealed class ContextMenuItem {
    data class Album(val album: AlbumItem) : ContextMenuItem()
    data class Artist(val artist: ArtistItem) : ContextMenuItem()
    data class RemotePlaylist(val playlist: PlaylistItem) : ContextMenuItem()
    data class LocalPlaylist(val playlist: UserPlaylist) : ContextMenuItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemOptionBottomSheet(
    item: ContextMenuItem,
    isSavedOrFollowed: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onToggleSaveOrFollow: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
    onRenamePlaylist: ((String) -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    musicRepo: MusicRepository = (LocalContext.current.applicationContext as AuricleApp).musicRepository,
    onGoToArtistCredit: ((ArtistCredit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showRenameDialog by remember(item) { mutableStateOf(false) }
    var renameText by remember(item) {
        mutableStateOf(
            if (item is ContextMenuItem.LocalPlaylist) item.playlist.name else ""
        )
    }
    var showDeleteDialog by remember(item) { mutableStateOf(false) }

    val initialAlbumArtists = remember(item) {
        if (item is ContextMenuItem.Album) item.album.parsedArtistCredits() else emptyList()
    }
    var resolvedAlbumArtists by remember(item) { mutableStateOf(initialAlbumArtists) }
    var isResolvingAlbumArtists by remember(item) { mutableStateOf(false) }
    var showArtistPicker by remember(item) { mutableStateOf(false) }
    var pendingArtistNavigate by remember(item) { mutableStateOf(false) }

    val currentOnGoToArtistCredit by rememberUpdatedState(onGoToArtistCredit)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val hasOnGoToArtistCredit = onGoToArtistCredit != null

    val hasAlbumArtists = item is ContextMenuItem.Album &&
        hasOnGoToArtistCredit &&
        (item.album.artist.isNotBlank() || initialAlbumArtists.isNotEmpty() || resolvedAlbumArtists.isNotEmpty())

    LaunchedEffect(item, hasOnGoToArtistCredit) {
        if (item is ContextMenuItem.Album &&
            hasOnGoToArtistCredit &&
            item.album.browseId.isNotBlank() &&
            (item.album.artists.isEmpty() || item.album.artists.any { it.browseId.isBlank() })
        ) {
            isResolvingAlbumArtists = true
            try {
                val fetched = musicRepo.resolveArtists("album", item.album.browseId, item.album.artist)
                if (fetched.isNotEmpty()) {
                    resolvedAlbumArtists = fetched
                }
            } finally {
                isResolvingAlbumArtists = false
            }
            if (pendingArtistNavigate) {
                pendingArtistNavigate = false
                when {
                    resolvedAlbumArtists.size > 1 -> showArtistPicker = true
                    resolvedAlbumArtists.size == 1 -> {
                        currentOnGoToArtistCredit?.invoke(resolvedAlbumArtists.first())
                        currentOnDismiss()
                    }
                    item.album.artist.isNotBlank() -> {
                        currentOnGoToArtistCredit?.invoke(ArtistCredit(name = item.album.artist))
                        currentOnDismiss()
                    }
                }
            }
        }
    }

    val artworkUrl = when (item) {
        is ContextMenuItem.Album -> item.album.displayThumbnailUrl
        is ContextMenuItem.Artist -> item.artist.displayThumbnailUrl
        is ContextMenuItem.RemotePlaylist -> item.playlist.displayThumbnailUrl
        is ContextMenuItem.LocalPlaylist -> item.playlist.tracks
            .firstOrNull { it.displayArtworkUrl.isNotBlank() }
            ?.displayArtworkUrl
            .orEmpty()
    }

    val title = when (item) {
        is ContextMenuItem.Album -> item.album.title
        is ContextMenuItem.Artist -> item.artist.name
        is ContextMenuItem.RemotePlaylist -> item.playlist.title
        is ContextMenuItem.LocalPlaylist -> item.playlist.name
    }

    val typeBadge = when (item) {
        is ContextMenuItem.Album -> item.album.itemType.ifBlank { "album" }
            .replaceFirstChar { it.uppercase() }
        is ContextMenuItem.Artist -> "Artist"
        is ContextMenuItem.RemotePlaylist -> "Playlist"
        is ContextMenuItem.LocalPlaylist -> "Playlist"
    }

    val subtitleDetail = when (item) {
        is ContextMenuItem.Album -> listOfNotNull(
            item.album.artist.takeIf { it.isNotBlank() },
            item.album.year.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        is ContextMenuItem.Artist -> item.artist.subscribers
        is ContextMenuItem.RemotePlaylist -> buildList {
            if (item.playlist.author.isNotBlank()) add(item.playlist.author)
            if (item.playlist.trackCount > 0) {
                add("${item.playlist.trackCount} ${if (item.playlist.trackCount == 1) "track" else "tracks"}")
            }
        }.joinToString(" · ")
        is ContextMenuItem.LocalPlaylist -> {
            val count = item.playlist.tracks.size
            "$count ${if (count == 1) "track" else "tracks"}"
        }
    }

    val safeShare: () -> Unit = {
        onShare?.let { shareAction ->
            try {
                shareAction()
            } catch (_: ActivityNotFoundException) {
            }
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = AuricleTheme.colors.sheetBg,
        scrimColor = AuricleTheme.colors.scrim,
        dragHandle = {
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
    ) {
        val view = LocalView.current
        val isDark = AuricleTheme.colors.isDark
        if (!view.isInEditMode) {
            SideEffect {
                var parent = view.parent
                var window: android.view.Window? = null
                while (parent != null) {
                    if (parent is DialogWindowProvider) {
                        window = parent.window
                        break
                    }
                    parent = (parent as? android.view.View)?.parent
                }
                if (window != null) {
                    WindowInsetsControllerCompat(window, view).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.decorView.isForceDarkAllowed = false
                    }
                }
            }
        }

        BackHandler(enabled = showArtistPicker) {
            showArtistPicker = false
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(AuricleTheme.colors.sheetBg)
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val thumbShape = if (item is ContextMenuItem.Artist) {
                    CircleShape
                } else {
                    RoundedCornerShape(8.dp)
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(thumbShape)
                        .background(AuricleBrandGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item is ContextMenuItem.Artist) AuricleIcons.Artist else AuricleIcons.Music,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
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

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = AuricleTheme.typography.headline,
                        color = AuricleTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AuricleTheme.colors.fillMed)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = typeBadge,
                                style = AuricleTheme.typography.micro,
                                color = AuricleTheme.colors.textMuted
                            )
                        }
                        if (subtitleDetail.isNotBlank()) {
                            Text(
                                text = subtitleDetail,
                                style = AuricleTheme.typography.sub,
                                color = AuricleTheme.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AuricleTheme.colors.border)
            )

            if (showArtistPicker && item is ContextMenuItem.Album) {
                // In-Sheet Album Artist Picker Submenu
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
                        if (isResolvingAlbumArtists && resolvedAlbumArtists.isNotEmpty()) {
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
                            .auriclePressClickable { showArtistPicker = false }
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

                if (isResolvingAlbumArtists && resolvedAlbumArtists.isEmpty()) {
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
                } else if (resolvedAlbumArtists.isEmpty()) {
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
                            items = resolvedAlbumArtists,
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
                                        onGoToArtistCredit?.invoke(credit)
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
            } else {
                // Quick Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ItemQuickPill(
                        icon = AuricleIcons.Play,
                        label = "Play",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onPlay()
                            onDismiss()
                        }
                    )

                    ItemQuickPill(
                        icon = AuricleIcons.Shuffle,
                        label = "Shuffle",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onShuffle()
                            onDismiss()
                        }
                    )

                    when (item) {
                        is ContextMenuItem.Artist -> {
                            if (onToggleSaveOrFollow != null) {
                                ItemQuickPill(
                                    icon = if (isSavedOrFollowed) AuricleIcons.Check else AuricleIcons.Plus,
                                    label = if (isSavedOrFollowed) "Following" else "Follow",
                                    iconTint = if (isSavedOrFollowed) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textPrimary,
                                    labelColor = if (isSavedOrFollowed) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleSaveOrFollow()
                                        onDismiss()
                                    }
                                )
                            } else if (onShare != null) {
                                ItemQuickPill(
                                    icon = AuricleIcons.Share,
                                    label = "Share",
                                    modifier = Modifier.weight(1f),
                                    onClick = safeShare
                                )
                            }
                        }
                        is ContextMenuItem.Album,
                        is ContextMenuItem.RemotePlaylist -> {
                            if (onToggleSaveOrFollow != null) {
                                ItemQuickPill(
                                    icon = if (isSavedOrFollowed) AuricleIcons.HeartFill else AuricleIcons.Heart,
                                    label = if (isSavedOrFollowed) "Saved" else "Save",
                                    iconTint = if (isSavedOrFollowed) AuricleTheme.colors.danger else AuricleTheme.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleSaveOrFollow()
                                        onDismiss()
                                    }
                                )
                            } else if (onShare != null) {
                                ItemQuickPill(
                                    icon = AuricleIcons.Share,
                                    label = "Share",
                                    modifier = Modifier.weight(1f),
                                    onClick = safeShare
                                )
                            }
                        }
                        is ContextMenuItem.LocalPlaylist -> {
                            if (onShare != null) {
                                ItemQuickPill(
                                    icon = AuricleIcons.Share,
                                    label = "Share",
                                    modifier = Modifier.weight(1f),
                                    onClick = safeShare
                                )
                            }
                        }
                    }
                }

                // Action Rows
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onOpenDetail != null) {
                        val openLabel = when (item) {
                            is ContextMenuItem.Artist -> "Open Artist"
                            is ContextMenuItem.Album -> "Open Album"
                            is ContextMenuItem.RemotePlaylist,
                            is ContextMenuItem.LocalPlaylist -> "Open Playlist"
                        }
                        val openIcon = when (item) {
                            is ContextMenuItem.Artist -> AuricleIcons.Artist
                            is ContextMenuItem.Album -> AuricleIcons.Album
                            is ContextMenuItem.RemotePlaylist,
                            is ContextMenuItem.LocalPlaylist -> AuricleIcons.Queue
                        }
                        ItemActionRow(
                            icon = openIcon,
                            label = openLabel,
                            trailingIcon = AuricleIcons.ChevronRight,
                            onClick = {
                                onOpenDetail()
                                onDismiss()
                            }
                        )
                    }

                    if (onAddToQueue != null) {
                        ItemActionRow(
                            icon = AuricleIcons.ListPlus,
                            label = "Add to Queue",
                            onClick = {
                                onAddToQueue()
                                onDismiss()
                            }
                        )
                    }

                    if (hasAlbumArtists && item is ContextMenuItem.Album) {
                        val hasMultipleArtists = resolvedAlbumArtists.size > 1
                        ItemActionRow(
                            icon = AuricleIcons.Artist,
                            label = "Go to Artist",
                            trailingLabel = if (hasMultipleArtists) "${resolvedAlbumArtists.size}" else null,
                            trailingIcon = if (hasMultipleArtists || (isResolvingAlbumArtists && resolvedAlbumArtists.isEmpty())) AuricleIcons.ChevronRight else null,
                            isLoading = pendingArtistNavigate && isResolvingAlbumArtists,
                            onClick = {
                                if (isResolvingAlbumArtists) {
                                    if (resolvedAlbumArtists.size > 1) {
                                        showArtistPicker = true
                                    } else {
                                        pendingArtistNavigate = true
                                    }
                                } else if (resolvedAlbumArtists.size > 1) {
                                    showArtistPicker = true
                                } else if (resolvedAlbumArtists.size == 1) {
                                    currentOnGoToArtistCredit?.invoke(resolvedAlbumArtists.first())
                                    currentOnDismiss()
                                } else {
                                    scope.launch {
                                        val fetched = musicRepo.resolveArtists(
                                            "album",
                                            item.album.browseId,
                                            item.album.artist
                                        )
                                        if (fetched.size > 1) {
                                            resolvedAlbumArtists = fetched
                                            showArtistPicker = true
                                        } else if (fetched.size == 1) {
                                            currentOnGoToArtistCredit?.invoke(fetched.first())
                                            currentOnDismiss()
                                        } else if (item.album.artist.isNotBlank()) {
                                            currentOnGoToArtistCredit?.invoke(ArtistCredit(name = item.album.artist))
                                            currentOnDismiss()
                                        }
                                    }
                                }
                            }
                        )
                    }

                    if (item is ContextMenuItem.LocalPlaylist) {
                        if (onRenamePlaylist != null) {
                            ItemActionRow(
                                icon = AuricleIcons.Edit,
                                label = "Rename Playlist",
                                onClick = {
                                    renameText = item.playlist.name
                                    showRenameDialog = true
                                }
                            )
                        }

                        if (onDeletePlaylist != null) {
                            ItemActionRow(
                                icon = AuricleIcons.Trash,
                                label = "Delete Playlist",
                                iconTint = AuricleTheme.colors.danger,
                                labelColor = AuricleTheme.colors.danger,
                                onClick = { showDeleteDialog = true }
                            )
                        }
                    }

                    val shareShownInQuickPills = item is ContextMenuItem.LocalPlaylist || onToggleSaveOrFollow == null
                    if (onShare != null && !shareShownInQuickPills) {
                        ItemActionRow(
                            icon = AuricleIcons.Share,
                            label = "Share",
                            onClick = safeShare
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog && item is ContextMenuItem.LocalPlaylist && onRenamePlaylist != null) {
        val submitRename: () -> Unit = {
            val trimmed = renameText.trim()
            if (trimmed.isNotEmpty()) {
                onRenamePlaylist(trimmed)
                showRenameDialog = false
                onDismiss()
            }
        }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Rename Playlist",
                    style = AuricleTheme.typography.title2,
                    color = AuricleTheme.colors.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("Playlist name", color = AuricleTheme.colors.textMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitRename() }),
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
                    enabled = renameText.isNotBlank(),
                    onClick = submitRename
                ) {
                    Text(
                        text = "Rename",
                        color = if (renameText.isNotBlank()) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textDim,
                        style = AuricleTheme.typography.calloutSB
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = AuricleTheme.colors.textMuted,
                        style = AuricleTheme.typography.callout
                    )
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }

    if (showDeleteDialog && item is ContextMenuItem.LocalPlaylist && onDeletePlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Delete Playlist",
                    style = AuricleTheme.typography.title2,
                    color = AuricleTheme.colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Delete \"${item.playlist.name}\"? This cannot be undone.",
                    style = AuricleTheme.typography.body,
                    color = AuricleTheme.colors.textMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist()
                        showDeleteDialog = false
                        onDismiss()
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = AuricleTheme.colors.danger,
                        style = AuricleTheme.typography.calloutSB
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = AuricleTheme.colors.textMuted,
                        style = AuricleTheme.typography.callout
                    )
                }
            },
            containerColor = AuricleTheme.colors.surface2
        )
    }
}

@Composable
private fun ItemQuickPill(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconTint: Color = AuricleTheme.colors.textPrimary,
    labelColor: Color = AuricleTheme.colors.textPrimary,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AuricleTheme.colors.fillMed)
            .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
            .auriclePressClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = AuricleTheme.typography.captionSB,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ItemActionRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = AuricleTheme.colors.textPrimary,
    labelColor: Color = AuricleTheme.colors.textPrimary,
    trailingLabel: String? = null,
    trailingIcon: ImageVector? = null,
    isLoading: Boolean = false,
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
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = AuricleTheme.colors.accentStrong
            )
        } else if (trailingLabel != null || trailingIcon != null) {
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
