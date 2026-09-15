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

/**
 * Best-effort, non-blocking Shizuku state holder. No command is executed during construction or
 * app startup. Privileged work is requested only by VirtualMicService after the user taps Start.
 */
class ShizukuManager {
    @Volatile var state: ShizukuState = ShizukuState.UNAVAILABLE
        private set
    private var initialized = false
    private var listener: ((ShizukuState) -> Unit)? = null

    @Synchronized
    fun init(onStateChanged: (ShizukuState) -> Unit = {}) {
        listener = onStateChanged
        if (!initialized) {
            initialized = true
            try {
                Shizuku.addBinderReceivedListener { refresh() }
                Shizuku.addBinderDeadListener {
                    state = ShizukuState.UNAVAILABLE
                    listener?.invoke(state)
                }
                Shizuku.addRequestPermissionResultListener { _, result ->
                    state = if (result == PackageManager.PERMISSION_GRANTED) {
                        ShizukuState.READY
                    } else {
                        ShizukuState.NEED_GRANT
                    }
                    listener?.invoke(state)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Shizuku listeners unavailable", error)
            }
        }
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) refresh()
        else {
            state = ShizukuState.UNAVAILABLE
            listener?.invoke(state)
        }
    }

    private fun refresh() {
        state = try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.READY
            } else {
                ShizukuState.NEED_GRANT
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to inspect Shizuku permission", error)
            ShizukuState.UNAVAILABLE
        }
        listener?.invoke(state)
    }

    fun requestPermission(): Boolean {
        if (state != ShizukuState.NEED_GRANT) return false
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Unable to request Shizuku permission", error)
            false
        }
    }

    fun exec(command: String): ShizukuCommandResult {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return ShizukuCommandResult.NotReady
        }
        if (state != ShizukuState.READY) return ShizukuCommandResult.PermissionDenied
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) ShizukuCommandResult.Success(output, error, exitCode)
            else ShizukuCommandResult.Failed(error.trim().ifEmpty { "Command failed" }, exitCode)
        } catch (error: IOException) {
            ShizukuCommandResult.Failed(error.message ?: "I/O error")
        } catch (error: ReflectiveOperationException) {
            ShizukuCommandResult.Failed(error.message ?: "Unable to create process")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ShizukuCommandResult.Failed("Command interrupted")
        } catch (error: SecurityException) {
            ShizukuCommandResult.PermissionDenied
        }
    }

    fun hasAlsaLoopbackSupport(): Boolean {
        exec("modprobe snd-aloop pcm_substreams=2 2>&1")
        val cards = exec("cat /proc/asound/cards 2>/dev/null")
        return cards is ShizukuCommandResult.Success &&
            cards.output.contains("Loopback", ignoreCase = true)
    }

    fun routeToLoopback(): Boolean {
        val result = exec("tinymix 'Loopback Mixer' 1 2>&1")
        return result is ShizukuCommandResult.Success
    }

    fun setAppOpsMicDefault(packageName: String): Boolean =
        exec("appops set $packageName RECORD_AUDIO allow") is ShizukuCommandResult.Success

    companion object {
        private const val TAG = "WebViewTemplate.Shizuku"
        private const val REQUEST_CODE = 42
    }
}
