package com.auricle.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.model.SongItem
import com.auricle.app.ui.theme.AuricleTheme
import kotlin.math.abs

@Composable
fun MiniPlayer(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    isLiked: Boolean = false,
    onLikeToggle: () -> Unit = {},
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit = {},
    onPreviousClick: () -> Unit = {},
    isInParty: Boolean = false,
    artworkModifier: Modifier = Modifier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = currentTrack ?: return

    var offsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var swipeUpTriggered by remember { mutableStateOf(false) }
    val reducedMotion = AuricleTheme.reducedMotion
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = if (reducedMotion) snap() else spring(),
        label = "miniPlayerOffsetX"
    )

    AuricleGlassBox(
        shape = RoundedCornerShape(16.dp),
        fallbackColor = AuricleTheme.colors.miniPlayerGlass,
        borderColor = AuricleTheme.colors.border,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(64.dp)
            .graphicsLayer { translationX = animatedOffsetX }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragOffsetY = 0f
                        swipeUpTriggered = false
                    },
                    onDragEnd = {
                        if (!swipeUpTriggered) {
                            if (offsetX < -48.dp.toPx()) {
                                onNextClick()
                            } else if (offsetX > 48.dp.toPx()) {
                                onPreviousClick()
                            } else if (dragOffsetY < -36.dp.toPx() && abs(offsetX) < 32.dp.toPx()) {
                                swipeUpTriggered = true
                                onClick()
                            }
                        }
                        offsetX = 0f
                        dragOffsetY = 0f
                        swipeUpTriggered = false
                    },
                    onDragCancel = {
                        offsetX = 0f
                        dragOffsetY = 0f
                        swipeUpTriggered = false
                    }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    dragOffsetY += dragAmount.y
                    if (!swipeUpTriggered && dragOffsetY < -40.dp.toPx() && abs(offsetX) < 32.dp.toPx()) {
                        swipeUpTriggered = true
                        onClick()
                    }
                }
            }
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 11.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val artUrl = track.displayArtworkUrl
            Box(
                modifier = artworkModifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AuricleTheme.colors.fillMed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.Music,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textDim.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
                if (artUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(artUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    style = AuricleTheme.typography.bodySB,
                    color = AuricleTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isInParty) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(AuricleTheme.colors.success)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${track.artist} · Party",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = track.artist,
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(36.dp)
                    .clip(CircleShape)
                    .auriclePressClickable(
                        hapticType = HapticFeedbackType.TextHandleMove,
                        onClick = onLikeToggle
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLiked) AuricleIcons.HeartFill else AuricleIcons.Heart,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clip(CircleShape)
                    .auriclePressClickable(
                        hapticType = HapticFeedbackType.TextHandleMove,
                        onClick = onPlayPauseClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AuricleTheme.colors.textPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) AuricleIcons.Pause else AuricleIcons.Play,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = AuricleTheme.colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clip(CircleShape)
                    .auriclePressClickable(onClick = onNextClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.Next,
                    contentDescription = "Next",
                    tint = AuricleTheme.colors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Progress bar
        val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 2.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(AuricleTheme.colors.fillMed)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(AuricleTheme.colors.accentStrong)
            )
        }
    }
}
