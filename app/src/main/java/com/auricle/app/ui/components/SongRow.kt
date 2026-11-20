package com.auricle.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.model.SongItem
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun PlayingBarsIndicator(
    color: Color,
    isAnimating: Boolean = true,
    modifier: Modifier = Modifier.size(18.dp)
) {
    val reducedMotion = AuricleTheme.reducedMotion
    val shouldAnimate = isAnimating && !reducedMotion

    val infiniteTransition = rememberInfiniteTransition()

    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse)
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse)
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse)
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        val h1 = if (shouldAnimate) bar1 else 0.5f
        val h2 = if (shouldAnimate) bar2 else 0.7f
        val h3 = if (shouldAnimate) bar3 else 0.6f

        Box(modifier = Modifier.width(3.dp).fillMaxHeight(h1).clip(RoundedCornerShape(1.5.dp)).background(color))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(h2).clip(RoundedCornerShape(1.5.dp)).background(color))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(h3).clip(RoundedCornerShape(1.5.dp)).background(color))
    }
}

@Composable
fun SongRow(
    song: SongItem,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onQuickAddQueue: () -> Unit = {},
    onOptionsClick: () -> Unit,
    isActivelyPlaying: Boolean = isPlaying,
    showDivider: Boolean = true,
    subtitleOverride: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .auriclePressClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AuricleTheme.colors.surface)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.displayArtworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.50f)),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayingBarsIndicator(
                            color = AuricleTheme.colors.accentStrong,
                            isAnimating = isActivelyPlaying,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = AuricleTheme.typography.bodyM,
                    color = if (isPlaying) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val sub = subtitleOverride ?: buildString {
                    append(song.artist)
                    if (song.displayDuration.isNotBlank()) append(" · ${song.displayDuration}")
                }
                Text(
                    text = sub,
                    style = AuricleTheme.typography.sub,
                    color = AuricleTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .semantics { contentDescription = "More options for ${song.title}" }
                    .testTag("SongOptions")
                    .auriclePressClickable(onClick = onOptionsClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.MoreVertical,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 76.dp) // 16 margin + 48 art + 12 gap
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AuricleTheme.colors.border)
            )
        }
    }
}

@Composable
fun TrackRow(
    index: Int,
    song: SongItem,
    isPlaying: Boolean,
    isActivelyPlaying: Boolean = isPlaying,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .auriclePressClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    PlayingBarsIndicator(
                        color = AuricleTheme.colors.accentStrong,
                        isAnimating = isActivelyPlaying,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = "$index",
                        style = AuricleTheme.typography.body,
                        color = AuricleTheme.colors.textMuted
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            val titleStyle = if (isPlaying) AuricleTheme.typography.bodySB else AuricleTheme.typography.body
            Text(
                text = song.title,
                style = titleStyle,
                color = if (isPlaying) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(12.dp))

            Text(
                text = song.displayDuration,
                style = AuricleTheme.typography.sub,
                color = AuricleTheme.colors.textMuted
            )

            Spacer(Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .auriclePressClickable(onClick = onOptionsClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.More,
                    contentDescription = "Track options",
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 52.dp) // 16 margin + 28 lead + 8 gap
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AuricleTheme.colors.border)
            )
        }
    }
}

@Composable
fun OnArtQueueRow(
    song: SongItem,
    onClick: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    alpha: Float = 1f,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .auriclePressClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.displayArtworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    alpha = alpha
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = AuricleTheme.typography.bodyM,
                    color = Color.White.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = AuricleTheme.typography.sub,
                    color = Color.White.copy(alpha = alpha * 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp)
                        .auriclePressClickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AuricleIcons.Trash,
                        contentDescription = "Remove",
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            Box(
                modifier = dragHandleModifier
                    .minimumInteractiveComponentSize()
                    .size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.Grip,
                    contentDescription = "Drag Handle",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
