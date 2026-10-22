//! Phase 11 D-e (PC half): the default webcam through a Media Foundation Source Reader.
//! Numbers only: frames, width x height, frames/s, mean brightness per sample. No frame is
//! ever saved, and the source is shut down (camera light off) before the command returns.

use windows::core::GUID;
use windows::Win32::Media::MediaFoundation::{
    IMFActivate, IMFAttributes, IMFMediaSource, IMFSourceReader, MFCreateAttributes,
    MFCreateMediaType, MFCreateSourceReaderFromMediaSource, MFEnumDeviceSources, MFShutdown,
    MFStartup, MFVideoFormat_RGB32, MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME,
    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID,
    MF_MT_FRAME_SIZE, MF_MT_MAJOR_TYPE, MF_MT_SUBTYPE, MFMediaType_Video, MFSTARTUP_FULL,
    MF_SOURCE_READER_FIRST_VIDEO_STREAM, MF_VERSION,
};
use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};

struct Webcam {
    reader: IMFSourceReader,
    source: IMFMediaSource,
    width: u32,
    height: u32,
}

fn open() -> Result<Webcam, String> {
    open_with_size(0, 0)
}

fn open_with_size(req_w: u32, req_h: u32) -> Result<Webcam, String> {
    unsafe {
        CoInitializeEx(None, COINIT_MULTITHREADED)
            .ok()
            .map_err(|e| format!("CoInitializeEx: {e}"))?;
        MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(|e| format!("MFStartup: {e}"))?;
        let mut attrs: Option<IMFAttributes> = None;
        MFCreateAttributes(&mut attrs, 2).map_err(|e| format!("MFCreateAttributes: {e}"))?;
        let attrs = attrs.ok_or("no attributes")?;
        attrs
            .SetGUID(
                &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
                &MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID,
            )
            .map_err(|e| format!("SetGUID: {e}"))?;
        let mut devices: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        MFEnumDeviceSources(&attrs, &mut devices, &mut count)
            .map_err(|e| format!("MFEnumDeviceSources: {e}"))?;
        if count == 0 || devices.is_null() {
            return Err("no video capture device".into());
        }
        let list = std::slice::from_raw_parts(devices, count as usize);
        let device = list[0].clone().ok_or("device slot empty")?;
        let mut name = windows::core::PWSTR::null();
        let mut n = 0u32;
        let _ = device.GetAllocatedString(&MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &mut name, &mut n);
        let label = if name.is_null() {
            "unknown".to_string()
        } else {
            name.to_string().unwrap_or_else(|_| "unknown".into())
        };
        let source: IMFMediaSource = device
            .ActivateObject()
            .map_err(|e| format!("ActivateObject: {e}"))?;
        let mut rattrs: Option<IMFAttributes> = None;
        MFCreateAttributes(&mut rattrs, 1).map_err(|e| format!("MFCreateAttributes(reader): {e}"))?;
        let rattrs = rattrs.ok_or("no reader attributes")?;
        rattrs
            .SetUINT32(
                &windows::Win32::Media::MediaFoundation::MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING,
                1,
            )
            .map_err(|e| format!("SetUINT32 processing: {e}"))?;
        let reader: IMFSourceReader =
            MFCreateSourceReaderFromMediaSource(&source, &rattrs)
                .map_err(|e| format!("MFCreateSourceReader: {e}"))?;
        // RGB32 keeps the conversion simple; the device converter does the work.
        let mt = MFCreateMediaType().map_err(|e| format!("MFCreateMediaType: {e}"))?;
        mt.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)
            .map_err(|e| format!("SetGUID major: {e}"))?;
        mt.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)
            .map_err(|e| format!("SetGUID subtype: {e}"))?;
        if req_w > 0 && req_h > 0 {
            let packed = (u64::from(req_w) << 32) | u64::from(req_h);
            let _ = mt.SetUINT64(&MF_MT_FRAME_SIZE, packed);
        }
        if let Err(e) = reader.SetCurrentMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32,
            None,
            &mt,
        ) {
            // A device that refuses the exact size still streams at its native size.
            println!("CAM requested {req_w}x{req_h} refused ({e}); using native");
            let mt2 = MFCreateMediaType().map_err(|e| format!("MFCreateMediaType: {e}"))?;
            mt2.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)
                .map_err(|e| format!("SetGUID major: {e}"))?;
            mt2.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)
                .map_err(|e| format!("SetGUID subtype: {e}"))?;
            reader
                .SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32, None, &mt2)
                .map_err(|e| format!("SetCurrentMediaType: {e}"))?;
        }
        let cur = reader
            .GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32)
            .map_err(|e| format!("GetCurrentMediaType: {e}"))?;
        let fs = cur.GetUINT64(&MF_MT_FRAME_SIZE).unwrap_or(0);
        let (width, height) = ((fs >> 32) as u32, (fs & 0xFFFF_FFFF) as u32);
        println!("CAM device=\"{label}\" size={width}x{height} format=RGB32");
        Ok(Webcam { reader, source, width, height })
    }
}

