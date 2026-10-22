//! Phase 11 D-b: phone audio on the PC. The wire format is 48 kHz stereo s16 PCM in 10 ms
//! packets with a sequence number and a phone timestamp (see Phase11Audio.kt); this module
//! is the PC end: a small jitter buffer that drops late packets instead of growing, and a
//! WASAPI shared-mode player. `audiotest` proves the same path end to end on one PC with a
//! generated 1 kHz tone and WASAPI loopback capture (numbers only, never audio).

use std::collections::VecDeque;

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: usize = 2;
/// 10 ms at 48 kHz stereo.
pub const PACKET_FRAMES: usize = 480;
/// Target buffer: 6 packets = 60 ms of audio. The phone's 10 ms packets reach the PC in
/// bursts of ~5 (adb-forward TCP coalescing), so the old 30 ms target drained to empty
/// between bursts - that is what produced 16-29k "underruns" per run (B-101). The gate
/// must sit above one burst so the queue never empties.
pub const JITTER_TARGET_PACKETS: usize = 6;

/// One wire packet's PCM plus its sequence number (the phone clock stamp is not needed by
/// the player; the phone logs it for the delay measurement).
#[derive(Debug)]
pub struct PcmPacket {
    pub seq: u32,
    pub samples: Vec<i16>,
}

#[derive(Debug, Default)]
pub struct JitterStats {
    pub received: u64,
    pub played: u64,
    pub late: u64,
    pub dropped: u64,
    pub underruns: u64,
}

/// Drop-late jitter buffer: packets older than the last played sequence are counted and
/// thrown away; packets are only returned once the target depth is reached, so a burst of
/// arrivals cannot make the delay grow without bound.
pub struct JitterBuffer {
    q: VecDeque<PcmPacket>,
    last_seq: Option<u32>,
    started: bool,
    pub stats: JitterStats,
}

impl JitterBuffer {
    pub fn new() -> Self {
        JitterBuffer {
            q: VecDeque::new(),
            last_seq: None,
            started: false,
            stats: JitterStats::default(),
        }
    }

    /// Returns true when the packet was queued, false when it was dropped as late.
    pub fn push(&mut self, p: PcmPacket) -> bool {
        self.stats.received += 1;
        if let Some(last) = self.last_seq {
            // Sequence wrap-safe comparison: any packet at or behind the last played one is
            // late for playback and must not reorder the stream.
            if p.seq.wrapping_sub(last) == 0 || p.seq.wrapping_sub(last) > 0x8000_0000 {
                self.stats.late += 1;
                return false;
            }
        }
        self.last_seq = Some(p.seq);
        self.q.push_back(p);
        // Hard cap: 12 packets (120 ms). Beyond this the link is not keeping up and the
        // newest audio matters more than completeness.
        while self.q.len() > 12 {
            self.q.pop_front();
            self.stats.dropped += 1;
        }
        true
    }

    /// Frames ready for the device: nothing until the target depth is reached, then one
    /// packet per call (the caller writes it to the render buffer). A gap while playing
    /// re-arms the target gate (re-buffer) instead of dribbling out single packets, and
    /// counts one underrun for the whole gap, not one per pump call (B-101).
    pub fn next(&mut self) -> Option<Vec<i16>> {
        if !self.started {
            if self.q.len() < JITTER_TARGET_PACKETS {
                return None;
            }
            self.started = true;
        }
        match self.q.pop_front() {
            Some(p) => {
                self.stats.played += 1;
                Some(p.samples)
            }
            None => {
                self.started = false;
                self.stats.underruns += 1;
                None
            }
        }
    }

    pub fn depth(&self) -> usize {
        self.q.len()
    }
}

impl Default for JitterBuffer {
    fn default() -> Self {
        Self::new()
    }
}

