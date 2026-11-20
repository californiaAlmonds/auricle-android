package com.auricle.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.BuildConfig
import com.auricle.app.data.SettingsRepository
import com.auricle.app.model.AccentColorPreset
import com.auricle.app.model.ThemeMode
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleBrandGradient
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun SettingsSheet(
    settingsRepo: SettingsRepository,
    availableUpdateVersion: String? = null,
    isUpdateReady: Boolean = false,
    isCheckingForUpdate: Boolean = false,
    updateStatusText: String? = null,
    onOpenUpdater: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsState()

    var cacheSizeBytes by remember { mutableStateOf(0L) }
    var isCalculatingCache by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSizeBytes = calculateCacheBytes(context)
            isCalculatingCache = false
        }
    }

    val cacheSizeDisplay = if (isCalculatingCache) {
        "Calculating…"
    } else {
        val mb = cacheSizeBytes / (1024.0 * 1024.0)
        String.format(Locale.US, "%.1f MB of temporary audio", mb)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.sheetBg)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = "Settings",
                style = AuricleTheme.typography.headline,
                color = AuricleTheme.colors.textPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .minimumInteractiveComponentSize()
                    .auriclePressClickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done",
                    style = AuricleTheme.typography.bodySB,
                    color = AuricleTheme.colors.accentStrong
                )
            }
        }

        // 1. APPEARANCE Group
        SettingsGroupCard(title = "APPEARANCE") {
            // Theme Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Moon, Color(0xFF8B5CF6))
                Text(
                    text = "Theme",
                    style = AuricleTheme.typography.bodyM,
                    color = AuricleTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                AuricleSegmentedControl(
                    options = listOf("System", "Dark", "Light"),
                    selectedIndex = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> 0
                        ThemeMode.DARK -> 1
                        ThemeMode.LIGHT -> 2
                    },
                    onSelect = { idx ->
                        val selected = listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT)[idx]
                        settingsRepo.updateThemeMode(selected)
                    },
                    modifier = Modifier.width(180.dp)
                )
            }

            SettingsDivider()

            // Accent Color
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsIconBadge(AuricleIcons.Palette, Color(0xFFE26AA0))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accent color",
                            style = AuricleTheme.typography.bodyM,
                            color = AuricleTheme.colors.textPrimary
                        )
                        Text(
                            text = "${settings.accentPreset.displayName} · matches Auricle desktop",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(AccentColorPreset.entries.toTypedArray()) { preset ->
                        val isSelected = settings.accentPreset == preset
                        val color = Color(preset.hex)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = preset.displayName }
                                .auriclePressClickable { settingsRepo.updateAccentPreset(preset) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .border(1.5.dp, AuricleTheme.colors.textPrimary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(1.dp, AuricleTheme.colors.borderSoft, CircleShape)
                                )
                            }
                        }
                    }
                }
            }

            SettingsDivider()

            // Dynamic Album Colors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Sparkles, Color(0xFFF2703A))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dynamic album colors",
                        style = AuricleTheme.typography.bodyM,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Tint the player and accents from artwork",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
                AuricleSwitch(
                    checked = settings.dynamicAlbumArtAccent,
                    onCheckedChange = { settingsRepo.updateDynamicAlbumArt(it) }
                )
            }
        }

        // 2. PLAYBACK Group
        SettingsGroupCard(title = "PLAYBACK") {
            // Autoplay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Infinity, Color(0xFF3FA96A))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Autoplay",
                        style = AuricleTheme.typography.bodyM,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Keep playing similar songs when the queue ends",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
                AuricleSwitch(
                    checked = settings.autoplay,
                    onCheckedChange = { settingsRepo.updateAutoplay(it) }
                )
            }

            SettingsDivider()

            // High Quality Audio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Volume, Color(0xFF6486B0))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "High quality audio",
                        style = AuricleTheme.typography.bodyM,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Prefer highest bitrate opus/AAC streams",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
                AuricleSwitch(
                    checked = settings.highQualityAudio,
                    onCheckedChange = { settingsRepo.updateHighQualityAudio(it) }
                )
            }
        }

        // 3. STORAGE Group
        SettingsGroupCard(title = "STORAGE") {
            // Stream Cache
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Database, Color(0xFF1699A8))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stream cache",
                        style = AuricleTheme.typography.bodyM,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = cacheSizeDisplay,
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AuricleTheme.colors.fillMed)
                        .auriclePressClickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { File(context.filesDir, "url_cache.json").delete() }
                                    runCatching {
                                        context.cacheDir.listFiles()?.forEach { file ->
                                            if (!file.name.endsWith(".apk") && file.name != "updates") {
                                                file.deleteRecursively()
                                            }
                                        }
                                    }
                                    cacheSizeBytes = calculateCacheBytes(context)
                                }
                                Toast.makeText(context, "Stream cache cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clear",
                        style = AuricleTheme.typography.subSB.copy(fontSize = 13.sp),
                        color = AuricleTheme.colors.textPrimary
                    )
                }
            }
        }

        // 4. UPDATES Group
        SettingsGroupCard(title = "UPDATES") {
            if (availableUpdateVersion != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AuricleBrandGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AuricleIcons.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auricle $availableUpdateVersion ${if (isUpdateReady) "ready" else "available"}",
                            style = AuricleTheme.typography.bodyM,
                            color = AuricleTheme.colors.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "You're on ${BuildConfig.VERSION_NAME}",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AuricleTheme.colors.primaryFill)
                            .auriclePressClickable(onClick = onOpenUpdater)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isUpdateReady) "Install" else "Update",
                            style = AuricleTheme.typography.subSB.copy(fontSize = 13.sp),
                            color = AuricleTheme.colors.onPrimaryFill
                        )
                    }
                }
                SettingsDivider()
            }

            // Check for updates
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .auriclePressClickable(onClick = onCheckForUpdates)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Update, Color(0xFF3FA96A))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Check for updates",
                        style = AuricleTheme.typography.bodyM,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = updateStatusText ?: "You're on v${BuildConfig.VERSION_NAME}",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
                if (isCheckingForUpdate) {
                    CircularProgressIndicator(
                        color = AuricleTheme.colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = AuricleIcons.ChevronRight,
                        contentDescription = null,
                        tint = AuricleTheme.colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            SettingsDivider()

            // Check for updates automatically
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Refresh, Color(0xFF46A6E6))
                Text(
                    text = "Check for updates automatically",
                    style = AuricleTheme.typography.bodyM,
                    color = AuricleTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                AuricleSwitch(
                    checked = settings.autoCheckUpdates,
                    onCheckedChange = { settingsRepo.updateAutoCheckUpdates(it) }
                )
            }
        }

        // 5. ABOUT Group
        SettingsGroupCard(title = "ABOUT") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .auriclePressClickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/californiaAlmonds/auricle-android"))
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(AuricleIcons.Github, Color(0xFF6B7280))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Source code",
                        style = AuricleTheme.typography.bodyM,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "github.com/californiaAlmonds/auricle-android",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }
                Icon(
                    imageVector = AuricleIcons.ChevronRight,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Footer
        Text(
            text = "Auricle Android v${BuildConfig.VERSION_NAME} · Native Core Engine",
            style = AuricleTheme.typography.caption,
            color = AuricleTheme.colors.textDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun SettingsIconBadge(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = AuricleTheme.colors.isDark
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = AuricleTheme.typography.overline,
            color = AuricleTheme.colors.textMuted,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDark) AuricleTheme.colors.surface2 else AuricleTheme.colors.surface)
                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp)),
            content = content
        )
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .height(1.dp)
            .background(AuricleTheme.colors.border)
    )
}

private fun calculateCacheBytes(context: Context): Long {
    var size = 0L
    runCatching {
        val urlCache = File(context.filesDir, "url_cache.json")
        if (urlCache.exists()) size += urlCache.length()
        context.cacheDir.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
    }
    return size
}
