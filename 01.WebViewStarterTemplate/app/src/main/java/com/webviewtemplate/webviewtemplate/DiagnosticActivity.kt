package com.webviewtemplate.webviewtemplate

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.Executors

class DiagnosticActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var resultText: TextView
    private lateinit var requestButton: Button

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            runOnUiThread {
                updateRequestButton()
                if (result == PackageManager.PERMISSION_GRANTED) {
                    runDiagnostics()
                } else {
                    resultText.text = "Quyền Shizuku bị từ chối (result=$result)."
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resultText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(24, 16, 24, 16)
        }
        requestButton = Button(this).apply {
            text = "Yêu cầu quyền Shizuku"
            setOnClickListener { requestShizukuPermission() }
        }
        val refreshButton = Button(this).apply {
            text = "Chạy lại chẩn đoán"
            setOnClickListener { runDiagnostics() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(requestButton)
            addView(refreshButton)
            addView(
                resultText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(ScrollView(this).apply { addView(content) })

        Shizuku.addRequestPermissionResultListener(permissionListener)
        updateRequestButton()
        runDiagnostics()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun updateRequestButton() {
        requestButton.visibility =
            if (hasShizukuPermission()) View.GONE else View.VISIBLE
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            resultText.text = "Shizuku chưa kết nối. Hãy khởi động Shizuku trước."
            return
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (error: SecurityException) {
            resultText.text = "Không thể yêu cầu quyền Shizuku: ${error.message}"
        }
    }

    private fun runDiagnostics() {
        resultText.text = "Đang chạy chẩn đoán..."
        updateRequestButton()
        executor.execute {
            val report = buildDiagnosticReport()
            runOnUiThread { resultText.text = report }
        }
    }

    private fun buildDiagnosticReport(): String {
        val binderConnected = Shizuku.pingBinder()
        val permission = try {
            Shizuku.checkSelfPermission()
        } catch (error: SecurityException) {
            PackageManager.PERMISSION_DENIED
        }
        val permissionGranted = permission == PackageManager.PERMISSION_GRANTED
        val report = StringBuilder()
            .appendLine("=== SHIZUKU / ALSA DIAGNOSTIC ===")
            .appendLine("SDK_INT: ${Build.VERSION.SDK_INT}")
            .appendLine("Shizuku binder: ${if (binderConnected) "ĐÃ KẾT NỐI" else "CHƯA KẾT NỐI"}")
            .appendLine(
                "Shizuku permission: ${
                    if (permissionGranted) "ĐÃ CẤP" else "CHƯA ĐƯỢC CẤP"
                }"
            )

        if (!binderConnected || !permissionGranted) {
            report.appendLine()
                .appendLine("Không chạy được lệnh: cần Shizuku đang hoạt động và đã cấp quyền.")
            return report.toString()
        }

        val before = runShizukuCommand(arrayOf("cat", "/proc/asound/cards"))
        report.appendLine()
            .appendLine("--- cat /proc/asound/cards (trước modprobe) ---")
            .appendLine(before.output)
            .appendLine("stderr: ${before.error.ifBlank { "(trống)" }}")
            .appendLine("exit code: ${before.exitCode}")
            .appendLine(loopbackResult(before.output))

        val modprobe = runShizukuCommand(
            arrayOf("modprobe", "snd-aloop", "pcm_substreams=2")
        )
        report.appendLine()
            .appendLine("--- modprobe snd-aloop pcm_substreams=2 ---")
            .appendLine(modprobe.output.ifBlank { "(trống)" })
            .appendLine("stderr: ${modprobe.error.ifBlank { "(trống)" }}")
            .appendLine("exit code: ${modprobe.exitCode}")

        val after = runShizukuCommand(arrayOf("cat", "/proc/asound/cards"))
        report.appendLine()
            .appendLine("--- cat /proc/asound/cards (sau modprobe) ---")
            .appendLine(after.output)
            .appendLine("stderr: ${after.error.ifBlank { "(trống)" }}")
            .appendLine("exit code: ${after.exitCode}")
            .appendLine(loopbackResult(after.output))
        return report.toString()
    }

    private fun loopbackResult(output: String): String =
        if (output.contains("Loopback", ignoreCase = true)) {
            "✅ CÓ HỖ TRỢ ALSA LOOPBACK"
        } else {
            "❌ KHÔNG HỖ TRỢ - kernel máy không có snd-aloop"
        }

    private fun runShizukuCommand(command: Array<String>): CommandResult {
        return try {
            // Shizuku 13.1.5 keeps newProcess private, but it is the API used to
            // create its public ShizukuRemoteProcess implementation.
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = newProcess.invoke(null, command, null, null) as Process
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            CommandResult(output, error, exitCode)
        } catch (error: IOException) {
            CommandResult("", error.message ?: error.javaClass.simpleName, -1)
        } catch (error: ReflectiveOperationException) {
            CommandResult("", error.message ?: error.javaClass.simpleName, -1)
        } catch (error: SecurityException) {
            CommandResult("", error.message ?: error.javaClass.simpleName, -1)
        }
    }

    private fun hasShizukuPermission(): Boolean =
        Shizuku.pingBinder() &&
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: SecurityException) {
                false
            }

    private data class CommandResult(
        val output: String,
        val error: String,
        val exitCode: Int
    )

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
