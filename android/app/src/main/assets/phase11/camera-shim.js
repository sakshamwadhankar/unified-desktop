// Phase 11 D-e: the getUserMedia shim. Our own test page (camera-shim.html) inlines this
// script, so it is part of the page (no document-start dependency). The receiver sends the PC
// camera as base64 JPEG frames and the PC microphone as base64 48 kHz s16 stereo PCM; this
// script exposes them as a normal MediaStream. Nothing is recorded; only counters leave here.
(function () {
  'use strict';
  // Marker: injected pages are detected by this (the shell re-serves HTML with this script
  // as the first <head> script, so it runs before the page's own scripts).
  window.__udCamShim = 1;
  var canvas = document.createElement('canvas');
  canvas.width = 640;
  canvas.height = 360;
  var ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  // A tiny canvas for brightness only: 32x18 pixels, never saved anywhere.
  var bright = document.createElement('canvas');
  bright.width = 32;
  bright.height = 18;
  var bctx = bright.getContext('2d', { willReadFrequently: true });

  var audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
  var target = audioCtx.createMediaStreamDestination();
  var queue = [];          // Float32Array chunks of interleaved stereo
  var pending = 0;
  var proc = audioCtx.createScriptProcessor(1024, 0, 2);
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

  var stats = {
    frames: 0, samples: 0, since: Date.now(),
    w: 0, h: 0, brightness: 0, lastBrightness: 0, changed: 0,
    audioRms: 0, packets: 0, fps: 0, winFrames: 0, winStart: Date.now()
  };

  // Reused across frames: one Image + one onload, not a new allocation per frame (the old
  // code allocated a fresh Image and read the brightness canvas back on EVERY frame; the
  // getImageData readback is what cost the phone 1-4 fps under the 10 fps feed).
  var frameImg = new Image();
  frameImg.onload = function () {
    var w = frameImg.__w, h = frameImg.__h;
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    ctx.drawImage(frameImg, 0, 0, w, h);
    stats.frames++;
    stats.winFrames++;
    stats.w = w; stats.h = h;
    // Mean brightness over the 32x18 downsample (0..1), every 5th frame. Numbers only.
    if (stats.frames % 5 === 0) {
      bctx.drawImage(canvas, 0, 0, bright.width, bright.height);
      var d = bctx.getImageData(0, 0, bright.width, bright.height).data;
      var sum = 0;
      for (var i = 0; i < d.length; i += 4) sum += d[i] + d[i + 1] + d[i + 2];
      var mean = sum / (bright.width * bright.height * 3 * 255);
      stats.lastBrightness = stats.brightness;
      stats.brightness = mean;
      stats.changed = Math.abs(stats.brightness - stats.lastBrightness);
    }
  };

  function pushFrame(b64, w, h) {
    frameImg.__w = w;
    frameImg.__h = h;
    frameImg.src = 'data:image/jpeg;base64,' + b64;
  }

  function pushAudio(b64) {
    var bin = atob(b64);
    var n = bin.length >> 1;
    var f = new Float32Array(n);
    var sq = 0;
    for (var i = 0; i < n; i++) {
      // The PC (and the phone's CH_AUDIO encoder) send s16 big-endian.
      var v = (bin.charCodeAt(i * 2) << 8) | bin.charCodeAt(i * 2 + 1);
      if (v > 0x7FFF) v -= 0x10000;
      f[i] = v / 32768;
      sq += f[i] * f[i];
    }
    if (n) stats.audioRms = Math.sqrt(sq / n);
    queue.push(f);
    pending += n;
    stats.samples += n;
    stats.packets++;
    while (pending > 48000 * 2) { pending -= queue[0].length; queue.shift(); } // 1 s cap
  }

  function snapshot() {
    var now = Date.now();
    var dt = (now - stats.winStart) / 1000;
    if (dt >= 1) {
      stats.fps = stats.winFrames / dt;
      stats.winFrames = 0;
      stats.winStart = now;
    }
    return {
      w: stats.w, h: stats.h, fps: Math.round(stats.fps * 10) / 10,
      frames: stats.frames, brightness: Math.round(stats.brightness * 1000) / 1000,
      changed: Math.round(stats.changed * 1000) / 1000,
      audioRms: Math.round(stats.audioRms * 10000) / 10000,
      samples: stats.samples, packets: stats.packets,
      seconds: Math.round((now - stats.since) / 100) / 10
    };
  }

  function report() {
    var s = snapshot();
    if (window.UdBridge && window.UdBridge.report) {
      window.UdBridge.report(JSON.stringify(s));
    }
    return s;
  }

  var stream = new MediaStream();
  canvas.captureStream(15).getVideoTracks().forEach(function (t) { stream.addTrack(t); });
  target.stream.getAudioTracks().forEach(function (t) { stream.addTrack(t); });

  var md = navigator.mediaDevices || (navigator.mediaDevices = {});
  md.getUserMedia = function () {
    // A page asked for the camera: ask the receiver to start the PC camera + microphone.
    try { if (window.UdBridge && window.UdBridge.start) window.UdBridge.start(); } catch (e) {}
    return Promise.resolve(stream);
  };
  md.enumerateDevices = function () {
    return Promise.resolve([
      { kind: 'videoinput', label: 'PC camera (unified-desktop)', deviceId: 'pc-cam' },
      { kind: 'audioinput', label: 'PC microphone (unified-desktop)', deviceId: 'pc-mic' }
    ]);
  };
  window.udCam = {
    pushFrame: pushFrame,
    pushAudio: pushAudio,
    stats: snapshot,
    report: report
  };
  setInterval(report, 1000);
  // B-102: a link reconnect makes the receiver release the PC camera, but the page keeps
  // holding our MediaStream, so no track event fires and nothing asks again. Re-assert
  // cam-on when frames have been frozen for ~3 s (the receiver ignores duplicate cam-on).
  var seenFrames = 0, frozen = 0;
  setInterval(function () {
    if (stats.frames !== seenFrames) {
      seenFrames = stats.frames;
      frozen = 0;
    } else {
      frozen++;
    }
    if (frozen >= 3 && seenFrames > 0) {
      frozen = 0;
      try { if (window.UdBridge && UdBridge.start) UdBridge.start(); } catch (e) {}
    }
  }, 1000);
})();