/// A 1 kHz stereo sine as 10 ms s16 packets, `amplitude` of full scale (0.06 = -24 dBFS).
pub fn tone_packets(seconds: f32, amplitude: f32, start_seq: u32) -> Vec<PcmPacket> {
    let total_frames = (SAMPLE_RATE as f32 * seconds) as usize;
    let packets = total_frames.div_ceil(PACKET_FRAMES);
    let amp = f64::from(amplitude) * 32767.0;
    (0..packets)
        .map(|p| {
            let mut samples = Vec::with_capacity(PACKET_FRAMES * CHANNELS);
            for f in 0..PACKET_FRAMES {
                let n = p * PACKET_FRAMES + f;
                let v = (amp * (2.0 * std::f64::consts::PI * 1000.0 * n as f64 / SAMPLE_RATE as f64).sin()) as i16;
                samples.push(v);
                samples.push(v);
            }
            PcmPacket { seq: start_seq + p as u32, samples }
        })
        .collect()
}

/// RMS of interleaved i16 samples, as a fraction of full scale.
#[allow(dead_code)]
pub fn rms_i16(samples: &[i16]) -> f64 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum: f64 = samples.iter().map(|&s| f64::from(s) * f64::from(s)).sum();
    (sum / samples.len() as f64).sqrt() / 32768.0
}

/// One CH_AUDIO payload: u32 sequence, u64 phone timestamp (ms), then big-endian s16 stereo.
pub fn parse_packet(payload: &[u8]) -> Option<PcmPacket> {
    if payload.len() < 12 || !(payload.len() - 12).is_multiple_of(4) {
        return None;
    }
    let seq = u32::from_be_bytes(payload[0..4].try_into().ok()?);
    let n = (payload.len() - 12) / 2;
    let mut samples = Vec::with_capacity(n);
    for i in 0..n {
        let o = 12 + i * 2;
        samples.push(i16::from_be_bytes([payload[o], payload[o + 1]]));
    }
    Some(PcmPacket { seq, samples })
}

/// The phone timestamp of a CH_AUDIO payload (ms, phone elapsedRealtime clock).
pub fn packet_ts_ms(payload: &[u8]) -> Option<u64> {
    if payload.len() < 12 {
        return None;
    }
    Some(u64::from_be_bytes(payload[4..12].try_into().ok()?))
}


