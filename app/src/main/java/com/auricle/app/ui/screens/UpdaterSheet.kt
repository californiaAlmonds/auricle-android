package com.auricle.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.updater.UpdateState
import com.auricle.app.updater.ReleaseUpdateInfo
import com.auricle.app.ui.components.AuriclePrimaryButton
import com.auricle.app.ui.components.AuricleSecondaryButton
import com.auricle.app.ui.components.AuricleIcons
import com.auricle.app.ui.theme.AuricleTheme

fun formatMb(bytes: Long): String = String.format(java.util.Locale.US, "%.2f MB", bytes / (1024f * 1024f))

@Composable
fun UpdaterSheet(
    updateState: UpdateState,
    onDownloadClick: () -> Unit,
    onCancelDownloadClick: () -> Unit,
    onInstallClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val info = when (updateState) {
        is UpdateState.Available -> updateState.info
        is UpdateState.Downloading -> updateState.info
        is UpdateState.Ready -> updateState.info
        is UpdateState.Error -> updateState.info ?: return
        else -> return
    }

    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.sheetBg)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2FD3A0), Color(0xFF1E8BF0)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AuricleIcons.Sparkles,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val overlineText = when (updateState) {
            is UpdateState.Available -> "UPDATE AVAILABLE"
            is UpdateState.Downloading -> "DOWNLOADING"
            is UpdateState.Ready -> "READY TO INSTALL"
            is UpdateState.Error -> "UPDATE FAILED"
            else -> ""
        }
        val overlineColor = when (updateState) {
            is UpdateState.Available, is UpdateState.Downloading -> AuricleTheme.colors.accentStrong
            is UpdateState.Ready -> AuricleTheme.colors.success
            is UpdateState.Error -> AuricleTheme.colors.danger
            else -> AuricleTheme.colors.textMuted
        }

        Text(
            text = overlineText,
            style = AuricleTheme.typography.overline,
            color = overlineColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Auricle ${info.version}",
            style = AuricleTheme.typography.title1,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = AuricleTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "You're on ${info.currentVersion} · ${formatMb(info.sizeBytes)}",
            style = AuricleTheme.typography.sub,
            fontSize = 13.sp,
            color = AuricleTheme.colors.textMuted
        )

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AuricleTheme.colors.surface2)
                .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "What's new",
                style = AuricleTheme.typography.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = AuricleTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            info.releaseNotesBullets.take(4).forEach { bullet ->
                Row(modifier = Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = AuricleIcons.Check,
                        contentDescription = null,
                        tint = AuricleTheme.colors.success,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = bullet,
                        color = AuricleTheme.colors.textPrimary,
                        fontSize = 13.sp,
                        style = AuricleTheme.typography.sub
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AuricleTheme.colors.fillSoft)
                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailColumn("Size", formatMb(info.sizeBytes), Modifier.weight(1f))
            DetailColumn("Released", info.publishedDateDisplay, Modifier.weight(1f))
            
            val isVerifiedReady = updateState is UpdateState.Ready && updateState.sha256Verified
            val sigText = if (isVerifiedReady) "Verified" else "Auricle"
            val sigColor = if (isVerifiedReady) AuricleTheme.colors.success else AuricleTheme.colors.textPrimary
            DetailColumn("Signature", sigText, Modifier.weight(1f), sigColor)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .clickable {
                    val safeUrl = info.htmlUrl.trim()
                    if (safeUrl.startsWith("https://github.com/")) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                        }
                    }
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = AuricleIcons.Github,
                contentDescription = null,
                tint = AuricleTheme.colors.accentStrong,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Full release notes on GitHub",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AuricleTheme.colors.accentStrong
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        when (updateState) {
            is UpdateState.Available -> {
                AuriclePrimaryButton(
                    text = "Download & install",
                    icon = AuricleIcons.Download,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDownloadClick
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Not now",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuricleTheme.colors.textMuted,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
            }
            is UpdateState.Downloading -> {
                val downloaded = updateState.downloadedBytes
                val total = updateState.totalBytes
                val etaSecs = updateState.etaSeconds
                val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                val etaText = if (etaSecs != null) " · ${etaSecs}s left" else ""
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Downloading…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AuricleTheme.colors.textPrimary)
                    Text("$percent%", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AuricleTheme.colors.textMuted)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AuricleTheme.colors.fillMed)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(if (total > 0) downloaded.toFloat() / total.toFloat() else 0f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF2FD3A0), Color(0xFF1E8BF0))))
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${formatMb(downloaded)} of ${formatMb(total)}$etaText",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AuricleTheme.colors.textMuted
                )
                Spacer(modifier = Modifier.height(16.dp))
                AuricleSecondaryButton(
                    text = "Cancel",
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelDownloadClick
                )
            }
            is UpdateState.Ready -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = AuricleIcons.Check,
                        contentDescription = null,
                        tint = AuricleTheme.colors.success,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Downloaded and verified",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuricleTheme.colors.success
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                AuriclePrimaryButton(
                    text = "Install now",
                    icon = AuricleIcons.Download,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onInstallClick
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Later",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuricleTheme.colors.textMuted,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
            }
            is UpdateState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AuricleTheme.colors.danger.copy(alpha = 0.1f))
                        .border(1.dp, AuricleTheme.colors.danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = updateState.message,
                        color = AuricleTheme.colors.danger,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                AuriclePrimaryButton(
                    text = "Try again",
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDownloadClick
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Close",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuricleTheme.colors.textMuted,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Android will ask you to confirm the install. Your library and settings are kept.",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = AuricleTheme.colors.textDim,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DetailColumn(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = AuricleTheme.colors.textPrimary) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = AuricleTheme.typography.caption, color = AuricleTheme.colors.textMuted, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = AuricleTheme.typography.subSB, color = valueColor, fontSize = 13.sp)
    }
}
