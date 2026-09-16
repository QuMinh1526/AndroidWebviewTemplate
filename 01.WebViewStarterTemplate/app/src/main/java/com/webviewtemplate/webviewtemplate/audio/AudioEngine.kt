package com.webviewtemplate.webviewtemplate.audio

import android.util.Log
import com.webviewtemplate.webviewtemplate.ui.AudioSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class NativeAudioLevels(
    val inputDb: Float = -100f,
    val outputDb: Float = -100f,
    val gainReductionDb: Float = 0f,
)

class AudioEngine private constructor() {
    private val oboe = OboeEngine()
    private val meterExecutor = Executors.newSingleThreadScheduledExecutor()
    private var meterTask: ScheduledFuture<*>? = null
    @Volatile var levels: NativeAudioLevels = NativeAudioLevels()
        private set
    @Volatile var running: Boolean = false
        private set

    fun start() {
        if (running) return
        oboe.start()
        oboe.setInjectionMode(false)
        running = true
        meterTask = meterExecutor.scheduleAtFixedRate({
            val raw = oboe.getLevels()
            levels = NativeAudioLevels(
                raw.getOrElse(0) { -100f },
                raw.getOrElse(1) { -100f },
                raw.getOrElse(2) { 0f },
            )
        }, 0, 50, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Native engine started at ${oboe.getSampleRate()}Hz/${oboe.getFramesPerBurst()} frames")
    }

    fun stop() {
        meterTask?.cancel(true)
        meterTask = null
        if (running) oboe.stop()
        running = false
        levels = NativeAudioLevels()
    }

    fun apply(settings: AudioSettings) {
        check(running) { "Native audio engine is not running" }
        oboe.setParam(99, 0, if (settings.enabled) 0f else 1f)
        oboe.setParam(0, 0, settings.gateThreshold)
        oboe.setParam(0, 1, settings.gateAttack)
        oboe.setParam(0, 2, settings.gateRelease)
        oboe.setParam(0, 3, if (settings.gateEnabled) 1f else 0f)
        settings.eq.forEachIndexed { i, value -> oboe.setParam(1, i, value) }
        oboe.setParam(1, 10, if (settings.eqEnabled) 1f else 0f)
        oboe.setParam(2, 0, settings.compThreshold)
        oboe.setParam(2, 1, settings.compRatio)
        oboe.setParam(2, 2, settings.compAttack)
        oboe.setParam(2, 3, settings.compRelease)
        oboe.setParam(2, 4, settings.compMakeup)
        oboe.setParam(2, 5, if (settings.compressorEnabled) 1f else 0f)
        oboe.setParam(3, 0, settings.reverbMix)
        oboe.setParam(3, 1, settings.reverbRoom)
        oboe.setParam(3, 2, settings.reverbDamping)
        oboe.setParam(3, 3, if (settings.reverbEnabled) 1f else 0f)
        oboe.setParam(4, 0, settings.pitchSemitones)
        oboe.setParam(4, 1, if (settings.pitchEnabled) 1f else 0f)
        oboe.setParam(6, 0, settings.echoAmount)
        oboe.setParam(6, 1, if (settings.echoEnabled) 1f else 0f)
        oboe.setParam(5, 0, settings.gain)
        oboe.setParam(5, 1, if (settings.gainEnabled) 1f else 0f)
    }

    /** The only web-facing control path for native DSP parameters. */
    fun setParam(effectId: Int, paramId: Int, value: Float) {
        check(running) { "Native audio engine is not running" }
        oboe.setParam(effectId, paramId, value)
    }

    fun setInputDevice(deviceId: Int) {
        if (running) oboe.setInputDevice(deviceId)
    }

    fun setNativeInjection(enabled: Boolean) {
        if (running) oboe.setInjectionMode(enabled)
    }

    fun sampleRate(): Int = oboe.getSampleRate()
    fun framesPerBurst(): Int = oboe.getFramesPerBurst()
    fun pullPcm(maxFrames: Int): FloatArray = oboe.pullPcm(maxFrames)
    fun pullMonitorPcm(maxFrames: Int): FloatArray = oboe.pullMonitorPcm(maxFrames)
    fun clearPcm() = oboe.clearPcm()
    fun requireHealthy() {
        if (!running || !oboe.isRunning()) {
            throw RuntimeException(
                "Native Oboe audio engine stopped unexpectedly; audio communication routing failed."
            )
        }
    }

    companion object {
        private const val TAG = "WebViewTemplate.Audio"
        val shared: AudioEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AudioEngine() }
    }
}
