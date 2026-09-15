package com.webviewtemplate.webviewtemplate.audio

class NativePcmProcessor {
    private val handle: Long

    init {
        System.loadLibrary("webviewtemplate_native")
        handle = nativeCreate()
        check(handle != 0L) { "Native PCM processor could not be created" }
    }

    fun process(samples: ShortArray, count: Int) {
        nativeProcess(handle, samples, count)
    }

    fun close() {
        if (handle != 0L) nativeDestroy(handle)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, samples: ShortArray, count: Int)
}
