package com.webviewtemplate.webviewtemplate

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
    }
}
