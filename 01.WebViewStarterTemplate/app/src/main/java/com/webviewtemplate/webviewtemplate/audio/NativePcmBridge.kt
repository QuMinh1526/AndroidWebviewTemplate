package com.webviewtemplate.webviewtemplate.audio

import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pull-only bridge for the document-start WebView audio worklet. It consumes the
 * native queue; it never opens or owns an AudioRecord.
 */
class NativePcmBridge {
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

    companion object {
        @Volatile
        private var routeMode: String = "off"

        fun setRouteMode(mode: String) {
            routeMode = if (mode == "software" || mode == "privileged") mode else "off"
        }
    }
}
