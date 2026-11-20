package com.auricle.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.model.PartySnapshot
import com.auricle.app.ui.theme.AuricleTheme
import com.auricle.app.ui.theme.AuricleBrandGradient

@Composable
fun AuricleTopBar(
    partySnapshot: PartySnapshot,
    onPartyClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App Logo & Brand Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AuricleBrandGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.Sparkles,
                    contentDescription = "Auricle Logo",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = "Auricle",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = AuricleTheme.colors.textPrimary,
                letterSpacing = (-0.5).sp
            )
        }

        // Actions: Party Pill + Settings Gear
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PartyPill(
                partySnapshot = partySnapshot,
                onClick = onPartyClick
            )

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .semantics { contentDescription = "Settings" }
                    .testTag("Settings")
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AuricleTheme.colors.fillSoft)
            ) {
                Icon(
                    imageVector = AuricleIcons.Settings,
                    contentDescription = "Settings",
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AuricleCollapsibleHeader(
    title: String,
    collapsedProgress: Float,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            style = AuricleTheme.typography.large,
            color = AuricleTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
fun AuricleCollapsedTopBar(
    title: String,
    visible: Boolean,
    actions: @Composable RowScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .auricleGlass(
                    shape = RoundedCornerShape(0.dp),
                    fallbackColor = AuricleTheme.colors.tabBarGlass,
                    borderColor = Color.Transparent
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = AuricleTheme.typography.headline,
                    color = AuricleTheme.colors.textPrimary,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AuricleTheme.colors.border)
            )
        }
    }
}