/// Read `frames` samples as fast as the device delivers them (at most `timeout_ms`), and
/// return (samples, brightness list, elapsed).
fn sample(cam: &Webcam, frames: u32, timeout_ms: u64) -> Result<(u32, Vec<f64>), String> {
    use std::time::Instant;
    let started = Instant::now();
    let mut got = 0u32;
    let mut means = Vec::new();
    unsafe {
        while got < frames && started.elapsed().as_millis() < u128::from(timeout_ms) {
            let mut flags = 0u32;
            let mut ts = 0i64;
            let mut sample: Option<windows::Win32::Media::MediaFoundation::IMFSample> = None;
            cam.reader
                .ReadSample(
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32,
                    0,
                    None,
                    Some(&mut flags),
                    Some(&mut ts),
                    Some(&mut sample),
                )
                .map_err(|e| format!("ReadSample: {e}"))?;
            let Some(s) = sample else { continue };
            let buf = s.ConvertToContiguousBuffer().map_err(|e| format!("ConvertToContiguousBuffer: {e}"))?;
            let mut ptr: *mut u8 = std::ptr::null_mut();
            let mut max = 0u32;
            let mut cur = 0u32;
            buf.Lock(&mut ptr, Some(&mut max), Some(&mut cur))
                .map_err(|e| format!("Lock: {e}"))?;
            if !ptr.is_null() && cur > 0 {
                // RGB32: mean of the three colour channels over every pixel (skip alpha).
                let pixels = cur as usize / 4;
                let bytes = std::slice::from_raw_parts(ptr, pixels * 4);
                let mut sum = 0u64;
                for px in bytes.chunks_exact(4) {
                    sum += u64::from(px[0]) + u64::from(px[1]) + u64::from(px[2]);
                }
                means.push(sum as f64 / (pixels as f64 * 3.0 * 255.0));
            }
            let _ = buf.Unlock();
            got += 1;
        }
    }
    Ok((got, means))
}

fn close(cam: Webcam) {
    unsafe {
        let _ = cam
            .reader
            .SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32, false);
        drop(cam.reader);
        drop(cam.source);
        let _ = MFShutdown();
    }
    println!("CAM source shut down (camera released)");
}

/// PC-only proof: open, sample 15 frames, close. Prints numbers only.
pub fn self_test() -> Result<String, String> {
    let cam = open()?;
    let t0 = std::time::Instant::now();
    let (n, means) = sample(&cam, 15, 4000)?;
    let elapsed = t0.elapsed().as_secs_f64();
    let (w, h) = (cam.width, cam.height);
    close(cam);
    if n == 0 {
        return Ok("CAM no frame arrived within 4 s".to_string());
    }
    let first = means.first().copied().unwrap_or(0.0);
    let last = means.last().copied().unwrap_or(0.0);
    let min = means.iter().copied().fold(1.0f64, f64::min);
    let max = means.iter().copied().fold(0.0f64, f64::max);
    Ok(format!(
        "CAM {w}x{h} frames={n} seconds={elapsed:.2} fps={:.1} brightness first={first:.3} last={last:.3} min={min:.3} max={max:.3} changed={:.3}",
        n as f64 / elapsed,
        (max - min).abs()
    ))
}

#[allow(dead_code)]
fn _guid(_: GUID) {}

// ------------------------------------------------------------------ PC -> phone stream

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::Arc;

