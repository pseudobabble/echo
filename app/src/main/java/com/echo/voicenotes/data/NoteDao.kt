package com.echo.voicenotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC") fun observeAll(): Flow<List<Note>>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun byId(id: String): Note?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(note: Note)
    @Query("UPDATE notes SET durationMs = :durationMs WHERE id = :id") suspend fun updateDuration(id: String, durationMs: Long)
    @Query("UPDATE notes SET transcriptionState = :state, transcriptionError = :error WHERE id = :id") suspend fun transcriptionState(id: String, state: TranscriptionState, error: String? = null)
    @Query("UPDATE notes SET transcript = :text, transcriptionState = 'COMPLETE', transcriptionError = NULL WHERE id = :id") suspend fun completeTranscript(id: String, text: String)
    @Query("UPDATE notes SET driveState = :state, driveError = :error WHERE id = :id") suspend fun driveState(id: String, state: SyncState, error: String? = null)
    @Query("SELECT id FROM notes WHERE transcriptionState = 'COMPLETE'") suspend fun completedIds(): List<String>
    @Query("SELECT id FROM notes WHERE transcriptionState = 'QUEUED'") suspend fun queuedIds(): List<String>
    @Query("SELECT id FROM notes") suspend fun allIds(): List<String>
}
