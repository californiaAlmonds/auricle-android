package com.auricle.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.auricle.app.AuricleApp
import com.auricle.app.R
import com.auricle.app.ui.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AuriclePlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    companion object {
        const val CHANNEL_ID = "auricle_playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()

        this.exoPlayer = player

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val controller = (application as AuricleApp).playbackController
        controller.attachPlayer(player)

        val forwardingPlayer = object : ForwardingPlayer(player) {
            private fun canSeekNext(): Boolean = controller.canSkipNext()
            private fun canSeekPrevious(): Boolean = controller.queue.value.isNotEmpty()

            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                if (canSeekNext()) {
                    builder.add(Player.COMMAND_SEEK_TO_NEXT)
                    builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                } else {
                    builder.remove(Player.COMMAND_SEEK_TO_NEXT)
                    builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (canSeekPrevious()) {
                    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                } else {
                    builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> canSeekNext()
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> canSeekPrevious()
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun play() {
                controller.setPlaying(true)
            }

            override fun pause() {
                controller.setPlaying(false)
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                controller.setPlaying(playWhenReady)
            }

            override fun seekToDefaultPosition() {
                controller.seekTo(0L)
            }

            override fun seekToDefaultPosition(mediaItemIndex: Int) {
                controller.seekTo(0L)
            }

            override fun seekBack() {
                val target = (controller.currentPosition.value - seekBackIncrement).coerceAtLeast(0L)
                controller.seekTo(target)
            }

            override fun seekForward() {
                val target = controller.currentPosition.value + seekForwardIncrement
                controller.seekTo(target)
            }

            override fun seekTo(positionMs: Long) {
                if (positionMs == C.TIME_UNSET) return
                controller.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (positionMs == C.TIME_UNSET) return
                controller.seekTo(positionMs)
            }

            override fun seekToNext() {
                controller.skipNext()
            }

            override fun seekToNextMediaItem() {
                controller.skipNext()
            }

            override fun seekToPrevious() {
                controller.skipPrevious()
            }

            override fun seekToPreviousMediaItem() {
                controller.skipPrevious()
            }
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val uid = controllerInfo.uid
                val allowed = uid == Process.myUid() ||
                    uid == Process.SYSTEM_UID ||
                    session.isMediaNotificationController(controllerInfo)
                return if (allowed) {
                    super.onConnect(session, controllerInfo)
                } else {
                    MediaSession.ConnectionResult.reject()
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val safeItems = mediaItems.mapNotNull { item ->
                    val uri = item.requestMetadata.mediaUri
                    if (uri != null && uri.scheme.equals("https", ignoreCase = true)) {
                        item.buildUpon().setUri(uri).build()
                    } else {
                        null
                    }
                }.toMutableList()
                return Futures.immediateFuture(safeItems)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        val app = application as? AuricleApp
        app?.playbackController?.detachPlayer()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }
}
