package com.echo.voicenotes.recording

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.echo.voicenotes.MainActivity
import com.echo.voicenotes.R
import com.echo.voicenotes.data.Note
import com.echo.voicenotes.data.NoteRepository
import com.echo.voicenotes.drive.DriveSyncScheduler
import com.echo.voicenotes.transcription.TranscriptionWorker
import kotlinx.coroutines.*

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: MediaRecorder? = null
    private var noteId: String? = null
    private var startedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { ACTION_STOP -> stopRecording(); else -> if (recorder == null) startRecording() }
        return START_NOT_STICKY
    }
    private fun startRecording() {
        NotificationChannels.ensure(this)
        startForeground(NOTIFICATION_ID, recordingNotification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val repo = NoteRepository.get(this)
        val (id, file) = repo.newAudioFile(); noteId = id; startedAt = System.currentTimeMillis()
        scope.launch { repo.notes.upsert(Note(id, startedAt, file.absolutePath)) }
        runCatching {
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(64_000); setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath); prepare(); start()
            }
        }.onSuccess {
            recorder = it
            RecordingStatus.started(id, startedAt)
        }.onFailure {
            noteId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun stopRecording() {
        val id = noteId ?: return
        RecordingStatus.stopped()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        val duration = System.currentTimeMillis() - startedAt
        scope.launch {
            NoteRepository.get(this@RecordingService).notes.updateDuration(id, duration)
            DriveSyncScheduler.enqueue(this@RecordingService, id)
            WorkManager.getInstance(this@RecordingService).enqueue(
                OneTimeWorkRequestBuilder<TranscriptionWorker>().setInputData(workDataOf(TranscriptionWorker.NOTE_ID to id)).build())
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun recordingNotification(): Notification {
        val stop = PendingIntent.getBroadcast(this, 2, Intent(this, RecordActionReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NotificationChannels.RECORDING).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording voice note").setContentText("Tap Stop when you are finished").setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).addAction(android.R.drawable.ic_media_pause, "Stop", stop).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { if (recorder != null) stopRecording(); RecordingStatus.stopped(); scope.cancel(); super.onDestroy() }
    companion object { const val ACTION_START = "com.echo.voicenotes.START"; const val ACTION_STOP = "com.echo.voicenotes.STOP"; const val NOTIFICATION_ID = 1001 }
}

object NotificationChannels {
    const val RECORDING = "recording"; const val TRANSCRIPTION = "transcription"
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) (context.getSystemService(NotificationManager::class.java)).createNotificationChannels(listOf(
            NotificationChannel(RECORDING, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(TRANSCRIPTION, context.getString(R.string.transcription_channel), NotificationManager.IMPORTANCE_LOW)))
    }
}
