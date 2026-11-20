package com.auricle.app

import android.app.Application
import android.util.Log
import com.auricle.app.bridge.AuricleNative
import com.auricle.app.data.LibraryRepository
import com.auricle.app.data.MusicRepository
import com.auricle.app.data.SettingsRepository
import com.auricle.app.extractor.NewPipeFallbackExtractor
import com.auricle.app.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AuricleApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var musicRepository: MusicRepository
        private set
    lateinit var libraryRepository: LibraryRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var playbackController: PlaybackController
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize Native Core Bridge
        try {
            val storagePath = filesDir.absolutePath
            val success = AuricleNative.nativeInit(this, storagePath)
            Log.d("AuricleApp", "nativeInit result: $success")
        } catch (e: Throwable) {
            Log.e("AuricleApp", "nativeInit failed: ${e.message}", e)
        }

        // Initialize Fallback Extractor
        appScope.launch(Dispatchers.IO) {
            NewPipeFallbackExtractor.init()
        }

        // Initialize Repositories and Playback Controller
        musicRepository = MusicRepository(this)
        libraryRepository = LibraryRepository(this, appScope)
        settingsRepository = SettingsRepository(this, appScope)
        playbackController = PlaybackController(
            context = this,
            musicRepo = musicRepository,
            libraryRepo = libraryRepository,
            coroutineScope = appScope
        )
    }
}
