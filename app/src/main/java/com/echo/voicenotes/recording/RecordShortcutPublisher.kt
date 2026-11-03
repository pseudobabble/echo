package com.echo.voicenotes.recording

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Privacy-safe, persistent entry point. Android decides whether the user permits it on lock screen. */
object RecordShortcutPublisher {
    private const val ID = 1000
    fun publish(context: Context) {
        NotificationChannels.ensure(context)
        val record = PendingIntent.getBroadcast(context, 1,
            Intent(context, RecordActionReceiver::class.java).setAction(RecordingService.ACTION_START), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.RECORDING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Echo")
            .setContentText("Ready to record a voice note").setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true).addAction(android.R.drawable.ic_btn_speak_now, "Record", record).build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }
}
