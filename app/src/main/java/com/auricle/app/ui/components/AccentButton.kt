package com.auricle.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun AccentPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (isPrimary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AuricleTheme.colors.accent,
                contentColor = AuricleTheme.colors.onAccent
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = AuricleTheme.colors.surface.copy(alpha = 0.6f),
                contentColor = AuricleTheme.colors.textPrimary
            ),
            border = BorderStroke(1.dp, AuricleTheme.colors.border),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content
        )
    }
}

@Composable
fun AuriclePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    cornerRadius: Dp = 14.dp,
    enabled: Boolean = true
) {
    val style = if (height >= 48.dp) AuricleTheme.typography.headline else AuricleTheme.typography.calloutSB
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(AuricleTheme.colors.primaryFill)
            .auriclePressClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AuricleTheme.colors.onPrimaryFill, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = AuricleTheme.colors.onPrimaryFill, style = style)
    }
}

@Composable
fun AuricleSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    cornerRadius: Dp = 14.dp,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(AuricleTheme.colors.fillMed)
            .auriclePressClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AuricleTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = AuricleTheme.colors.textPrimary, style = AuricleTheme.typography.calloutSB)
    }
}

@Composable
fun AuricleOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    cornerRadius: Dp = 14.dp,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .border(1.dp, AuricleTheme.colors.border, shape)
            .auriclePressClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AuricleTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = AuricleTheme.colors.textPrimary, style = AuricleTheme.typography.calloutSB)
    }
}

@Composable
fun AuricleDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 52.dp,
    cornerRadius: Dp = 14.dp,
    enabled: Boolean = true
) {
    val alpha = if (AuricleTheme.colors.isDark) 0.12f else 0.10f
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(AuricleTheme.colors.danger.copy(alpha = alpha))
            .auriclePressClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AuricleTheme.colors.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = AuricleTheme.colors.danger, style = AuricleTheme.typography.headline)
    }
}

@Composable
fun AuricleCircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    containerColor: Color = AuricleTheme.colors.fillMed,
    tint: Color = AuricleTheme.colors.textPrimary,
    haptic: Boolean = false
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .auriclePressClickable(
                onClick = onClick,
                hapticType = if (haptic) HapticFeedbackType.TextHandleMove else null
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize), tint = tint)
    }
}

@Composable
fun AuricleFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) AuricleTheme.colors.primaryFill else AuricleTheme.colors.fillSoft
    val textC = if (selected) AuricleTheme.colors.onPrimaryFill else AuricleTheme.colors.textPrimary
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(bg)
            .then(if (!selected) Modifier.border(1.dp, AuricleTheme.colors.border, shape) else Modifier)
            .auriclePressClickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(label, color = textC, style = AuricleTheme.typography.subSB)
    }
}

@Composable
fun AuricleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = if (checked) AuricleTheme.colors.accent else AuricleTheme.colors.fillMed
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = if (AuricleTheme.reducedMotion) androidx.compose.animation.core.snap() else tween(150)
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(50.dp, 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, bottom = 2.dp)
                .offset(x = knobOffset)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun AuricleSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false
) {
    val bg = if (onArtwork) Color.White.copy(alpha = 0.12f) else AuricleTheme.colors.fillMed
    BoxWithConstraints(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(2.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = if (AuricleTheme.reducedMotion) androidx.compose.animation.core.snap() else tween(200)
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(if (onArtwork) Color.White.copy(alpha = 0.25f) else AuricleTheme.colors.segmentedSelected)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, text ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (onArtwork) Color.White else {
                        if (isSelected) AuricleTheme.colors.textPrimary else AuricleTheme.colors.textMuted
                    }
                    val style = if (isSelected) AuricleTheme.typography.captionSB else AuricleTheme.typography.caption
                    Text(text, color = color, style = style)
                }
            }
        }
    }
}

@Composable
fun AuricleSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    glyph: ImageVector? = null,
    showChevron: Boolean = true,
    actionText: String? = null,
    onHeaderClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onHeaderClick != null) Modifier.clickable { onHeaderClick() } else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = AuricleTheme.typography.title2, color = AuricleTheme.colors.textPrimary)
        if (glyph != null) {
            Spacer(Modifier.width(8.dp))
            Icon(glyph, contentDescription = null, tint = AuricleTheme.colors.textMuted, modifier = Modifier.size(18.dp))
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(AuricleIcons.ChevronRight, contentDescription = null, tint = AuricleTheme.colors.textDim, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        if (actionText != null && onActionClick != null) {
            Text(
                text = actionText,
                style = AuricleTheme.typography.subSB,
                color = AuricleTheme.colors.accentStrong,
                modifier = Modifier
                    .clickable { onActionClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
