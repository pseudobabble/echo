package com.echo.voicenotes.transcription

import java.io.File

/** The only transcription implementation v1 permits: local whisper.cpp via JNI. */
interface WhisperEngine { suspend fun transcribe(audio: File): String }

class NativeWhisperEngine private constructor() : WhisperEngine {
    override suspend fun transcribe(audio: File): String = nativeTranscribe(audio.absolutePath)
    private external fun nativeTranscribe(audioPath: String): String
    companion object {
        fun create(): NativeWhisperEngine {
            try { System.loadLibrary("echo_whisper") } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("Whisper engine is not installed. Install a model pack before transcribing.", e)
            }
            return NativeWhisperEngine()
        }
    }
}
