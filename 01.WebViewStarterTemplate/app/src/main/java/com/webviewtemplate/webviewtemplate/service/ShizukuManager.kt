package com.webviewtemplate.webviewtemplate.service

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

enum class ShizukuState { UNAVAILABLE, NEED_GRANT, READY }

class ShizukuManager {
    @Volatile var state: ShizukuState = ShizukuState.UNAVAILABLE
        private set
    private var initialized = false

    fun init(onStateChanged: (ShizukuState) -> Unit) {
        if (!initialized) {
            initialized = true
            Shizuku.addBinderReceivedListener { refresh(onStateChanged) }
            Shizuku.addBinderDeadListener {
                state = ShizukuState.UNAVAILABLE
                onStateChanged(state)
            }
            Shizuku.addRequestPermissionResultListener { _, result ->
                state = if (result == PackageManager.PERMISSION_GRANTED) {
                    ShizukuState.READY
                } else {
                    ShizukuState.NEED_GRANT
                }
                onStateChanged(state)
            }
        }
        if (Shizuku.pingBinder()) refresh(onStateChanged)
        else {
            state = ShizukuState.UNAVAILABLE
            onStateChanged(state)
        }
    }

    private fun refresh(onStateChanged: (ShizukuState) -> Unit) {
        state = try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.READY
            } else {
                ShizukuState.NEED_GRANT
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to inspect Shizuku permission", t)
            ShizukuState.UNAVAILABLE
        }
        onStateChanged(state)
    }

    fun requestPermission() {
        if (state != ShizukuState.NEED_GRANT) {
            throw RuntimeException(
                "Shizuku permission cannot be requested because Shizuku is not running. " +
                    "Start Shizuku using Wireless debugging or ADB."
            )
        }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun requireReady() {
        if (state != ShizukuState.READY) {
            throw RuntimeException(
                "Shizuku is not ready (state=$state). Start Shizuku and grant this app API permission."
            )
        }
    }

    fun exec(command: String): String {
        requireReady()
        // Keep the command path identical to the reference MicUp implementation:
        // Shizuku permission is the required gate before invoking the device shell.
        val process = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (t: Throwable) {
            throw RuntimeException("Shizuku could not execute `$command`.", t)
        }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw RuntimeException(
                "Shizuku command failed ($exit): `$command`${error.trim().takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
            )
        }
        return output
    }

    fun requireAlsaLoopback() {
        val load = exec("modprobe snd-aloop pcm_substreams=2 2>&1 || " +
            "insmod /system/lib/modules/snd-aloop.ko pcm_substreams=2 2>&1 || true")
        val cards = exec("cat /proc/asound/cards 2>/dev/null")
        if (!cards.contains("Loopback", ignoreCase = true)) {
            throw RuntimeException(
                "ALSA snd-aloop is unavailable. Module probe output: ${load.trim().take(240)}"
            )
        }
    }

    fun routeToLoopback(sampleRate: Int) {
        val tinymix = exec("command -v tinymix").trim()
        if (tinymix.isEmpty()) {
            throw RuntimeException("Shizuku route failed: tinymix is not installed on this device.")
        }
        val result = exec("$tinymix 'Loopback Mixer' 1 2>&1")
        if (result.contains("error", ignoreCase = true) ||
            result.contains("invalid", ignoreCase = true)
        ) {
            throw RuntimeException("ALSA loopback mixer route failed at ${sampleRate}Hz: ${result.trim()}")
        }
    }

    fun setAppOpsMicDefault(packageName: String) {
        exec("appops set $packageName RECORD_AUDIO allow")
    }

    companion object {
        private const val TAG = "WebViewTemplate.Shizuku"
        private const val REQUEST_CODE = 42
    }
}