#[cfg(windows)]
mod wasapi {
    use windows::Win32::Media::Audio::{
        eCapture, eConsole, eRender, IAudioCaptureClient, IAudioClient, IAudioRenderClient,
        IMMDevice, IMMDeviceEnumerator, MMDeviceEnumerator, AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK, ISimpleAudioVolume, WAVEFORMATEX,
    };
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED,
    };

    pub struct Device {
        pub client: IAudioClient,
        #[allow(dead_code)]
        pub format: *mut WAVEFORMATEX,
        pub channels: u32,
        pub rate: u32,
        pub float32: bool,
    }
    unsafe impl Send for Device {}

    pub fn init_com() -> Result<(), String> {
        unsafe {
            match CoInitializeEx(None, COINIT_MULTITHREADED).ok() {
                Ok(()) => Ok(()),
                // RPC_E_CHANGED_MODE: the view thread is already an STA (D3D11/DirectComposition).
                // WASAPI works there too; only the apartment mode differs.
                Err(e) if e.code().0 as u32 == 0x8001_0106 => Ok(()),
                Err(e) => Err(format!("CoInitializeEx: {e}")),
            }
        }
    }

    fn enumerator() -> Result<IMMDeviceEnumerator, String> {
        unsafe {
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)
                .map_err(|e| format!("MMDeviceEnumerator: {e}"))
        }
    }

    fn endpoint(render: bool) -> Result<IMMDevice, String> {
        unsafe {
            enumerator()?
                .GetDefaultAudioEndpoint(if render { eRender } else { eCapture }, eConsole)
                .map_err(|e| format!("GetDefaultAudioEndpoint: {e}"))
        }
    }

    fn open(render: bool, loopback: bool, seconds_buffer: i64) -> Result<Device, String> {
        let dev = endpoint(render)?;
        let client: IAudioClient = unsafe {
            dev.Activate(CLSCTX_ALL, None)
                .map_err(|e| format!("Activate(IAudioClient): {e}"))?
        };
        let format = unsafe { client.GetMixFormat().map_err(|e| format!("GetMixFormat: {e}"))? };
        let bytes = unsafe {
            (*format).nChannels as usize * (*format).nBlockAlign as usize
        };
        let flags = if loopback { AUDCLNT_STREAMFLAGS_LOOPBACK } else { 0 };
        unsafe {
            client
                .Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    flags,
                    seconds_buffer * 10_000_000,
                    0,
                    format,
                    None,
                )
                .map_err(|e| format!("Initialize: {e}"))?;
        }
        let _ = bytes;
        Ok(Device {
            channels: u32::from(unsafe { (*format).nChannels }),
            rate: unsafe { (*format).nSamplesPerSec },
            float32: unsafe { (*format).wBitsPerSample } == 32,
            format,
            client,
        })
    }

    /// Render path: returns buffer size in frames and the render client.
    pub fn render() -> Result<(Device, IAudioRenderClient, ISimpleAudioVolume), String> {
        let d = open(true, false, 1)?;
        let rc: IAudioRenderClient = unsafe {
            d.client
                .GetService()
                .map_err(|e| format!("GetService(render): {e}"))?
        };
        let vol: ISimpleAudioVolume = unsafe {
            d.client
                .GetService()
                .map_err(|e| format!("GetService(volume): {e}"))?
        };
        unsafe {
            let _ = vol.SetMasterVolume(0.15, std::ptr::null());
        }
        Ok((d, rc, vol))
    }

    pub fn loopback() -> Result<(Device, IAudioCaptureClient), String> {
        let d = open(true, true, 1)?;
        let cc: IAudioCaptureClient = unsafe {
            d.client
                .GetService()
                .map_err(|e| format!("GetService(capture): {e}"))?
        };
        Ok((d, cc))
    }

    pub fn microphone() -> Result<(Device, IAudioCaptureClient), String> {
        let d = open(false, false, 1)?;
        let cc: IAudioCaptureClient = unsafe {
            d.client
                .GetService()
                .map_err(|e| format!("GetService(mic): {e}"))?
        };
        Ok((d, cc))
    }

    pub fn start(d: &Device) -> Result<(), String> {
        unsafe { d.client.Start().map_err(|e| format!("Start: {e}")) }
    }
    pub fn stop(d: &Device) {
        unsafe {
            let _ = d.client.Stop();
        }
    }
    pub fn buffer_frames(d: &Device) -> u32 {
        unsafe { d.client.GetBufferSize().unwrap_or(0) }
    }
    pub fn padding(d: &Device) -> u32 {
        unsafe { d.client.GetCurrentPadding().unwrap_or(0) }
    }

    /// Copy i16 stereo frames into the device's mix format (float32 when that is the mix).
    /// Returns the number of frames written.
    pub fn write_i16(
        d: &Device,
        rc: &IAudioRenderClient,
        frames: u32,
        samples: &[i16],
    ) -> Result<u32, String> {
        let n = (samples.len() / 2).min(frames as usize) as u32;
        if n == 0 {
            return Ok(0);
        }
        unsafe {
            let p = rc.GetBuffer(n).map_err(|e| format!("GetBuffer: {e}"))?;
            if d.float32 {
                let out = std::slice::from_raw_parts_mut(p as *mut f32, n as usize * d.channels as usize);
                for f in 0..n as usize {
                    for ch in 0..d.channels as usize {
                        let v = samples.get(f * 2 + ch.min(1)).copied().unwrap_or(0);
                        out[f * d.channels as usize + ch] = f32::from(v) / 32768.0;
                    }
                }
            } else {
                let out = std::slice::from_raw_parts_mut(p as *mut i16, n as usize * d.channels as usize);
                for f in 0..n as usize {
                    for ch in 0..d.channels as usize {
                        out[f * d.channels as usize + ch] =
                            samples.get(f * 2 + ch.min(1)).copied().unwrap_or(0);
                    }
                }
            }
            rc.ReleaseBuffer(n, 0).map_err(|e| format!("ReleaseBuffer: {e}"))?;
        }
        Ok(n)
    }

    /// RMS of one captured packet in the device format, plus its frame count.
    pub fn read_rms(d: &Device, cc: &IAudioCaptureClient) -> Result<(f64, u32), String> {
        let mut total = 0.0f64;
        let mut count = 0usize;
        let mut frames_total = 0u32;
        loop {
            let packet = unsafe { cc.GetNextPacketSize().map_err(|e| format!("GetNextPacketSize: {e}"))? };
            if packet == 0 {
                break;
            }
            let mut data: *mut u8 = std::ptr::null_mut();
            let mut frames = 0u32;
            let mut flags = 0u32;
            unsafe {
                cc.GetBuffer(&mut data, &mut frames, &mut flags, None, None)
                    .map_err(|e| format!("capture GetBuffer: {e}"))?;
            }
            let silent = flags & 0x2 != 0; // AUDCLNT_BUFFERFLAGS_SILENT
            let n = frames as usize * d.channels as usize;
            if !silent && !data.is_null() {
                if d.float32 {
                    let s = unsafe { std::slice::from_raw_parts(data as *const f32, n) };
                    for &v in s {
                        total += f64::from(v) * f64::from(v);
                    }
                } else {
                    let s = unsafe { std::slice::from_raw_parts(data as *const i16, n) };
                    for &v in s {
                        let f = f64::from(v) / 32768.0;
                        total += f * f;
                    }
                }
                count += n;
            }
            frames_total += frames;
            unsafe { cc.ReleaseBuffer(frames).map_err(|e| format!("capture ReleaseBuffer: {e}"))? };
        }
        Ok((if count == 0 { 0.0 } else { (total / count as f64).sqrt() }, frames_total))
    }

    /// Endpoint (hardware) volume and mute, so a silent loopback can be explained.
    pub fn endpoint_volume() -> Option<(bool, f32, windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume)> {
        let dev = endpoint(true).ok()?;
        let v: windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume =
            unsafe { dev.Activate(CLSCTX_ALL, None).ok()? };
        let mute = unsafe { v.GetMute().ok()? }.as_bool();
        let level = unsafe { v.GetMasterVolumeLevelScalar().ok()? };
        Some((mute, level, v))
    }

    pub fn describe(d: &Device) -> String {
        format!(
            "{} Hz {} ch {}",
            d.rate,
            d.channels,
            if d.float32 { "f32" } else { "i16" }
        )
    }

    /// Append mono samples (channel average) in -1..1 from whatever the capture client has.
    pub fn read_mono(
        d: &Device,
        cc: &IAudioCaptureClient,
        out: &mut Vec<f32>,
    ) -> Result<u32, String> {
        let mut frames_total = 0u32;
        loop {
            let packet = unsafe {
                cc.GetNextPacketSize().map_err(|e| format!("GetNextPacketSize: {e}"))?
            };
            if packet == 0 {
                break;
            }
            let mut data: *mut u8 = std::ptr::null_mut();
            let mut frames = 0u32;
            let mut flags = 0u32;
            unsafe {
                cc.GetBuffer(&mut data, &mut frames, &mut flags, None, None)
                    .map_err(|e| format!("capture GetBuffer: {e}"))?;
            }
            let silent = flags & 0x2 != 0;
            let ch = d.channels.max(1) as usize;
            if !silent && !data.is_null() {
                if d.float32 {
                    let s = unsafe { std::slice::from_raw_parts(data as *const f32, frames as usize * ch) };
                    for f in 0..frames as usize {
                        let mut v = 0.0f32;
                        for c in 0..ch { v += s[f * ch + c]; }
                        out.push(v / ch as f32);
                    }
                } else {
                    let s = unsafe { std::slice::from_raw_parts(data as *const i16, frames as usize * ch) };
                    for f in 0..frames as usize {
                        let mut v = 0i32;
                        for c in 0..ch { v += i32::from(s[f * ch + c]); }
                        out.push(v as f32 / ch as f32 / 32768.0);
                    }
                }
            } else {
                out.extend(std::iter::repeat_n(0.0f32, frames as usize));
            }
            frames_total += frames;
            unsafe { cc.ReleaseBuffer(frames).map_err(|e| format!("capture ReleaseBuffer: {e}"))? };
        }
        Ok(frames_total)
    }

    /// Shared-mode player fed by the Phase-11 jitter buffer (CH_AUDIO).
    pub struct Player {
        dev: Device,
        rc: IAudioRenderClient,
        pub jb: super::JitterBuffer,
        pub written_frames: u64,
        pub max_depth: usize,
        /// Frames the shared-mode device buffer holds; the render side of the end-to-end delay.
        pub buffer_frames: u32,
    }

    impl Player {
        pub fn new() -> Result<Self, String> {
            init_com()?;
            let (dev, rc, _vol) = render()?;
            start(&dev)?;
            let buffer_frames = unsafe { dev.client.GetBufferSize().unwrap_or(0) };
            println!(
                "AUDIO device buffer={buffer_frames} frames ({:.0} ms)",
                buffer_frames as f32 * 1000.0 / dev.rate as f32
            );
            Ok(Player {
                dev,
                rc,
                jb: super::JitterBuffer::new(),
                written_frames: 0,
                max_depth: 0,
                buffer_frames,
            })
        }

        /// Render-side latency: what the shared-mode device buffer adds to the wire delay.
        pub fn buffer_ms(&self) -> f32 {
            self.buffer_frames as f32 * 1000.0 / self.dev.rate as f32
        }

        pub fn feed(&mut self, payload: &[u8]) -> bool {
            match super::parse_packet(payload) {
                Some(p) => self.jb.push(p),
                None => false,
            }
        }

        /// Write every whole 10 ms packet the device has room for.
        pub fn pump(&mut self) -> Result<u32, String> {
            let mut total = 0u32;
            loop {
                let free = buffer_frames(&self.dev).saturating_sub(padding(&self.dev));
                if free < super::PACKET_FRAMES as u32 {
                    break;
                }
                let Some(samples) = self.jb.next() else { break };
                let n = write_i16(&self.dev, &self.rc, super::PACKET_FRAMES as u32, &samples)?;
                if n == 0 {
                    break;
                }
                total += n;
                self.written_frames += u64::from(n);
                self.max_depth = self.max_depth.max(self.jb.depth());
            }
            Ok(total)
        }

        pub fn depth(&self) -> usize {
            self.jb.depth()
        }

        pub fn describe(&self) -> String {
            describe(&self.dev)
        }
    }

    impl Drop for Player {
        fn drop(&mut self) {
            stop(&self.dev);
        }
    }

}

