package com.echo.voicenotes.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Decodes Echo's 16 kHz mono AAC recordings into the float PCM required by whisper.cpp. */
object PcmAudioDecoder {
    private const val SAMPLE_RATE = 16_000

    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("Recording has no audio track")
        val format = extractor.getTrackFormat(track)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(sampleRate == SAMPLE_RATE) { "Recording sample rate must be $SAMPLE_RATE Hz" }

        val codec = MediaCodec.createDecoderByType(mime)
        val output = ArrayList<Float>()
        try {
            extractor.selectTrack(track)
            codec.configure(format, null, null, 0)
            codec.start()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(outputIndex)).duplicate()
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            while (buffer.remaining() >= channels * 2) {
                                var mixed = 0
                                repeat(channels) { mixed += buffer.short.toInt() }
                                output += (mixed / channels) / 32768f
                            }
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return output.toFloatArray().also { require(it.isNotEmpty()) { "Recording contains no audio" } }
    }
}
