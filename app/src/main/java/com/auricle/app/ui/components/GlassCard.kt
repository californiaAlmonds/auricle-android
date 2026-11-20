package com.auricle.app.ui.components

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.ui.theme.AuricleTheme
import com.auricle.app.ui.theme.LocalBackdropLayer

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    backgroundColor: Color = AuricleTheme.colors.surface,
    borderColor: Color = AuricleTheme.colors.border,
    borderWidth: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val clickableModifier = if (onClick != null) {
        modifier.clickable { onClick() }
    } else {
        modifier
    }

    Surface(
        modifier = clickableModifier
            .clip(shape)
            .border(borderWidth, borderColor, shape),
        shape = shape,
        color = backgroundColor,
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.auriclePressClickable(
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    indication: Indication? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val reducedMotion = AuricleTheme.reducedMotion
    val pressVeil = AuricleTheme.colors.pressVeil

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 100)
    )

    if (isPressed && hapticType != null) {
        LaunchedEffect(Unit) {
            haptic.performHapticFeedback(hapticType)
        }
    }

    this
        .scale(scale)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onLongClickLabel = onLongClickLabel,
            role = role,
            onLongClick = onLongClick?.let { cb ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    cb()
                }
            },
            onClick = onClick
        )
        .drawWithContent {
            drawContent()
            if (isPressed) {
                drawRect(color = pressVeil)
            }
        }
}

@Composable
fun AuricleGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    fallbackColor: Color,
    borderColor: Color = AuricleTheme.colors.border,
    blurRadius: Dp = 24.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val backdropLayer = LocalBackdropLayer.current
    val reducedMotion = AuricleTheme.reducedMotion
    var offsetInRoot by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                offsetInRoot = coords.positionInRoot()
            }
            .clip(shape)
            .then(if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape) else Modifier),
        contentAlignment = contentAlignment
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdropLayer != null && !reducedMotion) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = BlurEffect(blurRadius.toPx(), blurRadius.toPx(), TileMode.Clamp)
                        clip = true
                    }
                    .drawBehind {
                        translate(-offsetInRoot.x, -offsetInRoot.y) {
                            drawLayer(backdropLayer)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(fallbackColor)
            )
        } else {
            val solidColor = fallbackColor.copy(alpha = fallbackColor.alpha.coerceAtLeast(0.94f))
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(solidColor)
            )
        }
        content()
    }
}

fun Modifier.auricleGlass(
    shape: Shape,
    fallbackColor: Color,
    borderColor: Color? = null,
    blurRadius: Dp = 24.dp
): Modifier = composed {
    val resolvedBorder = borderColor ?: AuricleTheme.colors.border
    val solidColor = fallbackColor.copy(alpha = fallbackColor.alpha.coerceAtLeast(0.92f))
    this
        .clip(shape)
        .background(solidColor)
        .then(if (resolvedBorder != Color.Transparent) Modifier.border(1.dp, resolvedBorder, shape) else Modifier)
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    val fillMed = AuricleTheme.colors.fillMed
    val fillSoft = AuricleTheme.colors.fillSoft
    val reducedMotion = AuricleTheme.reducedMotion
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerPhase"
    )
    val color = if (reducedMotion) fillMed else lerp(fillMed, fillSoft, phase)

    Box(modifier = modifier.clip(shape).background(color))
}

@Composable
fun FeedLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Section title
        ShimmerBox(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).size(160.dp, 22.dp), shape = RoundedCornerShape(6.dp))
        // Row of cards
        Row(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(3) {
                Column {
                    ShimmerBox(modifier = Modifier.size(160.dp), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(modifier = Modifier.size(120.dp, 12.dp), shape = RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(4.dp))
                    ShimmerBox(modifier = Modifier.size(90.dp, 10.dp), shape = RoundedCornerShape(4.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        // Row of songs
        repeat(4) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ShimmerBox(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    ShimmerBox(modifier = Modifier.size(140.dp, 12.dp), shape = RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(modifier = Modifier.size(100.dp, 10.dp), shape = RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

@Composable
fun AuricleStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onArtwork: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (onArtwork) Color.White.copy(alpha = 0.12f) else AuricleTheme.colors.fillMed),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = if (onArtwork) Color.White else AuricleTheme.colors.textPrimary)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = AuricleTheme.typography.headline, color = if (onArtwork) Color.White else AuricleTheme.colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = AuricleTheme.typography.sub,
            color = if (onArtwork) Color.White.copy(alpha = 0.7f) else AuricleTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp)
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (onArtwork) Color.White else AuricleTheme.colors.primaryFill)
                    .clickable { onActionClick() }
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    actionLabel,
                    style = AuricleTheme.typography.calloutSB.copy(fontSize = 14.sp),
                    color = if (onArtwork) Color.Black else AuricleTheme.colors.onPrimaryFill
                )
            }
        }
    }
}
