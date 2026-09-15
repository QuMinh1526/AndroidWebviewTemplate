package com.webviewtemplate.webviewtemplate.service

import android.content.Context
import com.webviewtemplate.webviewtemplate.audio.AudioEngine

class VirtualMicService(
    private val context: Context,
    private val shizuku: ShizukuManager,
) {
    fun activate(engine: AudioEngine) {
        shizuku.requireReady()
        shizuku.requireAlsaLoopback()
        shizuku.routeToLoopback(engine.sampleRate())
        shizuku.setAppOpsMicDefault(context.packageName)
    }
}
