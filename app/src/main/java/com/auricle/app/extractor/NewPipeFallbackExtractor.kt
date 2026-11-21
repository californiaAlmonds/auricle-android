package com.auricle.app.extractor

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class FallbackStreamResult(
    val url: String,
    val userAgent: String? = null,
    val isHls: Boolean = false,
    val durationSecs: Long = 0L
)

class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (name, values) ->
            values.forEach { value ->
                reqBuilder.addHeader(name, value)
            }
        }

        if (httpMethod.equals("POST", ignoreCase = true)) {
            val body = dataToSend?.toRequestBody() ?: "".toRequestBody()
            reqBuilder.post(body)
        } else {
            reqBuilder.get()
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            val responseHeaders = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                responseHeaders[name] = response.headers.values(name)
            }

            return Response(
                response.code,
                response.message,
                responseHeaders,
                responseBody,
                response.request.url.toString()
            )
        }
    }
}

object NewPipeFallbackExtractor {
    private const val TAG = "NewPipeFallback"

    @Volatile
    private var isInitialized = false

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun init() {
        if (!isInitialized) {
            try {
                NewPipe.init(OkHttpDownloader(okHttpClient))
                isInitialized = true
                Log.d(TAG, "NewPipeExtractor initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NewPipeExtractor: ${e.message}")
            }
        }
    }

    suspend fun extractAudioStreamUrl(
        videoId: String,
        highQuality: Boolean = true
    ): FallbackStreamResult? = withContext(Dispatchers.IO) {
        init()
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)
            val durationSecs = streamInfo.duration.coerceAtLeast(0L)

            val progressiveStreams = streamInfo.audioStreams.orEmpty().filter { stream ->
                val content = stream.content
                !content.isNullOrBlank() &&
                    stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    content.startsWith("https://", ignoreCase = true)
            }

            if (progressiveStreams.isNotEmpty()) {
                val selected = if (highQuality) {
                    progressiveStreams.sortedByDescending { it.averageBitrate }.first()
                } else {
                    progressiveStreams
                        .sortedWith(
                            compareBy<AudioStream> { if (it.averageBitrate > 0) 0 else 1 }
                                .thenBy { abs(it.averageBitrate - 128) }
                                .thenByDescending { it.averageBitrate }
                        )
                        .first()
                }
                Log.d(
                    TAG,
                    "Extracted fallback stream for $videoId (highQuality=$highQuality, bitrate=${selected.averageBitrate}, format=${selected.format})"
                )
                return@withContext FallbackStreamResult(
                    url = selected.content,
                    userAgent = null,
                    isHls = false,
                    durationSecs = durationSecs
                )
            }

            val hlsUrl = streamInfo.hlsUrl?.takeIf {
                it.isNotBlank() && it.startsWith("https://", ignoreCase = true)
            }
            if (hlsUrl != null) {
                Log.d(TAG, "Extracted HLS fallback stream for $videoId")
                return@withContext FallbackStreamResult(
                    url = hlsUrl,
                    userAgent = null,
                    isHls = true,
                    durationSecs = durationSecs
                )
            }

            Log.w(TAG, "No usable audio streams found for videoId=$videoId")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for videoId=$videoId: ${e.message}", e)
            null
        }
    }
}
