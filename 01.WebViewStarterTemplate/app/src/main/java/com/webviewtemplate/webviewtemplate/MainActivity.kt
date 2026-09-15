package com.webviewtemplate.webviewtemplate

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.window.OnBackInvokedDispatcher
import androidx.core.app.ActivityCompat
import com.webviewtemplate.webviewtemplate.databinding.ActivityMainBinding
import java.net.URLEncoder

class MainActivity : Activity() {
    private val recordAudioRequestCode = 1001
    private val preferencesName = "audio_preferences"
    private val defaultUrl = "https://discord.com/app"
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var preferences: SharedPreferences

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webView
        preferences = getSharedPreferences(preferencesName, MODE_PRIVATE)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                recordAudioRequestCode
            )
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        }


        webView.settings.apply {
            domStorageEnabled = true
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val audioResources = request.resources
                    .filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    .toTypedArray()

                if (audioResources.isNotEmpty()) {
                    runOnUiThread { request.grant(audioResources) }
                }
            }
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.pageProgress.progress = 0
                binding.pageProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.pageProgress.progress = 100
                binding.pageProgress.visibility = View.GONE
                binding.addressBar.setText(view.url ?: url)

                val processor = assets.open("audio_processor.js").bufferedReader().use { it.readText() }
                view.evaluateJavascript(processor) {
                    applyAudioSettings()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    binding.pageProgress.visibility = View.GONE
                }
            }
        }

        binding.btnGo.setOnClickListener {
            loadUrlSmart(binding.addressBar.text.toString())
        }
        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                loadUrlSmart(binding.addressBar.text.toString())
                true
            } else {
                false
            }
        }
        binding.btnSettings.setOnClickListener { showAudioSettingsDialog() }

        binding.addressBar.setText(defaultUrl)
        loadUrlSmart(defaultUrl)
    }

    private fun loadUrlSmart(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return

        val url = if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            value
        } else if (value.matches(Regex("""^[^\s/]+\.[^\s/]+(?:/.*)?$"""))) {
            "https://$value"
        } else {
            "https://www.google.com/search?q=" +
                URLEncoder.encode(value, Charsets.UTF_8.name())
        }
        webView.loadUrl(url)
    }

    private fun applyAudioSettings() {
        val enabled = preferences.getBoolean("enabled", true)
        val echoCancellation = preferences.getBoolean("echo_cancellation", true)
        val noiseSuppression = preferences.getBoolean("noise_suppression", true)
        val gain = preferences.getInt("gain", 100) / 100.0
        val script = """
            (function() {
                if (window.__setAudioProcessingEnabled) {
                    window.__setAudioProcessingEnabled($enabled);
                    window.__setEchoCancellation($echoCancellation);
                    window.__setNoiseSuppression($noiseSuppression);
                    window.__setMicGain($gain);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun showAudioSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val enabledSwitch = Switch(this).apply {
            text = "Enable audio processing"
            isChecked = preferences.getBoolean("enabled", true)
        }
        val echoSwitch = Switch(this).apply {
            text = "Echo Cancellation"
            isChecked = preferences.getBoolean("echo_cancellation", true)
        }
        val noiseSwitch = Switch(this).apply {
            text = "Noise Suppression"
            isChecked = preferences.getBoolean("noise_suppression", true)
        }
        val gainLabel = TextView(this)
        val gainSeekBar = SeekBar(this).apply {
            max = 300
            progress = preferences.getInt("gain", 100).coerceIn(0, 300)
        }
        fun updateGainLabel() {
            gainLabel.text = "Gain: ${"%.1f".format(gainSeekBar.progress / 100.0)}x"
        }
        updateGainLabel()

        container.addView(enabledSwitch)
        container.addView(echoSwitch)
        container.addView(noiseSwitch)
        container.addView(gainLabel)
        container.addView(gainSeekBar)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Microphone settings")
            .setView(container)
            .setPositiveButton("Done", null)
            .create()

        fun saveAndApply() {
            preferences.edit()
                .putBoolean("enabled", enabledSwitch.isChecked)
                .putBoolean("echo_cancellation", echoSwitch.isChecked)
                .putBoolean("noise_suppression", noiseSwitch.isChecked)
                .putInt("gain", gainSeekBar.progress)
                .apply()
            applyAudioSettings()
        }
        enabledSwitch.setOnCheckedChangeListener { _, _ -> saveAndApply() }
        echoSwitch.setOnCheckedChangeListener { _, _ -> saveAndApply() }
        noiseSwitch.setOnCheckedChangeListener { _, _ -> saveAndApply() }
        gainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateGainLabel()
                if (fromUser) saveAndApply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        dialog.show()
    }

}
