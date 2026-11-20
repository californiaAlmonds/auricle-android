package com.auricle.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun AlbumCard(
    title: String,
    subtitle: String,
    artworkUrl: String,
    onClick: () -> Unit,
    width: Dp = 160.dp,
    artworkModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(width)
            .auriclePressClickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = artworkModifier
                .size(width)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(10.dp))
                .background(AuricleTheme.colors.surface2)
        )
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
    modifier: Modifier = Modifier
) {
    AlbumCard(
        title = song.title,
        subtitle = song.artist,
        artworkUrl = song.displayArtworkUrl,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun ArtistCircleCard(
    name: String,
    artworkUrl: String,
    onClick: () -> Unit,
    size: Dp = 96.dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(size)
            .auriclePressClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, AuricleTheme.colors.borderSoft, CircleShape)
                .background(AuricleTheme.colors.surface2)
        )
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
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(AuricleTheme.colors.surface2),
        contentAlignment = Alignment.Center
    ) {
        if (artworkUrls.isEmpty()) {
            Icon(
                imageVector = AuricleIcons.Music,
                contentDescription = null,
                tint = AuricleTheme.colors.textMuted,
                modifier = Modifier.size(size / 2)
            )
        } else if (artworkUrls.size < 4) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrls.first())
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(artworkUrls[0]).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(artworkUrls[1]).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(artworkUrls[2]).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(artworkUrls[3]).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}
