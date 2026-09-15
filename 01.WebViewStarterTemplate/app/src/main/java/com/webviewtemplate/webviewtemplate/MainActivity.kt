package com.webviewtemplate.webviewtemplate

import android.Manifest
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.webviewtemplate.webviewtemplate.databinding.ActivityMainBinding
import com.webviewtemplate.webviewtemplate.audio.AudioEngine
import com.webviewtemplate.webviewtemplate.service.ShizukuManager
import com.webviewtemplate.webviewtemplate.service.ShizukuState
import com.webviewtemplate.webviewtemplate.service.VirtualMicService
import com.webviewtemplate.webviewtemplate.ui.AudioSettings
import com.webviewtemplate.webviewtemplate.ui.LevelInfo
import com.webviewtemplate.webviewtemplate.ui.MicSettingsSheet
import com.webviewtemplate.webviewtemplate.ui.StudioTheme
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.Locale

class MainActivity : ComponentActivity() {
    // Set true temporarily to verify CrashActivity, then rebuild and launch the app.
    private val enableCrashTest = false
    private val recordAudioRequestCode = 1001
    private val preferencesName = "audio_preferences"
    private val defaultUrl = "https://discord.com/app"
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var preferences: SharedPreferences
    private val levelHandler = Handler(Looper.getMainLooper())
    private var settingsDialog: ComponentDialog? = null
    private var sheetSettings by mutableStateOf(AudioSettings())
    private var levelInfo by mutableStateOf(LevelInfo())
    private val shizukuManager = ShizukuManager()
    private lateinit var virtualMicService: VirtualMicService
    private var nativeStackStarted = false

    private val levelPoller = object : Runnable {
        override fun run() {
            if (nativeStackStarted) {
                AudioEngine.shared.requireHealthy()
                val levels = AudioEngine.shared.levels
                levelInfo = levelInfo.copy(
                    inputDb = levels.inputDb,
                    outputDb = levels.outputDb,
                    sampleRate = AudioEngine.shared.sampleRate().toString(),
                    latency = "%.1f".format(
                        Locale.US,
                        AudioEngine.shared.framesPerBurst() * 2000f / AudioEngine.shared.sampleRate()
                    ),
                    bufferSize = AudioEngine.shared.framesPerBurst().toString()
                )
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
        virtualMicService = VirtualMicService(this, shizukuManager)
        sheetSettings = readSettings()
        if (enableCrashTest) throw RuntimeException("Test crash")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioRequestCode)
        }
        shizukuManager.init { state ->
            runOnUiThread {
                when (state) {
                    ShizukuState.NEED_GRANT -> shizukuManager.requestPermission()
                    ShizukuState.READY -> if (
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) initializeNativeStack()
                    ShizukuState.UNAVAILABLE -> throw RuntimeException(
                        "Shizuku is unavailable. Start Shizuku through Wireless debugging or ADB; " +
                            "this app intentionally has no non-native audio fallback."
                    )
                }
            }
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

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.pageProgress.progress = newProgress
                binding.progressText.text = "$newProgress%"
                val visible = newProgress < 100
                binding.pageProgress.visibility = if (visible) View.VISIBLE else View.GONE
                binding.progressText.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.pageProgress.progress = 0
                binding.progressText.text = "0%"
                binding.pageProgress.visibility = View.VISIBLE
                binding.progressText.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.pageProgress.visibility = View.GONE
                binding.progressText.visibility = View.GONE
                binding.addressBar.setText(view.url ?: url)
                // Keep WebView as the browser UI. Native Oboe/Shizuku is authoritative;
                // do not install the legacy JavaScript audio-processing fallback.
                applyAudioSettings()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                return handleUrlOverride(request.url.toString())
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlOverride(url)
            }
        }

