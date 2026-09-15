package com.webviewtemplate.webviewtemplate

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val stackTrace = Log.getStackTraceString(throwable)
        val crashIntent = Intent(context, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_STACK_TRACE, stackTrace)
        }

        try {
            context.startActivity(crashIntent)
        } catch (startActivityError: Exception) {
            Log.e(TAG, "Không thể mở CrashActivity", startActivityError)
        } finally {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        const val EXTRA_STACK_TRACE = "crash_stack_trace"
        private const val TAG = "CrashHandler"
    }
}
