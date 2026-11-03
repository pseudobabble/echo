package com.echo.voicenotes.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RecordActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            RecordingService.ACTION_STOP -> RecordingService.ACTION_STOP
            RecordingService.ACTION_PAUSE -> RecordingService.ACTION_PAUSE
            RecordingService.ACTION_RESUME -> RecordingService.ACTION_RESUME
            else -> RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(action))
    }
}
