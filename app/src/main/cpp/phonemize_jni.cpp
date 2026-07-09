#include <jni.h>
#include <string>
#include <android/log.h>
#include <espeak-ng/speak_lib.h>

#define LOG_TAG "KittenTTS-Phonemize"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_localaudiototext_EspeakPhonemizer_nativeInitialize(
        JNIEnv *env, jobject /* this */, jstring dataPath) {

    const char *path = env->GetStringUTFChars(dataPath, nullptr);

    int samplerate = espeak_Initialize(
            AUDIO_OUTPUT_RETRIEVAL, // We don't need audio output
            0,                      // Default buffer length
            path,                   // Path to espeak-ng-data
            0                       // No special options
    );

    env->ReleaseStringUTFChars(dataPath, path);

    if (samplerate <= 0) {
        LOGE("Failed to initialize espeak-ng, error code: %d", samplerate);
        return -1;
    }

    // Set English US voice with stress markers
    espeak_ERROR err = espeak_SetVoiceByName("en-us");
    if (err != EE_OK) {
        // Fall back to generic English
        err = espeak_SetVoiceByName("en");
        if (err != EE_OK) {
            LOGE("Failed to set English voice, error: %d", err);
            return -1;
        }
    }

    LOGI("espeak-ng initialized successfully, sample rate: %d", samplerate);
    return samplerate;
}

JNIEXPORT jstring JNICALL
Java_com_example_localaudiototext_EspeakPhonemizer_nativeTextToPhonemes(
        JNIEnv *env, jobject /* this */, jstring text) {

    const char *input = env->GetStringUTFChars(text, nullptr);

    // phonememode flags:
    // bit 0 (0x01): 1 = IPA output
    // bit 1 (0x02): include stress marks
    int phonemeMode = 0x02 | 0x01; // IPA with stress

    std::string result;
    const void *textPtr = static_cast<const void *>(input);

    // espeak_TextToPhonemes processes one clause at a time,
    // advancing textPtr. Loop until all text is consumed.
    while (textPtr != nullptr && *static_cast<const char *>(textPtr) != '\0') {
        const char *phonemes = espeak_TextToPhonemes(
                &textPtr,
                espeakCHARS_UTF8,
                phonemeMode
        );

        if (phonemes != nullptr && phonemes[0] != '\0') {
            if (!result.empty()) {
                result += " ";
            }
            result += phonemes;
        }
    }

    env->ReleaseStringUTFChars(text, input);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_localaudiototext_EspeakPhonemizer_nativeTerminate(
        JNIEnv * /* env */, jobject /* this */) {
    espeak_Terminate();
    LOGI("espeak-ng terminated");
}

} // extern "C"
