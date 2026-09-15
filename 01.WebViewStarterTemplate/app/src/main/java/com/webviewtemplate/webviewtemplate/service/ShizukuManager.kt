package com.webviewtemplate.webviewtemplate.service

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.IOException

enum class ShizukuState { UNAVAILABLE, NEED_GRANT, READY }

sealed interface ShizukuCommandResult {
    data class Success(val output: String, val error: String = "", val exitCode: Int = 0) :
        ShizukuCommandResult

    data class Failed(val message: String, val exitCode: Int = -1) : ShizukuCommandResult
    object NotReady : ShizukuCommandResult
    object PermissionDenied : ShizukuCommandResult
}

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
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to inspect Shizuku permission", error)
            ShizukuState.UNAVAILABLE
        }
        onStateChanged(state)
    }

    fun requestPermission(): Boolean {
        if (!Shizuku.pingBinder() || state != ShizukuState.NEED_GRANT) return false
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to request Shizuku permission", error)
            false
        }
    }

    fun exec(command: String): ShizukuCommandResult {
        if (!Shizuku.pingBinder()) return ShizukuCommandResult.NotReady
        if (state != ShizukuState.READY) return ShizukuCommandResult.PermissionDenied
        return try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = newProcess.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                ShizukuCommandResult.Success(output, error, exitCode)
            } else {
                ShizukuCommandResult.Failed(
                    error.trim().ifEmpty { "Command failed: $command" },
                    exitCode
                )
            }
        } catch (error: IOException) {
            ShizukuCommandResult.Failed(error.message ?: "I/O error while executing command")
        } catch (error: ReflectiveOperationException) {
            ShizukuCommandResult.Failed(error.message ?: "Unable to create Shizuku process")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ShizukuCommandResult.Failed("Command interrupted")
        } catch (error: SecurityException) {
            ShizukuCommandResult.PermissionDenied
        }
    }

    fun requireAlsaLoopback(): ShizukuCommandResult {
        val load = exec("modprobe snd-aloop pcm_substreams=2 2>&1")
        if (load is ShizukuCommandResult.NotReady ||
            load is ShizukuCommandResult.PermissionDenied
        ) return load

        val cards = exec("cat /proc/asound/cards 2>/dev/null")
        return if (cards is ShizukuCommandResult.Success &&
            cards.output.contains("Loopback", ignoreCase = true)
        ) {
            cards
        } else if (cards is ShizukuCommandResult.Success) {
            ShizukuCommandResult.Failed("Kernel máy không hỗ trợ ALSA loopback")
        } else {
            cards
        }
    }

    fun routeToLoopback(sampleRate: Int): ShizukuCommandResult {
        val tinymixResult = exec("command -v tinymix")
        if (tinymixResult !is ShizukuCommandResult.Success) return tinymixResult
        val tinymix = tinymixResult.output.trim()
        if (tinymix.isEmpty()) {
            return ShizukuCommandResult.Failed("tinymix is not installed on this device.")
        }
        return exec("$tinymix 'Loopback Mixer' 1 2>&1")
    }

    fun setAppOpsMicDefault(packageName: String): ShizukuCommandResult =
        exec("appops set $packageName RECORD_AUDIO allow")

    companion object {
        private const val TAG = "WebViewTemplate.Shizuku"
        private const val REQUEST_CODE = 42
    }
}
