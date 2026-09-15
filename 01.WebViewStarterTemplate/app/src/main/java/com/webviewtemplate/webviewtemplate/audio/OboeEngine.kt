package com.webviewtemplate.webviewtemplate.audio

import android.util.Log

class OboeEngine {
    private var handle: Long

    init {
        try {
            System.loadLibrary("webviewtemplate_native")
            handle = nativeCreate()
            check(handle != 0L) { "Native AudioEngine returned a null handle" }
        } catch (t: Throwable) {
            throw RuntimeException(
                "Unable to initialize native Oboe audio engine. " +
                    "Verify the APK contains webviewtemplate_native and the device supports audio input.",
                t
            )
        }
    }

    fun start() {
        check(handle != 0L) { "Native AudioEngine has already been destroyed" }
        if (!nativeStart(handle)) {
            throw RuntimeException(
                "Native Oboe audio engine failed to start. " +
                    "Check RECORD_AUDIO permission and the device audio input/output routes."
            )
        }
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun setParam(effectId: Int, paramId: Int, value: Float) {
        check(handle != 0L) { "Native AudioEngine is not available" }
        nativeSetParam(handle, effectId, paramId, value)
    }

    fun setInjectionMode(enabled: Boolean) {
        check(handle != 0L) { "Native AudioEngine is not available" }
        nativeSetInjectionMode(handle, enabled)
    }

    fun getLevels(): FloatArray = nativeGetLevels(handle)
    fun isRunning(): Boolean = nativeIsRunning(handle)
    fun getSampleRate(): Int = nativeGetSampleRate(handle)
    fun getFramesPerBurst(): Int = nativeGetFramesPerBurst(handle)

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetParam(handle: Long, effectId: Int, paramId: Int, value: Float)
    private external fun nativeGetLevels(handle: Long): FloatArray
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeSetInjectionMode(handle: Long, enabled: Boolean)
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetFramesPerBurst(handle: Long): Int
}
