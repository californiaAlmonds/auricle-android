package com.auricle.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.data.SettingsRepository
import com.auricle.app.model.AccentColorPreset
import com.auricle.app.model.ThemeMode
import com.auricle.app.ui.components.GlassCard
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsSheet(
    settingsRepo: SettingsRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.surface)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AuricleTheme.colors.textPrimary
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close", tint = AuricleTheme.colors.textMuted)
            }
        }

        // Appearance Theme Mode
        Text(
            text = "Theme Mode",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AuricleTheme.colors.textPrimary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ThemeMode.values().forEach { mode ->
                val isSelected = settings.themeMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AuricleTheme.colors.accent else AuricleTheme.colors.surface2)
                        .clickable { settingsRepo.updateThemeMode(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else AuricleTheme.colors.textPrimary
                    )
                }
            }
        }

        // Accent Color Swatches
        Text(
            text = "Accent Color",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = AuricleTheme.colors.textPrimary
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(AccentColorPreset.values()) { preset ->
                val isSelected = settings.accentPreset == preset
                val color = Color(preset.hex)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else AuricleTheme.colors.border,
                            shape = CircleShape
                        )
                        .clickable { settingsRepo.updateAccentPreset(preset) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Dynamic Album Art Accent Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AuricleTheme.colors.surface2)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Dynamic Album Art Accent", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AuricleTheme.colors.textPrimary)
                Text(text = "Extract theme accent from playing album cover", fontSize = 12.sp, color = AuricleTheme.colors.textMuted)
            }
            Switch(
                checked = settings.dynamicAlbumArtAccent,
                onCheckedChange = { settingsRepo.updateDynamicAlbumArt(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AuricleTheme.colors.accent
                )
            )
        }

        // Clear Cache Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AuricleTheme.colors.surface2)
                .clickable {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { File(context.filesDir, "url_cache.json").delete() }
                            runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        }
                        Toast.makeText(context, "Stream cache cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(imageVector = Icons.Rounded.CleaningServices, contentDescription = null, tint = AuricleTheme.colors.accent)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Clear Stream Cache", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AuricleTheme.colors.textPrimary)
                Text(text = "Free temporary audio chunk storage", fontSize = 12.sp, color = AuricleTheme.colors.textMuted)
            }
        }

        // About Auricle
        Text(
            text = "Auricle Android v0.2.0 • Native Core Engine",
            fontSize = 12.sp,
            color = AuricleTheme.colors.textDim,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
