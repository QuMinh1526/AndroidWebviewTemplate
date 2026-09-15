package com.webviewtemplate.webviewtemplate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.webviewtemplate.webviewtemplate.databinding.ActivityMainBinding
import com.webviewtemplate.webviewtemplate.ui.AudioSettings
import com.webviewtemplate.webviewtemplate.ui.MicSettingsSheet
import com.webviewtemplate.webviewtemplate.ui.StudioTheme
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private val recordAudioRequestCode = 1001
    private val preferencesName = "audio_preferences"
    private val defaultUrl = "https://discord.com/app"
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var preferences: SharedPreferences
    private val levelHandler = Handler(Looper.getMainLooper())
    private var settingsDialog: Dialog? = null
    private var sheetSettings by mutableStateOf(AudioSettings())

    private val levelPoller = object : Runnable {
        override fun run() {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "(function(){return JSON.stringify({levels:window.__getLevels?window.__getLevels():{},info:window.__getAudioInfo?window.__getAudioInfo():{}})})()"
                ) { result ->
                    parseMetering(result)
                }
            }
            levelHandler.postDelayed(this, 100)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webView
        preferences = getSharedPreferences(preferencesName, MODE_PRIVATE)
        sheetSettings = readSettings()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioRequestCode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                if (audio.isNotEmpty()) runOnUiThread { request.grant(audio) }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                binding.pageProgress.progress = 0
                binding.pageProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.pageProgress.visibility = View.GONE
                binding.addressBar.setText(view.url ?: url)
                val processor = assets.open("audio_processor.js").bufferedReader().use { it.readText() }
                view.evaluateJavascript(processor) { applyAudioSettings() }
            }
        }

        binding.btnGo.setOnClickListener { loadUrlSmart(binding.addressBar.text.toString()) }
        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) loadUrlSmart(binding.addressBar.text.toString())
            go
        }
        binding.btnSettings.setOnClickListener { showMicSettings() }
        binding.addressBar.setText(defaultUrl)
        loadUrlSmart(defaultUrl)
        levelHandler.post(levelPoller)
    }

    private fun loadUrlSmart(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val url = when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.matches(Regex("""^[^\s/]+\.[^\s/]+(?:/.*)?$""")) -> "https://$value"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
        webView.loadUrl(url)
    }

    private fun showMicSettings() {
        if (settingsDialog?.isShowing == true) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ComposeView(this).apply {
            setContent {
                StudioTheme {
                    MicSettingsSheet(
                        settings = sheetSettings,
                        onChange = {
                            sheetSettings = it
                            saveSettings(it)
                            applyAudioSettings()
                        },
                        onClose = { dialog.dismiss() }
                    )
                }
            }
        })
        dialog.setOnShowListener {
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        settingsDialog = dialog
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun applyAudioSettings() {
        if (!::webView.isInitialized) return
        val s = sheetSettings
        val eq = s.eq.joinToString(",") { it.toString() }
        val script = """
            (function(){
              if (!window.__setGain) return;
              window.__setAudioProcessingEnabled(${s.enabled});
              window.__setNoiseGate(${s.gateEnabled},${s.gateThreshold},${s.gateAttack},${s.gateRelease});
              window.__setEq(${s.eqEnabled},[$eq]);
              window.__setCompressor(${s.compressorEnabled},${s.compThreshold},${s.compRatio},${s.compAttack},${s.compRelease},${s.compMakeup});
              window.__setReverb(${s.reverbEnabled},${s.reverbMix},${s.reverbRoom},${s.reverbDamping});
              window.__setEcho(${s.echoEnabled},${s.echoAmount});
              window.__setGain(${s.gainEnabled},${s.gain});
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun parseMetering(raw: String) {
        try {
            val decoded = JSONTokener(raw).nextValue() as? String ?: return
            val json = JSONObject(decoded)
            val levels = json.optJSONObject("levels") ?: return
            val info = json.optJSONObject("info")
            sheetSettings = sheetSettings.copy(
                inputDb = levels.optDouble("inDb", -60.0).toFloat(),
                outputDb = levels.optDouble("outDb", -60.0).toFloat(),
                sampleRate = info?.optInt("sampleRate", 0)?.takeIf { it > 0 }?.toString() ?: sheetSettings.sampleRate,
                latency = info?.optDouble("latencyMs", 0.0)?.takeIf { it > 0 }?.let { "%.1f".format(Locale.US, it) } ?: sheetSettings.latency,
                bufferSize = info?.optInt("bufferSize", 0)?.takeIf { it > 0 }?.toString() ?: sheetSettings.bufferSize
            )
        } catch (_: Exception) {
            // WebView returns an escaped JSON string while a page is still initializing.
        }
    }

    private fun readSettings() = AudioSettings(
        enabled = preferences.getBoolean("enabled", true),
        gateEnabled = preferences.getBoolean("gate_enabled", true),
        gateThreshold = preferences.getFloat("gate_threshold", -45f),
        gateAttack = preferences.getFloat("gate_attack", 10f),
        gateRelease = preferences.getFloat("gate_release", 180f),
        eqEnabled = preferences.getBoolean("eq_enabled", true),
        eq = List(10) { preferences.getFloat("eq_$it", 0f) },
        compressorEnabled = preferences.getBoolean("comp_enabled", true),
        compThreshold = preferences.getFloat("comp_threshold", -24f),
        compRatio = preferences.getFloat("comp_ratio", 4f),
        compAttack = preferences.getFloat("comp_attack", 10f),
        compRelease = preferences.getFloat("comp_release", 180f),
        compMakeup = preferences.getFloat("comp_makeup", 0f),
        reverbEnabled = preferences.getBoolean("reverb_enabled", false),
        reverbMix = preferences.getFloat("reverb_mix", .2f),
        reverbRoom = preferences.getFloat("reverb_room", .5f),
        reverbDamping = preferences.getFloat("reverb_damping", .5f),
        echoEnabled = preferences.getBoolean("echo_enabled", false),
        echoAmount = preferences.getFloat("echo_amount", 0f),
        gainEnabled = preferences.getBoolean("gain_enabled", true),
        gain = preferences.getFloat("gain", 1f)
    )

    private fun saveSettings(s: AudioSettings) {
        preferences.edit().apply {
            putBoolean("enabled", s.enabled)
            putBoolean("gate_enabled", s.gateEnabled).putFloat("gate_threshold", s.gateThreshold)
                .putFloat("gate_attack", s.gateAttack).putFloat("gate_release", s.gateRelease)
            putBoolean("eq_enabled", s.eqEnabled)
            s.eq.forEachIndexed { i, value -> putFloat("eq_$i", value) }
            putBoolean("comp_enabled", s.compressorEnabled).putFloat("comp_threshold", s.compThreshold)
                .putFloat("comp_ratio", s.compRatio).putFloat("comp_attack", s.compAttack)
                .putFloat("comp_release", s.compRelease).putFloat("comp_makeup", s.compMakeup)
            putBoolean("reverb_enabled", s.reverbEnabled).putFloat("reverb_mix", s.reverbMix)
                .putFloat("reverb_room", s.reverbRoom).putFloat("reverb_damping", s.reverbDamping)
            putBoolean("echo_enabled", s.echoEnabled).putFloat("echo_amount", s.echoAmount)
            putBoolean("gain_enabled", s.gainEnabled).putFloat("gain", s.gain).apply()
        }
    }

    override fun onDestroy() {
        levelHandler.removeCallbacks(levelPoller)
        settingsDialog?.dismiss()
        super.onDestroy()
    }
}
