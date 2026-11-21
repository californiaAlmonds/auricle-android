package com.auricle.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auricle.app.model.SongItem
import com.auricle.app.ui.theme.AuricleTheme
import com.auricle.app.ui.theme.blendWithAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun FeaturedHeroPager(
    items: List<SongItem>,
    onPlayClick: (SongItem) -> Unit,
    onAddToQueueClick: (SongItem) -> Unit,
    onHeroLongClick: ((SongItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { minOf(items.size, 5) })
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colorCache = remember { mutableStateMapOf<String, Color>() }
    val isDark = AuricleTheme.colors.isDark
    val elevated = AuricleTheme.colors.elevated
    val defaultArtColor = AuricleTheme.colors.accentStrong

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 16.dp
        ) { page ->
            val song = items[page]
            val artColor = colorCache[song.videoId] ?: defaultArtColor

            LaunchedEffect(song.videoId) {
                if (!colorCache.containsKey(song.videoId)) {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val loader = context.imageLoader
                            val request = ImageRequest.Builder(context)
                                .data(song.displayArtworkUrl)
                                .allowHardware(false)
                                .size(200)
                                .build()
                            val result = loader.execute(request)
                            if (result is SuccessResult) {
                                val bitmap = result.drawable.toBitmap()
                                val palette = Palette.from(bitmap).generate()
                                val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                                if (swatch != null) {
                                    colorCache[song.videoId] = Color(swatch.rgb)
                                }
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }

            val brush = if (isDark) {
                Brush.linearGradient(
                    listOf(
                        blendWithAccent(elevated, artColor, 0.55f),
                        blendWithAccent(elevated, artColor, 0.28f),
                        elevated
                    )
                )
            } else {
                Brush.linearGradient(
                    listOf(
                        blendWithAccent(Color.White, artColor, 0.28f),
                        blendWithAccent(Color.White, artColor, 0.12f),
                        Color.White
                    )
                )
            }

            val cardShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp)
                    .clip(cardShape)
                    .background(brush)
                    .border(1.dp, AuricleTheme.colors.borderSoft, cardShape)
                    .auriclePressClickable(
                        onClick = { onPlayClick(song) },
                        onLongClick = onHeroLongClick?.let { cb -> { cb(song) } }
                    )
                    .padding(18.dp)
            ) {
                Icon(
                    imageVector = AuricleIcons.Sparkles,
                    contentDescription = null,
                    tint = artColor.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(song.displayArtworkUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(128.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuricleTheme.colors.surface2)
                    )

                    Spacer(Modifier.width(16.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "MADE FOR YOUR TASTE",
                            style = AuricleTheme.typography.overline,
                            color = artColor
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = song.title,
                            style = AuricleTheme.typography.title1,
                            color = AuricleTheme.colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = song.artist,
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(AuricleTheme.colors.primaryFill)
                                    .auriclePressClickable { onPlayClick(song) }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AuricleIcons.Play, contentDescription = null, tint = AuricleTheme.colors.onPrimaryFill, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Listen Now", style = AuricleTheme.typography.subSB, color = AuricleTheme.colors.onPrimaryFill)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            AuricleCircleIconButton(
                                icon = AuricleIcons.Plus,
                                contentDescription = "Add to Queue",
                                onClick = { onAddToQueueClick(song) },
                                size = 36.dp,
                                iconSize = 16.dp,
                                containerColor = AuricleTheme.colors.fillMed,
                                tint = AuricleTheme.colors.textPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pageCount = minOf(items.size, 5)
            for (i in 0 until pageCount) {
                val isSelected = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (isSelected) 18.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) AuricleTheme.colors.textPrimary else AuricleTheme.colors.textDim)
                )
            }
        }
    }
}

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
                        imageVector = AuricleIcons.Sparkles,
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
                        imageVector = AuricleIcons.Refresh,
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
                                imageVector = AuricleIcons.Play,
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
                                imageVector = AuricleIcons.Plus,
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
