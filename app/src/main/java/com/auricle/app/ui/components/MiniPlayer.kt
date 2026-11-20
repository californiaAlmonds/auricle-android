package com.auricle.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    AnimatedVisibility(
        visible = currentTrack != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        if (currentTrack == null) return@AnimatedVisibility

        var offsetX by remember { mutableStateOf(0f) }
        val animatedOffsetX by animateFloatAsState(targetValue = offsetX)

        AuricleGlassBox(
            shape = RoundedCornerShape(16.dp),
            fallbackColor = AuricleTheme.colors.miniPlayerGlass,
            borderColor = AuricleTheme.colors.border,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(64.dp)
                .graphicsLayer { translationX = animatedOffsetX }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            if (offsetX < -48.dp.toPx()) {
                                onNextClick()
                            } else if (offsetX > 48.dp.toPx()) {
                                onPreviousClick()
                            }
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        if (dragAmount.y < -30f && abs(offsetX) < 20f) {
                            onClick()
                        }
                    }
                }
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 11.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(currentTrack.displayArtworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = artworkModifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AuricleTheme.colors.fillMed)
                )

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentTrack.title,
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
                                text = "${currentTrack.artist} · Party",
                                style = AuricleTheme.typography.sub,
                                color = AuricleTheme.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = currentTrack.artist,
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
                        .size(40.dp)
                        .auriclePressClickable(
                            hapticType = androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
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

                Spacer(Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(40.dp)
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
}
