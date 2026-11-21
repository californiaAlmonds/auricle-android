package com.auricle.app.bridge

import android.content.Context
import androidx.annotation.Keep

@Keep
object AuricleNative {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("auricle_core")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("AuricleNative", "Failed to load libauricle_core.so: ${e.message}")
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    external fun nativeInit(context: Context, storageDir: String): Boolean
    external fun nativeGetHomeFeed(): String
    external fun nativeSearch(query: String, category: String): String
    external fun nativeSearchSuggestions(query: String): String
    external fun nativeGetWatchPlaylist(videoId: String): String
    external fun nativeGetAlbum(browseId: String): String
    external fun nativeGetArtist(browseId: String): String
    external fun nativeGetPlaylist(playlistId: String): String
    external fun nativeGetLyrics(videoId: String, langMode: String): String
    external fun nativeResolveStream(videoId: String, forceRefresh: Boolean): String
    external fun nativeResolveStreamQuality(videoId: String, forceRefresh: Boolean, highQuality: Boolean): String
    external fun nativeClearStreamCache()

    external fun nativePartyStart(displayName: String, initialStateJson: String): String
    external fun nativePartyJoin(inviteInput: String, displayName: String): String
    external fun nativePartyLeave(): String
    external fun nativePartyGetSnapshot(): String
    external fun nativePartyPollRemoteUpdate(): String
    external fun nativePartySendAction(actionJson: String): String
    external fun nativePartyGetTargetPositionMs(videoId: String): Long
}
