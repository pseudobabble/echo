package com.echo.voicenotes.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveRecording(val noteId: String, val startedAt: Long)

/** Process-local UI state for the foreground recording service. */
object RecordingStatus {
    private val mutableActive = MutableStateFlow<ActiveRecording?>(null)
    val active = mutableActive.asStateFlow()

    fun started(noteId: String, startedAt: Long) {
        mutableActive.value = ActiveRecording(noteId, startedAt)
    }

    fun stopped() {
        mutableActive.value = null
    }
}
