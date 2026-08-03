package com.fotoxplorr.app.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface LocalModelState {
    data object NotInstalled : LocalModelState
    data class Downloading(val bytesRead: Long, val totalBytes: Long?) : LocalModelState
    data class Ready(
        val file: File,
        val sizeBytes: Long,
        val sha256: String,
        val source: String,
    ) : LocalModelState
    data class Failed(val message: String) : LocalModelState
}

data class DeviceAiCapability(
    val androidVersion: Int,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val openGlEsVersion: String,
    val recommendedConcurrentWorkers: Int,
    val canRunImageEmbedding: Boolean,
    val warning: String?,
)

class LocalModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val metadataFile = File(modelDirectory, MODEL_METADATA_FILE)
    private val state = MutableStateFlow(loadState())

    fun observe(): StateFlow<LocalModelState> = state.asStateFlow()

    fun readyFile(): File? = (state.value as? LocalModelState.Ready)?.file

    fun capability(): DeviceAiCapability {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val glVersion = activityManager.deviceConfigurationInfo.glEsVersion.orEmpty()
        val memory = activityManager.memoryClass
        val largeMemory = activityManager.largeMemoryClass
        val workers = when {
            memory >= 512 -> 3
            memory >= 256 -> 2
            else -> 1
        }
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && memory >= 128
        return DeviceAiCapability(
            androidVersion = Build.VERSION.SDK_INT,
            memoryClassMb = memory,
            largeMemoryClassMb = largeMemory,
            openGlEsVersion = glVersion,
            recommendedConcurrentWorkers = workers,
            canRunImageEmbedding = supported,
            warning = when {
                !supported -> "This device has too little application memory for reliable local indexing."
                memory < 256 -> "Local indexing will use one worker and may take a long time."
                else -> null
            },
        )
    }

    suspend fun installRecommendedModel(): Result<LocalModelState.Ready> = withContext(Dispatchers.IO) {
        runCatching {
            modelDirectory.mkdirs()
            val temporary = File(modelDirectory, "$MODEL_FILE_NAME.download")
            temporary.delete()

            val request = Request.Builder().url(RECOMMENDED_MODEL_URL).get().build()
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Model download returned HTTP ${response.code}" }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0L }
                body.byteStream().use { input ->
                    FileOutputStream(temporary).buffered(DOWNLOAD_BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            state.value = LocalModelState.Downloading(written, total)
                        }
                        output.flush()
                    }
                }
            }

            validateTflite(temporary)
            replaceAtomically(temporary, modelFile)
            val hash = sha256(modelFile)
            metadataFile.writeText(
                listOf(
                    "source=$RECOMMENDED_MODEL_URL",
                    "sha256=$hash",
                    "size=${modelFile.length()}",
                    "installedAt=${System.currentTimeMillis()}",
                ).joinToString("\n"),
            )
            LocalModelState.Ready(modelFile, modelFile.length(), hash, RECOMMENDED_MODEL_URL).also {
                state.value = it
            }
        }.onFailure { error ->
            state.value = LocalModelState.Failed(error.message ?: "Unable to install local model")
        }
    }

    suspend fun installFromUri(uri: Uri): Result<LocalModelState.Ready> = withContext(Dispatchers.IO) {
        runCatching {
            modelDirectory.mkdirs()
            val temporary = File(modelDirectory, "$MODEL_FILE_NAME.import")
            temporary.delete()
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).buffered(DOWNLOAD_BUFFER_SIZE).use { output ->
                    input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                }
            } ?: error("Unable to open selected model")
            validateTflite(temporary)
            replaceAtomically(temporary, modelFile)
            val hash = sha256(modelFile)
            metadataFile.writeText(
                "source=user-import\nsha256=$hash\nsize=${modelFile.length()}\ninstalledAt=${System.currentTimeMillis()}",
            )
            LocalModelState.Ready(modelFile, modelFile.length(), hash, "user-import").also {
                state.value = it
            }
        }.onFailure { error ->
            state.value = LocalModelState.Failed(error.message ?: "Unable to import local model")
        }
    }

    fun deleteModel() {
        modelFile.delete()
        metadataFile.delete()
        state.value = LocalModelState.NotInstalled
    }

    private fun loadState(): LocalModelState {
        if (!modelFile.isFile) return LocalModelState.NotInstalled
        return runCatching {
            validateTflite(modelFile)
            val metadata = metadataFile.takeIf(File::isFile)?.readLines().orEmpty()
                .mapNotNull { line -> line.substringBefore('=', "").takeIf(String::isNotBlank)?.let { it to line.substringAfter('=', "") } }
                .toMap()
            LocalModelState.Ready(
                file = modelFile,
                sizeBytes = modelFile.length(),
                sha256 = metadata["sha256"] ?: sha256(modelFile),
                source = metadata["source"] ?: "unknown",
            )
        }.getOrElse { LocalModelState.Failed("Installed model is invalid; remove and reinstall it.") }
    }

    private fun validateTflite(file: File) {
        require(file.length() >= MIN_MODEL_BYTES) { "Selected file is too small to be a supported embedding model" }
        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            require(input.read(header) == header.size) { "Model header could not be read" }
            require(header.copyOfRange(4, 8).contentEquals(byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte()))) {
                "Selected file is not a TensorFlow Lite model"
            }
        }
    }

    private fun replaceAtomically(source: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(DOWNLOAD_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODEL_DIRECTORY = "ai-models"
        const val MODEL_FILE_NAME = "mobilenet_v3_small_image_embedder.tflite"
        const val MODEL_METADATA_FILE = "mobilenet_v3_small_image_embedder.properties"
        const val MIN_MODEL_BYTES = 1_000_000L
        const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
        const val RECOMMENDED_MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite"
    }
}
