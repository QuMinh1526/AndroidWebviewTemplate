(function () {
  "use strict";
  if (window.__micProcessorInstalled) return;
  window.__micProcessorInstalled = true;

  window.__audioSettings = window.__audioSettings || {
    enabled: true, gateEnabled: true, gateThreshold: -45, gateAttack: 10, gateRelease: 180,
    eqEnabled: true, eq: Array(10).fill(0),
    compressorEnabled: true, compThreshold: -24, compRatio: 4, compAttack: 10, compRelease: 180, compMakeup: 0,
    reverbEnabled: false, reverbMix: .2, reverbRoom: .5, reverbDamping: .5,
    pitchEnabled: false, pitchSemitones: 0,
    echoEnabled: false, echoAmount: 0, gainEnabled: true, gain: 1
  };
  var settings = window.__audioSettings;
  var originalGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia
    ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;
  var gainNodes = [];
  var inputAnalyser = null, outputAnalyser = null, audioContext = null;
  var compressor = null, filters = [], finalGain = null, gateNode = null, pitchNode = null;
  var rawPath = null, processedPath = null, outputSelector = null;
  var nativeMode = "off";
  var nativeNode = null, nativePollTimer = null, nativeStream = null, nativeStreamPromise = null;
  try {
    if (window.NativePcmBridge && window.NativePcmBridge.mode) {
      nativeMode = window.NativePcmBridge.mode();
    }
  } catch (_) {}

  function destroyNativeStream() {
    if (nativePollTimer) {
      clearInterval(nativePollTimer);
      nativePollTimer = null;
    }
    if (nativeNode) {
      try { nativeNode.disconnect(); } catch (_) {}
      nativeNode = null;
    }
    if (nativeStream) {
      nativeStream.getTracks().forEach(function (track) { try { track.stop(); } catch (_) {} });
      nativeStream = null;
    }
    nativeStreamPromise = null;
  }

  window.__destroyAudioProcessor = function () {
    destroyNativeStream();
    if (audioContext) {
      try { audioContext.close(); } catch (_) {}
    }
    audioContext = null;
    inputAnalyser = outputAnalyser = null;
    rawPath = processedPath = outputSelector = null;
    gainNodes = [];
  };

  window.__setNativeAudioMode = function (mode) {
    var next = mode === "software" || mode === "privileged" ? mode : "off";
    if (next === "off" && nativeMode !== "off") {
      destroyNativeStream();
      if (audioContext) {
        try { audioContext.close(); } catch (_) {}
        audioContext = null;
      }
    }
    nativeMode = next;
  };

  // A privileged Shizuku/root route feeds the processed native stream back through Android's
  // microphone path. In that mode the WebView must not process the stream a second time.
  window.__setNativeAudioRoute = function (enabled) {
    window.__setNativeAudioMode(enabled ? "privileged" : "off");
  };

  function setEnabled(v) {
    settings.enabled = !!v;
    if (!rawPath || !processedPath || !outputSelector || !audioContext) return;
    var now = audioContext.currentTime;
    rawPath.gain.cancelScheduledValues(now);
    processedPath.gain.cancelScheduledValues(now);
    rawPath.gain.setTargetAtTime(settings.enabled ? 0 : 1, now, .01);
    processedPath.gain.setTargetAtTime(settings.enabled ? 1 : 0, now, .01);
  }
  function setNoiseGate(enabled, threshold, attack, release) {
    settings.gateEnabled = !!enabled; settings.gateThreshold = Number(threshold);
    settings.gateAttack = Number(attack); settings.gateRelease = Number(release);
    if (gateNode) gateNode.port.postMessage({enabled: settings.gateEnabled, threshold: settings.gateThreshold});
  }
  function setEq(enabled, values) {
    settings.eqEnabled = !!enabled;
    if (Array.isArray(values)) settings.eq = values.slice(0, 10).map(Number);
    filters.forEach(function (f, i) { f.gain.value = settings.eqEnabled ? (settings.eq[i] || 0) : 0; });
  }
  function setCompressor(enabled, threshold, ratio, attack, release, makeup) {
    settings.compressorEnabled = !!enabled; settings.compThreshold = Number(threshold);
    settings.compRatio = Number(ratio); settings.compAttack = Number(attack);
    settings.compRelease = Number(release); settings.compMakeup = Number(makeup);
    if (compressor) {
      compressor.threshold.value = settings.compressorEnabled ? settings.compThreshold : 0;
      compressor.ratio.value = settings.compressorEnabled ? settings.compRatio : 1;
      compressor.attack.value = settings.compAttack / 1000;
      compressor.release.value = settings.compRelease / 1000;
    }
  }
  function setReverb(enabled, mix, room, damping) {
    settings.reverbEnabled = !!enabled; settings.reverbMix = Number(mix);
    settings.reverbRoom = Number(room); settings.reverbDamping = Number(damping);
  }
  function setPitch(enabled, semitones) {
    settings.pitchEnabled = !!enabled;
    settings.pitchSemitones = Number(semitones);
    if (pitchNode && pitchNode.port) {
      pitchNode.port.postMessage({enabled: settings.pitchEnabled, semitones: settings.pitchSemitones});
    }
  }
  function setEcho(enabled, amount) { settings.echoEnabled = !!enabled; settings.echoAmount = Number(amount); }
  function setGain(enabled, value) {
    settings.gainEnabled = !!enabled; settings.gain = Number(value);
    gainNodes.forEach(function (n) { n.gain.value = settings.gainEnabled ? settings.gain : 1; });
    if (finalGain) finalGain.gain.value = settings.gainEnabled ? settings.gain : 1;
  }
  window.__setAudioProcessingEnabled = setEnabled;
  window.__setNoiseGate = setNoiseGate;
  window.__setEq = setEq;
  window.__setCompressor = setCompressor;
  window.__setReverb = setReverb;
  window.__setPitch = setPitch;
  window.__setEcho = setEcho;
  window.__setGain = setGain;
  window.__setMicGain = function (value) { setGain(true, value); };

  function dbFromAnalyser(analyser) {
    if (!analyser) return -60;
    var data = new Float32Array(analyser.fftSize);
    analyser.getFloatTimeDomainData(data);
    var sum = 0;
    for (var i = 0; i < data.length; i++) sum += data[i] * data[i];
    var rms = Math.sqrt(sum / data.length);
    return rms > 0 ? Math.max(-60, 20 * Math.log10(rms)) : -60;
  }
  window.__getLevels = function () {
    return { inDb: dbFromAnalyser(inputAnalyser), outDb: dbFromAnalyser(outputAnalyser) };
  };
  window.__getAudioInfo = function () {
    return {
      sampleRate: audioContext ? audioContext.sampleRate : 0,
      latencyMs: audioContext && audioContext.baseLatency ? audioContext.baseLatency * 1000 : 0,
      bufferSize: inputAnalyser ? inputAnalyser.fftSize : 0
    };
  };

  function impulseResponse(ctx, seconds, decay) {
    var length = Math.floor(ctx.sampleRate * seconds);
    var buffer = ctx.createBuffer(2, length, ctx.sampleRate);
    for (var channel = 0; channel < 2; channel++) {
      var data = buffer.getChannelData(channel);
      for (var i = 0; i < length; i++) data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, decay);
    }
    return buffer;
  }

  function createGate(ctx) {
    if (ctx.audioWorklet && window.AudioWorkletNode) {
      var source = [
        "class NoiseGateProcessor extends AudioWorkletProcessor {",
        "constructor() { super(); this.enabled = true; this.threshold = -45; this.port.onmessage = e => { this.enabled = e.data.enabled; this.threshold = e.data.threshold; }; }",
        "process(inputs, outputs) {",
        "  var input = inputs[0], output = outputs[0];",
        "  if (!input.length || !output.length) return true;",
        "  var sum = 0, count = 0;",
        "  for (var c = 0; c < input.length; c++) for (var j = 0; j < input[c].length; j++) { sum += input[c][j] * input[c][j]; count++; }",
        "  var db = 20 * Math.log10(Math.sqrt(sum / Math.max(1, count)) || 0.000001);",
        "  var open = !this.enabled || db >= this.threshold;",
        "  for (var c = 0; c < output.length; c++) {",
        "    for (var i = 0; i < output[c].length; i++) output[c][i] = open && input[c] ? input[c][i] : 0;",
        "  }",
        "  return true;",
        "}}",
        "registerProcessor('mic-noise-gate', NoiseGateProcessor);"
      ].join("\n");
      var url = URL.createObjectURL(new Blob([source], {type: "application/javascript"}));
      return ctx.audioWorklet.addModule(url).then(function () {
        URL.revokeObjectURL(url);
        gateNode = new AudioWorkletNode(ctx, "mic-noise-gate");
        console.log("[audio_processor] Noise gate: AudioWorklet active");
        gateNode.port.postMessage({enabled: settings.gateEnabled, threshold: settings.gateThreshold});
        return gateNode;
      }).catch(function (error) {
        URL.revokeObjectURL(url);
        console.log("[audio_processor] Noise gate: AudioWorklet failed, using ScriptProcessor", error);
        return createScriptProcessorGate(ctx);
      });
    }
    console.log("[audio_processor] Noise gate: AudioWorklet unavailable, using ScriptProcessor");
    return createScriptProcessorGate(ctx);
  }

  function createScriptProcessorGate(ctx) {
    var processor = ctx.createScriptProcessor(1024, 1, 1);
    processor.onaudioprocess = function (event) {
      var input = event.inputBuffer.getChannelData(0), output = event.outputBuffer.getChannelData(0);
      var sum = 0;
      for (var i = 0; i < input.length; i++) sum += input[i] * input[i];
      var db = 20 * Math.log10(Math.sqrt(sum / input.length) || 0.000001);
      var open = !settings.gateEnabled || db >= settings.gateThreshold;
      for (var j = 0; j < output.length; j++) output[j] = open ? input[j] : 0;
    };
    return Promise.resolve(processor);
  }

  // Lightweight granular demo: two crossfaded taps in an AudioWorklet. It is
  // intentionally simpler than a native phase-vocoder and may have grain artifacts.
  function createPitchNode(ctx) {
    if (!ctx.audioWorklet || !window.AudioWorkletNode) return Promise.resolve(ctx.createGain());
    var source = [
      "class PitchShifterProcessor extends AudioWorkletProcessor {",
      "constructor() { super(); this.enabled=false; this.ratio=1; this.size=sampleRate*.07; this.buffer=[]; this.write=0; this.phase=0; this.port.onmessage=e=>{this.enabled=!!e.data.enabled; this.ratio=Math.pow(2,(e.data.semitones||0)/12);}; }",
      "process(inputs, outputs) {",
      "  var input=inputs[0], output=outputs[0]; if(!input.length||!output.length)return true;",
      "  for(var c=0;c<output.length;c++){ if(!this.buffer[c])this.buffer[c]=new Float32Array(Math.ceil(this.size)+2); var b=this.buffer[c], x=input[c]||input[0], y=output[c];",
      "    for(var i=0;i<y.length;i++){ var v=x?x[i]||0:0; b[this.write]=v; var p=this.phase, a=(this.write-Math.floor((p+.5)*this.size)+b.length)%b.length, q=(this.write-Math.floor((p)*this.size)+b.length)%b.length; var w=p<.5?p*2:2-p*2; y[i]=this.enabled?(b[a]*(1-w)+b[q]*w):v; this.write=(this.write+1)%b.length; this.phase+=this.ratio/this.size; if(this.phase>=1)this.phase-=1; } } return true;",
      "}}",
      "registerProcessor('pitch-shifter', PitchShifterProcessor);"
    ].join("\n");
    var url = URL.createObjectURL(new Blob([source], {type: "application/javascript"}));
    return ctx.audioWorklet.addModule(url).then(function () {
      URL.revokeObjectURL(url);
      pitchNode = new AudioWorkletNode(ctx, "pitch-shifter");
      pitchNode.port.postMessage({enabled: settings.pitchEnabled, semitones: settings.pitchSemitones});
      console.log("[audio_processor] Pitch shifter: AudioWorklet active");
      return pitchNode;
    }).catch(function (error) {
      URL.revokeObjectURL(url);
      console.log("[audio_processor] Pitch shifter unavailable, bypassing", error);
      return ctx.createGain();
    });
  }

  function buildPipeline(rawStream) {
    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass || !rawStream.getAudioTracks().length) return Promise.resolve(rawStream);
    audioContext = new AudioContextClass();
    var source = audioContext.createMediaStreamSource(rawStream);
    rawPath = audioContext.createGain();
    rawPath.gain.value = settings.enabled ? 0 : 1;
    source.connect(rawPath);
    inputAnalyser = audioContext.createAnalyser();
    inputAnalyser.fftSize = 1024;
    source.connect(inputAnalyser);

    return createGate(audioContext).then(function (gate) {
      inputAnalyser.connect(gate);
    var cursor = gate;
    var frequencies = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
    filters = frequencies.map(function (frequency, index) {
      var filter = audioContext.createBiquadFilter();
      filter.type = "peaking"; filter.frequency.value = frequency; filter.Q.value = 1;
      filter.gain.value = settings.eqEnabled ? settings.eq[index] : 0;
      cursor.connect(filter); cursor = filter; return filter;
    });

    compressor = audioContext.createDynamicsCompressor();
    setCompressor(settings.compressorEnabled, settings.compThreshold, settings.compRatio,
      settings.compAttack, settings.compRelease, settings.compMakeup);
    cursor.connect(compressor); cursor = compressor;

    var reverb = audioContext.createConvolver();
    reverb.buffer = impulseResponse(audioContext, 1 + settings.reverbRoom * 2, 2 + settings.reverbDamping * 4);
    var reverbWet = audioContext.createGain(); reverbWet.gain.value = settings.reverbEnabled ? settings.reverbMix : 0;
    var dry = audioContext.createGain(); dry.gain.value = 1;
    cursor.connect(dry); cursor.connect(reverb); reverb.connect(reverbWet);
    var reverbMix = audioContext.createGain(); dry.connect(reverbMix); reverbWet.connect(reverbMix);
    cursor = reverbMix;

    return createPitchNode(audioContext).then(function (pitch) {
    pitchNode = pitch;
    cursor.connect(pitch);
    cursor = pitch;
    var delay = audioContext.createDelay(1); delay.delayTime.value = .3;
    var feedback = audioContext.createGain(); feedback.gain.value = settings.echoEnabled ? settings.echoAmount / 100 * .7 : 0;
    var echoWet = audioContext.createGain(); echoWet.gain.value = settings.echoEnabled ? settings.echoAmount / 100 : 0;
    cursor.connect(delay); delay.connect(feedback); feedback.connect(delay); delay.connect(echoWet);
    var echoMix = audioContext.createGain(); cursor.connect(echoMix); echoWet.connect(echoMix); cursor = echoMix;

    finalGain = audioContext.createGain();
    finalGain.gain.value = settings.gainEnabled ? settings.gain : 1;
    cursor.connect(finalGain);
    outputAnalyser = audioContext.createAnalyser(); outputAnalyser.fftSize = 1024;
    processedPath = audioContext.createGain();
    processedPath.gain.value = settings.enabled ? 1 : 0;
    finalGain.connect(outputAnalyser);
    outputAnalyser.connect(processedPath);
    outputSelector = audioContext.createGain();
    rawPath.connect(outputSelector);
    processedPath.connect(outputSelector);
    var destination = audioContext.createMediaStreamDestination();
    outputSelector.connect(destination);
    gainNodes.push(finalGain);
    var processed = new MediaStream();
    destination.stream.getAudioTracks().forEach(function (track) { processed.addTrack(track); });
    rawStream.getVideoTracks().forEach(function (track) { processed.addTrack(track); });
      return processed;
      });
    });
  }

  function decodeFloat32(base64) {
    if (!base64) return new Float32Array(0);
    var binary = atob(base64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Float32Array(bytes.buffer);
  }

  function createNativePcmStream() {
    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass || !audioContext.audioWorklet || !window.AudioWorkletNode ||
        !window.NativePcmBridge ||
        !window.NativePcmBridge.isRunning()) {
      return Promise.reject(new Error("native PCM bridge is not running"));
    }
    audioContext = new AudioContextClass();
    var sourceCode = [
      "class NativePcmProcessor extends AudioWorkletProcessor {",
      "constructor() {",
      " super(); this.capacity=32768; this.buffer=new Float32Array(this.capacity);",
      " this.read=0; this.write=0; this.available=0; this.frac=0; this.inputRate=sampleRate;",
      " this.port.onmessage=e=>{",
      "  if(e.data.type==='format'){this.inputRate=e.data.sampleRate||sampleRate;return;}",
      "  var a=e.data.samples; if(!a)return;",
      "  var x=new Float32Array(a);",
      "  for(var i=0;i<x.length;i++){",
      "   if(this.available>=this.capacity){this.read=(this.read+1)%this.capacity;this.available--;}",
      "   this.buffer[this.write]=x[i];this.write=(this.write+1)%this.capacity;this.available++;",
      "  }",
      " };",
      "}",
      "process(inputs,outputs){",
      " var out=outputs[0]; if(!out||!out.length)return true; var ch=out[0];",
      " var step=this.inputRate/sampleRate;",
      " for(var i=0;i<ch.length;i++){",
      "  if(this.available<2){ch[i]=0;continue;}",
      "  var p=Math.floor(this.frac), a=this.buffer[(this.read+p)%this.capacity];",
      "  var b=this.buffer[(this.read+p+1)%this.capacity]; ch[i]=a+(b-a)*(this.frac-p);",
      "  this.frac+=step; var consume=Math.floor(this.frac); this.frac-=consume;",
      "  if(consume>0){var n=Math.min(consume,this.available-1);this.read=(this.read+n)%this.capacity;this.available-=n;}",
      " }",
      " for(var c=1;c<out.length;c++)out[c].set(ch); return true;",
      "}}",
      "registerProcessor('native-pcm-source',NativePcmProcessor);"
    ].join("\n");
    var url = URL.createObjectURL(new Blob([sourceCode], {type: "application/javascript"}));
    return audioContext.audioWorklet.addModule(url).then(function () {
      URL.revokeObjectURL(url);
      nativeNode = new AudioWorkletNode(audioContext, "native-pcm-source", {
        numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1]
      });
      var format = JSON.parse(window.NativePcmBridge.format());
      nativeNode.port.postMessage({type: "format", sampleRate: format.sampleRate || audioContext.sampleRate});
      window.NativePcmBridge.clear();
      var analyser = audioContext.createAnalyser();
      analyser.fftSize = 1024;
      inputAnalyser = analyser;
      nativeNode.connect(analyser);
      var destination = audioContext.createMediaStreamDestination();
      analyser.connect(destination);
      nativeStream = destination.stream;
      nativePollTimer = setInterval(function () {
        try {
          if (!window.NativePcmBridge.isRunning()) return;
          var packet = JSON.parse(window.NativePcmBridge.pullPcm(1024));
          if (packet.frames > 0 && packet.data) {
            var samples = decodeFloat32(packet.data);
            nativeNode.port.postMessage({samples: samples.buffer}, [samples.buffer]);
          }
        } catch (error) {
          console.log("[audio_processor] native PCM pull failed", error);
        }
      }, 10);
      return audioContext.resume().catch(function () {}).then(function () {
        return nativeStream;
      });
    }).catch(function (error) {
      URL.revokeObjectURL(url);
      destroyNativeStream();
      if (audioContext) { try { audioContext.close(); } catch (_) {} }
      audioContext = null;
      throw error;
    });
  }

  function nativeGetUserMedia(constraints) {
    if (!nativeStreamPromise) {
      nativeStreamPromise = createNativePcmStream();
    }
    var videoPromise = constraints.video
      ? originalGetUserMedia(Object.assign({}, constraints, {audio: false}))
      : Promise.resolve(new MediaStream());
    return Promise.all([nativeStreamPromise, videoPromise]).then(function (streams) {
      var result = new MediaStream();
      streams[0].getAudioTracks().forEach(function (track) { result.addTrack(track.clone()); });
      streams[1].getVideoTracks().forEach(function (track) { result.addTrack(track); });
      return result;
    });
  }

  if (originalGetUserMedia) {
    navigator.mediaDevices.getUserMedia = function (constraints) {
      if (!constraints || !constraints.audio) return originalGetUserMedia(constraints);
      var audio = constraints.audio === true ? {} : constraints.audio;
      var requested = Object.assign({}, constraints, {
        audio: Object.assign({}, audio, {
          echoCancellation: settings.echoCancellation !== false,
          noiseSuppression: settings.noiseSuppression !== false,
          autoGainControl: false
        })
      });
      if (nativeMode === "software") {
        return nativeGetUserMedia(constraints).catch(function (error) {
          console.log("[audio_processor] native PCM unavailable; using browser mic", error);
          return originalGetUserMedia(requested).then(buildPipeline);
        });
      }
      if (nativeMode === "privileged") return originalGetUserMedia(requested);
      return originalGetUserMedia(requested).then(buildPipeline);
    };
  }
})();
