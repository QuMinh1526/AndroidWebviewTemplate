(function () {
  "use strict";
  if (window.__micProcessorInstalled) return;
  window.__micProcessorInstalled = true;

  window.__audioSettings = window.__audioSettings || {
    enabled: true, gateEnabled: true, gateThreshold: -45, gateAttack: 10, gateRelease: 180,
    eqEnabled: true, eq: Array(10).fill(0),
    compressorEnabled: true, compThreshold: -24, compRatio: 4, compAttack: 10, compRelease: 180, compMakeup: 0,
    reverbEnabled: false, reverbMix: .2, reverbRoom: .5, reverbDamping: .5,
    echoEnabled: false, echoAmount: 0, gainEnabled: true, gain: 1
  };
  var settings = window.__audioSettings;
  var originalGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia
    ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;
  var gainNodes = [];
  var inputAnalyser = null, outputAnalyser = null, audioContext = null;
  var compressor = null, filters = [], finalGain = null, gateNode = null;

  function setEnabled(v) { settings.enabled = !!v; }
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
        gateNode.port.postMessage({enabled: settings.gateEnabled, threshold: settings.gateThreshold});
        return gateNode;
      });
    }
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

  function buildPipeline(rawStream) {
    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass || !rawStream.getAudioTracks().length) return rawStream;
    audioContext = new AudioContextClass();
    var source = audioContext.createMediaStreamSource(rawStream);
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

    var delay = audioContext.createDelay(1); delay.delayTime.value = .3;
    var feedback = audioContext.createGain(); feedback.gain.value = settings.echoEnabled ? settings.echoAmount / 100 * .7 : 0;
    var echoWet = audioContext.createGain(); echoWet.gain.value = settings.echoEnabled ? settings.echoAmount / 100 : 0;
    cursor.connect(delay); delay.connect(feedback); feedback.connect(delay); delay.connect(echoWet);
    var echoMix = audioContext.createGain(); cursor.connect(echoMix); echoWet.connect(echoMix); cursor = echoMix;

    finalGain = audioContext.createGain();
    finalGain.gain.value = settings.gainEnabled ? settings.gain : 1;
    cursor.connect(finalGain);
    outputAnalyser = audioContext.createAnalyser(); outputAnalyser.fftSize = 1024;
    finalGain.connect(outputAnalyser);
    var destination = audioContext.createMediaStreamDestination(); outputAnalyser.connect(destination);
    gainNodes.push(finalGain);
    var processed = new MediaStream();
    destination.stream.getAudioTracks().forEach(function (track) { processed.addTrack(track); });
    rawStream.getVideoTracks().forEach(function (track) { processed.addTrack(track); });
      return processed;
    });
  }

  if (originalGetUserMedia) {
    navigator.mediaDevices.getUserMedia = function (constraints) {
      if (!constraints || !constraints.audio || !settings.enabled) return originalGetUserMedia(constraints);
      var audio = constraints.audio === true ? {} : constraints.audio;
      var requested = Object.assign({}, constraints, {
        audio: Object.assign({}, audio, {
          echoCancellation: settings.echoCancellation !== false,
          noiseSuppression: settings.noiseSuppression !== false,
          autoGainControl: false
        })
      });
      return originalGetUserMedia(requested).then(buildPipeline);
    };
  }
})();
