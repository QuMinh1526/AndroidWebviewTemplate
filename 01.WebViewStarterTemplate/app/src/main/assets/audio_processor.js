(function () {
  "use strict";

  window.__audioSettings = window.__audioSettings || {
    enabled: true,
    echoCancellation: true,
    noiseSuppression: true
  };

  if (window.__micProcessorInstalled) {
    return;
  }
  window.__micProcessorInstalled = true;

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    return;
  }

  var originalGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  var currentGain = 1.0;

  window.__setAudioProcessingEnabled = function (value) {
    window.__audioSettings.enabled = Boolean(value);
  };

  // These constraints are used on the next getUserMedia call, not on active streams.
  window.__setEchoCancellation = function (value) {
    window.__audioSettings.echoCancellation = Boolean(value);
  };

  window.__setNoiseSuppression = function (value) {
    window.__audioSettings.noiseSuppression = Boolean(value);
  };

  window.__setMicGain = function (value) {
    var gain = Number(value);
    if (!Number.isFinite(gain) || gain < 0) {
      throw new TypeError("Microphone gain must be a finite non-negative number");
    }
    currentGain = gain;
    if (window.__micGainNodes) {
      window.__micGainNodes.forEach(function (gainNode) {
        gainNode.gain.value = gain;
      });
    }
  };

  window.__micGainNodes = [];

  navigator.mediaDevices.getUserMedia = function (constraints) {
    if (!constraints || !constraints.audio || !window.__audioSettings.enabled) {
      return originalGetUserMedia(constraints);
    }

    var audioConstraints = constraints.audio === true ? {} : constraints.audio;
    var processedConstraints = Object.assign({}, constraints, {
      audio: Object.assign({}, audioConstraints, {
        echoCancellation: window.__audioSettings.echoCancellation,
        noiseSuppression: window.__audioSettings.noiseSuppression,
        autoGainControl: false
      })
    });

    return originalGetUserMedia(processedConstraints).then(function (rawStream) {
      var AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) {
        return rawStream;
      }

      var audioTracks = rawStream.getAudioTracks();
      if (!audioTracks.length) {
        return rawStream;
      }

      var audioContext = new AudioContextClass();
      var source = audioContext.createMediaStreamSource(rawStream);
      var gainNode = audioContext.createGain();
      var destination = audioContext.createMediaStreamDestination();

      gainNode.gain.value = currentGain;
      source.connect(gainNode);
      gainNode.connect(destination);
      window.__micGainNodes.push(gainNode);

      var processedStream = new MediaStream();
      destination.stream.getAudioTracks().forEach(function (track) {
        processedStream.addTrack(track);
      });
      rawStream.getVideoTracks().forEach(function (track) {
        processedStream.addTrack(track);
      });

      return processedStream;
    });
  };
})();
