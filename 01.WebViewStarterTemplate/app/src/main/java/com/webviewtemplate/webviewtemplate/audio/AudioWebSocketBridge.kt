package com.webviewtemplate.webviewtemplate.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioWebSocketBridge {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var captureThread: Thread? = null
    private var client: Socket? = null
    private var processor: NativePcmProcessor? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Audio WebSocket bridge is already running" }
        processor = NativePcmProcessor()
        server = ServerSocket(PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
        captureThread = thread(name = "AudioRecord-WebSocket") {
            captureLoop()
        }
    }

    private fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "AudioRecord does not support 48 kHz mono PCM16" }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, FRAME_SAMPLES * 2)
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
        val samples = ShortArray(FRAME_SAMPLES)
        try {
            audioRecord.startRecording()
            client = server!!.accept()
            handshake(client!!)
            while (running.get()) {
                val count = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("AudioRecord.read failed: $count")
                if (count > 0) {
                    processor!!.process(samples, count)
                    sendBinary(client!!, samples, count)
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
            client?.close()
            server?.close()
            processor?.close()
        }
    }

    private fun handshake(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        var key: String? = null
        while (true) {
            val line = reader.readLine() ?: error("WebSocket handshake closed")
            if (line.isEmpty()) break
            if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim()
        }
        val accept = java.security.MessageDigest.getInstance("SHA-1")
            .digest(((key ?: error("Missing WebSocket key")) + WS_GUID).toByteArray())
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${Base64.getEncoder().encodeToString(accept)}\r\n\r\n"
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII))
        writer.write(response)
        writer.flush()
    }

    private fun sendBinary(socket: Socket, samples: ShortArray, count: Int) {
        val payload = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { payload.putShort(samples[it]) }
        val length = payload.position()
        val out = socket.getOutputStream()
        out.write(0x82)
        when {
            length < 126 -> out.write(length)
            else -> {
                out.write(126)
                out.write((length ushr 8) and 0xff)
                out.write(length and 0xff)
            }
        }
        out.write(payload.array(), 0, length)
        out.flush()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        client?.close()
        server?.close()
        captureThread?.join(1000)
        captureThread = null
    }

    companion object {
        const val PORT = 8765
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 480
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