        binding.btnGo.setOnClickListener { loadUrlSmart(binding.addressBar.text.toString()) }
        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) loadUrlSmart(binding.addressBar.text.toString())
            go
        }
        binding.btnSettings.setOnClickListener {
            try {
                showMicSettings()
            } catch (e: Exception) {
                Log.e("MicSettingsCrash", "Lỗi khi mở settings", e)
            }
        }
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
        val dialog = ComponentDialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(dialog)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(dialog)
            setContent {
                StudioTheme {
                    MicSettingsSheet(
                        settings = sheetSettings,
                        levels = levelInfo,
                        onChange = {
                            sheetSettings = it
                            saveSettings(it)
                            applyAudioSettings()
                        },
                        onClose = { dialog.dismiss() }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setOnShowListener {
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        settingsDialog = dialog
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun applyAudioSettings() {
        val s = sheetSettings
        if (nativeStackStarted) AudioEngine.shared.apply(s)
        if (!::webView.isInitialized || isFinishing) return
        val eq = s.eq.joinToString(",") { it.toString() }
        val script = """
            (function(){
              if (!window.__setGain) return;
              window.__setAudioProcessingEnabled(${s.enabled});
              window.__setNoiseGate(${s.gateEnabled},${s.gateThreshold},${s.gateAttack},${s.gateRelease});
              window.__setEq(${s.eqEnabled},[$eq]);
              window.__setCompressor(${s.compressorEnabled},${s.compThreshold},${s.compRatio},${s.compAttack},${s.compRelease},${s.compMakeup});
              window.__setReverb(${s.reverbEnabled},${s.reverbMix},${s.reverbRoom},${s.reverbDamping});
              window.__setPitch(${s.pitchEnabled},${s.pitchSemitones});
              window.__setEcho(${s.echoEnabled},${s.echoAmount});
              window.__setGain(${s.gainEnabled},${s.gain});
            })();
        """.trimIndent()
        safeEvaluateJavascript(webView, script)
    }

    private fun initializeNativeStack() {
        if (nativeStackStarted) return
        shizukuManager.requireReady()
        try {
            AudioEngine.shared.start()
            virtualMicService.activate(AudioEngine.shared)
            nativeStackStarted = true
            applyAudioSettings()
        } catch (t: Throwable) {
            AudioEngine.shared.stop()
            throw RuntimeException(
                "Native audio/Shizuku ALSA loopback initialization failed. " +
                    "No software audio fallback is available.",
                t
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != recordAudioRequestCode) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            throw RuntimeException("RECORD_AUDIO permission is required for the native audio engine.")
        }
        if (shizukuManager.state == ShizukuState.READY) initializeNativeStack()
    }

    private fun safeEvaluateJavascript(view: WebView, script: String, result: ((String) -> Unit)? = null) {
        try {
            if (!isFinishing) view.evaluateJavascript(script, result)
        } catch (e: Exception) {
            Log.e("MicSettingsCrash", "Lỗi evaluateJavascript", e)
        }
    }

    private fun handleUrlOverride(url: String): Boolean {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            return false
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w("WebViewApp", "Không mở được scheme lạ: $url", e)
        }
        return true
    }

    private fun parseMetering(raw: String) {
        try {
            val decoded = JSONTokener(raw).nextValue() as? String ?: return
            val json = JSONObject(decoded)
            val levels = json.optJSONObject("levels") ?: return
            val info = json.optJSONObject("info")
            levelInfo = levelInfo.copy(
                inputDb = levels.optDouble("inDb", -60.0).toFloat(),
                outputDb = levels.optDouble("outDb", -60.0).toFloat(),
                sampleRate = info?.optInt("sampleRate", 0)?.takeIf { it > 0 }?.toString() ?: levelInfo.sampleRate,
                latency = info?.optDouble("latencyMs", 0.0)?.takeIf { it > 0 }?.let { "%.1f".format(Locale.US, it) } ?: levelInfo.latency,
                bufferSize = info?.optInt("bufferSize", 0)?.takeIf { it > 0 }?.toString() ?: levelInfo.bufferSize
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
        pitchEnabled = preferences.getBoolean("pitch_enabled", false),
        pitchSemitones = preferences.getFloat("pitch_semitones", 0f),
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
            putBoolean("pitch_enabled", s.pitchEnabled).putFloat("pitch_semitones", s.pitchSemitones)
            putBoolean("echo_enabled", s.echoEnabled).putFloat("echo_amount", s.echoAmount)
            putBoolean("gain_enabled", s.gainEnabled).putFloat("gain", s.gain).apply()
        }
    }

    override fun onDestroy() {
        levelHandler.removeCallbacks(levelPoller)
        settingsDialog?.dismiss()
        if (nativeStackStarted) AudioEngine.shared.stop()
        super.onDestroy()
    }
}
