package com.auricle.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.auricle.app.ui.theme.AuricleTheme

enum class NavigationTab(val title: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library")
}

@Composable
fun AuricleBottomNav(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    AuricleGlassBox(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        fallbackColor = AuricleTheme.colors.tabBarGlass,
        borderColor = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(AuricleTheme.colors.border)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavigationTab.entries.forEach { tab ->
                val isActive = selectedTab == tab
                val icon = when (tab) {
                    NavigationTab.HOME -> AuricleIcons.Home
                    NavigationTab.SEARCH -> AuricleIcons.Search
                    NavigationTab.LIBRARY -> AuricleIcons.Library
                }
                val tint = if (isActive) AuricleTheme.colors.accentStrong else AuricleTheme.colors.textMuted

                Column(
                    modifier = Modifier
                        .semantics { contentDescription = tab.title }
                        .testTag(tab.title)
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabSelected(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, contentDescription = tab.title, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.title, style = AuricleTheme.typography.micro, color = tint)
                }
            }
        }
    }
}

@Composable
fun BottomChromeFade(modifier: Modifier = Modifier) {
    val bg = AuricleTheme.colors.bg
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(
                Brush.verticalGradient(
                    0f to bg.copy(alpha = 0f),
                    0.55f to bg.copy(alpha = 0.8f),
                    1f to bg
                )
            )
    )
}