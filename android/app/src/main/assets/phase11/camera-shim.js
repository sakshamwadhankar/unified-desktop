// Phase 11 D-e: the getUserMedia shim. The desktop WebView loads this page; the receiver sends
// the PC camera as base64 JPEG frames and the PC microphone as base64 s16 stereo PCM, and this
// script exposes them as a normal MediaStream, so the page (and later real web apps) see
// "camera + microphone" without any native device access. Nothing is recorded.
(function () {
  'use strict';
  var canvas = document.createElement('canvas');
  canvas.width = 1280;
  canvas.height = 720;
  var ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  var audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
  var target = audioCtx.createMediaStreamDestination();
  var queue = [];          // Float32Array chunks of interleaved stereo
  var pending = 0;
  var proc = audioCtx.createScriptProcessor(960, 0, 2);
  proc.onaudioprocess = function (e) {
    var outL = e.outputBuffer.getChannelData(0);
    var outR = e.outputBuffer.getChannelData(1);
    var i = 0;
    while (i < outL.length && queue.length) {
      var chunk = queue[0];
      var take = Math.min(chunk.length / 2, outL.length - i);
      for (var n = 0; n < take; n++) {
        outL[i + n] = chunk[n * 2] || 0;
        outR[i + n] = chunk[n * 2 + 1] || 0;
      }
      if (take * 2 >= chunk.length) queue.shift(); else queue[0] = chunk.subarray(take * 2);
      i += take;
    }
    for (; i < outL.length; i++) { outL[i] = 0; outR[i] = 0; }
  };
  proc.connect(target);
  var mute = audioCtx.createGain();
  mute.gain.value = 0;
  proc.connect(mute);
  mute.connect(audioCtx.destination);
  if (audioCtx.state === 'suspended') audioCtx.resume();

  function pushFrame(b64, w, h) {
    var img = new Image();
    img.onload = function () {
      if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
      ctx.drawImage(img, 0, 0, w, h);
      stats.frames++;
    };
    img.src = 'data:image/jpeg;base64,' + b64;
  }

  function pushAudio(b64) {
    var bin = atob(b64);
    var n = bin.length >> 1;
    var f = new Float32Array(n);
    for (var i = 0; i < n; i++) {
      var v = (bin.charCodeAt(i * 2) | (bin.charCodeAt(i * 2 + 1) << 8));
      if (v > 0x7FFF) v -= 0x10000;
      f[i] = v / 32768;
    }
    queue.push(f);
    pending += n;
    stats.samples += n;
    while (pending > 48000 * 2) { pending -= queue[0].length; queue.shift(); } // 1 s cap
  }

  var stream = new MediaStream();
  canvas.captureStream(15).getVideoTracks().forEach(function (t) { stream.addTrack(t); });
  target.stream.getAudioTracks().forEach(function (t) { stream.addTrack(t); });

  var stats = { frames: 0, samples: 0, since: Date.now() };
  var md = navigator.mediaDevices || (navigator.mediaDevices = {});
  md.getUserMedia = function () { return Promise.resolve(stream); };
  md.enumerateDevices = function () {
    return Promise.resolve([
      { kind: 'videoinput', label: 'PC camera (unified-desktop)', deviceId: 'pc-cam' },
      { kind: 'audioinput', label: 'PC microphone (unified-desktop)', deviceId: 'pc-mic' }
    ]);
  };
  window.udCam = {
    pushFrame: pushFrame,
    pushAudio: pushAudio,
    stats: function () { return { frames: stats.frames, samples: stats.samples, seconds: (Date.now() - stats.since) / 1000 }; }
  };
})();
