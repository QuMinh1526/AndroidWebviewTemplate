package com.webviewtemplate.webviewtemplate.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.webviewtemplate.webviewtemplate.audio.AudioEngine
import com.webviewtemplate.webviewtemplate.audio.NativePcmBridge
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class VirtualMicTier {
    SOFTWARE_LOOPBACK,
    SHIZUKU_ALSA,
    ROOT_ALSA
}

sealed interface VirtualMicResult {
    data class Activated(val tier: VirtualMicTier) : VirtualMicResult
    data class Failed(val reason: String) : VirtualMicResult
}

/**
 * Activates privileged routing asynchronously. SoftwareLoopback remains the safe fallback and
 * is already running when this check begins, so a missing Shizuku/ALSA never breaks startup.
 */
class VirtualMicService(
    private val context: Context,
    private val shizuku: ShizukuManager
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile var activeTier: VirtualMicTier = VirtualMicTier.SOFTWARE_LOOPBACK
        private set

    fun activateAsync(engine: AudioEngine, callback: (VirtualMicResult) -> Unit) {
        executor.execute {
            val result = try {
                val activated = when {
                    RootRouting.isRooted() && RootRouting.hasLoopback() &&
                        RootRouting.route() -> VirtualMicTier.ROOT_ALSA
                    shizuku.state == ShizukuState.READY &&
                        shizuku.hasAlsaLoopbackSupport() &&
                        shizuku.routeToLoopback() &&
                        shizuku.setAppOpsMicDefault(context.packageName) -> VirtualMicTier.SHIZUKU_ALSA
                    else -> VirtualMicTier.SOFTWARE_LOOPBACK
                }
                activeTier = activated
                engine.setNativeInjection(activated != VirtualMicTier.SOFTWARE_LOOPBACK)
                NativePcmBridge.setRouteMode(
                    if (activated == VirtualMicTier.SOFTWARE_LOOPBACK) "software" else "privileged"
                )
                SoftwareLoopback.setMonitorEnabled(
                    activated == VirtualMicTier.SOFTWARE_LOOPBACK &&
                        context.getSharedPreferences("audio_preferences", Context.MODE_PRIVATE)
                            .getBoolean("monitor_enabled", true)
                )
                VirtualMicResult.Activated(activated)
            } catch (error: Exception) {
                engine.setNativeInjection(false)
                activeTier = VirtualMicTier.SOFTWARE_LOOPBACK
                NativePcmBridge.setRouteMode("software")
                SoftwareLoopback.setMonitorEnabled(true)
                VirtualMicResult.Failed(error.message ?: "Privileged route unavailable")
            }
            callback(result)
        }
    }

    fun requestShizukuPermission(): Boolean = shizuku.requestPermission()

    fun close() {
        executor.shutdownNow()
    }

    object RootRouting {
        private val suPaths = listOf(
            "/data/adb/magisk/busybox",
            "/data/adb/ksu/bin/su",
            "/system/xbin/su",
            "/system/bin/su",
            "su"
        )

        fun isRooted(): Boolean {
            if (suPaths.dropLast(1).any { File(it).exists() }) return true
            return run("id")?.contains("uid=0") == true
        }

        fun hasLoopback(): Boolean {
            run("modprobe snd-aloop pcm_substreams=2 2>&1 || " +
                "insmod /system/lib/modules/snd-aloop.ko pcm_substreams=2 2>&1")
            return run("cat /proc/asound/cards 2>/dev/null")
                ?.contains("Loopback", ignoreCase = true) == true
        }

        fun route(): Boolean = run("tinymix 'Loopback Mixer' 1 2>&1") != null

        private fun run(command: String): String? {
            val su = suPaths.firstOrNull { it == "su" || File(it).exists() } ?: return null
            return try {
                val args = if (su.contains("busybox")) arrayOf(su, "sh", "-c", command)
                else arrayOf(su, "-c", command)
                val process = Runtime.getRuntime().exec(args)
                val output = process.inputStream.bufferedReader().readText()
                process.errorStream.bufferedReader().readText()
                process.waitFor()
                output
            } catch (error: Exception) {
                Log.d("WebViewTemplate.Root", "root command failed", error)
                null
            }
        }
    }
}
