package com.webviewtemplate.webviewtemplate

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlin.system.exitProcess

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        findViewById<TextView>(R.id.crash_stack_trace).apply {
            typeface = Typeface.MONOSPACE
            text = intent.getStringExtra(CrashHandler.EXTRA_STACK_TRACE)
                ?: "Không có thông tin stack trace."
            setTextIsSelectable(true)
        }

        findViewById<Button>(R.id.crash_refresh).setOnClickListener {
            val restartIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(restartIntent)
            finish()
        }
        findViewById<Button>(R.id.crash_exit).setOnClickListener {
            finishAffinity()
            exitProcess(0)
        }
    }
}
