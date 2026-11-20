package com.auricle.app.extractor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.IOException
import java.util.concurrent.TimeUnit

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

        val response = client.newCall(reqBuilder.build()).execute()
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

object NewPipeFallbackExtractor {
    private const val TAG = "NewPipeFallback"
    private var isInitialized = false

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

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

    suspend fun extractAudioStreamUrl(videoId: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        init()
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(url) as? YoutubeStreamExtractor
                ?: return@withContext null

            extractor.fetchPage()
            val audioStreams: List<AudioStream> = extractor.audioStreams
            if (audioStreams.isEmpty()) {
                Log.w(TAG, "No audio streams found for videoId=$videoId")
                return@withContext null
            }

            // Prefer highest average bitrate M4A / WebM
            val bestStream = audioStreams.maxByOrNull { it.averageBitrate } ?: audioStreams.first()
            val streamUrl = bestStream.content
            Log.d(TAG, "Extracted fallback stream for $videoId (bitrate=${bestStream.averageBitrate}, format=${bestStream.format})")
            Pair(streamUrl, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for videoId=$videoId: ${e.message}", e)
            null
        }
    }
}
