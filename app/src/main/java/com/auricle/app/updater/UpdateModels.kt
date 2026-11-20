package com.auricle.app.updater

import androidx.annotation.Keep
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
)

@Keep
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
    val digest: String? = null,
    @SerialName("content_type") val contentType: String = ""
)

data class ReleaseUpdateInfo(
    val version: String,
    val currentVersion: String,
    val releaseNotesBullets: List<String>,
    val htmlUrl: String,
    val publishedDateDisplay: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val expectedSha256: String?
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedVersion: String) : UpdateState
    data class Available(val info: ReleaseUpdateInfo) : UpdateState
    data class Downloading(val info: ReleaseUpdateInfo, val downloadedBytes: Long, val totalBytes: Long, val etaSeconds: Long?) : UpdateState
    data class Ready(val info: ReleaseUpdateInfo, val apkFile: File, val sha256Verified: Boolean) : UpdateState
    data class Error(val message: String, val info: ReleaseUpdateInfo? = null) : UpdateState
}
