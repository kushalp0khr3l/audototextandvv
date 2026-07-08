#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper/include/whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_localaudiototext_WhisperLib_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    
    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }
    LOGI("Whisper context initialized");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_example_localaudiototext_WhisperLib_fullTranscribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data) {
    if (context_ptr == 0) {
        return env->NewStringUTF("");
    }
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    
    jsize audio_len = env->GetArrayLength(audio_data);
    jfloat *audio_elements = env->GetFloatArrayElements(audio_data, nullptr);
    
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.language = "en"; // Can be configured
    wparams.n_threads = 4;
    
    int ret = whisper_full(ctx, wparams, audio_elements, audio_len);
    env->ReleaseFloatArrayElements(audio_data, audio_elements, JNI_ABORT);
    
    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
        return env->NewStringUTF("");
    }
    
    int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        result += text;
    }
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_localaudiototext_WhisperLib_freeContext(JNIEnv *env, jobject thiz, jlong context_ptr) {
    if (context_ptr != 0) {
        struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

}
