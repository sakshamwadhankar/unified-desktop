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
        // RGB32 keeps the brightness math simple; the device converts.
        let mt = MFCreateMediaType().map_err(|e| format!("MFCreateMediaType: {e}"))?;
        mt.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)
            .map_err(|e| format!("SetGUID major: {e}"))?;
        mt.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)
            .map_err(|e| format!("SetGUID subtype: {e}"))?;
        reader
            .SetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32,
                None,
                &mt,
            )
            .map_err(|e| format!("SetCurrentMediaType: {e}"))?;
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
