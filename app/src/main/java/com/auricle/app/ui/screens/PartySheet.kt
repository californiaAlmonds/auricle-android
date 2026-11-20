package com.auricle.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auricle.app.data.SettingsRepository
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.*
import com.auricle.app.ui.theme.AuricleBrandGradient
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PartySheet(
    playbackController: PlaybackController,
    settingsRepo: SettingsRepository,
    initialInviteLink: String = "",
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val partySnapshot by playbackController.partySnapshot.collectAsState()
    val appSettings by settingsRepo.settings.collectAsState()
    val currentTrack by playbackController.currentTrack.collectAsState()
    val currentPosition by playbackController.currentPosition.collectAsState()
    val isPlaying by playbackController.isPlaying.collectAsState()

    var displayName by remember(appSettings.userDisplayName) { mutableStateOf(appSettings.userDisplayName) }
    var inviteInput by remember { mutableStateOf(initialInviteLink) }
    var isOperating by remember { mutableStateOf(false) }
    var localErrorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialInviteLink) {
        if (initialInviteLink.isNotBlank()) {
            inviteInput = initialInviteLink
            localErrorText = null
        }
    }

    val isActive = partySnapshot.isActive
    val status = partySnapshot.status // "off" | "starting" | "hosting" | "joining" | "joined" | "error"
    val isConnecting = status == "starting" || status == "joining"
    val memberCount = if (partySnapshot.participants.isNotEmpty()) partySnapshot.participants.size else partySnapshot.participantCount.coerceAtLeast(1)

    val displayError = localErrorText ?: (if (status == "error") partySnapshot.errorText.ifBlank { "Failed to connect to party" } else null)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.sheetBg)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AuricleBrandGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AuricleIcons.Radio,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = "Listening Party",
                    style = AuricleTheme.typography.title2,
                    color = AuricleTheme.colors.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                when {
                    isConnecting -> {
                        Text(
                            text = "Establishing encrypted P2P session…",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                    isActive -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AuricleTheme.colors.success)
                            )
                            Text(
                                text = "${if (partySnapshot.isHost) "Hosting" else "Joined"} · $memberCount ${if (memberCount == 1) "listener" else "listeners"}",
                                style = AuricleTheme.typography.subSB,
                                color = AuricleTheme.colors.success
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "Listen in sync with friends, peer-to-peer",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted
                        )
                    }
                }
            }

            AuricleCircleIconButton(
                icon = AuricleIcons.Close,
                contentDescription = "Close",
                onClick = onDismiss,
                size = 32.dp,
                iconSize = 16.dp,
                containerColor = AuricleTheme.colors.fillSoft,
                tint = AuricleTheme.colors.textMuted
            )
        }

        // Error Banner
        if (!displayError.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AuricleTheme.colors.danger.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, AuricleTheme.colors.danger.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = displayError,
                    color = AuricleTheme.colors.danger,
                    style = AuricleTheme.typography.sub,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        if (isConnecting) {
            // Connecting Spinner State
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = AuricleTheme.colors.accent,
                    strokeWidth = 3.dp
                )
                Text(
                    text = if (status == "starting") "Establishing encrypted P2P session…" else "Connecting to Party host…",
                    style = AuricleTheme.typography.sub,
                    color = AuricleTheme.colors.textMuted
                )
                AuricleDestructiveButton(
                    text = "Cancel",
                    height = 44.dp,
                    cornerRadius = 12.dp,
                    onClick = {
                        scope.launch {
                            playbackController.leaveParty()
                        }
                    }
                )
            }
        } else if (!isActive) {
            // Idle State: Host or Join

            // 1. Display Name Input
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "YOUR DISPLAY NAME",
                    style = AuricleTheme.typography.overline,
                    color = AuricleTheme.colors.textMuted
                )
                BasicTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        settingsRepo.updateDisplayName(it)
                    },
                    singleLine = true,
                    textStyle = AuricleTheme.typography.body.copy(color = AuricleTheme.colors.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(AuricleTheme.colors.accent),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AuricleTheme.colors.fillMed)
                                .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                if (displayName.isEmpty()) {
                                    Text(
                                        text = "Enter your display name",
                                        style = AuricleTheme.typography.body.copy(fontSize = 15.sp),
                                        color = AuricleTheme.colors.textDim
                                    )
                                }
                                innerTextField()
                            }
                            Icon(
                                imageVector = AuricleIcons.Edit,
                                contentDescription = null,
                                tint = AuricleTheme.colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 2. Host a Party Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AuricleTheme.colors.surface2)
                    .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(16.dp))
                    .auriclePressClickable {
                        if (!isOperating) {
                            isOperating = true
                            localErrorText = null
                            scope.launch {
                                val res = playbackController.startParty(displayName.ifBlank { "Android User" })
                                res.onFailure { localErrorText = it.message }
                                isOperating = false
                            }
                        }
                    }
                    .testTag("Host a Party")
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AuricleTheme.colors.accentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AuricleIcons.Radio,
                        contentDescription = null,
                        tint = AuricleTheme.colors.accentStrong,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Host a Party",
                        style = AuricleTheme.typography.headline,
                        color = AuricleTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Start a synchronized room and invite friends",
                        style = AuricleTheme.typography.sub,
                        color = AuricleTheme.colors.textMuted
                    )
                }

                Icon(
                    imageVector = AuricleIcons.ChevronRight,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 3. Divider Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(AuricleTheme.colors.border))
                Text(
                    text = "or join one",
                    style = AuricleTheme.typography.caption,
                    color = AuricleTheme.colors.textDim
                )
                Box(modifier = Modifier.weight(1f).height(1.dp).background(AuricleTheme.colors.border))
            }

            // 4. Join Field
            BasicTextField(
                value = inviteInput,
                onValueChange = {
                    inviteInput = it
                    localErrorText = null
                },
                singleLine = true,
                textStyle = AuricleTheme.typography.body.copy(color = AuricleTheme.colors.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(AuricleTheme.colors.accent),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AuricleTheme.colors.fillMed)
                            .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = AuricleIcons.Link,
                            contentDescription = null,
                            tint = AuricleTheme.colors.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (inviteInput.isEmpty()) {
                                Text(
                                    text = "Paste invite link or code",
                                    style = AuricleTheme.typography.body.copy(fontSize = 14.sp),
                                    color = AuricleTheme.colors.textDim
                                )
                            }
                            innerTextField()
                        }
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuricleTheme.colors.accentSoft)
                                .auriclePressClickable {
                                    val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipMgr.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString() ?: ""
                                        if (text.isNotBlank()) {
                                            inviteInput = text.trim()
                                            localErrorText = null
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Paste",
                                style = AuricleTheme.typography.subSB.copy(fontSize = 13.sp),
                                color = AuricleTheme.colors.accentStrong
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("InviteCodeInput")
            )

            // 5. Join Party Button
            AuriclePrimaryButton(
                text = if (isOperating) "Joining…" else "Join Party",
                height = 52.dp,
                cornerRadius = 14.dp,
                enabled = inviteInput.isNotBlank() && !isOperating,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("Join Party"),
                onClick = {
                    if (inviteInput.isNotBlank() && !isOperating) {
                        isOperating = true
                        localErrorText = null
                        scope.launch {
                            val res = playbackController.joinParty(inviteInput.trim(), displayName.ifBlank { "Android User" })
                            res.onFailure { localErrorText = it.message }
                            isOperating = false
                        }
                    }
                }
            )

            // 6. Footnote
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = AuricleIcons.Info,
                    contentDescription = null,
                    tint = AuricleTheme.colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Parties sync over an encrypted peer-to-peer link and work with Auricle on Windows too.",
                    style = AuricleTheme.typography.caption,
                    color = AuricleTheme.colors.textMuted
                )
            }
        } else {
            // Active Party State — Hosting or Joined

            // 1. Now Playing Sync Card
            if (currentTrack != null) {
                val track = currentTrack!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AuricleTheme.colors.surface2)
                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(track.displayArtworkUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AuricleTheme.colors.surface)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = AuricleTheme.typography.bodySB,
                            color = AuricleTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        val posStr = formatPartyPosition(currentPosition)
                        Text(
                            text = "Shared queue · synced at $posStr",
                            style = AuricleTheme.typography.sub,
                            color = AuricleTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    PlayingBarsIndicator(
                        color = AuricleTheme.colors.accentStrong,
                        isAnimating = isPlaying,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 2. Invite Friends Card
            val link = partySnapshot.inviteLink.ifBlank { partySnapshot.partyCode }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AuricleTheme.colors.surface2)
                    .border(1.dp, AuricleTheme.colors.border, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Invite friends",
                    style = AuricleTheme.typography.headline,
                    color = AuricleTheme.colors.textPrimary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AuricleTheme.colors.fillMed)
                        .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = AuricleIcons.Link,
                        contentDescription = null,
                        tint = AuricleTheme.colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = link,
                        style = AuricleTheme.typography.body.copy(fontSize = 15.sp),
                        color = AuricleTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = AuricleIcons.Qr,
                        contentDescription = null,
                        tint = AuricleTheme.colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AuricleSecondaryButton(
                        text = "Copy Link",
                        icon = AuricleIcons.Copy,
                        height = 44.dp,
                        cornerRadius = 12.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("Copy Link"),
                        onClick = {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Auricle Party Link", link)
                            clipMgr.setPrimaryClip(clip)
                            Toast.makeText(context, "Party invite copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )

                    AuriclePrimaryButton(
                        text = "Share Link",
                        icon = AuricleIcons.Share,
                        height = 44.dp,
                        cornerRadius = 12.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("Share Link"),
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Join my Auricle Music Party!")
                                putExtra(Intent.EXTRA_TEXT, "Listen along with me in Auricle: $link")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Invite to Party"))
                        }
                    )
                }
            }

            // 3. Members List ("In this party")
            val deterministicColors = listOf(
                Color(0xFF6486B0),
                Color(0xFF3FA96A),
                Color(0xFF8B5CF6),
                Color(0xFFF2703A),
                Color(0xFFE26AA0),
                Color(0xFF1699A8)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "In this party",
                    style = AuricleTheme.typography.headline,
                    color = AuricleTheme.colors.textPrimary
                )
                Text(
                    text = "$memberCount ${if (memberCount == 1) "listener" else "listeners"}",
                    style = AuricleTheme.typography.sub,
                    color = AuricleTheme.colors.textMuted
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                partySnapshot.participants.forEachIndexed { index, member ->
                    val isLocalUser = (partySnapshot.isHost && member.isHost) || (!partySnapshot.isHost && !member.isHost && member.name == partySnapshot.localName)
                    val avatarColor = deterministicColors[(member.name.hashCode().let { if (it < 0) -it else it } + index) % deterministicColors.size]
                    val titleText = buildString {
                        append(member.name.ifBlank { "Listener" })
                        if (isLocalUser) {
                            append(" (you)")
                        } else if (member.platform.isNotBlank()) {
                            append(" · ${member.platform.replaceFirstChar { it.uppercase() }}")
                        }
                    }
                    val (dotColor, subtitleText) = when {
                        member.isHost -> Pair(AuricleTheme.colors.accentStrong, "Controls playback")
                        isLocalUser -> when {
                            partySnapshot.rttMs in 1..180 -> Pair(AuricleTheme.colors.success, "In sync · ${partySnapshot.rttMs} ms")
                            partySnapshot.rttMs > 180 -> Pair(AuricleTheme.colors.warning, "Catching up · ${partySnapshot.rttMs} ms")
                            else -> Pair(AuricleTheme.colors.success, "In sync")
                        }
                        else -> Pair(AuricleTheme.colors.success, "Connected")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuricleTheme.colors.surface2)
                            .border(1.dp, AuricleTheme.colors.borderSoft, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(avatarColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = member.name.take(1).uppercase().ifBlank { "U" },
                                style = AuricleTheme.typography.bodySB,
                                color = Color.White
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = titleText,
                                style = AuricleTheme.typography.bodyM,
                                color = AuricleTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Text(
                                    text = subtitleText,
                                    style = AuricleTheme.typography.caption,
                                    color = AuricleTheme.colors.textMuted
                                )
                            }
                        }

                        if (member.isHost) {
                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 24.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AuricleTheme.colors.accentSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Host",
                                    style = AuricleTheme.typography.captionSB.copy(fontSize = 12.sp),
                                    color = AuricleTheme.colors.accentStrong
                                )
                            }
                        }
                    }
                }
            }

            // 4. End / Leave Party Button
            AuricleDestructiveButton(
                text = if (partySnapshot.isHost) "End Party" else "Leave Party",
                icon = AuricleIcons.Logout,
                height = 52.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("Leave Party"),
                onClick = {
                    scope.launch {
                        playbackController.leaveParty()
                    }
                }
            )

            // 5. Footer note
            Text(
                text = "Play, pause, seek and queue changes sync for everyone. Volume stays local.",
                style = AuricleTheme.typography.caption,
                color = AuricleTheme.colors.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

private fun formatPartyPosition(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