/// One live PC->phone webcam + microphone feed for CH_CAM. Dropping it stops both threads
/// and releases the camera (the light goes off) and the microphone.
pub struct CamStream {
    stop: Arc<AtomicBool>,
    threads: Vec<std::thread::JoinHandle<()>>,
    pub frames: Arc<AtomicU64>,
    pub audio_packets: Arc<AtomicU64>,
}

impl Drop for CamStream {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
        println!("CAM stream stopped (camera and microphone released)");
    }
}

fn encode_cam_message(seq: u64, ts_ms: u64, w: u32, h: u32, jpeg: &[u8]) -> Vec<u8> {
    let mut m = Vec::with_capacity(26 + jpeg.len());
    m.push(1u8); // CAM kind, same header as android Phase11Media.encodeCam
    m.extend_from_slice(&seq.to_be_bytes());
    m.extend_from_slice(&ts_ms.to_be_bytes());
    m.extend_from_slice(&(w as u16).to_be_bytes());
    m.extend_from_slice(&(h as u16).to_be_bytes());
    m.push(1u8); // JPEG
    m.extend_from_slice(&(jpeg.len() as u32).to_be_bytes());
    m.extend_from_slice(jpeg);
    m
}

fn encode_mic_message(seq: u32, ts_ms: u64, mono: &[f32]) -> Vec<u8> {
    let mut m = Vec::with_capacity(1 + 12 + mono.len() * 4);
    m.push(2u8); // PCM kind
    m.extend_from_slice(&seq.to_be_bytes());
    m.extend_from_slice(&ts_ms.to_be_bytes());
    for &v in mono {
        let s = (v.clamp(-1.0, 1.0) * 32767.0) as i16;
        m.extend_from_slice(&s.to_be_bytes()); // left
        m.extend_from_slice(&s.to_be_bytes()); // right
    }
    m
}

