package com.echo.voicenotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val audioPath: String,
    val durationMs: Long = 0,
    val transcript: String? = null,
    val transcriptionState: TranscriptionState = TranscriptionState.QUEUED,
    val transcriptionError: String? = null,
    val kind: NoteKind = NoteKind.NOTE,
    // Retained solely for database compatibility while S3 work is paused.
    val s3State: SyncState = SyncState.NOT_CONFIGURED,
    val driveState: SyncState = SyncState.NOT_CONFIGURED,
    val driveError: String? = null,
)

enum class NoteKind { NOTE, TASK }
enum class TranscriptionState { QUEUED, RUNNING, COMPLETE, FAILED }
enum class SyncState { NOT_CONFIGURED, PENDING, SYNCED, FAILED }
