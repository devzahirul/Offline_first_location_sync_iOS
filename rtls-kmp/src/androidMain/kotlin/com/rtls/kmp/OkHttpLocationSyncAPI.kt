package com.rtls.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.util.concurrent.TimeUnit

class OkHttpLocationSyncAPI(
    private val baseUrl: String,
    private val tokenProvider: AuthTokenProvider,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .build()
) : LocationSyncAPI {

    private val json = Json { ignoreUnknownKeys = true }

    private fun gzipRequestBody(original: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = -1L
        override fun writeTo(sink: BufferedSink) {
            val gzipSink = GzipSink(sink).buffer()
            original.writeTo(gzipSink)
            gzipSink.close()
        }
    }

    override suspend fun upload(batch: LocationUploadBatch): LocationUploadResult = withContext(Dispatchers.IO) {
        val token = tokenProvider.accessToken()
        val url = baseUrl.trimEnd('/') + "/v1/locations/batch"
        val rawBody = json.encodeToString(batch).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(gzipRequestBody(rawBody))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Content-Encoding", "gzip")
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RuntimeException("Upload failed: ${response.code} ${response.body?.string()}")
            }
            val raw = response.body?.string() ?: throw RuntimeException("Empty response")
            json.decodeFromString<LocationUploadResult>(raw)
        } catch (e: java.net.UnknownServiceException) {
            throw RuntimeException(
                "Cleartext HTTP to $url blocked by Android network security policy. " +
                "Add android:usesCleartextTraffic=\"true\" to your AndroidManifest.xml <application> tag, " +
                "or use https:// instead.", e
            )
        }
    }
}
