package com.echo.voicenotes.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RecordActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = if (intent.action == RecordingService.ACTION_STOP) RecordingService.ACTION_STOP else RecordingService.ACTION_START
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(action))
    }
}
