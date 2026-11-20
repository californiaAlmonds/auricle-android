package com.auricle.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auricle.app.data.SettingsRepository
import com.auricle.app.playback.PlaybackController
import com.auricle.app.ui.components.AccentPillButton
import com.auricle.app.ui.components.GlassCard
import com.auricle.app.ui.theme.AuricleTheme
import kotlinx.coroutines.launch

@Composable
fun PartySheet(
    playbackController: PlaybackController,
    settingsRepo: SettingsRepository,
    initialInviteLink: String = "",
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val partySnapshot by playbackController.partySnapshot.collectAsState()
    val appSettings by settingsRepo.settings.collectAsState()

    var displayName by remember { mutableStateOf(appSettings.userDisplayName) }
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

    val displayError = localErrorText ?: (if (status == "error" && inviteInput.isBlank()) partySnapshot.errorText.ifBlank { "Failed to connect to party" } else if (status == "error") partySnapshot.errorText.ifBlank { null } else null)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuricleTheme.colors.surface)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Sheet Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isActive) AuricleTheme.colors.success.copy(alpha = 0.2f) else AuricleTheme.colors.surface2),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = if (isActive) AuricleTheme.colors.success else AuricleTheme.colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = when {
                        isActive -> "Party Session"
                        isConnecting -> if (status == "starting") "Starting Party..." else "Joining Party..."
                        else -> "Auricle Party"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuricleTheme.colors.textPrimary
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close", tint = AuricleTheme.colors.textMuted)
            }
        }

        // Display Name input (editable when not active)
        if (!isActive && !isConnecting) {
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    settingsRepo.updateDisplayName(it)
                },
                label = { Text("Your Display Name", color = AuricleTheme.colors.textMuted, fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AuricleTheme.colors.accent,
                    unfocusedBorderColor = AuricleTheme.colors.border,
                    focusedTextColor = AuricleTheme.colors.textPrimary,
                    unfocusedTextColor = AuricleTheme.colors.textPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!displayError.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AuricleTheme.colors.danger.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AuricleTheme.colors.danger.copy(alpha = 0.3f))
            ) {
                Text(
                    text = displayError,
                    color = AuricleTheme.colors.danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
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
                    text = if (status == "starting") "Establishing P2P relay room..." else "Connecting to Party host...",
                    fontSize = 14.sp,
                    color = AuricleTheme.colors.textMuted
                )
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            playbackController.leaveParty()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AuricleTheme.colors.danger),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AuricleTheme.colors.danger.copy(alpha = 0.5f))
                ) {
                    Text("Cancel")
                }
            }
        } else if (!isActive) {
            // Idle State: Host or Join
            // 1. Host Card
            GlassCard(
                backgroundColor = AuricleTheme.colors.surface2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isOperating = true
                        localErrorText = null
                        coroutineScope.launch {
                            val res = playbackController.startParty(displayName.ifBlank { "Android User" })
                            res.onFailure { localErrorText = it.message }
                            isOperating = false
                        }
                    }
                    .testTag("Host a Party")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(AuricleTheme.colors.accent, Color(0xFF8B5CF6))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Rounded.AddReaction, contentDescription = null, tint = Color.White)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Host a Party",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuricleTheme.colors.textPrimary
                        )
                        Text(
                            text = "Start a synchronized listening room and invite friends",
                            fontSize = 12.sp,
                            color = AuricleTheme.colors.textMuted
                        )
                    }

                    Icon(imageVector = Icons.Rounded.ChevronRight, contentDescription = null, tint = AuricleTheme.colors.textMuted)
                }
            }

            // 2. Join Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Join with Link or Code",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuricleTheme.colors.textPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = { inviteInput = it },
                        placeholder = { Text("Paste invite URL or code", color = AuricleTheme.colors.textDim, fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AuricleTheme.colors.accent,
                            unfocusedBorderColor = AuricleTheme.colors.border,
                            focusedTextColor = AuricleTheme.colors.textPrimary,
                            unfocusedTextColor = AuricleTheme.colors.textPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("InviteCodeInput")
                    )

                    Button(
                        onClick = {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipMgr.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).text?.toString() ?: ""
                                if (text.isNotBlank()) {
                                    inviteInput = text
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AuricleTheme.colors.surface2),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = AuricleTheme.colors.accent, modifier = Modifier.size(16.dp))
                    }
                }

                AccentPillButton(
                    onClick = {
                        if (inviteInput.isNotBlank()) {
                            isOperating = true
                            localErrorText = null
                            coroutineScope.launch {
                                val res = playbackController.joinParty(inviteInput.trim(), displayName.ifBlank { "Android User" })
                                res.onFailure { localErrorText = it.message }
                                isOperating = false
                            }
                        }
                    },
                    isPrimary = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Join Party" }
                        .testTag("Join Party")
                ) {
                    if (isOperating) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Join Party", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Active Party State
            // Status Pill + RTT
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AuricleTheme.colors.success.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AuricleTheme.colors.success.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AuricleTheme.colors.success))
                        Text(
                            text = if (partySnapshot.isHost) "Hosting Party" else "Joined ${partySnapshot.hostName}'s Party",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AuricleTheme.colors.success
                        )
                    }
                }

                if (partySnapshot.rttMs > 0) {
                    Text(
                        text = "Synced • ${partySnapshot.rttMs} ms",
                        fontSize = 12.sp,
                        color = AuricleTheme.colors.textDim
                    )
                }
            }

            // Last Action Activity Banner
            if (partySnapshot.lastActionDesc.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AuricleTheme.colors.surface2
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.NotificationsActive, contentDescription = null, tint = AuricleTheme.colors.accent, modifier = Modifier.size(16.dp))
                        Text(
                            text = partySnapshot.lastActionDesc,
                            fontSize = 12.sp,
                            color = AuricleTheme.colors.textPrimary
                        )
                    }
                }
            }

            // Share & Copy Link Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val link = partySnapshot.inviteLink.ifBlank { partySnapshot.partyCode }

                AccentPillButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Join my Auricle Music Party!")
                            putExtra(Intent.EXTRA_TEXT, "Listen along with me in Auricle: $link")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Invite to Party"))
                    },
                    isPrimary = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("Share Link")
                ) {
                    Icon(imageVector = Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Link")
                }

                AccentPillButton(
                    onClick = {
                        val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Auricle Party Link", link)
                        clipMgr.setPrimaryClip(clip)
                        Toast.makeText(context, "Party invite copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    isPrimary = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("Copy Link")
                ) {
                    Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Link")
                }
            }

            // Listeners List
            val members = partySnapshot.participants
            Text(
                text = "Listeners (${if (members.isNotEmpty()) members.size else partySnapshot.participantCount})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AuricleTheme.colors.textPrimary
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                members.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AuricleTheme.colors.surface2)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AuricleTheme.colors.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = member.name.take(1).uppercase().ifBlank { "U" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = member.name.ifBlank { "User" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AuricleTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )

                        if (member.isHost) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AuricleTheme.colors.accent.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Host",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuricleTheme.colors.accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AuricleTheme.colors.surface
                        ) {
                            Text(
                                text = member.platform.replaceFirstChar { it.uppercase() },
                                fontSize = 10.sp,
                                color = AuricleTheme.colors.textDim,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Leave Party Button
            Button(
                onClick = {
                    coroutineScope.launch {
                        playbackController.leaveParty()
                    }
                },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuricleTheme.colors.danger.copy(alpha = 0.2f), contentColor = AuricleTheme.colors.danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Leave Party" }
                    .testTag("Leave Party")
            ) {
                Icon(imageVector = Icons.Rounded.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = if (partySnapshot.isHost) "End Party" else "Leave Party", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
