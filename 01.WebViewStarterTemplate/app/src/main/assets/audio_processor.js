(function () {
  "use strict";
  if (window.__micProcessorInstalled) return;
  window.__micProcessorInstalled = true;

  // DSP belongs exclusively to the native AudioEngine. This script only adapts
  // processed native PCM to a MediaStream that sites can consume as a microphone,
  // and normalises WebRTC constraints so any web app (Discord, TikTok Web,
  // Facebook, Meet...) negotiates the processed stream instead of the raw device.
  var VIRTUAL_MIC_ID = "virtual-mic-processed";
  var VIRTUAL_MIC_GROUP = "virtual-mic-group";
  var VIRTUAL_MIC_LABEL = "Virtual Microphone (Processed)";

  // ---- Console debug realtime: đẩy log về app qua NativePcmBridge.postLog ----
  var logBuffer = [];
  var LOG_FLUSH_MS = 250;
  function postLog(level, message) {
    var line = (typeof message === "string") ? message : safeStringify(message);
    logBuffer.push(level + ": " + line);
    if (logBuffer.length > 40) logBuffer.shift();
    if (logTimer === null) {
      logTimer = setTimeout(flushLogs, LOG_FLUSH_MS);
    }
  }
  function safeStringify(value) {
    try { return JSON.stringify(value); } catch (_) { return String(value); }
  }
  function flushLogs() {
    logTimer = null;
    if (!logBuffer.length) return;
    var batch = logBuffer.join("\n");
    logBuffer = [];
    try {
      if (window.NativePcmBridge && typeof window.NativePcmBridge.postLog === "function") {
        window.NativePcmBridge.postLog("JS", batch);
        return;
      }
    } catch (_) {}
    if (window.__androidConsoleBridge && typeof window.__androidConsoleBridge.log === "function") {
      try { window.__androidConsoleBridge.log(batch); } catch (_) {}
    }
  }
  var logTimer = null;
  var logThrottle = {};
  function logThrottled(key, level, message, intervalMs) {
    var now = Date.now();
    if (logThrottle[key] && now - logThrottle[key] < (intervalMs || 2000)) return;
    logThrottle[key] = now;
    postLog(level, message);
  }
  postLog("INFO", "audio_processor v2 installed @ " + location.href);

  var originalGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia
    ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;
  var inputAnalyser = null, outputAnalyser = null, audioContext = null;
  var nativeMode = "off";
  var nativeNode = null, nativePollTimer = null, nativeStream = null, nativeStreamPromise = null;
  var lastNativeLevels = { inDb: -60, outDb: -60 };
  try { if (window.NativePcmBridge && window.NativePcmBridge.mode) nativeMode = window.NativePcmBridge.mode(); } catch (_) {}
  postLog("INFO", "bridge mode khởi động = " + nativeMode);

  function engineReady() {
    return nativeMode !== "off" && !!window.NativePcmBridge &&
      typeof window.NativePcmBridge.isRunning === "function" && window.NativePcmBridge.isRunning();
  }

  function destroyNativeStream() {
    if (nativePollTimer) { clearInterval(nativePollTimer); nativePollTimer = null; }
    if (nativeNode) { try { nativeNode.disconnect(); } catch (_) {} nativeNode = null; }
    if (nativeStream) {
      nativeStream.getTracks().forEach(function (track) { try { track.stop(); } catch (_) {} });
      nativeStream = null;
    }
    nativeStreamPromise = null;
  }

  window.__destroyAudioProcessor = function () {
    postLog("INFO", "__destroyAudioProcessor called");
    destroyNativeStream();
    if (audioContext) { try { audioContext.close(); } catch (_) {} }
    audioContext = null;
    inputAnalyser = outputAnalyser = null;
  };

  window.__setNativeAudioMode = function (mode) {
    var next = mode === "software" || mode === "privileged" ? mode : "off";
    if (next !== nativeMode) {
      postLog("INFO", "native audio mode: " + nativeMode + " -> " + next);
      if (nativeMode === "off" && next !== "off") {
        // Chuyển từ "off" sang "software"/"privileged": chỉ reset promise nếu
        // stream hiện tại KHÔNG chạy được — giữ nguyên stream đang phát tốt để
        // không gián đoạn cuộc gọi giữa chừng (nguyên nhân mic "chết đột ngột").
        if (nativeStreamPromise === null) {
          postLog("INFO", "chưa có native stream -> sẽ tạo khi web xin mic");
        } else if (!engineReady()) {
          postLog("WARN", "native stream cũ lỗi (engine off) -> tạo lại");
          destroyNativeStream();
        } else {
          postLog("INFO", "native stream đang chạy tốt -> giữ nguyên");
        }
      }
      if (next === "off") destroyNativeStream();
    }
    nativeMode = next;
  };

  // ---- Tầng constraints: ép bộ lọc WebRTC chuẩn cho mọi web (dynamicmic.md) ----
  function sanitizeAudioConstraints(constraints) {
    // sound.md: ép chuẩn WebRTC 48kHz/16bit/mono + AEC/AGC để Discord/Messenger
    // không phải resample từ 44.1kHz (nguyên nhân chính gây rè, méo tiếng).
    // fixmicoutput.md: noiseSuppression=false vì NS của Android hay nhầm tiếng người
    // ở vài giây đầu thành tiếng ồn (mic chỉ thu được đoạn cuối).
    // KHÔNG ép latency=0: Chromium hiểu 0 là "overconstrained" -> NotReadableError
    // khiến mic mở lỗi hoàn toàn. Buffer 180ms bên dưới vẫn xử lý yếu tố giật lag.
    if (constraints.audio === true) {
      constraints.audio = {
        sampleRate: { ideal: 48000 },
        sampleSize: { ideal: 16 },
        channelCount: { ideal: 1 },
        echoCancellation: true,
        noiseSuppression: false,
        autoGainControl: true
      };
      return;
    }
    if (constraints.audio && typeof constraints.audio === "object") {
      var a = constraints.audio;
      a.sampleRate = { ideal: 48000 };
      a.sampleSize = { ideal: 16 };
      a.channelCount = { ideal: 1 };
      a.echoCancellation = true;
      a.noiseSuppression = false;
      a.autoGainControl = true;
      delete a.latency;
    }
  }

  function constraintsAskForVirtualDevice(audio) {
    if (!audio || typeof audio !== "object") return false;
    var exact = audio.deviceId && typeof audio.deviceId === "object" ? audio.deviceId.exact : audio.deviceId;
    if (exact === VIRTUAL_MIC_ID) return true;
    if (typeof exact === "string" && exact.split(",").indexOf(VIRTUAL_MIC_ID) !== -1) return true;
    if (Array.isArray(exact) && exact.indexOf(VIRTUAL_MIC_ID) !== -1) return true;
    return false;
  }

  // ---- Tầng device spoofing: web nhìn thấy track ảo như một mic thật ----
  function enhanceVirtualTrack(track) {
    if (!track || track.__micVirtualEnhanced) return track;
    track.__micVirtualEnhanced = true;
    var realSettings = typeof track.getSettings === "function" ? track.getSettings.bind(track) : null;
    track.getSettings = function () {
      var settings = realSettings ? realSettings() : {};
      settings.deviceId = VIRTUAL_MIC_ID;
      settings.groupId = VIRTUAL_MIC_GROUP;
      settings.kind = "audio";
      settings.label = VIRTUAL_MIC_LABEL;
      settings.echoCancellation = true;
      settings.noiseSuppression = false;
      settings.autoGainControl = true;
      settings.channelCount = 1;
      settings.sampleRate = 48000;
      return settings;
    };
    track.getCapabilities = function () {
      return {
        deviceId: VIRTUAL_MIC_ID,
        groupId: VIRTUAL_MIC_GROUP,
        kind: "audioinput",
        echoCancellation: [true],
        noiseSuppression: [false],
        autoGainControl: [true],
        channelCount: { min: 1, max: 2 },
        // Khôi phục dải sampleRate rộng 8k-48k: cache {48000,48000} cứng cộng với
        // constraint sampleRate ideal 48000 của một số web (Discord gọi 2 lần với
        // deviceId khác nhau) gây OverconstrainedError -> web tưởng "không có mic".
        sampleRate: { min: 8000, max: 48000 },
        sampleSize: { min: 16, max: 16 }
      };
    };
    return track;
  }

  function virtualDeviceFrom() {
    return {
      deviceId: VIRTUAL_MIC_ID,
      kind: "audioinput",
      label: VIRTUAL_MIC_LABEL,
      groupId: VIRTUAL_MIC_GROUP,
      toJSON: function () { return this; }
    };
  }

  function patchEnumerateDevices(mediaDevices) {
    if (!mediaDevices || typeof mediaDevices.enumerateDevices !== "function" || mediaDevices.__micEnumeratePatched) return;
    var originalEnumerate = mediaDevices.enumerateDevices.bind(mediaDevices);
    mediaDevices.__micEnumeratePatched = true;
    mediaDevices.enumerateDevices = function () {
      return Promise.resolve().then(function () { return originalEnumerate(); }).then(function (devices) {
        var list = Array.prototype.slice.call(devices || []);
        for (var i = 0; i < list.length; i++) {
          if (list[i] && list[i].deviceId === VIRTUAL_MIC_ID) return list;
        }
        var injected = false;
        for (var j = 0; j < list.length; j++) {
          if (list[j] && list[j].kind === "audioinput") {
            list[j] = virtualDeviceFrom();
            injected = true;
            break;
          }
        }
        if (!injected) list.unshift(virtualDeviceFrom());
        return list;
      });
    };
  }

  function dbFromAnalyser(analyser) {
    if (!analyser) return null;
    var data = new Float32Array(analyser.fftSize);
    analyser.getFloatTimeDomainData(data);
    var sum = 0;
    for (var i = 0; i < data.length; i++) sum += data[i] * data[i];
    var rms = Math.sqrt(sum / data.length);
    return rms > 0 ? Math.max(-60, 20 * Math.log10(rms)) : -60;
  }
  window.__getLevels = function () {
    var inDb = dbFromAnalyser(inputAnalyser);
    if (inDb !== null) return { inDb: inDb, outDb: dbFromAnalyser(outputAnalyser) };
    return lastNativeLevels;
  };
  window.__getAudioInfo = function () {
    return { sampleRate: audioContext ? audioContext.sampleRate : 0,
      latencyMs: audioContext && audioContext.baseLatency ? audioContext.baseLatency * 1000 : 0,
      bufferSize: inputAnalyser ? inputAnalyser.fftSize : 0 };
  };

  function decodeFloat32(base64) {
    if (!base64) return new Float32Array(0);
    var binary = atob(base64), bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Float32Array(bytes.buffer);
  }

  function createNativePcmStream() {
    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass || !window.AudioWorkletNode || !window.NativePcmBridge || !window.NativePcmBridge.isRunning()) {
      var reason = !window.NativePcmBridge ? "NativePcmBridge chưa được inject"
        : !window.NativePcmBridge.isRunning() ? "native engine chưa chạy (hãy bấm Start audio)"
        : !window.AudioWorkletNode ? "WebView không hỗ trợ AudioWorklet"
        : "AudioContext unavailable";
      postLog("ERROR", "createNativePcmStream từ chối: " + reason);
      return Promise.reject(new Error("Native PCM bridge is not running; start the native audio service before requesting a microphone."));
    }
    audioContext = new AudioContextClass({latencyHint: "playback"});
    if (!audioContext.audioWorklet) {
      try { audioContext.close(); } catch (_) {}
      audioContext = null;
      postLog("ERROR", "AudioWorklet không khả dụng trong WebView này");
      return Promise.reject(new Error("WebView AudioWorklet is unavailable"));
    }
    var sourceCode = [
      "class NativePcmProcessor extends AudioWorkletProcessor {",
      "constructor(){super();this.capacity=32768;this.buffer=new Float32Array(this.capacity);this.read=0;this.write=0;this.available=0;this.frac=0;this.inputRate=sampleRate;this.started=false;this.target=Math.floor(sampleRate*.18);this.peak=0;this.tick=0;this.lastFrameTime=currentTime;this.port.onmessage=e=>{if(e.data.type==='format'){this.inputRate=e.data.sampleRate||sampleRate;return;}var x=e.data.samples?new Float32Array(e.data.samples):null;if(!x)return;this.lastFrameTime=currentTime;for(var i=0;i<x.length;i++){if(this.available>=this.capacity){this.read=(this.read+1)%this.capacity;this.available--;}this.buffer[this.write]=x[i];this.write=(this.write+1)%this.capacity;this.available++;}};}",
      "process(inputs,outputs){var out=outputs[0];if(!out||!out.length)return true;var ch=out[0];if(!this.started){if(this.available<this.target){ch.fill(0);return true;}this.started=true;}var step=this.inputRate/sampleRate;for(var i=0;i<ch.length;i++){if(this.available<2){ch[i]=0;continue;}var p=Math.floor(this.frac),a=this.buffer[(this.read+p)%this.capacity],b=this.buffer[(this.read+p+1)%this.capacity];ch[i]=a+(b-a)*(this.frac-p);this.frac+=step;var consume=Math.floor(this.frac);this.frac-=consume;if(consume>0){var n=Math.min(consume,this.available-1);this.read=(this.read+n)%this.capacity;this.available-=n;}var v=ch[i]<0?-ch[i]:ch[i];if(v>this.peak)this.peak=v;}for(var c=1;c<out.length;c++)out[c].set(ch);if((this.tick=(this.tick+1)%8)===0){this.port.postMessage({type:'levels',peak:this.peak});this.peak=0;}return true;}",
            "registerProcessor('native-pcm-source',NativePcmProcessor);"
    ].join("\n");
    var url = URL.createObjectURL(new Blob([sourceCode], {type: "application/javascript"}));
    postLog("INFO", "tạo AudioContext + worklet native-pcm-source…");
    return audioContext.audioWorklet.addModule(url).then(function () {
      URL.revokeObjectURL(url);
      nativeNode = new AudioWorkletNode(audioContext, "native-pcm-source", {numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1]});
      var lastLevelsMsgAt = Date.now();
      nativeNode.port.onmessage = function (event) {
        var data = event && event.data;
        if (!data || data.type !== "levels") return;
        lastLevelsMsgAt = Date.now();
        var db = data.peak > 0 ? Math.max(-60, 20 * Math.log10(data.peak)) : -60;
        lastNativeLevels = { inDb: db, outDb: db };
      };
      nativeNode.port.onmessageerror = function () {
        postLog("ERROR", "worklet message error — pipeline native PCM bị lỗi");
      };
      var format = JSON.parse(window.NativePcmBridge.format());
      postLog("INFO", "native format: " + format.sampleRate + "Hz, burst=" + format.framesPerBurst);
      nativeNode.port.postMessage({type: "format", sampleRate: format.sampleRate || audioContext.sampleRate});
      window.NativePcmBridge.clear();
      inputAnalyser = audioContext.createAnalyser(); inputAnalyser.fftSize = 1024; outputAnalyser = inputAnalyser;
      nativeNode.connect(inputAnalyser);
      var destination = audioContext.createMediaStreamDestination();
      inputAnalyser.connect(destination); nativeStream = destination.stream;
      var idlePolls = 0;
      nativePollTimer = setInterval(function () {
        try {
          if (!window.NativePcmBridge.isRunning()) {
            logThrottled("engine-off", "WARN", "poll: engine native KHÔNG chạy (isRunning=false) — mic sẽ im lặng", 5000);
            return;
          }
          var packet = JSON.parse(window.NativePcmBridge.pullPcm(2048));
          if (packet.frames > 0 && packet.data) {
            idlePolls = 0;
            var samples = decodeFloat32(packet.data);
            nativeNode.port.postMessage({samples: samples.buffer}, [samples.buffer]);
          } else {
            idlePolls++;
            logThrottled("pull-idle", "WARN", "poll: engine trả 0 frame (lần " + idlePolls + ") — engine chạy nhưng không thu được PCM", 5000);
          }
          // Self-heal: worklet ngừng gửi levels > 1s HOẶC engine im lặng kéo dài
          // -> dựng lại toàn bộ stream. Đây là nguyên nhân "mic đang chạy rồi
          // đột nhiên chết" mà không tự phục hồi.
          var stalled = (Date.now() - lastLevelsMsgAt) > 1000;
          if (idlePolls >= 50 || stalled) {
            postLog("ERROR", "self-heal: " + (stalled ? "worklet ngừng xử lý >1s" : "engine im lặng ~1s") + " -> dựng lại native stream");
            destroyNativeStream();
          }
        } catch (error) {
          logThrottled("pull-error", "ERROR", "native PCM pull failed: " + (error && error.message), 3000);
        }
      }, 20);
      return audioContext.resume().catch(function () {}).then(function () { return nativeStream; });
    }).catch(function (error) {
      URL.revokeObjectURL(url); destroyNativeStream();
      if (audioContext) { try { audioContext.close(); } catch (_) {} }
      audioContext = null;
      postLog("ERROR", "createNativePcmStream thất bại: " + (error && error.message));
      throw error;
    });
  }

  function nativeGetUserMedia(constraints) {
    return Promise.resolve().then(function () {
      if (!nativeStreamPromise) nativeStreamPromise = createNativePcmStream();
      var videoPromise = constraints.video ? originalGetUserMedia(Object.assign({}, constraints, {audio: false})) : Promise.resolve(new MediaStream());
      return Promise.all([nativeStreamPromise, videoPromise]);
    }).then(function (streams) {
      postLog("INFO", "native mic stream OK: " + streams[0].getAudioTracks().length + " audio track(s) ảo");
      var result = new MediaStream();
      streams[0].getAudioTracks().forEach(function (track) {
        result.addTrack(enhanceVirtualTrack(track.clone()));
      });
      streams[1].getVideoTracks().forEach(function (track) { result.addTrack(track); });
      return result;
    }).catch(function (error) {
      // Không cache promise lỗi: lần getUserMedia tiếp theo phải được thử lại,
      // nếu không mic sẽ chết vĩnh viễn cho tới khi reload trang.
      postLog("ERROR", "nativeGetUserMedia lỗi: " + (error && error.message) + " — xoá cache stream");
      destroyNativeStream();
      throw error;
    });
  }

  if (originalGetUserMedia) {
    navigator.mediaDevices.getUserMedia = function (constraints) {
      if (!constraints || !constraints.audio) return originalGetUserMedia(constraints);
      sanitizeAudioConstraints(constraints);
      var explicitVirtual = constraintsAskForVirtualDevice(constraints.audio);
      var nativeAvailable = engineReady();
      postLog("INFO", "getUserMedia audio: native=" + (nativeAvailable ? "ready" : "off") +
        " mode=" + nativeMode + " explicitVirtual=" + explicitVirtual +
        " constraints=" + safeStringify(constraints.audio).slice(0, 200));
      if (nativeAvailable && (explicitVirtual || nativeMode === "software")) {
        return nativeGetUserMedia(constraints).catch(function (error) {
          if (explicitVirtual || !originalGetUserMedia) throw error;
          postLog("WARN", "native stream lỗi -> fallback mic Chromium: " + (error && error.message));
          return originalGetUserMedia(constraints);
        });
      }
      // Privileged route: processed audio đã được bơm vào đường mic hệ thống.
      if (nativeMode === "privileged") {
        postLog("INFO", "privileged route -> dùng mic hệ thống (đã xử lý)");
        return originalGetUserMedia(constraints);
      }
      // Engine chưa bật: vẫn dùng mic thật của Chromium (đã ép AEC/NS/AGC),
      // và tự chuyển sang stream đã xử lý nếu engine bật giữa chừng.
      return originalGetUserMedia(constraints).catch(function (error) {
        if (!engineReady()) {
          postLog("ERROR", "mic Chromium lỗi & engine off: " + (error && (error.name + ": " + error.message)));
          throw error;
        }
        postLog("WARN", "mic Chromium lỗi -> thử stream native đã xử lý: " + (error && error.message));
        return nativeGetUserMedia(constraints);
      });
    };
  } else {
    postLog("ERROR", "navigator.mediaDevices.getUserMedia không tồn tại — mic web sẽ không chạy");
  }

  if (navigator.mediaDevices) patchEnumerateDevices(navigator.mediaDevices);

  // Shim API cũ để web legacy (flash-era, WebRTC cũ) vẫn gọi được mic ảo.
  if (!navigator.webkitGetUserMedia && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    navigator.webkitGetUserMedia = function (constraints, success, error) {
      navigator.mediaDevices.getUserMedia(constraints).then(success, function (e) { if (error) error(e); });
    };
  }
  if (!navigator.getUserMedia && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    navigator.getUserMedia = function (constraints, success, error) {
      navigator.mediaDevices.getUserMedia(constraints).then(success, function (e) { if (error) error(e); });
    };
  }

  // Console debug realtime: giám sát nhịp 3s — engine, route, meter, queue.
  setInterval(function () {
    flushLogs();
    try {
      if (!window.NativePcmBridge || typeof window.NativePcmBridge.diagnose !== "function") {
        postLog("INFO", "[monitor] bridge chưa sẵn sàng");
        return;
      }
      var d = JSON.parse(window.NativePcmBridge.diagnose());
      var lv = window.__getLevels();
      var inDb = (typeof lv.inDb === "number") ? lv.inDb.toFixed(0) : "?";
      var streamState = nativeStream ? (nativeStream.active ? "active" : "INACTIVE") : "none";
      var hint;
      if (d.nativeHealthy) {
        hint = (inDb > -50) ? "thu tốt" : "engine chạy nhưng METER YẾU -> kiểm tra nguồn mic/gate/permission";
      } else {
        hint = (nativeMode === "off") ? "engine tắt — bấm Start audio trong Settings"
          : "engine HEALTHY=false (service bị kill?) -> Stop rồi Start lại audio";
      }
      postLog("INFO", "[monitor] route=" + d.routeMode + " healthy=" + d.nativeHealthy +
        " inDb=" + inDb + " queue=" + d.queuedFrames + " frames" +
        " stream=" + streamState + " mode=" + nativeMode + " — " + hint);
    } catch (error) {
      logThrottled("monitor-error", "ERROR", "monitor lỗi: " + (error && error.message), 5000);
    }
  }, 3000);

  // Bắt lỗi/chặn console của chính trang (Discord văng NotReadableError v.v.)
  window.addEventListener("error", function (event) {
    if (event && event.message && /microphone|audio|getUserMedia|NotAllowed|NotReadable/i.test(event.message)) {
      postLog("PAGE-ERR", event.message);
    }
  }, true);
  window.addEventListener("unhandledrejection", function (event) {
    var reason = event && event.reason;
    var msg = reason && (reason.message || String(reason));
    if (msg && /microphone|audio|getUserMedia|NotAllowed|NotReadable/i.test(msg)) {
      postLog("PAGE-REJ", msg);
    }
  });
})();
