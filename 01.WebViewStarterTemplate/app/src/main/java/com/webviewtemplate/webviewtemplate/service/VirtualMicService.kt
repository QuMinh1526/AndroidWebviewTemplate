package com.webviewtemplate.webviewtemplate.service

import android.content.Context
import com.webviewtemplate.webviewtemplate.audio.AudioEngine

class VirtualMicService(
    private val context: Context,
    private val shizuku: ShizukuManager,
) {
    fun activate(engine: AudioEngine): VirtualMicResult {
        return try {
            when (val loopback = shizuku.requireAlsaLoopback()) {
                is ShizukuCommandResult.NotReady ->
                    VirtualMicResult.NotReady
                is ShizukuCommandResult.PermissionDenied ->
                    VirtualMicResult.PermissionDenied
                is ShizukuCommandResult.Failed ->
                    VirtualMicResult.KernelUnsupported(loopback.message)
                is ShizukuCommandResult.Success -> {
                    when (val route = shizuku.routeToLoopback(engine.sampleRate())) {
                        is ShizukuCommandResult.Success -> {
                            when (val appOps = shizuku.setAppOpsMicDefault(context.packageName)) {
                                is ShizukuCommandResult.Success -> VirtualMicResult.Success
                                is ShizukuCommandResult.PermissionDenied ->
                                    VirtualMicResult.PermissionDenied
                                is ShizukuCommandResult.NotReady ->
                                    VirtualMicResult.NotReady
                                is ShizukuCommandResult.Failed ->
                                    VirtualMicResult.Failed(appOps.message)
                            }
                        }
                        is ShizukuCommandResult.PermissionDenied ->
                            VirtualMicResult.PermissionDenied
                        is ShizukuCommandResult.NotReady ->
                            VirtualMicResult.NotReady
                        is ShizukuCommandResult.Failed ->
                            VirtualMicResult.Failed(route.message)
                    }
                }
            }
        } catch (error: Exception) {
            VirtualMicResult.Failed(error.message ?: "Không thể bật Virtual Mic")
        }
    }
}

sealed interface VirtualMicResult {
    object NotReady : VirtualMicResult
    object PermissionDenied : VirtualMicResult
    data class KernelUnsupported(val reason: String) : VirtualMicResult
    data class Failed(val reason: String) : VirtualMicResult
    object Success : VirtualMicResult
}
