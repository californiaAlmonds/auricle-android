package com.auricle.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.model.SongItem
import com.auricle.app.model.upgradeArtworkUrl
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun AlbumCard(
    title: String,
    subtitle: String,
    artworkUrl: String,
    onClick: () -> Unit,
    width: Dp = 160.dp,
    artworkModifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedUrl = remember(artworkUrl) { upgradeArtworkUrl(artworkUrl, 544) }

    Column(
        modifier = modifier
            .width(width)
            .auriclePressClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = artworkModifier
                .size(width)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(10.dp))
                .background(AuricleTheme.colors.surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AuricleIcons.Music,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim.copy(alpha = 0.45f),
                modifier = Modifier.size(32.dp)
            )
            if (resolvedUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(resolvedUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = AuricleTheme.typography.callout,
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
}

@Composable
fun SongCard(
    song: SongItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AlbumCard(
        title = song.title,
        subtitle = song.artist,
        artworkUrl = song.displayArtworkUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
    )
}

@Composable
fun ArtistCircleCard(
    name: String,
    artworkUrl: String,
    onClick: () -> Unit,
    size: Dp = 96.dp,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedUrl = remember(artworkUrl) { upgradeArtworkUrl(artworkUrl, 544) }

    Column(
        modifier = modifier
            .width(size)
            .auriclePressClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, AuricleTheme.colors.borderSoft, CircleShape)
                .background(AuricleTheme.colors.surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AuricleIcons.Artist,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.36f)
            )
            if (resolvedUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(resolvedUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = AuricleTheme.typography.subM,
            color = AuricleTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PlaylistCollageArt(
    artworkUrls: List<String>,
    size: Dp = 56.dp,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedUrls = remember(artworkUrls) {
        artworkUrls.map { upgradeArtworkUrl(it, 544) }.filter { it.isNotBlank() }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(AuricleTheme.colors.surface2),
        contentAlignment = Alignment.Center
    ) {
        if (resolvedUrls.isEmpty()) {
            Icon(
                imageVector = AuricleIcons.Music,
                contentDescription = null,
                tint = AuricleTheme.colors.textMuted,
                modifier = Modifier.size(size / 2)
            )
        } else if (resolvedUrls.size < 4) {
            Icon(
                imageVector = AuricleIcons.Music,
                contentDescription = null,
                tint = AuricleTheme.colors.textDim.copy(alpha = 0.45f),
                modifier = Modifier.size(size * 0.42f)
            )
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(resolvedUrls.first())
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    CollageCell(url = resolvedUrls[0], modifier = Modifier.weight(1f).fillMaxHeight())
                    CollageCell(url = resolvedUrls[1], modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(modifier = Modifier.weight(1f)) {
                    CollageCell(url = resolvedUrls[2], modifier = Modifier.weight(1f).fillMaxHeight())
                    CollageCell(url = resolvedUrls[3], modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun CollageCell(
    url: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(AuricleTheme.colors.surface2),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = AuricleIcons.Music,
            contentDescription = null,
            tint = AuricleTheme.colors.textDim.copy(alpha = 0.35f),
            modifier = Modifier.size(12.dp)
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
