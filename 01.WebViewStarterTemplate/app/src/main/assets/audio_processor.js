(function () {
  "use strict";
  if (window.__micProcessorInstalled) return;
  window.__micProcessorInstalled = true;

  // DSP belongs exclusively to the native AudioEngine. This script only adapts
  // processed native PCM to a MediaStream that sites can consume as a microphone.
  var originalGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia
    ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;
  var inputAnalyser = null, outputAnalyser = null, audioContext = null;
  var nativeMode = "off";
  var nativeNode = null, nativePollTimer = null, nativeStream = null, nativeStreamPromise = null;
  try { if (window.NativePcmBridge && window.NativePcmBridge.mode) nativeMode = window.NativePcmBridge.mode(); } catch (_) {}

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
    destroyNativeStream();
    if (audioContext) { try { audioContext.close(); } catch (_) {} }
    audioContext = null;
    inputAnalyser = outputAnalyser = null;
  };

  window.__setNativeAudioMode = function (mode) {
    var next = mode === "software" || mode === "privileged" ? mode : "off";
    if (next !== nativeMode) window.__destroyAudioProcessor();
    nativeMode = next;
  };

  // A privileged Shizuku/root route feeds Android's microphone path directly.
  window.__setNativeAudioRoute = function (enabled) {
    window.__setNativeAudioMode(enabled ? "privileged" : "off");
  };

  function dbFromAnalyser(analyser) {
    if (!analyser) return -60;
    var data = new Float32Array(analyser.fftSize);
    analyser.getFloatTimeDomainData(data);
    var sum = 0;
    for (var i = 0; i < data.length; i++) sum += data[i] * data[i];
    var rms = Math.sqrt(sum / data.length);
    return rms > 0 ? Math.max(-60, 20 * Math.log10(rms)) : -60;
  }
  window.__getLevels = function () { return { inDb: dbFromAnalyser(inputAnalyser), outDb: dbFromAnalyser(outputAnalyser) }; };
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
      return Promise.reject(new Error("Native PCM bridge is not running; start the native audio service before requesting a microphone."));
    }
    audioContext = new AudioContextClass({latencyHint: "playback"});
    if (!audioContext.audioWorklet) {
      try { audioContext.close(); } catch (_) {}
      audioContext = null;
      return Promise.reject(new Error("WebView AudioWorklet is unavailable"));
    }
    var sourceCode = [
      "class NativePcmProcessor extends AudioWorkletProcessor {",
      "constructor(){super();this.capacity=32768;this.buffer=new Float32Array(this.capacity);this.read=0;this.write=0;this.available=0;this.frac=0;this.inputRate=sampleRate;this.started=false;this.target=Math.floor(sampleRate*.18);this.port.onmessage=e=>{if(e.data.type==='format'){this.inputRate=e.data.sampleRate||sampleRate;return;}var x=e.data.samples?new Float32Array(e.data.samples):null;if(!x)return;for(var i=0;i<x.length;i++){if(this.available>=this.capacity){this.read=(this.read+1)%this.capacity;this.available--;}this.buffer[this.write]=x[i];this.write=(this.write+1)%this.capacity;this.available++;}};}",
      "process(inputs,outputs){var out=outputs[0];if(!out||!out.length)return true;var ch=out[0];if(!this.started){if(this.available<this.target){ch.fill(0);return true;}this.started=true;}var step=this.inputRate/sampleRate;for(var i=0;i<ch.length;i++){if(this.available<2){ch[i]=0;continue;}var p=Math.floor(this.frac),a=this.buffer[(this.read+p)%this.capacity],b=this.buffer[(this.read+p+1)%this.capacity];ch[i]=a+(b-a)*(this.frac-p);this.frac+=step;var consume=Math.floor(this.frac);this.frac-=consume;if(consume>0){var n=Math.min(consume,this.available-1);this.read=(this.read+n)%this.capacity;this.available-=n;}}for(var c=1;c<out.length;c++)out[c].set(ch);return true;}",
      "}", "registerProcessor('native-pcm-source',NativePcmProcessor);"
    ].join("\n");
    var url = URL.createObjectURL(new Blob([sourceCode], {type: "application/javascript"}));
    return audioContext.audioWorklet.addModule(url).then(function () {
      URL.revokeObjectURL(url);
      nativeNode = new AudioWorkletNode(audioContext, "native-pcm-source", {numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1]});
      var format = JSON.parse(window.NativePcmBridge.format());
      nativeNode.port.postMessage({type: "format", sampleRate: format.sampleRate || audioContext.sampleRate});
      window.NativePcmBridge.clear();
      inputAnalyser = audioContext.createAnalyser(); inputAnalyser.fftSize = 1024; outputAnalyser = inputAnalyser;
      nativeNode.connect(inputAnalyser);
      var destination = audioContext.createMediaStreamDestination();
      inputAnalyser.connect(destination); nativeStream = destination.stream;
      nativePollTimer = setInterval(function () {
        try {
          if (!window.NativePcmBridge.isRunning()) return;
          var packet = JSON.parse(window.NativePcmBridge.pullPcm(2048));
          if (packet.frames > 0 && packet.data) {
            var samples = decodeFloat32(packet.data);
            nativeNode.port.postMessage({samples: samples.buffer}, [samples.buffer]);
          }
        } catch (error) { console.error("[audio_processor] native PCM pull failed", error); }
      }, 20);
      return audioContext.resume().catch(function () {}).then(function () { return nativeStream; });
    }).catch(function (error) {
      URL.revokeObjectURL(url); destroyNativeStream();
      if (audioContext) { try { audioContext.close(); } catch (_) {} }
      audioContext = null;
      throw error;
    });
  }

  function nativeGetUserMedia(constraints) {
    return Promise.resolve().then(function () {
      if (!nativeStreamPromise) nativeStreamPromise = createNativePcmStream();
      var videoPromise = constraints.video ? originalGetUserMedia(Object.assign({}, constraints, {audio: false})) : Promise.resolve(new MediaStream());
      return Promise.all([nativeStreamPromise, videoPromise]);
    }).then(function (streams) {
      var result = new MediaStream();
      streams[0].getAudioTracks().forEach(function (track) { result.addTrack(track.clone()); });
      streams[1].getVideoTracks().forEach(function (track) { result.addTrack(track); });
      return result;
    });
  }

  if (originalGetUserMedia) {
    navigator.mediaDevices.getUserMedia = function (constraints) {
      if (!constraints || !constraints.audio) return originalGetUserMedia(constraints);
      if (nativeMode === "privileged") return originalGetUserMedia(constraints);
      return nativeGetUserMedia(constraints).catch(function (error) {
        console.error("[audio_processor] refusing browser microphone fallback: native PCM is unavailable", error);
        throw error;
      });
    };
  }
})();
