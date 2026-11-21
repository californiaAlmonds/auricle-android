package com.auricle.app.model

import java.net.URI
import java.util.Locale

private val GOOGLE_ARTWORK_HOSTS = setOf(
    "lh3.googleusercontent.com",
    "yt3.ggpht.com",
    "yt3.googleusercontent.com",
    "lh3.ggpht.com"
)

private val GOOGLE_SIZE_SUFFIX_REGEX = Regex("=(?:w\\d+-h\\d+|s\\d+)[^\\s]*$")

private val YTIMG_LOW_RES_REGEX = Regex(
    "^(https?://(?:i\\d*\\.ytimg\\.com|img\\.youtube\\.com)/vi/[^/]+/)(?:default|mqdefault)\\.jpg(\\?.*)?$",
    RegexOption.IGNORE_CASE
)

private fun extractHostLower(url: String): String {
    return runCatching {
        URI(url).host?.lowercase(Locale.US).orEmpty()
    }.getOrDefault("")
}

fun upgradeArtworkUrl(url: String, targetPx: Int = 544): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return ""
    val px = targetPx.coerceIn(64, 2048)

    val host = extractHostLower(trimmed)
    if (host in GOOGLE_ARTWORK_HOSTS) {
        val sizeSuffix = "=w$px-h$px-l90-rj"
        if (GOOGLE_SIZE_SUFFIX_REGEX.containsMatchIn(trimmed)) {
            return GOOGLE_SIZE_SUFFIX_REGEX.replace(trimmed, sizeSuffix)
        }
        val queryIdx = trimmed.indexOf('?')
        val base = if (queryIdx >= 0) trimmed.substring(0, queryIdx) else trimmed
        val query = if (queryIdx >= 0) trimmed.substring(queryIdx) else ""
        val lastSlash = base.lastIndexOf('/')
        val afterSlash = if (lastSlash >= 0) base.substring(lastSlash + 1) else base
        val eqIdx = afterSlash.indexOf('=')
        return if (eqIdx < 0) {
            base + sizeSuffix + query
        } else {
            val prefix = base.substring(0, lastSlash + 1 + eqIdx)
            prefix + sizeSuffix + query
        }
    }

    val ytMatch = YTIMG_LOW_RES_REGEX.matchEntire(trimmed)
    if (ytMatch != null) {
        val prefix = ytMatch.groupValues[1]
        val variant = if (px >= 640) "sddefault.jpg" else "hqdefault.jpg"
        return "$prefix$variant"
    }

    return trimmed
}

fun ytimgFallbackUrls(videoId: String, preferMaxRes: Boolean = false): List<String> {
    val id = videoId.trim()
    if (id.isEmpty()) return emptyList()
    return buildList {
        if (preferMaxRes) {
            add("https://i.ytimg.com/vi/$id/maxresdefault.jpg")
        }
        add("https://i.ytimg.com/vi/$id/sddefault.jpg")
        add("https://i.ytimg.com/vi/$id/hqdefault.jpg")
    }
}

fun resolveHighResArtwork(artworkUrl: String, thumbnailUrl: String, videoId: String): String {
    if (artworkUrl.isNotBlank()) return upgradeArtworkUrl(artworkUrl, 1080)
    if (thumbnailUrl.isNotBlank()) return upgradeArtworkUrl(thumbnailUrl, 1080)
    val id = videoId.trim()
    return if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/sddefault.jpg" else ""
}

fun resolveCardArtwork(artworkUrl: String, thumbnailUrl: String, videoId: String): String {
    val preferred = thumbnailUrl.ifBlank { artworkUrl }
    if (preferred.isNotBlank()) return upgradeArtworkUrl(preferred, 544)
    val id = videoId.trim()
    return if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else ""
}
