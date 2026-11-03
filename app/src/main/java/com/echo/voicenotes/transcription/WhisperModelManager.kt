package com.echo.voicenotes.transcription

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echo.voicenotes.data.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class WhisperModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models")
    private val modelFile = File(modelDirectory, FILE_NAME)

    fun installedModel(): File? = modelFile.takeIf { it.isFile && it.length() == MODEL_SIZE && sha256(it) == SHA256 }

    companion object {
        const val FILE_NAME = "ggml-base-q5_1.bin"
        const val MODEL_SIZE = 59_707_625L
        const val SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
        private const val UNIQUE_WORK = "install-whisper-model"

        fun enqueueInstall(context: Context) {
            val request = OneTimeWorkRequestBuilder<WhisperModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

class WhisperModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = WhisperModelManager(applicationContext)
        if (manager.installedModel() != null) return@withContext Result.success()
        val directory = File(applicationContext.filesDir, "models").also { it.mkdirs() }
        val partial = File(directory, "${WhisperModelManager.FILE_NAME}.part")
        val destination = File(directory, WhisperModelManager.FILE_NAME)
        try {
            val connection = (URL(WhisperModelManager.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            require(partial.length() == WhisperModelManager.MODEL_SIZE) { "Downloaded model has an unexpected size" }
            require(WhisperModelManager.sha256(partial) == WhisperModelManager.SHA256) { "Downloaded model checksum did not match" }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) throw IllegalStateException("Unable to install downloaded model")
            NoteRepository.get(applicationContext).notes.queuedIds().forEach { id ->
                androidx.work.WorkManager.getInstance(applicationContext).enqueue(
                    androidx.work.OneTimeWorkRequestBuilder<TranscriptionWorker>()
                        .setInputData(androidx.work.workDataOf(TranscriptionWorker.NOTE_ID to id)).build())
            }
            Result.success()
        } catch (_: Exception) {
            partial.delete()
            Result.retry()
        }
    }
}
