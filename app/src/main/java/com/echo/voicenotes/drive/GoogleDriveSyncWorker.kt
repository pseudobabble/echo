package com.echo.voicenotes.drive

import android.content.Context
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echo.voicenotes.data.NoteRepository
import com.echo.voicenotes.data.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies completed local notes into a user-selected Google Drive folder through Android's Drive provider. */
class GoogleDriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val noteId = inputData.getString(NOTE_ID) ?: return@withContext Result.failure()
        val folder = DriveFolder.get(applicationContext) ?: return@withContext Result.success()
        val repo = NoteRepository.get(applicationContext); val note = repo.notes.byId(noteId) ?: return@withContext Result.failure()
        repo.notes.driveState(noteId, SyncState.PENDING)
        try {
            val resolver = applicationContext.contentResolver
            val audio = findOrCreate(folder, "${note.id}.m4a", "audio/mp4")
            resolver.openOutputStream(audio, "wt")!!.use { output -> File(note.audioPath).inputStream().use { it.copyTo(output) } }
            val transcript = findOrCreate(folder, "${note.id}.txt", "text/plain")
            resolver.openOutputStream(transcript, "wt")!!.bufferedWriter().use { it.write(note.transcript.orEmpty()) }
            repo.notes.driveState(noteId, SyncState.SYNCED); Result.success()
        } catch (e: Exception) {
            repo.notes.driveState(noteId, SyncState.FAILED, e.message ?: e.javaClass.simpleName)
            Result.retry()
        }
    }
    private fun findOrCreate(tree: android.net.Uri, name: String, mime: String): android.net.Uri {
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex))
        }
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return requireNotNull(DocumentsContract.createDocument(resolver, folder, mime, name)) { "Drive folder refused new document" }
    }
    companion object { const val NOTE_ID = "note_id" }
}
