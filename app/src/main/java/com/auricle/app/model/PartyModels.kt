package com.auricle.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class PartyParticipant(
    @SerialName("peer_id") val peerId: String = "",
    val name: String = "",
    val platform: String = "android",
    @SerialName("is_host") val isHost: Boolean = false
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PartySnapshot(
    val status: String = "off", // "off" | "starting" | "hosting" | "joining" | "joined" | "error"
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("party_id") val partyId: String = "",
    @SerialName("party_code") val partyCode: String = "",
    @SerialName("invite_link") @JsonNames("invite_link", "invite_url") val inviteLink: String = "",
    @SerialName("host_name") val hostName: String = "",
    @SerialName("local_name") val localName: String = "",
    @SerialName("participant_count") val participantCount: Int = 0,
    val participants: List<PartyParticipant> = emptyList(),
    @SerialName("rtt_ms") val rttMs: Long = 0L,
    @SerialName("last_action_by") val lastActionBy: String = "",
    @SerialName("last_action_desc") val lastActionDesc: String = "",
    @SerialName("error_text") val errorText: String = ""
)

@Serializable
data class PartySeedState(
    val queue: List<SongItem> = emptyList(),
    @SerialName("current_index") val currentIndex: Int = 0,
    @SerialName("current_track") val currentTrack: SongItem? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("repeat_mode") val repeatMode: String = "off"
)

@Serializable
data class RemotePartyState(
    val seq: Long = 0L,
    @SerialName("party_id") val partyId: String = "",
    @SerialName("host_name") val hostName: String = "",
    val participants: List<PartyParticipant> = emptyList(),
    val queue: List<SongItem> = emptyList(),
    @SerialName("current_index") val currentIndex: Int = 0,
    @SerialName("current_track") val currentTrack: SongItem? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("anchor_host_mono_ms") val anchorHostMonoMs: Long = 0L,
    @SerialName("repeat_mode") val repeatMode: String = "off",
    @SerialName("last_action_by") val lastActionBy: String = "",
    @SerialName("last_action_desc") val lastActionDesc: String = ""
)

@Serializable
data class PartyPollResult(
    @SerialName("has_update") val hasUpdate: Boolean = false,
    val state: RemotePartyState? = null,
    @SerialName("target_position_ms") val targetPositionMs: Long? = null
)

@Serializable
data class PartyAction(
    val type: String,
    @SerialName("is_playing") val isPlaying: Boolean? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    val track: SongItem? = null,
    val tracks: List<SongItem>? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    val index: Int? = null,
    @SerialName("play_next") val playNext: Boolean? = null,
    @SerialName("upcoming_index") val upcomingIndex: Int? = null,
    val mode: String? = null
)
