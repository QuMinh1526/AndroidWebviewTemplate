package com.webviewtemplate.webviewtemplate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.app.DownloadManager
import android.os.Environment
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.PermissionRequest
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.webviewtemplate.webviewtemplate.databinding.ActivityMainBinding
import com.webviewtemplate.webviewtemplate.audio.AudioEngine
import com.webviewtemplate.webviewtemplate.audio.NativePcmBridge
import com.webviewtemplate.webviewtemplate.service.AudioProcessingService
import com.webviewtemplate.webviewtemplate.service.ShizukuManager
import com.webviewtemplate.webviewtemplate.service.SoftwareLoopback
import com.webviewtemplate.webviewtemplate.service.VirtualMicResult
import com.webviewtemplate.webviewtemplate.service.VirtualMicTier
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
    companion object {
        private const val TAG = "WebViewTemplate.Main"
        private const val FILE_CHOOSER_REQUEST_CODE = 2001
    }
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
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var sheetSettings by mutableStateOf(AudioSettings())
    private var levelInfo by mutableStateOf(LevelInfo())
    private var nativeStackStarted = false
    private val shizukuManager = ShizukuManager()
    private lateinit var virtualMicService: VirtualMicService
    private var documentStartAudioInstalled = false
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionResources: Array<String>? = null
    private var popupWebView: WebView? = null
    private var popupDialog: Dialog? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var desktopMode = false
    private lateinit var mobileUserAgent: String

    private val levelPoller = object : Runnable {
        override fun run() {
            if (nativeStackStarted) {
                try {
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
                } catch (error: Exception) {
                    Log.w("WebViewApp", "Audio meter unavailable", error)
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
        desktopMode = preferences.getBoolean("desktop_site", false)
        mobileUserAgent = WebSettings.getDefaultUserAgent(this)
        virtualMicService = VirtualMicService(applicationContext, shizukuManager)
        shizukuManager.init { state ->
            runOnUiThread {
                if (!isFinishing && !AudioProcessingService.running.get()) {
                    binding.audioStatus.text = "Audio idle · Shizuku: ${state.name}"
                }
            }
        }
        sheetSettings = readSettings()
        if (enableCrashTest) throw RuntimeException("Test crash")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }

        configureWebView(webView)
        webView.addJavascriptInterface(NativePcmBridge(), "NativePcmBridge")
        installDocumentStartAudioBridge()
        webView.webChromeClient = createWebChromeClient()
        webView.webViewClient = createWebViewClient(isPopup = false)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
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
        binding.btnDesktopSite.setOnClickListener { setDesktopMode(!desktopMode, reload = true) }
        updateDesktopModeUi()
        binding.btnAudio.setOnClickListener { toggleAudio() }
        binding.btnAudioPanelToggle.setOnClickListener {
            val expanded = binding.audioControls.visibility != View.VISIBLE
            binding.audioControls.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnAudioPanelToggle.text = if (expanded) "Audio -" else "Audio +"
        }
        populateAudioDevices()
        if (savedInstanceState == null) {
            binding.addressBar.setText(defaultUrl)
            loadUrlSmart(defaultUrl)
        } else {
            binding.addressBar.setText(webView.url ?: defaultUrl)
        }
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

    /** Configure only stable WebView/Chromium switches; networking remains Chromium-owned. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            blockNetworkImage = false
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setUseWideViewPort(true)
            loadWithOverviewMode = false
            textZoom = 100
            layoutAlgorithm = if (desktopMode) {
                WebSettings.LayoutAlgorithm.NORMAL
            } else {
                WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            }
            userAgentString = userAgentForMode()
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) offscreenPreRaster = false
        }
        view.setInitialScale(0)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(view, true)
            }
        }
    }

    private fun userAgentForMode(): String {
        if (!desktopMode) return mobileUserAgent
        // Keep the exact Chromium/WebView Chrome version; only change the platform
        // tokens that select the server's desktop representation.
        val chromeToken = Regex("Chrome/[^\\s]+")
            .find(mobileUserAgent)?.value ?: "Chrome/" + Build.VERSION.RELEASE
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $chromeToken Safari/537.36"
    }

    private fun setDesktopMode(enabled: Boolean, reload: Boolean) {
        if (desktopMode == enabled) return
        desktopMode = enabled
        preferences.edit().putBoolean("desktop_site", enabled).apply()
        webView.settings.apply {
            userAgentString = userAgentForMode()
            setUseWideViewPort(true)
            loadWithOverviewMode = false
            layoutAlgorithm = if (enabled) {
                WebSettings.LayoutAlgorithm.NORMAL
            } else {
                WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            }
        }
        webView.setInitialScale(0)
        popupWebView?.let { popup ->
            popup.settings.userAgentString = userAgentForMode()
            popup.settings.setUseWideViewPort(true)
            popup.settings.loadWithOverviewMode = false
            popup.setInitialScale(0)
            popup.settings.layoutAlgorithm = if (enabled) {
                WebSettings.LayoutAlgorithm.NORMAL
            } else {
                WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            }
            if (reload && popup.url != null) popup.reload()
        }
        updateDesktopModeUi()
        if (reload && webView.url != null) {
            // UA and CSS media queries are evaluated during navigation; reload only
            // the current document, preserving cookies and Chromium HTTP cache.
            webView.reload()
        }
    }

    private fun updateDesktopModeUi() {
        binding.btnDesktopSite.text = if (desktopMode) "Desktop: ON" else "Desktop: OFF"
        binding.btnDesktopSite.isSelected = desktopMode
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val resources = request.resources.filter {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }.toTypedArray()
            if (resources.isEmpty()) return
            val permissions = buildList {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(Manifest.permission.RECORD_AUDIO)
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(Manifest.permission.CAMERA)
            }.toTypedArray()
            runOnUiThread {
                val missing = permissions.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    request.grant(resources)
                } else {
                    pendingWebPermissionRequest = request
                    pendingWebPermissionResources = resources
                    ActivityCompat.requestPermissions(this@MainActivity, missing.toTypedArray(), recordAudioRequestCode)
                }
            }
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (view !== webView) return
            binding.pageProgress.progress = newProgress
            binding.progressText.text = "$newProgress%"
            val visible = newProgress < 100
            binding.pageProgress.visibility = if (visible) View.VISIBLE else View.GONE
            binding.progressText.visibility = if (visible) View.VISIBLE else View.GONE
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // Keep Chromium's popup decision at the WebView boundary. OAuth providers
            // frequently open their window after an async callback, without a current
            // gesture; rejecting that window causes an apparent infinite login spinner.
            closePopup()
            val child = WebView(this@MainActivity)
            configureWebView(child)
            child.addJavascriptInterface(NativePcmBridge(), "NativePcmBridge")
            child.webViewClient = createWebViewClient(isPopup = true)
            child.webChromeClient = createWebChromeClient()
            child.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
            popupWebView = child
            popupDialog = Dialog(this@MainActivity).apply {
                setTitle("Web authentication")
                setContentView(child)
                setOnDismissListener { closePopup() }
                show()
                window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                    (resources.displayMetrics.heightPixels * 0.90f).toInt()
                )
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popupWebView) closePopup()
            else super.onCloseWindow(window)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            return try {
                val intent = fileChooserParams.createIntent().apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                true
            } catch (error: Exception) {
                pendingFileCallback = null
                Log.w(TAG, "Unable to open WebView file chooser", error)
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            onHideCustomView()
            fullscreenView = view
            fullscreenCallback = callback
            (binding.root as ViewGroup).addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            webView.visibility = View.GONE
        }

        override fun onHideCustomView() {
            fullscreenView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            webView.visibility = View.VISIBLE
        }
    }

    private fun createWebViewClient(isPopup: Boolean): WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (isPopup) return
            safeEvaluateJavascript(view, "window.__destroyAudioProcessor && window.__destroyAudioProcessor();")
            binding.pageProgress.progress = 0
            binding.progressText.text = "0%"
            binding.pageProgress.visibility = View.VISIBLE
            binding.progressText.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (isPopup) return
            binding.pageProgress.visibility = View.GONE
            binding.progressText.visibility = View.GONE
            binding.addressBar.setText(view.url ?: url)
            if (!documentStartAudioInstalled) safeEvaluateJavascript(view, loadAsset("audio_processor.js"))
            applyAudioSettings()
            applyNativeRouteToWebView()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleUrlOverride(view, request.url.toString())
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrlOverride(view, url)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (!isPopup && request.isForMainFrame) showPageError("Không tải được trang (${error.errorCode})")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (!isPopup && request.isForMainFrame && errorResponse.statusCode >= 400) {
                showPageError("Trang trả về HTTP ${errorResponse.statusCode}")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                Log.e(TAG, "WebView renderer exited; crash=${detail.didCrash()}")
                runOnUiThread {
                    showPageError("Trình render WebView đã dừng; đang khởi tạo lại…")
                    if (!isFinishing) recreate()
                }
            } else {
                closePopup()
            }
            return true
        }
    }

    private fun showPageError(message: String) {
        binding.pageProgress.visibility = View.GONE
        binding.progressText.visibility = View.GONE
        binding.audioStatus.text = message
    }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment ?: "download")
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to enqueue WebView download", error)
        }
    }

    private fun closePopup() {
        popupWebView?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        popupWebView = null
        popupDialog?.setOnDismissListener(null)
        popupDialog?.dismiss()
        popupDialog = null
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

    private fun installDocumentStartAudioBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartAudioInstalled = true
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                loadAsset("audio_processor.js"),
                setOf("*")
            )
        }
    }

    private fun loadAsset(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private fun toggleAudio() {
        if (AudioProcessingService.running.get()) {
            AudioProcessingService.stop(this)
            nativeStackStarted = false
            binding.btnAudio.text = "Start audio"
            binding.audioStatus.text = "Audio idle"
            safeEvaluateJavascript(webView, "window.__setNativeAudioRoute && window.__setNativeAudioRoute(false);")
            safeEvaluateJavascript(webView, "window.__destroyAudioProcessor && window.__destroyAudioProcessor();")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                recordAudioRequestCode
            )
            return
        }
        binding.btnAudio.isEnabled = false
        binding.audioStatus.text = "Starting audio engine…"
        AudioProcessingService.onEngineStartFailed = { error ->
            runOnUiThread {
                binding.btnAudio.isEnabled = true
                binding.audioStatus.text = "Audio unavailable: ${error.message ?: "unknown error"}"
            }
        }
        AudioProcessingService.onEngineReady = { engine ->
            runOnUiThread {
                nativeStackStarted = true
                engine.apply(sheetSettings)
                binding.btnAudio.isEnabled = true
                binding.btnAudio.text = "Stop audio"
                binding.audioStatus.text = "Active · software monitor"
                applyOutputMode()
                applyNativeRouteToWebView()
            }
            virtualMicService.activateAsync(engine) { result ->
                runOnUiThread {
                    val status = when (result) {
                        is VirtualMicResult.Activated ->
                            "Active · ${result.tier.name.lowercase().replace('_', ' ')}"
                        is VirtualMicResult.Failed ->
                            "Active · software monitor (${result.reason})"
                    }
                    binding.audioStatus.text = status
                    applyNativeRouteToWebView()
                }
            }
        }
        AudioProcessingService.start(this)
    }

    private fun applyNativeRouteToWebView() {
        if (!::webView.isInitialized || isFinishing) return
        val mode = when {
            !AudioProcessingService.running.get() -> "off"
            virtualMicService.activeTier != VirtualMicTier.SOFTWARE_LOOPBACK -> "privileged"
            else -> "software"
        }
        safeEvaluateJavascript(
            webView,
            "window.__setNativeAudioMode && window.__setNativeAudioMode('$mode');"
        )
    }

    private fun populateAudioDevices() {
        val inputDevices = SoftwareLoopback.getInputDevices(this)
        val inputLabels = listOf("Auto") + inputDevices
            .filter { it.id != -1 }
            .map { device ->
                when {
                    device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                        "Mic điện thoại"
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ->
                        "Mic tai nghe (${device.name})"
                    else -> device.name
                }
            }
        val outputLabels = listOf("Loa điện thoại", "Loa trong", "WebView")
        binding.inputDeviceSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            inputLabels
        )
        binding.outputDeviceSpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            outputLabels
        )
        val inputId = preferences.getInt("input_device_id", -1)
        val outputMode = preferences.getString("output_mode", "speaker") ?: "speaker"
        binding.inputDeviceSpinner.setSelection(
            inputDevices.indexOfFirst { it.id == inputId }.coerceAtLeast(0)
        )
        binding.outputDeviceSpinner.setSelection(
            when (outputMode) {
                "earpiece" -> 1
                "webview" -> 2
                else -> 0
            }
        )
        binding.inputDeviceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val deviceId = inputDevices.getOrNull(position)?.id ?: -1
                preferences.edit().putInt("input_device_id", deviceId).apply()
                if (nativeStackStarted && deviceId >= -1) AudioEngine.shared.setInputDevice(deviceId)
            }
        })
        binding.outputDeviceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    1 -> "earpiece"
                    2 -> "webview"
                    else -> "speaker"
                }
                preferences.edit().putString("output_mode", mode).apply()
                if (AudioProcessingService.running.get()) {
                    when (mode) {
                        "webview" -> SoftwareLoopback.setMonitorEnabled(false)
                        "earpiece" -> {
                            SoftwareLoopback.setMonitorEnabled(true)
                            SoftwareLoopback.setOutputDevice(
                                this@MainActivity,
                                SoftwareLoopback.findOutputDeviceId(
                                    this@MainActivity,
                                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                                )
                            )
                        }
                        else -> {
                            SoftwareLoopback.setMonitorEnabled(true)
                            SoftwareLoopback.setOutputDevice(
                                this@MainActivity,
                                SoftwareLoopback.findOutputDeviceId(
                                    this@MainActivity,
                                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                                )
                            )
                        }
                    }
                }
            }
        })
    }

    private fun applyOutputMode() {
        if (!AudioProcessingService.running.get()) return
        when (preferences.getString("output_mode", "speaker")) {
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
    }

    private fun applyAudioSettings() {
        val s = sheetSettings
        if (nativeStackStarted) AudioEngine.shared.apply(s)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != recordAudioRequestCode) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingWebPermissionRequest?.let { request ->
                val resources = pendingWebPermissionResources
                pendingWebPermissionRequest = null
                pendingWebPermissionResources = null
                if (resources != null) request.grant(resources) else request.deny()
            } ?: toggleAudio()
        } else {
            pendingWebPermissionRequest = null
            pendingWebPermissionResources = null
            android.widget.Toast.makeText(
                this,
                "Quyền microphone chưa được cấp; WebView vẫn hoạt động bình thường.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun safeEvaluateJavascript(view: WebView, script: String, result: ((String) -> Unit)? = null) {
        try {
            if (!isFinishing) view.evaluateJavascript(script, result)
        } catch (e: Exception) {
            Log.e("MicSettingsCrash", "Lỗi evaluateJavascript", e)
        }
    }

    private fun handleUrlOverride(view: WebView, url: String): Boolean {
        if (url.startsWith("http://", true) || url.startsWith("https://", true) ||
            url.startsWith("about:", true) || url.startsWith("data:", true) ||
            url.startsWith("blob:", true) || url.startsWith("javascript:", true)) {
            return false
        }
        try {
            val intent = if (url.startsWith("intent:", true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                intent.getStringExtra("browser_fallback_url")?.let(view::loadUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không mở được scheme lạ: ${Uri.parse(url).scheme}", e)
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
        CookieManager.getInstance().flush()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        fullscreenCallback?.onCustomViewHidden()
        closePopup()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        settingsDialog?.dismiss()
        AudioProcessingService.onEngineReady = null
        AudioProcessingService.onEngineStartFailed = null
        virtualMicService.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val result = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else null
            pendingFileCallback?.onReceiveValue(result)
            pendingFileCallback = null
        }
    }
}
