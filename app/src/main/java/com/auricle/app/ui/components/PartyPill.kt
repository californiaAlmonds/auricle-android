package com.auricle.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.model.PartySnapshot
import com.auricle.app.ui.theme.AuricleTheme

@Composable
fun PartyPill(
    partySnapshot: PartySnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = partySnapshot.isActive
    val memberCount = if (partySnapshot.participants.isNotEmpty()) partySnapshot.participants.size else partySnapshot.participantCount.coerceAtLeast(1)

    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) AuricleTheme.colors.surface2 else AuricleTheme.colors.surface,
        label = "partyPillBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) AuricleTheme.colors.success.copy(alpha = 0.7f) else AuricleTheme.colors.border,
        label = "partyPillBorder"
    )

    val labelText = if (isActive) "Party · $memberCount" else "Party"

    Box(
        modifier = modifier
            .semantics { contentDescription = "Party Pill: $labelText" }
            .testTag("Party")
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isActive) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )
                // Glowing pulsating green dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AuricleTheme.colors.success.copy(alpha = pulseAlpha))
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = AuricleTheme.colors.accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = labelText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) AuricleTheme.colors.success else AuricleTheme.colors.textPrimary
            )
        }
    }
}