#[cfg(windows)]
pub use wasapi::*;

/// The PC-only proof: feed the jitter buffer from a generator at real-time pace, play
/// through WASAPI shared mode at low volume, and measure the loopback RMS before/during/
/// after the 3 s tone. Prints numbers; no audio is written anywhere.
#[cfg(windows)]
pub fn self_test(seconds: f32) -> Result<String, String> {
    use std::time::{Duration, Instant};
    init_com()?;
    let (render, rc, _vol) = render()?;
    let (cap, cc) = loopback()?;
    let cap_desc = describe(&cap);
    // Night rule: the proof is allowed to make the endpoint audible for these 3 s, then
    // the previous mute/volume state is restored.
    let ep = endpoint_volume();
    let (mute0, level0) = ep.as_ref().map_or((false, 1.0), |(m, l, _)| (*m, *l));
    if let Some((_, _, v)) = ep.as_ref() {
        unsafe {
            if mute0 {
                let _ = v.SetMute(false, std::ptr::null());
            }
            if level0 < 0.2 {
                let _ = v.SetMasterVolumeLevelScalar(0.2, std::ptr::null());
            }
        }
    }
    start(&render)?;
    start(&cap)?;
    let mut jb = JitterBuffer::new();
    let mut pending = tone_packets(seconds, 0.06, 1_000);
    pending.reverse();
    let mut baseline = Vec::new();
    let mut during = Vec::new();
    let mut after = Vec::new();
    let started = Instant::now();
    let mut tone_end = None;
    let mut next_feed = Instant::now();
    let mut written = 0u64;
    let mut max_pad = 0u32;
    while started.elapsed() < Duration::from_secs_f64(f64::from(seconds) + 1.2) {
        let now = Instant::now();
        if now >= next_feed {
            if let Some(p) = pending.pop() {
                jb.push(p);
                next_feed += Duration::from_millis(10);
            } else if tone_end.is_none() {
                tone_end = Some(started.elapsed());
            }
        }
        // Fill whatever the device can take from the jitter buffer.
        let free = buffer_frames(&render).saturating_sub(padding(&render));
        if free >= PACKET_FRAMES as u32 {
            if let Some(samples) = jb.next() {
                written += u64::from(write_i16(&render, &rc, free, &samples)?);
            }
        }
        max_pad = max_pad.max(padding(&render));
        let (rms, _frames) = read_rms(&cap, &cc)?;
        let t = started.elapsed();
        if rms > 0.0 {
            match tone_end {
                None if t > Duration::from_millis(500) => during.push(rms),
                None => baseline.push(rms),
                Some(_) => after.push(rms),
            }
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    stop(&render);
    stop(&cap);
    if let Some((_, _, v)) = ep {
        unsafe {
            let _ = v.SetMasterVolumeLevelScalar(level0, std::ptr::null());
            if mute0 {
                let _ = v.SetMute(true, std::ptr::null());
            }
        }
    }
    let mean = |v: &Vec<f64>| if v.is_empty() { 0.0 } else { v.iter().sum::<f64>() / v.len() as f64 };
    let max = |v: &Vec<f64>| v.iter().copied().fold(0.0f64, f64::max);
    Ok(format!(
        "AUDIO self-test: device {cap_desc}; tone {} s 1 kHz at 0.06 FS, session volume 0.15\n\
         endpoint before: mute={mute0} level={level0:.2}\n\
         jitter: packets={} played={} late={} dropped={} underruns={} depth_end={}\n\
         loopback RMS: baseline={:.5} during={:.5} after={:.5} max={:.5}\n\
         window_ms={} loops={} frames_written={} max_padding={}",
        seconds,
        jb.stats.received,
        jb.stats.played,
        jb.stats.late,
        jb.stats.dropped,
        jb.stats.underruns,
        jb.depth(),
        mean(&baseline),
        mean(&during),
        mean(&after),
        max(&during).max(max(&baseline)),
        started.elapsed().as_millis(),
        baseline.len() + during.len() + after.len(),
        written,
        max_pad
    ))
}

/// PC microphone RMS for `seconds` (numbers only). Used by the webcam/mic proof.
#[cfg(windows)]
pub fn mic_level(seconds: f32) -> Result<String, String> {
    use std::time::{Duration, Instant};
    init_com()?;
    let (mic, cc) = microphone()?;
    let desc = describe(&mic);
    start(&mic)?;
    let started = Instant::now();
    let mut peak = 0.0f64;
    let mut sum = 0.0f64;
    let mut n = 0usize;
    let mut last_report = Instant::now();
    let mut line = String::new();
    while started.elapsed() < Duration::from_secs_f64(f64::from(seconds)) {
        let (rms, _) = read_rms(&mic, &cc)?;
        if rms > 0.0 {
            peak = peak.max(rms);
            sum += rms;
            n += 1;
        }
        if last_report.elapsed() >= Duration::from_millis(500) {
            line.push_str(&format!("{:.4} ", rms));
            last_report = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    stop(&mic);
    Ok(format!(
        "MIC {desc}: seconds={} packets={} mean_rms={:.5} peak_rms={:.5} samples_500ms=[{}]",
        seconds,
        n,
        if n == 0 { 0.0 } else { sum / n as f64 },
        peak,
        line.trim()
    ))
}

/// Loopback RMS over `seconds` (numbers only). Used to test whether the endpoint's loopback
/// can see a WAV played by Windows itself (`System.Media.SoundPlayer`).
#[cfg(windows)]
pub fn loopback_level(seconds: f32) -> Result<String, String> {
    use std::time::{Duration, Instant};    init_com()?;
    let (cap, cc) = loopback()?;
    let desc = describe(&cap);
    start(&cap)?;
    let started = Instant::now();
    let wall0 = std::time::SystemTime::now();
    let mut values = Vec::new();
    let mut last = Instant::now();
    while started.elapsed() < Duration::from_secs_f64(f64::from(seconds)) {
        let (rms, _) = read_rms(&cap, &cc)?;
        if last.elapsed() >= Duration::from_millis(100) {
            let t_ms = wall0.elapsed().map(|d| d.as_millis()).unwrap_or(0);
            println!("LOOPBACK_SAMPLE t_ms={t_ms} rms={rms:.6}");
            values.push(rms);
            last = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    stop(&cap);
    let max = values.iter().copied().fold(0.0f64, f64::max);
    let mean = if values.is_empty() { 0.0 } else { values.iter().sum::<f64>() / values.len() as f64 };
    let nonzero = values.iter().filter(|v| **v > 0.0005).count();
    Ok(format!(
        "LOOPBACK {desc}: seconds={seconds} samples={} mean_rms={mean:.5} max_rms={max:.5} above_0.0005={nonzero}",
        values.len()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pkt(seq: u32, v: i16) -> PcmPacket {
        PcmPacket { seq, samples: vec![v; PACKET_FRAMES * CHANNELS] }
    }

    #[test]
    fn jitter_buffer_waits_for_target_depth() {
        let mut jb = JitterBuffer::new();
        for s in 1..JITTER_TARGET_PACKETS as u32 {
            jb.push(pkt(s, 100));
        }
        assert!(jb.next().is_none(), "below the target depth: silence");
        jb.push(pkt(JITTER_TARGET_PACKETS as u32, 100));
        assert!(jb.next().is_some(), "the target depth starts playback");
    }

    #[test]
    fn jitter_buffer_rebuffers_after_a_gap_and_counts_one_underrun() {
        let mut jb = JitterBuffer::new();
        for s in 1..=JITTER_TARGET_PACKETS as u32 {
            jb.push(pkt(s, 1));
        }
        for _ in 0..JITTER_TARGET_PACKETS {
            assert!(jb.next().is_some());
        }
        assert!(jb.next().is_none(), "queue empty: a gap");
        assert_eq!(jb.stats.underruns, 1, "one underrun for the whole gap");
        // Dry pump calls while re-arming are not counted again.
        assert!(jb.next().is_none());
        assert_eq!(jb.stats.underruns, 1);
        for s in 10..(10 + JITTER_TARGET_PACKETS as u32 - 1) {
            jb.push(pkt(s, 1));
        }
        assert!(jb.next().is_none(), "below the target again: still silent");
        assert_eq!(jb.stats.underruns, 1);
        jb.push(pkt(10 + JITTER_TARGET_PACKETS as u32 - 1, 1));
        assert!(jb.next().is_some(), "playback restarts at the target depth");
        assert_eq!(jb.stats.underruns, 1);
    }

    #[test]
    fn jitter_buffer_drops_late_and_duplicate_packets() {
        let mut jb = JitterBuffer::new();
        for s in 1..=JITTER_TARGET_PACKETS as u32 {
            jb.push(pkt(s, 1));
        }
        assert!(jb.next().is_some());
        assert!(!jb.push(pkt(2, 1)), "older than the last played packet");
        assert!(!jb.push(pkt(3, 1)), "duplicate");
        assert_eq!(jb.stats.late, 2);
        assert!(jb.push(pkt(JITTER_TARGET_PACKETS as u32 + 1, 1)));
    }

    #[test]
    fn jitter_buffer_caps_depth_and_counts_drops() {
        let mut jb = JitterBuffer::new();
        for s in 1..=20 {
            jb.push(pkt(s, 1));
        }
        assert_eq!(jb.depth(), 12);
        assert_eq!(jb.stats.dropped, 8);
    }

    #[test]
    fn tone_is_1khz_at_the_expected_amplitude() {
        let pk = tone_packets(0.1, 0.06, 7);
        assert_eq!(pk.len(), 10, "100 ms / 10 ms packets");
        assert_eq!(pk[0].seq, 7);
        let rms = rms_i16(&pk[0].samples);
        let expected = 0.06 / std::f64::consts::SQRT_2;
        assert!((rms - expected).abs() < 0.005, "rms {rms} vs {expected}");
        // one period at 1 kHz is 48 frames; the first sample is 0 and the 12th is positive
        assert_eq!(pk[0].samples[0], 0);
        assert!(pk[0].samples[24] > 0);
    }

    #[test]
    fn audio_payload_is_big_endian_and_checked() {
        let mut p = vec![0u8, 0, 0, 7];
        p.extend_from_slice(&1234u64.to_be_bytes());
        p.extend_from_slice(&1000i16.to_be_bytes());
        p.extend_from_slice(&(-1000i16).to_be_bytes());
        let pkt = parse_packet(&p).expect("valid packet");
        assert_eq!(pkt.seq, 7);
        assert_eq!(pkt.samples, vec![1000, -1000]);
        assert_eq!(packet_ts_ms(&p), Some(1234));
        assert!(parse_packet(&p[..10]).is_none(), "short packet refused");
        assert!(parse_packet(&[0u8; 13]).is_none(), "odd tail refused");
    }
}
