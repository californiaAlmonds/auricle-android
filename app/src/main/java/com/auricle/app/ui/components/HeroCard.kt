package com.auricle.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.auricle.app.model.SongItem
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun HeroCard(
    song: SongItem,
    onPlayClick: () -> Unit,
    onQueueClick: () -> Unit,
    onRefreshPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        AuricleTheme.colors.surface2,
                        AuricleTheme.colors.surface
                    )
                )
            )
            .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column {
            // Eyebrow Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = AuricleTheme.colors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "FEATURED PICK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuricleTheme.colors.accent,
                        letterSpacing = 1.sp
                    )
                }

                IconButton(
                    onClick = onRefreshPick,
                    modifier = Modifier
                        .semantics { contentDescription = "Refresh featured pick" }
                        .testTag("RefreshFeatured")
                        .size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Refresh",
                        tint = AuricleTheme.colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Content Row: Artwork + Song Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork (108dp)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.displayArtworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuricleTheme.colors.surface)
                )

                // Info & Action Buttons
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = song.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuricleTheme.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = song.artist,
                        fontSize = 13.sp,
                        color = AuricleTheme.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccentPillButton(
                            onClick = onPlayClick,
                            isPrimary = true,
                            modifier = Modifier.semantics { contentDescription = "Listen Now" }.testTag("ListenNow")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Listen Now", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        AccentPillButton(
                            onClick = onQueueClick,
                            isPrimary = false,
                            modifier = Modifier.semantics { contentDescription = "Add to Queue" }.testTag("QueueFeatured")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
