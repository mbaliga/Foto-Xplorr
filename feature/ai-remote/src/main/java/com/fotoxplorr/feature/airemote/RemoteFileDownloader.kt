package com.fotoxplorr.feature.airemote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * The streaming download that used to live inside the app's `LocalModelManager` (FX-012):
 * fetch [url] into [destination], reporting progress as it goes. The caller owns
 * validation, atomic replacement and hashing — this class only moves bytes, because it is
 * the only part of that flow that needs a network and therefore the only part that
 * belongs on this side of the flavor boundary.
 */
class RemoteFileDownloader {

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download returned HTTP ${response.code}" }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0L }
                body.byteStream().use { input ->
                    FileOutputStream(destination).buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                        output.flush()
                    }
                }
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
    }
}
