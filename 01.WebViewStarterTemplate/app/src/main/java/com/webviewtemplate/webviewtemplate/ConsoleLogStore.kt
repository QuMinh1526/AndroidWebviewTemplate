package com.webviewtemplate.webviewtemplate

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Bộ đệm log vòng dùng chung cho mọi tầng của app (JavaScript trong WebView,
 * bridge PCM, service audio, engine native, MainActivity) — nguồn dữ liệu cho
 * panel "Console (realtime)" trong Settings và nút "Sao chép tất cả".
 *
 * Thread-safe: JS bridge gọi từ thread riêng của JavascriptInterface, service
 * gọi từ thread nền — mọi listener đều được thông báo trên main thread.
 */
object ConsoleLogStore {
    private const val MAX_LINES = 600

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    /** Nhận từng dòng log mới (đăng trên main thread) và tín hiệu xoá console. */
    interface Listener {
        fun onLogLine(line: String)
        fun onCleared()
    }

    fun log(tag: String, message: String) {
        val clean = message.replace('\n', ' ').take(600)
        val line = "${timeFormat.format(Date())} [$tag] $clean"
        val targets: List<Listener>
        synchronized(this) {
            while (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            targets = ArrayList(listeners)
        }
        mainHandler.post {
            targets.forEach { it.onLogLine(line) }
        }
    }

    val lineCount: Int
        get() = synchronized(this) { lines.size }

    fun recentLines(): List<String> = synchronized(this) { ArrayList(lines) }

    fun snapshot(): String = synchronized(this) { lines.joinToString("\n") }

    fun clear() {
        val targets: List<Listener>
        synchronized(this) {
            lines.clear()
            targets = ArrayList(listeners)
        }
        mainHandler.post { targets.forEach { it.onCleared() } }
    }

    fun addListener(listener: Listener) = synchronized(this) { listeners.add(listener) }

    fun removeListener(listener: Listener) = synchronized(this) { listeners.remove(listener) }
}
