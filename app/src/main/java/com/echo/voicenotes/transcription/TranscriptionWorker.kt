package com.echo.voicenotes.transcription

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.echo.voicenotes.recording.NotificationChannels
import com.echo.voicenotes.data.NoteRepository
import com.echo.voicenotes.data.TranscriptionState
import com.echo.voicenotes.drive.DriveSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(NOTE_ID) ?: return Result.failure()
        val repo = NoteRepository.get(applicationContext)
        val note = repo.notes.byId(id) ?: return Result.failure()
        NotificationChannels.ensure(applicationContext); setForeground(foregroundInfo("Transcribing voice note"))
        repo.notes.transcriptionState(id, TranscriptionState.RUNNING)
        return try {
            val transcript = withContext(Dispatchers.Default) { NativeWhisperEngine.create().transcribe(File(note.audioPath)) }
            repo.notes.completeTranscript(id, transcript)
            DriveSyncScheduler.enqueue(applicationContext, id)
            Result.success()
        } catch (e: IllegalStateException) {
            repo.notes.transcriptionState(id, TranscriptionState.FAILED, e.message)
            Result.failure()
        } catch (e: Exception) {
            repo.notes.transcriptionState(id, TranscriptionState.FAILED, e.message ?: "Transcription failed")
            Result.retry()
        }
    }
    private fun foregroundInfo(text: String): ForegroundInfo = ForegroundInfo(1002,
        NotificationCompat.Builder(applicationContext, NotificationChannels.TRANSCRIPTION)
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(text).setOngoing(true).build())
    companion object { const val NOTE_ID = "note_id" }
}
