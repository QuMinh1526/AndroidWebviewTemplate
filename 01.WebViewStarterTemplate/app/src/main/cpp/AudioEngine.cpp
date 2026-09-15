#include <jni.h>
#include <algorithm>
#include <cmath>

struct PcmProcessor {};

extern "C" JNIEXPORT jlong JNICALL
Java_com_webviewtemplate_webviewtemplate_audio_NativePcmProcessor_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new PcmProcessor());
}

extern "C" JNIEXPORT void JNICALL
Java_com_webviewtemplate_webviewtemplate_audio_NativePcmProcessor_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<PcmProcessor*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_webviewtemplate_webviewtemplate_audio_NativePcmProcessor_nativeProcess(
    JNIEnv* env, jobject, jlong, jshortArray input, jint count) {
    jshort* samples = env->GetShortArrayElements(input, nullptr);
    if (!samples) return;
    const int n = std::min(count, env->GetArrayLength(input));
    float sum = 0.0f;
    for (int i = 0; i < n; ++i) {
        const float value = static_cast<float>(samples[i]) / 32768.0f;
        sum += value * value;
    }
    const float rms = std::sqrt(sum / std::max(1, n));
    const float gain = rms < 0.003f ? 0.0f : 1.0f;
    for (int i = 0; i < n; ++i) {
        const float value = std::clamp((static_cast<float>(samples[i]) / 32768.0f) * gain, -1.0f, 1.0f);
        samples[i] = static_cast<jshort>(value * 32767.0f);
    }
    env->ReleaseShortArrayElements(input, samples, 0);
}
