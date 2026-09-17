package com.webviewtemplate.webviewtemplate.audio

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.webviewtemplate.webviewtemplate.ConsoleLogStore
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pull-only bridge for the document-start WebView audio worklet. It consumes the
 * native queue; it never opens or owns an AudioRecord.
 */
class NativePcmBridge {
    /** Sets a native DSP parameter; JavaScript never creates DSP AudioNodes. */
    @JavascriptInterface
    fun setParam(effectId: Int, paramId: Int, value: Float) {
        runCatching { AudioEngine.shared.setParam(effectId, paramId, value) }
    }

    @JavascriptInterface
    fun mode(): String = routeMode

    @JavascriptInterface
    fun isRunning(): Boolean = runCatching {
        AudioEngine.shared.requireHealthy()
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun format(): String = runCatching {
        JSONObject()
            .put("sampleRate", AudioEngine.shared.sampleRate())
            .put("channels", 1)
            .put("framesPerBurst", AudioEngine.shared.framesPerBurst())
            .toString()
    }.getOrDefault("""{"sampleRate":48000,"channels":1,"framesPerBurst":128}""")

    @JavascriptInterface
    fun pullPcm(maxFrames: Int): String {
        return runCatching {
            val samples = AudioEngine.shared.pullPcm(maxFrames.coerceIn(64, 4096))
            val bytes = ByteBuffer.allocate(samples.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            val floats = bytes.asFloatBuffer()
            for (sample in samples) floats.put(sample)
            JSONObject()
                .put("sampleRate", AudioEngine.shared.sampleRate())
                .put("channels", 1)
                .put("frames", samples.size)
                .put(
                    "data",
                    Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
                )
                .toString()
        }.getOrDefault("""{"sampleRate":48000,"channels":1,"frames":0,"data":""}""")
    }

    @JavascriptInterface
    fun clear(): Boolean = runCatching {
        AudioEngine.shared.clearPcm()
        true
    }.getOrDefault(false)

    /**
     * Console realtime: JavaScript (audio_processor.js) đẩy log về app để hiển thị
     * trong panel Console của Settings và phục vụ nút "Sao chép tất cả".
     */
    @JavascriptInterface
    fun postLog(level: String, message: String) {
        ConsoleLogStore.log(level.ifBlank { "JS" }, message)
    }

    /**
     * Chẩn đoán một phát: tình trạng engine, route, meter đầu vào và số frame PCM
     * đang chờ trong queue bơm sang WebView — dùng cho console debug realtime.
     */
    @JavascriptInterface
    fun diagnose(): String {
        val payload = JSONObject()
        try {
            payload.put("engineRunning", runCatching { AudioEngine.shared.running }.getOrDefault(false))
            payload.put("nativeHealthy", isRunning())
            payload.put("routeMode", routeMode)
            try {
                val levels = AudioEngine.shared.levels
                payload.put("inputDb", levels.inputDb.toDouble())
                payload.put("outputDb", levels.outputDb.toDouble())
            } catch (_: Exception) {
                payload.put("inputDb", -100.0)
                payload.put("outputDb", -100.0)
            }
            payload.put("queuedFrames", runCatching { AudioEngine.shared.pullPcm(0).size }.getOrDefault(0))
            payload.put("workletInstalled", false)
        } catch (error: Exception) {
            Log.w("NativePcmBridge", "diagnose failed", error)
        }
        return payload.toString()
    }

    companion object {
        @Volatile
        private var routeMode: String = "off"

        fun setRouteMode(mode: String) {
            routeMode = if (mode == "software" || mode == "privileged") mode else "off"
            ConsoleLogStore.log("AudioRoute", "route mode -> $routeMode")
        }
    }
}
