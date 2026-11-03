package com.echo.voicenotes.recording

import com.echo.voicenotes.data.NoteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveRecording(
    val noteId: String,
    val startedAt: Long,
    val kind: NoteKind,
    val isPaused: Boolean = false,
    val pauseStartedAt: Long? = null,
    val pausedDurationMs: Long = 0,
) {
    fun elapsedAt(now: Long): Long {
        val end = if (isPaused) pauseStartedAt ?: now else now
        return (end - startedAt - pausedDurationMs).coerceAtLeast(0)
    }
}

/** Process-local UI state for the foreground recording service. */
object RecordingStatus {
    private val mutableActive = MutableStateFlow<ActiveRecording?>(null)
    val active = mutableActive.asStateFlow()

    fun started(noteId: String, startedAt: Long, kind: NoteKind) {
        mutableActive.value = ActiveRecording(noteId, startedAt, kind)
    }

    fun paused(now: Long) {
        val current = mutableActive.value ?: return
        if (!current.isPaused) mutableActive.value = current.copy(isPaused = true, pauseStartedAt = now)
    }

    fun resumed(now: Long) {
        val current = mutableActive.value ?: return
        if (current.isPaused) mutableActive.value = current.copy(
            isPaused = false,
            pauseStartedAt = null,
            pausedDurationMs = current.pausedDurationMs + now - (current.pauseStartedAt ?: now),
        )
    }

    fun stopped() {
        mutableActive.value = null
    }
}
