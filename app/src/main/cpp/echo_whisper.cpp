#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <string>
#include <thread>

#include "whisper.h"

namespace {
constexpr const char * kTag = "EchoWhisper";

jstring fail(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exception, message);
    return nullptr;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_echo_voicenotes_transcription_NativeWhisperEngine_nativeTranscribe(
        JNIEnv * env,
        jobject,
        jstring model_path,
        jfloatArray samples) {
    const char * model = env->GetStringUTFChars(model_path, nullptr);
    if (model == nullptr) return fail(env, "Unable to read Whisper model path");

    whisper_context_params context_params = whisper_context_default_params();
    whisper_context * context = whisper_init_from_file_with_params(model, context_params);
    env->ReleaseStringUTFChars(model_path, model);
    if (context == nullptr) return fail(env, "Unable to load Whisper model");

    const jsize sample_count = env->GetArrayLength(samples);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) {
        whisper_free(context);
        return fail(env, "Unable to read recorded audio");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1u, std::thread::hardware_concurrency() / 2);
    params.translate = false;
    params.language = "auto";
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;

    const int result = whisper_full(context, params, pcm, sample_count);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (result != 0) {
        whisper_free(context);
        return fail(env, "Whisper could not transcribe this recording");
    }

    std::string transcript;
    const int segments = whisper_full_n_segments(context);
    for (int index = 0; index < segments; ++index) transcript += whisper_full_get_segment_text(context, index);
    whisper_free(context);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Transcribed %d samples into %d segments", sample_count, segments);
    return env->NewStringUTF(transcript.c_str());
}
