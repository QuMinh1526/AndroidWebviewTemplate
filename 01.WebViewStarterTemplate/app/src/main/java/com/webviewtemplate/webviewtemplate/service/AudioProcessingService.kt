package com.webviewtemplate.webviewtemplate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.webviewtemplate.webviewtemplate.MainActivity
import com.webviewtemplate.webviewtemplate.audio.AudioEngine
import com.webviewtemplate.webviewtemplate.audio.NativePcmBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the one native capture/processing stream. It is deliberately started only by an
 * explicit user action; Shizuku and ALSA probing happen later in VirtualMicService.
 */
class AudioProcessingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val notification = buildNotification("Starting audio engine")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebViewTemplate:Audio")
                ?.apply { acquire() }
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Unable to enter microphone foreground state", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (stopping.get()) return START_NOT_STICKY

        try {
            val engine = AudioEngine.shared
            val inputId = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt("input_device_id", -1)
            engine.start()
            // sound.md #2: MODE_IN_COMMUNICATION kích hoạt đường lọc phần cứng (hardware AEC)
            // cho WebRTC voice, tránh thu lẫn tiếng loa vào mic gây bể tiếng.
            forceCommunicationMode(true)
            NativePcmBridge.setRouteMode("software")
            if (inputId != -1) engine.setInputDevice(inputId)
            SoftwareLoopback.start(this)
            when (getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString("output_mode", "speaker")) {
                "webview" -> SoftwareLoopback.setMonitorEnabled(false)
                "earpiece" -> {
                    SoftwareLoopback.setMonitorEnabled(true)
                    SoftwareLoopback.setOutputDevice(
                        this,
                        SoftwareLoopback.findOutputDeviceId(
                            this,
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        )
                    )
                }
                else -> {
                    SoftwareLoopback.setMonitorEnabled(true)
                    SoftwareLoopback.setOutputDevice(
                        this,
                        SoftwareLoopback.findOutputDeviceId(
                            this,
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        )
                    )
                }
            }
            running.set(true)
            monitorThread = Thread({
                while (running.get() && !stopping.get()) {
                    val samples = engine.pullMonitorPcm(1024)
                    if (samples.isNotEmpty()) {
                        SoftwareLoopback.writeProcessed(samples, samples.size)
                    } else {
                        try {
                            Thread.sleep(4)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }, "WebView-Monitor-Pcm-Pump").also {
                it.priority = Thread.NORM_PRIORITY
                it.start()
            }
            onEngineReady?.invoke(engine)
            updateNotification("Active · software monitor")
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Audio engine failed to start", error)
            onEngineStartFailed?.invoke(error)
            updateNotification("Audio unavailable")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        running.set(false)
        NativePcmBridge.setRouteMode("off")
        onEngineReady = null
        onEngineStartFailed = null
        monitorThread?.interrupt()
        monitorThread?.join(500)
        monitorThread = null
        forceCommunicationMode(false)
        SoftwareLoopback.stop()
        AudioEngine.shared.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * sound.md #2: ép/mở chế độ đàm thoại MODE_IN_COMMUNICATION cho luồng voice.
     * fixmicoutput.md: khi đang ở MODE_IN_COMMUNICATION phải bật speaker để âm
     * thanh chạy ra loa ngoài (đường đàm thoại mặc định chỉ chạy tai nghe);
     * đồng thời tôn trọng output mode người dùng đã chọn (webview = tắt monitor).     */
    private fun forceCommunicationMode(enabled: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = if (enabled) {
                AudioManager.MODE_IN_COMMUNICATION
            } else {
                AudioManager.MODE_NORMAL
            }
            if (enabled) {
                val webviewOnly = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getString("output_mode", "speaker") == "webview"
                audioManager.isSpeakerphoneOn = !webviewOnly
            } else {
                audioManager.isSpeakerphoneOn = false
            }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Unable to switch audio mode", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "WebView microphone processing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioProcessingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("WebView microphone")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "WebViewTemplate.AudioService"
        private const val CHANNEL_ID = "webview_audio"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.webviewtemplate.webviewtemplate.STOP_AUDIO"
        private const val PREFERENCES = "audio_preferences"
        private val stopping = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        @Volatile var onEngineReady: ((AudioEngine) -> Unit)? = null
        @Volatile var onEngineStartFailed: ((Throwable) -> Unit)? = null

        fun start(context: Context) {
            stopping.set(false)
            val intent = Intent(context, AudioProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioProcessingService::class.java))
        }
    }
}
