package com.echo.voicenotes.drive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object DriveSyncScheduler {
    fun enqueue(context: Context, noteId: String) {
        if (DriveFolder.get(context) == null) return
        val request = OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(GoogleDriveSyncWorker.NOTE_ID to noteId)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("drive-$noteId", ExistingWorkPolicy.KEEP, request)
    }
}
