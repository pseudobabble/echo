package com.echo.voicenotes.transcription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The only transcription implementation v1 permits: local whisper.cpp via JNI. */
interface WhisperEngine { suspend fun transcribe(audio: File): String }

class NativeWhisperEngine private constructor(private val model: File) : WhisperEngine {
    override suspend fun transcribe(audio: File): String = withContext(Dispatchers.Default) {
        nativeTranscribe(model.absolutePath, PcmAudioDecoder.decode(audio))
    }
    private external fun nativeTranscribe(modelPath: String, samples: FloatArray): String
    companion object {
        fun create(context: Context): NativeWhisperEngine {
            val model = WhisperModelManager(context).installedModel()
                ?: throw IllegalStateException("Transcription model is not installed. Download it from Echo before recording.")
            try { System.loadLibrary("echo_whisper") } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("The device does not support Echo's Whisper engine.", e)
            }
            return NativeWhisperEngine(model)
        }
    }
}
