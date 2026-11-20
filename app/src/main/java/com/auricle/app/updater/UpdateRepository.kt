package com.auricle.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.auricle.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private fun defaultHttpClient() = OkHttpClient.Builder().build()

class UpdateRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val hasPendingUpdate: StateFlow<Boolean> = _state
        .map { it is UpdateState.Available || it is UpdateState.Ready }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = File(context.filesDir, "updater_state.json")
    private val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    
    private var downloadJob: Job? = null

    suspend fun checkOnLaunchIfDue(autoCheckEnabled: Boolean) {
        if (!autoCheckEnabled) return
        val lastCheckMs = withContext(Dispatchers.IO) {
            if (stateFile.exists()) {
                try {
                    val content = stateFile.readText()
                    // very simple parsing or just storing timestamp
                    content.toLongOrNull() ?: 0L
                } catch (e: Exception) { 0L }
            } else 0L
        }
        val now = System.currentTimeMillis()
        if (now - lastCheckMs >= 24 * 3600 * 1000L) {
            checkForUpdates(false)
        } else {
            // Check if ready apk is sitting in cache for a newer version
            withContext(Dispatchers.IO) {
                // Not strictly fully defined how to discover `info`, but we skip for now unless requested
            }
        }
    }

    suspend fun checkForUpdates(manual: Boolean = true) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/californiaAlmonds/auricle-android/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Auricle-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val bodyStr = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = UpdateState.Error("Failed to check for updates: HTTP ${response.code}")
                    return@withContext
                }
                response.body?.string() ?: ""
            }
            val release = json.decodeFromString<GitHubRelease>(bodyStr)

            stateFile.writeText(System.currentTimeMillis().toString())

            if (!release.draft && !release.prerelease && isNewerVersion(release.tagName, BuildConfig.VERSION_NAME)) {
                val supportedAbis = Build.SUPPORTED_ABIS.toList()
                val asset = selectBestApkAsset(release.assets, release.tagName, supportedAbis)
                if (asset != null) {
                    val expectedSha = parseExpectedSha256(asset.digest).getOrNull()
                    if (expectedSha == null) {
                        _state.value = UpdateState.Error("Release asset is missing a valid SHA-256 digest.")
                        return@withContext
                    }
                    
                    val bullets = release.body.lines()
                        .map { it.trim() }
                        .filter { it.startsWith("-") || it.startsWith("*") }
                        .map { it.removePrefix("-").removePrefix("*").trim() }
                        .filter { it.isNotEmpty() }
                    
                    val finalBullets = if (bullets.isEmpty()) listOf("Performance improvements and bug fixes.") else bullets
                    
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    val date = try { sdf.parse(release.publishedAt) } catch (e: Exception) { null }
                    val displayDate = if (date != null) {
                        SimpleDateFormat("dd MMM yyyy", Locale.US).format(date)
                    } else "Unknown date"

                    val info = ReleaseUpdateInfo(
                        version = release.tagName.removePrefix("v"),
                        currentVersion = BuildConfig.VERSION_NAME,
                        releaseNotesBullets = finalBullets,
                        htmlUrl = release.htmlUrl,
                        publishedDateDisplay = displayDate,
                        assetName = asset.name,
                        downloadUrl = asset.browserDownloadUrl,
                        sizeBytes = asset.size,
                        expectedSha256 = expectedSha
                    )
                    
                    // Enforce HTTPS host allowlist for download URL!
                    val parsedUri = Uri.parse(info.downloadUrl)
                    val urlScheme = parsedUri.scheme?.lowercase() ?: ""
                    val urlHost = parsedUri.host?.lowercase() ?: ""
                    if (urlScheme != "https" || !isAllowedDownloadHost(urlHost)) {
                        _state.value = UpdateState.Error("Invalid download server: $urlHost")
                        return@withContext
                    }

                    _state.value = UpdateState.Available(info)
                } else {
                    _state.value = UpdateState.Error("No compatible APK found in the latest release.")
                }
            } else {
                _state.value = UpdateState.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.localizedMessage ?: "Network error")
        }
    }

    fun downloadUpdate() {
        val currentState = _state.value
        val info = when (currentState) {
            is UpdateState.Available -> currentState.info
            is UpdateState.Error -> currentState.info ?: return
            else -> return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            val sanitizedName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val canonicalDir = updatesDir.canonicalFile
            val partFile = File(updatesDir, "$sanitizedName.part")
            val apkFile = File(updatesDir, sanitizedName)

            if (partFile.canonicalFile.parentFile != canonicalDir || apkFile.canonicalFile.parentFile != canonicalDir) {
                _state.value = UpdateState.Error("Path traversal detected", info)
                return@launch
            }

            try {
                val request = Request.Builder()
                    .url(info.downloadUrl)
                    .header("User-Agent", "Auricle-Android/${BuildConfig.VERSION_NAME}")
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val finalUrl = resp.request.url
                    if (finalUrl.scheme != "https" || !isAllowedDownloadHost(finalUrl.host.lowercase())) {
                        throw SecurityException("Untrusted redirect host: ${finalUrl.host}")
                    }

                    val body = resp.body ?: throw Exception("Empty response body")
                    val totalBytes = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes

                    var downloadedBytes = 0L
                    val startTime = System.currentTimeMillis()
                    var lastEmitTime = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = input.read(buffer)
                            while (read != -1) {
                                ensureActive()
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                if (downloadedBytes > 250L * 1024 * 1024) {
                                    throw SecurityException("Downloaded APK exceeds 250 MB safety limit.")
                                }

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 100) {
                                    val elapsedSec = (now - startTime) / 1000.0
                                    val speed = if (elapsedSec > 0) downloadedBytes / elapsedSec else 0.0
                                    val eta = if (speed > 0 && totalBytes > 0) ((totalBytes - downloadedBytes) / speed).toLong() else null

                                    _state.value = UpdateState.Downloading(info, downloadedBytes, totalBytes, eta)
                                    lastEmitTime = now
                                }
                                read = input.read(buffer)
                            }
                        }
                    }
                }

                if (!verifyFileSha256(partFile, info.expectedSha256)) {
                    partFile.delete()
                    _state.value = UpdateState.Error("Package checksum verification failed (SHA-256 mismatch). Partial download deleted.", info)
                    return@launch
                }

                if (apkFile.exists()) {
                    apkFile.delete()
                }
                if (partFile.renameTo(apkFile)) {
                    _state.value = UpdateState.Ready(info, apkFile, true)
                } else {
                    throw Exception("Failed to finalize downloaded package.")
                }

            } catch (e: CancellationException) {
                partFile.delete()
                _state.value = UpdateState.Available(info)
            } catch (e: Exception) {
                partFile.delete()
                _state.value = UpdateState.Error(e.localizedMessage ?: "Download failed", info)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun installUpdate(context: Context): Boolean {
        val currentState = _state.value
        if (currentState !is UpdateState.Ready) return false

        val apkFile = currentState.apkFile
        val info = currentState.info

        if (!apkFile.exists() || (info.sizeBytes > 0 && apkFile.length() != info.sizeBytes)) {
            _state.value = UpdateState.Error("APK file missing or corrupted", info)
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return false
            }
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Failed to start installer: ${e.message}", info)
            return false
        }
    }

    companion object {
        fun isAllowedDownloadHost(host: String): Boolean {
            return host == "github.com" ||
                host == "objects.githubusercontent.com" ||
                host == "release-assets.githubusercontent.com"
        }

        fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            val remote = remoteTag.removePrefix("v")
            val current = currentVersion.removePrefix("v")
            
            val remoteCore = remote.substringBefore('-')
            val currentCore = current.substringBefore('-')
            
            val remoteParts = remoteCore.split('.').mapNotNull { it.toIntOrNull() }
            val currentParts = currentCore.split('.').mapNotNull { it.toIntOrNull() }
            
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        fun selectBestApkAsset(assets: List<GitHubAsset>, version: String, supportedAbis: List<String>): GitHubAsset? {
            val strippedVersion = version.removePrefix("v")
            for (abi in supportedAbis) {
                val exact = assets.find { it.name == "auricle-android-$strippedVersion-$abi.apk" }
                if (exact != null) return exact
            }
            val exactUniversal = assets.find { it.name == "auricle-android-$strippedVersion-universal.apk" }
            if (exactUniversal != null) return exactUniversal
            val anyUniversal = assets.find { it.name.endsWith("-universal.apk") }
            if (anyUniversal != null) return anyUniversal
            for (abi in supportedAbis) {
                val match = assets.find { it.name.contains(abi) && it.name.endsWith(".apk") }
                if (match != null) return match
            }
            return assets.find { it.name.endsWith(".apk") }
        }

        fun parseExpectedSha256(digestField: String?): Result<String?> {
            if (digestField.isNullOrBlank()) return Result.success(null)
            val lower = digestField.lowercase()
            if (!lower.startsWith("sha256:")) return Result.failure(IllegalArgumentException("Invalid digest format"))
            val hex = lower.removePrefix("sha256:")
            if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                return Result.failure(IllegalArgumentException("Invalid sha256 hex"))
            }
            return Result.success(hex)
        }

        fun verifyFileSha256(file: File, expectedSha256: String?): Boolean {
            if (expectedSha256 == null || !file.exists()) return false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var read = fis.read(buffer)
                    while (read != -1) {
                        digest.update(buffer, 0, read)
                        read = fis.read(buffer)
                    }
                }
                val hashBytes = digest.digest()
                val hashHex = hashBytes.joinToString("") { "%02x".format(Locale.US, it) }
                return MessageDigest.isEqual(hashHex.toByteArray(Charsets.US_ASCII), expectedSha256.toByteArray(Charsets.US_ASCII))
            } catch (e: Exception) {
                return false
            }
        }
    }
}