/// Open the webcam (640x480 RGB32 requested) and the microphone, then stream JPEG frames
/// (<= 15 fps) and 10 ms 48 kHz mono-as-stereo PCM packets into the channel. Numbers are
/// counted; no frame or sample is ever written to disk.
pub fn start_stream(width: u32, height: u32, quality: u8) -> Result<(CamStream, Receiver<Vec<u8>>), String> {
    let (tx, rx) = std::sync::mpsc::channel::<Vec<u8>>();
    let stop = Arc::new(AtomicBool::new(false));
    let frames = Arc::new(AtomicU64::new(0));
    let audio_packets = Arc::new(AtomicU64::new(0));
    let mut threads = Vec::new();

    let cam_tx: Sender<Vec<u8>> = tx.clone();
    let cam_stop = stop.clone();
    let cam_frames = frames.clone();
    threads.push(std::thread::spawn(move || {
        let cam = match open_with_size(width, height) {
            Ok(c) => c,
            Err(e) => {
                println!("CAM stream open failed: {e}");
                return;
            }
        };
        let (w, h) = (cam.width, cam.height);
        let started = std::time::Instant::now();
        let mut seq = 0u64;
        let mut next = std::time::Instant::now();
        while !cam_stop.load(Ordering::SeqCst) {
            let mut flags = 0u32;
            let mut ts = 0i64;
            let mut sample: Option<windows::Win32::Media::MediaFoundation::IMFSample> = None;
            if let Err(e) = unsafe {
                cam.reader.ReadSample(
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32,
                    0,
                    None,
                    Some(&mut flags),
                    Some(&mut ts),
                    Some(&mut sample),
                )
            } {
                println!("CAM stream read failed: {e}");
                break;
            }
            let Some(s) = sample else { continue };
            let got = std::time::Instant::now();
            if got < next {
                continue; // device may deliver faster than the 15 fps we send
            }
            let buf = match unsafe { s.ConvertToContiguousBuffer() } {
                Ok(b) => b,
                Err(_) => continue,
            };
            let mut ptr: *mut u8 = std::ptr::null_mut();
            let mut max = 0u32;
            let mut cur = 0u32;
            if unsafe { buf.Lock(&mut ptr, Some(&mut max), Some(&mut cur)) }.is_err() || ptr.is_null() {
                continue;
            }
            // The phone only shows a 640-wide window. Convert BGRX->RGB and downscale in ONE
            // pass (the old two-pass code converted all 921,600 pixels, then downscaled with
            // float math, ~30 ms per frame; that is what dragged the send rate from the 15 fps
            // target down to the 10 fps the phone saw).
            let (ow, oh) = if w > 640 {
                (640u32, ((u64::from(h) * 640 / u64::from(w)) as u32).max(2) & !1)
            } else {
                (w, h)
            };
            let mut frame_rgb: Vec<u8> = Vec::with_capacity((ow * oh * 3) as usize);
            let pixels = cur as usize / 4;
            unsafe {
                let bytes = std::slice::from_raw_parts(ptr, pixels * 4);
                let sx = w as f32 / ow as f32;
                let sy = h as f32 / oh as f32;
                for y in 0..oh {
                    let row = ((y as f32 * sy) as usize) * (w as usize) * 4;
                    for x in 0..ow {
                        let i = row + ((x as f32 * sx) as usize) * 4;
                        frame_rgb.push(bytes[i + 2]); // RGB32 is B,G,R,X in memory
                        frame_rgb.push(bytes[i + 1]);
                        frame_rgb.push(bytes[i]);
                    }
                }
            }
            let _ = unsafe { buf.Unlock() };
            let (fw, fh) = (ow, oh);
            let mut jpeg = Vec::new();
            let encoded = {
                let enc = jpeg_encoder::Encoder::new(&mut jpeg, quality);
                enc.encode(&frame_rgb, fw as u16, fh as u16, jpeg_encoder::ColorType::Rgb).is_ok()
            };
            if encoded && !jpeg.is_empty() {
                let msg = encode_cam_message(seq, started.elapsed().as_millis() as u64, fw, fh, &jpeg);
                if cam_tx.send(msg).is_err() {
                    break;
                }
                seq += 1;
                cam_frames.fetch_add(1, Ordering::Relaxed);
                if seq.is_multiple_of(50) {
                    println!("CAM stream frames={seq} {fw}x{fh} jpeg_bytes={}", jpeg.len());
                }
            }
            // Cadence from the sample time. The old `now + 66ms` measured from after the work
            // made the effective period ~99 ms on the device's 33 ms sample grid (10.0 fps
            // measured, 15 targeted).
            next = got + std::time::Duration::from_millis(66);
        }
        close(cam);
    }));

    let mic_tx = tx;
    let mic_stop = stop.clone();
    let mic_count = audio_packets.clone();
    threads.push(std::thread::spawn(move || {
        let (mic, cc) = match crate::audio::microphone() {
            Ok(v) => v,
            Err(e) => {
                println!("MIC stream open failed: {e}");
                return;
            }
        };
        let desc = crate::audio::describe(&mic);
        if let Err(e) = crate::audio::start(&mic) {
            println!("MIC stream start failed: {e}");
            return;
        }
        println!("MIC stream start {desc} -> 48 kHz mono packets");
        let rate = f64::from(mic.rate.max(1));
        let ratio = rate / 48_000.0;
        let started = std::time::Instant::now();
        let mut pending: Vec<f32> = Vec::new();
        let mut out: Vec<f32> = Vec::with_capacity(480);
        let mut pos = 0.0f64;
        let mut seq = 0u32;
        while !mic_stop.load(Ordering::SeqCst) {
            let _ = crate::audio::read_mono(&mic, &cc, &mut pending);
            while pos < pending.len() as f64 {
                let i = pos.floor() as usize;
                let frac = (pos - i as f64) as f32;
                let a = pending[i];
                let b = if i + 1 < pending.len() { pending[i + 1] } else { a };
                out.push(a + (b - a) * frac);
                pos += ratio;
                if out.len() >= 480 {
                    let msg = encode_mic_message(seq, started.elapsed().as_millis() as u64, &out);
                    out.clear();
                    if mic_tx.send(msg).is_err() {
                        crate::audio::stop(&mic);
                        return;
                    }
                    seq = seq.wrapping_add(1);
                    mic_count.fetch_add(1, Ordering::Relaxed);
                }
            }
            let consumed = (pos.floor() as usize).min(pending.len());
            if consumed > 0 {
                pending.drain(0..consumed);
                pos -= consumed as f64;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        crate::audio::stop(&mic);
        println!("MIC stream stopped packets={seq}");
    }));

    Ok((CamStream { stop, threads, frames, audio_packets }, rx))
}
