package com.webviewtemplate.webviewtemplate.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.webviewtemplate.webviewtemplate.audio.AudioEngine

class AudioProcessingService : Service() {
    override fun onCreate() {
        super.onCreate()
        AudioEngine.shared.start()
    }

    override fun onDestroy() {
        AudioEngine.shared.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, AudioProcessingService::class.java))
        }
    }
}
