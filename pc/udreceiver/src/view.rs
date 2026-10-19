//! Phase-5 `udreceiver view`: shows the phone's desktop surface on the PC.
//!
//! HARDWARE decode, the Windows way: the system H.264 decoder MFT
//! (`CLSID_CMSH264DecoderMFT`) is handed an `IMFDXGIDeviceManager` on a D3D11 device, so
//! it runs DXVA/D3D11 and returns decoded frames as **NV12 DXGI textures** with no CPU
//! copy. An `ID3D11VideoProcessor` converts NV12 -> BGRA straight into a flip-model swap
//! chain (FLIP_DISCARD, max frame latency 1). `CODECAPI_AVLowLatencyMode` is turned on.
//!
//! If the decoder refuses the D3D manager (`--soft`), the same MFT runs in system memory
//! and the NV12 is converted on the CPU. Every status line prints `d3d11=true|false`, so
//! nothing is ever reported as hardware when it is not.
//!
//! Input: window messages -> `phase5::InputEvent` on CH_INPUT immediately, scaled to
//! desktop coordinates. Pointer moves coalesce; keys and buttons never do. Focus loss, Esc
//! and window close all send release-all so nothing stays stuck on the phone.
//!
//! One worker thread owns the connection, decoder and swap chain; the main thread runs the
//! Win32 message loop.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use windows::core::{Interface, GUID, HSTRING, PCWSTR};
use windows::Win32::Foundation::{COLORREF, HINSTANCE, HMODULE, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Direct3D::{
    D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_11_1,
};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, D3D11_BIND_SHADER_RESOURCE, D3D11_BOX, D3D11_CPU_ACCESS_WRITE,
    D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
    D3D11_MAPPED_SUBRESOURCE, D3D11_MAP_WRITE_DISCARD, D3D11_SDK_VERSION, D3D11_TEX2D_VPIV,
    D3D11_TEX2D_VPOV, D3D11_TEXTURE2D_DESC, D3D11_USAGE_DYNAMIC,
    D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE, D3D11_VIDEO_PROCESSOR_CONTENT_DESC,
    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC, D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC_0,
    D3D11_VIDEO_PROCESSOR_OUTPUT_RATE_NORMAL, D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC,
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC_0, D3D11_VIDEO_PROCESSOR_STREAM,
    D3D11_VIDEO_USAGE_PLAYBACK_NORMAL, D3D11_VPIV_DIMENSION_TEXTURE2D,
    D3D11_VPOV_DIMENSION_TEXTURE2D, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D,
    ID3D11VideoContext, ID3D11VideoDevice, ID3D11VideoProcessor, ID3D11VideoProcessorEnumerator,
    ID3D11VideoProcessorInputView, ID3D11VideoProcessorOutputView,
};
use windows::Win32::Graphics::Dxgi::Common::{
    DXGI_ALPHA_MODE_IGNORE, DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_RATIONAL, DXGI_SAMPLE_DESC,
};
use windows::Win32::Graphics::Dxgi::{
    IDXGIDevice, IDXGIFactory2, IDXGISwapChain1, IDXGISwapChain2, DXGI_MWA_NO_ALT_ENTER,
    DXGI_SCALING_STRETCH,
    DXGI_PRESENT, DXGI_SWAP_CHAIN_DESC1, DXGI_SWAP_EFFECT_FLIP_DISCARD,
    DXGI_USAGE_RENDER_TARGET_OUTPUT,
};
use windows::Win32::Media::MediaFoundation::{
    ICodecAPI, IMFDXGIBuffer, IMFDXGIDeviceManager, IMFMediaBuffer, IMFMediaType, IMFSample,
    IMFTransform, MFCreateDXGIDeviceManager, MFCreateMediaType, MFCreateMemoryBuffer,
    MFCreateSample, MFT_MESSAGE_COMMAND_FLUSH, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING,
    MFT_MESSAGE_NOTIFY_START_OF_STREAM, MFT_MESSAGE_SET_D3D_MANAGER, MFT_OUTPUT_DATA_BUFFER,
    MFT_OUTPUT_STREAM_PROVIDES_SAMPLES, MFSTARTUP_FULL, MFShutdown, MFStartup, MFMediaType_Video,
    MFVideoFormat_H264, MFVideoFormat_NV12, MFVideoInterlace_Progressive,
    MF_MT_FRAME_RATE, MF_MT_FRAME_SIZE, MF_MT_INTERLACE_MODE, MF_MT_MAJOR_TYPE, MF_MT_SUBTYPE,
    MF_SA_D3D11_AWARE, MF_E_NOTACCEPTING, MF_E_TRANSFORM_NEED_MORE_INPUT,
    MF_E_TRANSFORM_STREAM_CHANGE, MF_VERSION, CODECAPI_AVLowLatencyMode,
};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, ReleaseCapture, SetCapture, SetFocus, keybd_event, KEYBD_EVENT_FLAGS,
    KEYEVENTF_KEYUP, VK_CONTROL, VK_MENU, VK_SHIFT,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetClientRect, GetForegroundWindow,
    GetMessageW,
    GetSystemMetrics, PostMessageW, PostQuitMessage, RegisterClassW, SetForegroundWindow,
    SetWindowLongPtrW, SetWindowPos, ShowWindow, TranslateMessage,
    GWL_STYLE, HWND_TOPMOST, MSG, SM_CXSCREEN, SM_CYSCREEN,
    SWP_FRAMECHANGED, SWP_SHOWWINDOW, SW_SHOW, WM_CLOSE, WM_DESTROY, WM_KEYDOWN, WM_KEYUP,
    WM_KILLFOCUS, WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MBUTTONDOWN, WM_MBUTTONUP, WM_MOUSEMOVE,
    WM_MOUSEWHEEL, WM_RBUTTONDOWN, WM_RBUTTONUP, WM_SIZE, WM_SYSKEYDOWN,
    WM_SYSKEYUP, WNDCLASSW, WS_OVERLAPPEDWINDOW, WS_POPUP, WS_VISIBLE, WINDOW_EX_STYLE,
};

use crate::phase5::{self, InputEvent, VideoFrame};
use crate::protocol::{Message, PriorityWriter, CH_CONTROL, CH_INPUT, CH_VIDEO};

const TAG: &str = "p5pc";
/// Frames remembered for the latency report (about 7 s at 84 fps).
const FRAME_RING: usize = 600;

/// `CLSID_CMSH264DecoderMFT` - Windows' own H.264 decoder (DXVA when a D3D manager is set).
const CLSID_CMSH264_DECODER_MFT: GUID = GUID::from_u128(0x62CE7E72_4C71_4D20_B15D_452831A87D9D);

/// The one window of this process; the WndProc reads it without pointer juggling.
static SHARED: OnceLock<Arc<Shared>> = OnceLock::new();

struct Shared {
    hwnd: AtomicU64,
    events: Mutex<Vec<InputEvent>>,
    pointer: Mutex<(i32, i32)>,
    running: AtomicBool,
    fullscreen: AtomicBool,
    client_w: AtomicU32,
    client_h: AtomicU32,
    need_keyframe: AtomicU32,
    release_all: AtomicU32,
    presented: AtomicU64,
    decoded: AtomicU64,
    dropped_frames: AtomicU64,
    bytes_in: AtomicU64,
    decode_us_total: AtomicU64,
    last_seq: AtomicU32,
    d3d11: AtomicU32,
}

impl Shared {
    fn new() -> Arc<Self> {
        Arc::new(Shared {
            hwnd: AtomicU64::new(0),
            events: Mutex::new(Vec::new()),
            pointer: Mutex::new((960, 540)),
            running: AtomicBool::new(true),
            fullscreen: AtomicBool::new(true),
            client_w: AtomicU32::new(1920),
            client_h: AtomicU32::new(1080),
            need_keyframe: AtomicU32::new(0),
            release_all: AtomicU32::new(0),
            presented: AtomicU64::new(0),
            decoded: AtomicU64::new(0),
            dropped_frames: AtomicU64::new(0),
            bytes_in: AtomicU64::new(0),
            decode_us_total: AtomicU64::new(0),
            last_seq: AtomicU32::new(0),
            d3d11: AtomicU32::new(0),
        })
    }

    fn push(&self, e: InputEvent) {
        if let Ok(mut v) = self.events.lock() {
            if v.len() < 8192 {
                v.push(e);
            }
        }
    }

    /// Pointer moves coalesce - only the newest position matters; keys/buttons never drop.
    fn drain_events(&self) -> Vec<InputEvent> {
        let mut v = match self.events.lock() {
            Ok(mut g) => std::mem::take(&mut *g),
            Err(_) => Vec::new(),
        };
        let mut last_move: Option<usize> = None;
        for (i, e) in v.iter().enumerate() {
            if matches!(e, InputEvent::Move { .. }) {
                last_move = Some(i);
            }
        }
        if let Some(i) = last_move {
            let keep = v[i].clone();
            v.retain(|e| !matches!(e, InputEvent::Move { .. }));
            let at = i.min(v.len());
            v.insert(at, keep);
        }
        v
    }

    fn client(&self) -> (i32, i32) {
        (
            self.client_w.load(Ordering::SeqCst) as i32,
            self.client_h.load(Ordering::SeqCst) as i32,
        )
    }
}

pub fn run(
    addr: &str,
    seconds: u64,
    force_soft: bool,
    identity: &crate::crypto::ReceiverIdentity,
    store: &crate::crypto::TrustedPhoneStore,
) -> Result<(), String> {
    println!("[{TAG}] connecting to {addr}");
    let (mut session, description) = crate::session::connect_and_pair(addr, identity, store, "PC")?;
    println!("[{TAG}] {description}");
    session
        .tls
        .get_mut()
        .set_read_timeout(Some(Duration::from_millis(2)))
        .map_err(|e| format!("set_read_timeout: {e}"))?;
    let _ = session.tls.get_mut().set_nodelay(true);

    let shared = Shared::new();
    SHARED.set(shared.clone()).map_err(|_| "view already running")?;

    let hwnd = create_window(&shared)?;
    shared.hwnd.store(hwnd.0 as u64, Ordering::SeqCst);
    let (cw, ch) = shared.client();
    focus_window(hwnd);
    println!(
        "[{TAG}] window up: hwnd={:?} client={cw}x{ch} foreground={} (Esc quits, F11 windowed)",
        hwnd.0,
        foreground_is(hwnd)
    );

    let sh = shared.clone();
    let pipeline = std::thread::Builder::new()
        .name("p5-pipeline".into())
        .spawn(move || {
            let r = pipeline_loop(&mut session, &sh, force_soft, seconds);
            sh.running.store(false, Ordering::SeqCst);
            let h = HWND(sh.hwnd.load(Ordering::SeqCst) as *mut std::ffi::c_void);
            unsafe {
                let _ = PostMessageW(Some(h), WM_CLOSE, WPARAM(0), LPARAM(0));
            }
            r
        })
        .map_err(|e| format!("spawn pipeline: {e}"))?;

    let sh2 = shared.clone();
    let stats = std::thread::Builder::new()
        .name("p5-stats".into())
        .spawn(move || stats_loop(sh2))
        .map_err(|e| format!("spawn stats: {e}"))?;

    unsafe {
        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    shared.running.store(false, Ordering::SeqCst);
    let r = pipeline.join().map_err(|_| "pipeline panicked")?;
    let _ = stats.join();
    println!("[{TAG}] window closed");
    r
}

fn stats_loop(shared: Arc<Shared>) {
    let mut last = Instant::now();
    let mut last_presented = 0u64;
    let mut last_bytes = 0u64;
    let mut last_decode_us = 0u64;
    let mut last_decoded = 0u64;
    let mut last_focus_claim = Instant::now() - Duration::from_secs(10);
    loop {
        std::thread::sleep(Duration::from_millis(1000));
        if !shared.running.load(Ordering::SeqCst) {
            return;
        }
        // Keep the keyboard on this window: without it, PC keystrokes go to whatever else is
        // open and the phone never sees them. Re-claimed at most every 2 s.
        let hwnd = HWND(shared.hwnd.load(Ordering::Relaxed) as *mut std::ffi::c_void);
        if !hwnd.0.is_null()
            && !foreground_is(hwnd)
            && last_focus_claim.elapsed() >= Duration::from_secs(2)
        {
            focus_window(hwnd);
            last_focus_claim = Instant::now();
            println!("[{TAG}] keyboard focus reclaimed by the session window");
        }
        let now = Instant::now();
        let dt = now.duration_since(last).as_secs_f64().max(1e-6);
        let presented = shared.presented.load(Ordering::Relaxed);
        let bytes = shared.bytes_in.load(Ordering::Relaxed);
        let decode_us = shared.decode_us_total.load(Ordering::Relaxed);
        let decoded = shared.decoded.load(Ordering::Relaxed);
        let fps = (presented - last_presented) as f64 / dt;
        let kbit = (bytes - last_bytes) as f64 * 8.0 / 1000.0 / dt;
        let mean_dec = if decoded > last_decoded {
            // decode_us_total is in microseconds; VSTAT prints milliseconds
            (decode_us - last_decode_us) as f64 / (decoded - last_decoded) as f64 / 1000.0
        } else {
            0.0
        };
        println!(
            "[{TAG}] VSTAT fps={fps:.1} presented={presented} decoded={decoded} dropped={} \
             rx={kbit:.0}kbit/s decode={mean_dec:.2}ms seq={} d3d11={}",
            shared.dropped_frames.load(Ordering::Relaxed),
            shared.last_seq.load(Ordering::Relaxed),
            shared.d3d11.load(Ordering::Relaxed) == 1,
        );
        last = now;
        last_presented = presented;
        last_bytes = bytes;
        last_decode_us = decode_us;
        last_decoded = decoded;
    }
}

// ------------------------------------------------------------------ window

fn foreground_is(hwnd: HWND) -> bool {
    unsafe { GetForegroundWindow() == hwnd }
}

/// Windows only lets a process take the foreground if it owns the last input event; a pair of
/// synthetic ALT events puts us in that state (the documented workaround), after which a plain
/// SetForegroundWindow succeeds. The session window must hold the keyboard, or PC keystrokes
/// land in whatever else the user has open.
fn focus_window(hwnd: HWND) {
    unsafe {
        let alt = VK_MENU.0 as u8;
        keybd_event(alt, 0, KEYBD_EVENT_FLAGS(0), 0);
        keybd_event(alt, 0, KEYEVENTF_KEYUP, 0);
        let _ = SetForegroundWindow(hwnd);
    }
}

fn create_window(shared: &Arc<Shared>) -> Result<HWND, String> {
    unsafe {
        let hinstance: HMODULE = GetModuleHandleW(None).map_err(|e| format!("GetModuleHandleW: {e}"))?;
        let class_name = HSTRING::from("udreceiver-view");
        let title = HSTRING::from("unified-desktop view");
        let wc = WNDCLASSW {
            lpfnWndProc: Some(wndproc),
            hInstance: HINSTANCE(hinstance.0),
            lpszClassName: PCWSTR(class_name.as_ptr()),
            hbrBackground: windows::Win32::Graphics::Gdi::CreateSolidBrush(COLORREF(0x000000)),
            ..Default::default()
        };
        // Registering twice in one process is harmless; the first result is what matters.
        let _ = RegisterClassW(&wc);
        let sw = GetSystemMetrics(SM_CXSCREEN);
        let sh = GetSystemMetrics(SM_CYSCREEN);
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            PCWSTR(class_name.as_ptr()),
            PCWSTR(title.as_ptr()),
            WS_POPUP | WS_VISIBLE,
            0,
            0,
            sw,
            sh,
            None,
            None,
            Some(HINSTANCE(hinstance.0)),
            Some(Arc::as_ptr(shared) as *const std::ffi::c_void),
        )
        .map_err(|e| format!("CreateWindowExW: {e}"))?;
        let _ = SetWindowPos(hwnd, Some(HWND_TOPMOST), 0, 0, sw, sh, SWP_SHOWWINDOW);
        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = SetFocus(Some(hwnd));
        focus_window(hwnd);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        shared.client_w.store((rc.right - rc.left).max(1) as u32, Ordering::SeqCst);
        shared.client_h.store((rc.bottom - rc.top).max(1) as u32, Ordering::SeqCst);
        Ok(hwnd)
    }
}

fn mods() -> u8 {
    unsafe {
        let shift = GetKeyState(VK_SHIFT.0 as i32) < 0;
        let ctrl = GetKeyState(VK_CONTROL.0 as i32) < 0;
        let alt = GetKeyState(VK_MENU.0 as i32) < 0;
        phase5::mods_from_shift(shift, ctrl, alt, false)
    }
}

/// PC monotonic microseconds (the wire carries the low 32 bits; sync owns the epoch).
fn now_us() -> u32 {
    now_us64() as u32
}

/// The same clock at full width, for local bookkeeping (frame ring, latency stages).
fn now_us64() -> i64 {
    static BASE: OnceLock<Instant> = OnceLock::new();
    let base = *BASE.get_or_init(Instant::now);
    Instant::now().duration_since(base).as_micros() as i64
}

fn scaled(shared: &Shared, x: i32, y: i32) -> (i32, i32) {
    let (cw, ch) = shared.client();
    // The video is letterboxed inside the client (the same fit() the blit uses), so pointer
    // coordinates must be mapped through that rect - mapping through the whole client area
    // would land ~5% low for a 16:10 window showing a 16:9 desktop.
    let (vw, vh) = fit(phase5::DESKTOP_W, phase5::DESKTOP_H, cw, ch);
    let (ox, oy) = ((cw - vw) / 2, (ch - vh) / 2);
    phase5::scale_to_desktop(x - ox, y - oy, vw, vh, phase5::DESKTOP_W, phase5::DESKTOP_H)
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    let shared = match SHARED.get() {
        Some(s) => s.clone(),
        None => return DefWindowProcW(hwnd, msg, wparam, lparam),
    };
    match msg {
        WM_CLOSE | WM_DESTROY => {
            shared.running.store(false, Ordering::SeqCst);
            shared.release_all.store(1, Ordering::SeqCst);
            PostQuitMessage(0);
            LRESULT(0)
        }
        WM_SIZE => {
            let w = (lparam.0 & 0xFFFF) as u32;
            let h = ((lparam.0 >> 16) & 0xFFFF) as u32;
            if w > 0 && h > 0 {
                shared.client_w.store(w, Ordering::SeqCst);
                shared.client_h.store(h, Ordering::SeqCst);
            }
            LRESULT(0)
        }
        WM_KILLFOCUS => {
            // Never leave a key or a button down on the phone.
            shared.release_all.store(1, Ordering::SeqCst);
            LRESULT(0)
        }
        WM_MOUSEMOVE => {
            let x = (lparam.0 & 0xFFFF) as i16 as i32;
            let y = ((lparam.0 >> 16) & 0xFFFF) as i16 as i32;
            let (dx, dy) = scaled(&shared, x, y);
            if let Ok(mut p) = shared.pointer.lock() {
                *p = (dx, dy);
            }
            shared.push(InputEvent::Move { x: dx, y: dy });
            LRESULT(0)
        }
        WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN | WM_LBUTTONUP | WM_RBUTTONUP
        | WM_MBUTTONUP => {
            let x = (lparam.0 & 0xFFFF) as i16 as i32;
            let y = ((lparam.0 >> 16) & 0xFFFF) as i16 as i32;
            let (dx, dy) = scaled(&shared, x, y);
            let down = matches!(msg, WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN);
            let button = match msg {
                WM_LBUTTONDOWN | WM_LBUTTONUP => phase5::BTN_LEFT,
                WM_RBUTTONDOWN | WM_RBUTTONUP => phase5::BTN_RIGHT,
                _ => phase5::BTN_MIDDLE,
            };
            shared.push(InputEvent::Button { button, down, x: dx, y: dy, t_pc_us: now_us() });
            if down {
                let _ = SetCapture(hwnd);
            } else {
                let _ = ReleaseCapture();
            }
            LRESULT(0)
        }
        WM_MOUSEWHEEL => {
            let delta = ((wparam.0 >> 16) & 0xFFFF) as i16 as i32;
            let (dx, dy) = *shared.pointer.lock().unwrap_or_else(|e| e.into_inner());
            // one notch (120) scrolls 60 desktop pixels
            shared.push(InputEvent::Wheel { delta: delta / 2, x: dx, y: dy, t_pc_us: now_us() });
            LRESULT(0)
        }
        WM_KEYDOWN | WM_SYSKEYDOWN | WM_KEYUP | WM_SYSKEYUP => {
            let vk = (wparam.0 & 0xFF) as u8;
            let scan = ((lparam.0 >> 16) & 0xFF) as u8;
            let down = matches!(msg, WM_KEYDOWN | WM_SYSKEYDOWN);
            if vk == 0x1B && down {
                shared.running.store(false, Ordering::SeqCst);
                shared.release_all.store(1, Ordering::SeqCst);
                PostQuitMessage(0);
                return LRESULT(0);
            }
            if vk == 0x7A && down {
                toggle_fullscreen(hwnd, &shared);
                return LRESULT(0);
            }
            // Alt+Enter must not be handled by DefWindowProc (no menu beeps either).
            if vk == 0x0D && (msg == WM_SYSKEYDOWN || msg == WM_SYSKEYUP) {
                return LRESULT(0);
            }
            shared.push(InputEvent::Key { down, vk, scan, mods: mods(), t_pc_us: now_us() });
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn toggle_fullscreen(hwnd: HWND, shared: &Shared) {
    unsafe {
        if shared.fullscreen.load(Ordering::SeqCst) {
            let _ = SetWindowLongPtrW(hwnd, GWL_STYLE, (WS_OVERLAPPEDWINDOW | WS_VISIBLE).0 as isize);
            let _ = SetWindowPos(hwnd, Some(HWND_TOPMOST), 100, 100, 1280, 720,
                SWP_SHOWWINDOW | SWP_FRAMECHANGED);
            shared.fullscreen.store(false, Ordering::SeqCst);
        } else {
            let sw = GetSystemMetrics(SM_CXSCREEN);
            let sh = GetSystemMetrics(SM_CYSCREEN);
            let _ = SetWindowLongPtrW(hwnd, GWL_STYLE, (WS_POPUP | WS_VISIBLE).0 as isize);
            let _ = SetWindowPos(hwnd, Some(HWND_TOPMOST), 0, 0, sw, sh,
                SWP_SHOWWINDOW | SWP_FRAMECHANGED);
            shared.fullscreen.store(true, Ordering::SeqCst);
        }
        println!("[{TAG}] fullscreen={} (F11 toggles)", shared.fullscreen.load(Ordering::SeqCst));
    }
}

// ------------------------------------------------------------------ pipeline

struct Pipeline {
    shared: Arc<Shared>,
    #[allow(dead_code)] // held so the D3D11 device outlives the decoder and swap chain
    device: ID3D11Device,
    d3d_ctx: ID3D11DeviceContext,
    video_device: ID3D11VideoDevice,
    video_ctx: ID3D11VideoContext,
    swap: IDXGISwapChain1,
    dec: Decoder,
    vp: Option<VideoProc>,
    awaiting_keyframe: bool,
    logged_frames: u64,
    /// B-047: every access unit the phone sent, decoded or not (the video-on pacer stops on
    /// the first one).
    frames_in: u64,
    /// Recent frames: (seq, received, presented), PC microseconds. The phone's latency
    /// report names a frame seq; this ring supplies that frame's PC-side times.
    frames: std::collections::VecDeque<(u32, i64, i64)>,
    /// Latency reports that arrived before their frame.
    pending_lat: Vec<PendingLat>,
}

fn pipeline_loop(
    session: &mut crate::session::Session,
    shared: &Arc<Shared>,
    force_soft: bool,
    seconds: u64,
) -> Result<(), String> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(|e| format!("MFStartup: {e}"))?;
    }
    let hwnd = HWND(shared.hwnd.load(Ordering::SeqCst) as *mut std::ffi::c_void);
    let (device, d3d_ctx) = create_d3d11()?;
    let video_device: ID3D11VideoDevice =
        device.cast().map_err(|e| format!("ID3D11VideoDevice: {e}"))?;
    let video_ctx: ID3D11VideoContext =
        d3d_ctx.cast().map_err(|e| format!("ID3D11VideoContext: {e}"))?;
    describe_adapter(&device);
    let swap = create_swap_chain(&device, hwnd)?;
    let dec = create_decoder(&device, force_soft)?;
    shared.d3d11.store(u32::from(dec.d3d11), Ordering::SeqCst);
    println!(
        "[{TAG}] DECODE hardware={} path={}",
        dec.d3d11,
        if dec.d3d11 { "D3D11 NV12 surface" } else { "system memory NV12 + CPU colour convert" }
    );

    let mut p = Pipeline {
        shared: shared.clone(),
        device,
        d3d_ctx,
        video_device,
        video_ctx,
        swap,
        dec,
        vp: None,
        awaiting_keyframe: false,
        logged_frames: 0,
        frames_in: 0,
        frames: std::collections::VecDeque::with_capacity(FRAME_RING),
        pending_lat: Vec::new(),
    };

    let start = Instant::now();
    let mut read_buf = vec![0u8; 65536];
    let mut decoder = crate::protocol::FrameDecoder::new();
    let mut pending: std::collections::VecDeque<Message> = std::collections::VecDeque::new();
    let mut asm_video = phase5::ChunkAssembler::new();
    let mut asm_other = phase5::ChunkAssembler::new();
    let mut last_rx = Instant::now();
    let mut last_csq = Instant::now() - Duration::from_secs(2);
    let mut clock = phase5::ClockSync::new();
    let mut last_need = Instant::now() - Duration::from_secs(9);
    // B-047: ask for video; keep asking until the first access unit shows up.
    let mut video_on = phase5::VideoOnPacer::new();
    let started_at = Instant::now();

    while shared.running.load(Ordering::SeqCst) {
        if seconds > 0 && start.elapsed() > Duration::from_secs(seconds) {
            println!("[{TAG}] --seconds {seconds} reached");
            break;
        }
        // ---- outgoing: input, control, clock, keep-alive (all through the priority writer)
        let events = shared.drain_events();
        if !events.is_empty() {
            let payload = phase5::pack_input_events(&events);
            let mut w = PriorityWriter::new(&mut session.tls);
            let _ = w.push(Message::new(CH_INPUT, payload)?);
            let _ = w.emit_all();
        }
        if shared.release_all.swap(0, Ordering::SeqCst) == 1 {
            let mut w = PriorityWriter::new(&mut session.tls);
            let _ = w.push(Message::new(CH_CONTROL, phase5::CTRL_RELEASE_ALL.as_bytes().to_vec())?);
            let _ = w.emit_all();
            println!("[{TAG}] -> phone release-all");
        }
        if shared.need_keyframe.swap(0, Ordering::SeqCst) > 0
            && last_need.elapsed() > Duration::from_millis(300)
        {
            let mut w = PriorityWriter::new(&mut session.tls);
            let _ = w.push(Message::new(
                CH_CONTROL,
                phase5::CTRL_NEED_KEYFRAME.as_bytes().to_vec(),
            )?);
            let _ = w.emit_all();
            last_need = Instant::now();
            p.awaiting_keyframe = true;
            println!("[{TAG}] -> phone need-keyframe");
        }
        // B-047: video starts because the receiver asks for it, never because a session
        // exists. `pair` and `bench` do not run this loop at all.
        if video_on.due(started_at.elapsed().as_millis() as u64, p.frames_in > 0) {
            let mut w = PriorityWriter::new(&mut session.tls);
            let _ = w.push(Message::new(
                CH_CONTROL,
                phase5::CTRL_VIDEO_ON.as_bytes().to_vec(),
            )?);
            let _ = w.emit_all();
            if video_on.sends() <= 5 {
                println!("[{TAG}] -> phone video-on (ask #{})", video_on.sends());
            }
        }
        if last_csq.elapsed() > Duration::from_secs(1) {
            let t = now_us();
            let mut w = PriorityWriter::new(&mut session.tls);
            let _ = w.push(Message::new(CH_CONTROL, format!("csq {t}").into_bytes())?);
            let _ = w.emit_all();
            last_csq = Instant::now();
        }
        if last_rx.elapsed() > Duration::from_secs(5) {
            let mut w = PriorityWriter::new(&mut session.tls);
            let _ = w.push(Message::new(CH_CONTROL, Vec::new())?);
            let _ = w.emit_all();
        }

        // ---- incoming
        let mut worked = false;
        loop {
            let Some(m) = pending.pop_front() else {
                match std::io::Read::read(&mut session.tls, &mut read_buf) {
                    Ok(0) => {
                        println!("[{TAG}] phone closed the link");
                        return Ok(());
                    }
                    Ok(n) => {
                        last_rx = Instant::now();
                        shared.bytes_in.fetch_add(n as u64, Ordering::Relaxed);
                        let msgs = decoder.feed(&read_buf[..n]).map_err(|e| format!("frame: {e}"))?;
                        pending.extend(msgs);
                        continue;
                    }
                    Err(e)
                        if e.kind() == std::io::ErrorKind::WouldBlock
                            || e.kind() == std::io::ErrorKind::TimedOut =>
                    {
                        break;
                    }
                    Err(e) => return Err(format!("read: {e}")),
                }
            };
            worked = true;
            match m.channel {
                CH_VIDEO => {
                    if let Some(whole) = asm_video.feed(m.flags, &m.payload)? {
                        p.handle_video(&whole, &mut asm_video)?;
                    }
                }
                CH_INPUT => {
                    if let Some(whole) = asm_other.feed(m.flags, &m.payload)? {
                        if let Ok(evs) = phase5::parse_input_events(&whole) {
                            for e in evs {
                                if let InputEvent::Move { x, y } = e {
                                    if let Ok(mut pt) = shared.pointer.lock() {
                                        *pt = (x, y);
                                    }
                                }
                                shared.push(e);
                            }
                        }
                    }
                }
                CH_CONTROL => {
                    if let Some(whole) = asm_other.feed(m.flags, &m.payload)? {
                        if whole.starts_with(b"lat ") {
                            p.handle_lat(&whole, &clock);
                        } else {
                            handle_control(&whole, &mut clock);
                        }
                    }
                }
                _ => {}
            }
        }
        if !worked {
            std::thread::sleep(Duration::from_millis(1));
        }
        if last_rx.elapsed() > Duration::from_secs(15) {
            return Err("link dead: nothing inbound for 15 s".into());
        }
    }

    // Every exit path releases whatever the phone still holds.
    {
        let mut w = PriorityWriter::new(&mut session.tls);
        let _ = w.push(Message::new(CH_CONTROL, phase5::CTRL_RELEASE_ALL.as_bytes().to_vec())?);
        let _ = w.emit_all();
    }
    println!("[{TAG}] pipeline stopping (release-all sent)");
    unsafe {
        let _ = MFShutdown();
    }
    Ok(())
}

fn handle_control(whole: &[u8], clock: &mut phase5::ClockSync) {
    let text = String::from_utf8_lossy(whole).to_string();
    let t = text.trim();
    if let Some(rest) = t.strip_prefix("csr ") {
        let mut it = rest.split_whitespace();
        if let (Some(a), Some(b)) = (it.next(), it.next()) {
            if let (Ok(t1_raw), Ok(t3)) = (a.parse::<i64>(), b.parse::<i64>()) {
                let t4 = now_us() as i64;
                let t1 = nearest_epoch(t1_raw, t4);
                // The phone stamps this at send time; its receive time is assumed equal, so
                // rtt is measured PC-send -> PC-receive and the offset is RFC-5905 shaped.
                clock.offer(phase5::ClockSample { t1, t2: t3, t3, t4 });
                if let Some(off) = clock.offset_us() {
                    println!(
                        "[{TAG}] clock: phone-pc={off:.0} us (rtt {} us)",
                        clock.best_rtt_us().unwrap_or(-1)
                    );
                }
            }
        }
    } else if let Some(rest) = t.strip_prefix("lat ") {
        println!("[{TAG}] phone-lat {rest} (t_pc t_recv t_dispatch t_draw t_encode, phone clock)");
    } else if !t.is_empty() {
        println!("[{TAG}] control: {t}");
    }
}

/// The wire carries 32-bit microsecond stamps; put a wrapped one back in `now`'s epoch.
fn nearest_epoch(v: i64, now: i64) -> i64 {
    const SPAN: i64 = 1 << 32;
    v + ((now - v + SPAN / 2).div_euclid(SPAN)) * SPAN
}

/// A phone latency report waiting for its frame (phone microseconds, PC time for t_pc).
struct PendingLat {
    seq: u32,
    t_pc: f64,
    recv: f64,
    disp: f64,
    drawn: f64,
    enc: f64,
    /// clock offset at report time: phone_us = pc_us + off
    off: f64,
    rtt: i64,
}

fn print_latstage(l: &PendingLat, rx: i64, shown: i64) {
    let ms = |a: f64, b: f64| (b - a) / 1000.0;
    let (rx, shown) = (rx as f64, shown as f64);
    println!(
        "[{TAG}] LATSTAGE seq={} total={:.1}ms pc>phone={:.1} recv>dispatch={:.1} \
         dispatch>drawn={:.1} drawn>encoded={:.1} encoded>pc_rx={:.1} pc_rx>presented={:.1} \
         (clock rtt {} us)",
        l.seq,
        ms(l.t_pc, shown),
        ms(l.t_pc, l.recv - l.off),
        ms(l.recv, l.disp),
        ms(l.disp, l.drawn),
        ms(l.drawn, l.enc),
        ms(l.enc - l.off, rx),
        ms(rx, shown),
        l.rtt
    );
}

impl Pipeline {
    /// One phone latency report (`lat t_pc t_recv t_dispatch t_drawn t_encoded seq`, phone
    /// microseconds except t_pc) joined with that frame's PC times, as stages on one clock.
    fn handle_lat(&mut self, whole: &[u8], clock: &phase5::ClockSync) {
        let text = String::from_utf8_lossy(whole);
        let v: Vec<i64> = text.split_whitespace().skip(1).filter_map(|x| x.parse().ok()).collect();
        if v.len() < 6 {
            println!("[{TAG}] phone-lat (old format) {}", text.trim());
            return;
        }
        let t_pc = nearest_epoch(v[0], now_us64()) as f64;
        let (recv, disp, drawn, enc) = (v[1] as f64, v[2] as f64, v[3] as f64, v[4] as f64);
        let seq = v[5] as u32;
        let Some(off) = clock.offset_us() else {
            println!("[{TAG}] phone-lat {} (no clock sync yet)", text.trim());
            return;
        };
        let lat = PendingLat {
            seq,
            t_pc,
            recv,
            disp,
            drawn,
            enc,
            off,
            rtt: clock.best_rtt_us().unwrap_or(-1),
        };
        // The report rides the control channel, which outranks video, so it usually arrives
        // before its own frame: park it until that frame has been presented.
        match self.frames.iter().rev().find(|f| f.0 == seq) {
            Some(&(_, rx, shown)) => print_latstage(&lat, rx, shown),
            None => {
                if self.pending_lat.len() >= 32 {
                    self.pending_lat.remove(0);
                }
                self.pending_lat.push(lat);
            }
        }
    }

    /// After a frame is presented: print any latency report that was waiting for it.
    fn resolve_lat(&mut self, seq: u32, rx: i64, shown: i64) {
        if let Some(i) = self.pending_lat.iter().position(|l| l.seq == seq) {
            let lat = self.pending_lat.remove(i);
            print_latstage(&lat, rx, shown);
        }
    }

    fn handle_video(&mut self, whole: &[u8], asm: &mut phase5::ChunkAssembler) -> Result<(), String> {
        let vf = match phase5::parse_video_frame(whole) {
            Ok(v) => v,
            Err(e) => {
                println!("[{TAG}] bad video message: {e}");
                asm.reset();
                self.shared.need_keyframe.store(1, Ordering::SeqCst);
                return Ok(());
            }
        };
        if vf.is_config() {
            return Ok(());
        }
        self.frames_in += 1;
        if !phase5::annex_b_is_sane(&vf.annex_b) {
            println!("[{TAG}] access unit without a start code (seq {})", vf.seq);
            self.shared.need_keyframe.store(1, Ordering::SeqCst);
            return Ok(());
        }
        if self.awaiting_keyframe && !vf.is_keyframe() {
            self.shared.dropped_frames.fetch_add(1, Ordering::Relaxed);
            return Ok(());
        }
        if vf.is_keyframe() {
            self.awaiting_keyframe = false;
        }
        let rx = now_us64();
        match self.decode_and_present(&vf) {
            Ok(us) => {
                if self.frames.len() >= FRAME_RING {
                    self.frames.pop_front();
                }
                let shown = now_us64();
                self.frames.push_back((vf.seq, rx, shown));
                self.resolve_lat(vf.seq, rx, shown);
                self.logged_frames += 1;
                if self.logged_frames % 120 == 1 {
                    println!(
                        "[{TAG}] frame seq={} key={} au={}B decode={}us",
                        vf.seq,
                        vf.is_keyframe(),
                        vf.annex_b.len(),
                        us
                    );
                }
            }
            Err(e) => {
                println!("[{TAG}] decode: {e}");
                self.dec.in_set = false;
                self.shared.need_keyframe.store(1, Ordering::SeqCst);
            }
        }
        Ok(())
    }

    fn decode_and_present(&mut self, vf: &VideoFrame) -> Result<u64, String> {
        let t0 = Instant::now();
        if !self.dec.in_set {
            // A decoder that just reported a stream change cannot resume on a P-frame: it
            // reports another stream change, and feeding more P-frames spins forever (seen on
            // the emulator's c2.android.avc.encoder stream, 2026-10-19). Wait for the IDR the
            // phone sends in answer to our need-keyframe.
            if !vf.is_keyframe() {
                self.awaiting_keyframe = true;
                self.shared.need_keyframe.store(1, Ordering::SeqCst);
                return Ok(0);
            }
            recover(&mut self.dec)?;
        }
        // ProcessInput may refuse while undrained output is pending: drain, then retry.
        let mut spins = 0;
        loop {
            match feed(&mut self.dec, vf) {
                Ok(()) => break,
                Err(FeedErr::NotAccepting) => {
                    let n = self.drain_outputs()?;
                    if n == 0 {
                        spins += 1;
                        if spins > 4 {
                            return Err("decoder accepts no input and produces no output".into());
                        }
                    }
                }
                Err(FeedErr::Fatal(e)) => return Err(e),
            }
        }
        let produced = self.drain_outputs()?;
        if produced > 0 {
            unsafe {
                let _ = self.swap.Present(1, DXGI_PRESENT(0));
            }
            self.shared.presented.fetch_add(produced as u64, Ordering::Relaxed);
            self.shared.decoded.fetch_add(produced as u64, Ordering::Relaxed);
            let us = t0.elapsed().as_micros() as u64;
            self.shared.decode_us_total.fetch_add(us, Ordering::Relaxed);
            self.shared.last_seq.store(vf.seq, Ordering::Relaxed);
            Ok(us)
        } else {
            Ok(0)
        }
    }

    /// Drain every decodable frame and put it on screen. Returns how many were presented.
    fn drain_outputs(&mut self) -> Result<usize, String> {
        let mut produced = 0usize;
        loop {
            let mut db = [MFT_OUTPUT_DATA_BUFFER::default()];
            db[0].dwStreamID = 0;
            let mut status = 0u32;
            let hr = unsafe { self.dec.mft.ProcessOutput(0, &mut db, &mut status) };
            if let Err(e) = hr {
                let code = e.code();
                if code == MF_E_TRANSFORM_NEED_MORE_INPUT {
                    break;
                }
                if code == MF_E_TRANSFORM_STREAM_CHANGE {
                    println!("[{TAG}] decoder stream change; renegotiating the output type");
                    self.dec.in_set = false;
                    // Drop non-keyframes until the IDR answer arrives, or every following
                    // P-frame just re-reports the same stream change.
                    self.awaiting_keyframe = true;
                    self.shared.need_keyframe.store(1, Ordering::SeqCst);
                    break;
                }
                return Err(format!("ProcessOutput: {e}"));
            }
            let sample: Option<IMFSample> =
                unsafe { std::mem::ManuallyDrop::take(&mut db[0].pSample) };
            if db[0].pEvents.is_some() {
                unsafe { std::mem::ManuallyDrop::drop(&mut db[0].pEvents) };
            }
            let Some(sample) = sample else { break };
            if !take_sample(&mut self.dec, &sample) {
                continue;
            }
            let (in_w, in_h) = (self.dec.out_w, self.dec.out_h);
            let (out_w, out_h) = self.shared.client();
            if self.dec.d3d11 {
                let need_new = match &self.vp {
                    Some(v) => v.key != (in_w, in_h, out_w, out_h),
                    None => true,
                };
                if need_new {
                    self.vp = Some(VideoProc::build(&self.video_device, in_w, in_h, out_w, out_h)?);
                }
                let v = self.vp.as_ref().ok_or("no video processor")?;
                blit_dxgi(&self.video_ctx, &self.video_device, v, &self.dec, &self.swap, in_w, in_h, out_w, out_h)?;
            } else {
                blit_soft(&self.d3d_ctx, &mut self.dec, &self.swap)?;
            }
            produced += 1;
        }
        Ok(produced)
    }
}

// ------------------------------------------------------------------ decoder

struct Decoder {
    mft: IMFTransform,
    in_set: bool,
    out_w: i32,
    out_h: i32,
    d3d11: bool,
    latest: Option<ID3D11Texture2D>,
    latest_sub: u32,
    soft_nv12: Vec<u8>,
    soft_tex: Option<ID3D11Texture2D>,
    soft_w: i32,
    soft_h: i32,
}

struct VideoProc {
    enumerator: ID3D11VideoProcessorEnumerator,
    processor: ID3D11VideoProcessor,
    key: (i32, i32, i32, i32),
}

impl VideoProc {
    fn build(
        video_device: &ID3D11VideoDevice,
        in_w: i32,
        in_h: i32,
        out_w: i32,
        out_h: i32,
    ) -> Result<Self, String> {
        unsafe {
            let desc = D3D11_VIDEO_PROCESSOR_CONTENT_DESC {
                InputFrameFormat: D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE,
                InputFrameRate: DXGI_RATIONAL { Numerator: 30, Denominator: 1 },
                InputWidth: in_w.max(2) as u32,
                InputHeight: in_h.max(2) as u32,
                OutputFrameRate: DXGI_RATIONAL { Numerator: 60, Denominator: 1 },
                OutputWidth: out_w.max(2) as u32,
                OutputHeight: out_h.max(2) as u32,
                Usage: D3D11_VIDEO_USAGE_PLAYBACK_NORMAL,
            };
            let enumerator = video_device
                .CreateVideoProcessorEnumerator(&desc)
                .map_err(|e| format!("CreateVideoProcessorEnumerator: {e}"))?;
            let processor = video_device
                .CreateVideoProcessor(&enumerator, 0)
                .map_err(|e| format!("CreateVideoProcessor: {e}"))?;
            Ok(VideoProc { enumerator, processor, key: (in_w, in_h, out_w, out_h) })
        }
    }
}

fn create_d3d11() -> Result<(ID3D11Device, ID3D11DeviceContext), String> {
    unsafe {
        let mut device: Option<ID3D11Device> = None;
        let mut ctx: Option<ID3D11DeviceContext> = None;
        let mut level = D3D_FEATURE_LEVEL_11_0;
        let levels = [D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0];
        D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_HARDWARE,
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
            Some(&levels),
            D3D11_SDK_VERSION,
            Some(&mut device as *mut _),
            Some(&mut level as *mut _),
            Some(&mut ctx as *mut _),
        )
        .map_err(|e| format!("D3D11CreateDevice: {e}"))?;
        let device = device.ok_or("D3D11CreateDevice: no device")?;
        let ctx = ctx.ok_or("D3D11CreateDevice: no context")?;
        Ok((device, ctx))
    }
}

fn create_swap_chain(device: &ID3D11Device, hwnd: HWND) -> Result<IDXGISwapChain1, String> {
    unsafe {
        let dxgi: IDXGIDevice = device.cast().map_err(|e| format!("IDXGIDevice: {e}"))?;
        let adapter = dxgi.GetAdapter().map_err(|e| format!("GetAdapter: {e}"))?;
        let factory: IDXGIFactory2 = adapter.GetParent().map_err(|e| format!("IDXGIFactory2: {e}"))?;
        let desc = DXGI_SWAP_CHAIN_DESC1 {
            Width: 0,
            Height: 0,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            Stereo: false.into(),
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            BufferUsage: DXGI_USAGE_RENDER_TARGET_OUTPUT,
            BufferCount: 2,
            Scaling: DXGI_SCALING_STRETCH,
            SwapEffect: DXGI_SWAP_EFFECT_FLIP_DISCARD,
            AlphaMode: DXGI_ALPHA_MODE_IGNORE,
            Flags: 0,
        };
        let swap = factory
            .CreateSwapChainForHwnd(device, hwnd, &desc, None, None)
            .map_err(|e| format!("CreateSwapChainForHwnd: {e}"))?;
        if let Ok(swap2) = swap.cast::<IDXGISwapChain2>() {
            let _ = swap2.SetMaximumFrameLatency(1);
            println!("[{TAG}] swap chain: FLIP_DISCARD BGRA8 bufs=2 max-frame-latency=1");
        }
        let _ = factory.MakeWindowAssociation(hwnd, DXGI_MWA_NO_ALT_ENTER);
        Ok(swap)
    }
}

fn create_decoder(device: &ID3D11Device, force_soft: bool) -> Result<Decoder, String> {
    unsafe {
        let mft: IMFTransform = CoCreateInstance(&CLSID_CMSH264_DECODER_MFT, None, CLSCTX_INPROC_SERVER)
            .map_err(|e| format!("CoCreateInstance(MS H.264 decoder MFT): {e}"))?;
        println!("[{TAG}] decoder MFT created");
        let mut d3d11 = false;
        if !force_soft {
            // The MFT must announce D3D11 awareness before it may be given a device manager.
            let is_aware = mft
                .GetAttributes()
                .and_then(|a| a.GetUINT32(&MF_SA_D3D11_AWARE))
                .map(|v| v != 0)
                .unwrap_or(false);
            println!("[{TAG}] decoder MF_SA_D3D11_AWARE={is_aware}");
            if is_aware {
                let mut token = 0u32;
                let mut manager: Option<IMFDXGIDeviceManager> = None;
                if MFCreateDXGIDeviceManager(&mut token, &mut manager).is_ok() {
                    println!("[{TAG}] DXGI device manager created (token={token})");
                    if let Some(mgr) = manager.as_ref() {
                        if let Err(e) = mgr.ResetDevice(device, token) {
                            println!("[{TAG}] ResetDevice(D3D11) failed: {e}");
                        } else {
                            println!("[{TAG}] ResetDevice(D3D11) ok");
                            // ulParam is an IUnknown pointer to the device manager (the token is
                            // NOT what this message wants - passing it dereferences a small integer).
                            let raw = windows::core::Interface::as_raw(mgr) as usize;
                            match mft.ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, raw) {
                                Ok(()) => {
                                    d3d11 = true;
                                    println!("[{TAG}] SET_D3D_MANAGER ok (IMFDXGIDeviceManager @ {raw:#x})");
                                }
                                Err(e) => println!("[{TAG}] SET_D3D_MANAGER failed: {e}"),
                            }
                        }
                    }
                } else {
                    println!("[{TAG}] MFCreateDXGIDeviceManager failed");
                }
            } else {
                println!("[{TAG}] decoder is not D3D11 aware; hardware path unavailable");
            }
        }
        match mft.cast::<ICodecAPI>() {
            Ok(api) => {
                // This decoder's low-latency property wants VT_UI4 (a plain UINT32): passing
                // VT_BOOL is refused with E_INVALIDARG and the message says exactly that.
                let v = windows::Win32::System::Variant::VARIANT::from(1u32);
                match api.SetValue(&CODECAPI_AVLowLatencyMode, &v) {
                    Ok(()) => println!("[{TAG}] CODECAPI_AVLowLatencyMode = 1 (VT_UI4)"),
                    Err(e) => println!("[{TAG}] CODECAPI_AVLowLatencyMode refused: {e}"),
                }
            }
            Err(_) => println!("[{TAG}] decoder exposes no ICodecAPI"),
        }
        let _ = mft.ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);

        // This MFT refuses to enumerate output types until the input type is set
        // (MF_E_TRANSFORM_TYPE_NOT_SET), so: input -> output -> streaming.
        set_input_type(&mft)?;
        let (ow, oh) = choose_and_set_output(&mft)?;
        let info = mft
            .GetOutputStreamInfo(0)
            .map_err(|e| format!("GetOutputStreamInfo: {e}"))?;
        let provides = info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 != 0;
        println!(
            "[{TAG}] decoder: MS H.264 MFT d3d11={d3d11} provides_samples={provides} NV12 {ow}x{oh}"
        );
        let mut dec = Decoder {
            mft,
            in_set: true,
            out_w: ow,
            out_h: oh,
            d3d11,
            latest: None,
            latest_sub: 0,
            soft_nv12: Vec::new(),
            soft_tex: None,
            soft_w: 0,
            soft_h: 0,
        };
        start_streaming(&mut dec)?;
        Ok(dec)
    }
}

/// The H.264 input type. `MF_MT_FRAME_SIZE` is set up front because the decoder needs a size
/// before it will offer an output type.
fn set_input_type(mft: &IMFTransform) -> Result<(), String> {
    unsafe {
        let mt: IMFMediaType = MFCreateMediaType().map_err(|e| format!("MFCreateMediaType: {e}"))?;
        mt.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video).map_err(|e| format!("{e}"))?;
        mt.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264).map_err(|e| format!("{e}"))?;
        mt.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32).ok();
        mt.SetUINT64(
            &MF_MT_FRAME_SIZE,
            ((phase5::DESKTOP_W as u64) << 32) | phase5::DESKTOP_H as u64,
        )
        .ok();
        mt.SetUINT64(&MF_MT_FRAME_RATE, (30u64 << 32) | 1).ok();
        mft.SetInputType(0, &mt, 0)
            .map_err(|e| format!("SetInputType(H264): {e}"))
    }
}

/// Pick the NV12 output type and set it **exactly as the decoder offered it** - overriding its
/// attributes (frame rate, frame size, all-samples-independent) makes this MFT report
/// MF_E_TRANSFORM_STREAM_CHANGE forever, because what it then decodes never matches the type
/// the client forced on it. Returns the offered (width, height).
fn choose_and_set_output(mft: &IMFTransform) -> Result<(i32, i32), String> {
    unsafe {
        let mut out_type: Option<IMFMediaType> = None;
        let mut idx = 0u32;
        loop {
            match mft.GetOutputAvailableType(0, idx) {
                Ok(t) => {
                    let sub = t.GetGUID(&MF_MT_SUBTYPE).unwrap_or_default();
                    let size = t.GetUINT64(&MF_MT_FRAME_SIZE).unwrap_or(0);
                    let rate = t.GetUINT64(&MF_MT_FRAME_RATE).unwrap_or(0);
                    let is_nv12 = sub == MFVideoFormat_NV12;
                    println!(
                        "[{TAG}] output type {idx}: {sub:?} nv12={is_nv12} size={}x{} fps={}/{}{}",
                        size >> 32,
                        size & 0xFFFF_FFFF,
                        rate >> 32,
                        rate & 0xFFFF_FFFF,
                        if is_nv12 { " <- using this" } else { "" }
                    );
                    if is_nv12 {
                        out_type = Some(t);
                        break;
                    }
                    idx += 1;
                }
                Err(e) => {
                    println!("[{TAG}] no more output types after {idx}: {e}");
                    break;
                }
            }
        }
        let out_type = out_type.ok_or("decoder offers no NV12 output type")?;
        mft.SetOutputType(0, &out_type, 0)
            .map_err(|e| format!("SetOutputType(NV12): {e}"))?;
        if let Ok(sz) = out_type.GetUINT64(&MF_MT_FRAME_SIZE) {
            if sz >> 32 > 0 {
                return Ok(((sz >> 32) as i32, (sz & 0xFFFF_FFFF) as i32));
            }
        }
        Ok((phase5::DESKTOP_W, phase5::DESKTOP_H))
    }
}

fn start_streaming(dec: &mut Decoder) -> Result<(), String> {
    unsafe {
        dec.mft
            .ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)
            .map_err(|e| format!("BEGIN_STREAMING: {e}"))?;
        let _ = dec.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
        Ok(())
    }
}

/// After a decode failure or a stream change: flush, re-type the **output** only, restart
/// streaming. Re-setting the input type here would produce another stream change, forever.
fn recover(dec: &mut Decoder) -> Result<(), String> {
    unsafe {
        let _ = dec.mft.ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
    }
    let (w, h) = choose_and_set_output(&dec.mft)?;
    dec.out_w = w;
    dec.out_h = h;
    dec.soft_tex = None;
    dec.soft_w = 0;
    dec.soft_h = 0;
    start_streaming(dec)?;
    dec.in_set = true;
    Ok(())
}

enum FeedErr {
    /// The decoder still holds output: drain it and try again.
    NotAccepting,
    Fatal(String),
}

/// Feed one access unit (Annex-B H.264).
fn feed(dec: &mut Decoder, vf: &VideoFrame) -> Result<(), FeedErr> {
    unsafe {
        let sample: IMFSample =
            MFCreateSample().map_err(|e| FeedErr::Fatal(format!("MFCreateSample: {e}")))?;
        let buffer: IMFMediaBuffer = MFCreateMemoryBuffer(vf.annex_b.len() as u32)
            .map_err(|e| FeedErr::Fatal(format!("MFCreateMemoryBuffer: {e}")))?;
        let mut p: *mut u8 = std::ptr::null_mut();
        buffer
            .Lock(&mut p, None, None)
            .map_err(|e| FeedErr::Fatal(format!("Lock: {e}")))?;
        std::ptr::copy_nonoverlapping(vf.annex_b.as_ptr(), p, vf.annex_b.len());
        let _ = buffer.Unlock();
        buffer
            .SetCurrentLength(vf.annex_b.len() as u32)
            .map_err(|e| FeedErr::Fatal(format!("SetCurrentLength: {e}")))?;
        sample
            .AddBuffer(&buffer)
            .map_err(|e| FeedErr::Fatal(format!("AddBuffer: {e}")))?;
        let _ = sample.SetSampleTime(vf.pts_us);
        let _ = sample.SetSampleDuration(333_333);
        match dec.mft.ProcessInput(0, &sample, 0) {
            Ok(()) => Ok(()),
            Err(e) if e.code() == MF_E_NOTACCEPTING => Err(FeedErr::NotAccepting),
            Err(e) => Err(FeedErr::Fatal(format!("ProcessInput: {e}"))),
        }
    }
}

/// Pull the decoded surface out of a sample: a DXGI texture (hardware) or plain NV12 bytes.
fn take_sample(dec: &mut Decoder, sample: &IMFSample) -> bool {
    unsafe {
        let Ok(buf) = sample.GetBufferByIndex(0) else {
            return false;
        };
        if let Ok(dxgi) = buf.cast::<IMFDXGIBuffer>() {
            let mut raw: *mut std::ffi::c_void = std::ptr::null_mut();
            if dxgi.GetResource(&ID3D11Texture2D::IID, &mut raw).is_ok() && !raw.is_null() {
                let tex = ID3D11Texture2D::from_raw(raw);
                let mut desc = D3D11_TEXTURE2D_DESC::default();
                tex.GetDesc(&mut desc);
                dec.out_w = desc.Width as i32;
                dec.out_h = desc.Height as i32;
                dec.latest_sub = dxgi.GetSubresourceIndex().unwrap_or(0);
                dec.latest = Some(tex);
                return true;
            }
        }
        let mut p: *mut u8 = std::ptr::null_mut();
        let mut len = 0u32;
        if buf.Lock(&mut p, None, Some(&mut len)).is_err() {
            return false;
        }
        dec.soft_nv12.clear();
        dec.soft_nv12
            .extend_from_slice(std::slice::from_raw_parts(p, len as usize));
        let _ = buf.Unlock();
        dec.latest = None;
        true
    }
}

/// NV12 DXGI texture -> BGRA back buffer, on the GPU, with hardware scaling.
#[allow(clippy::too_many_arguments)]
fn blit_dxgi(
    video_ctx: &ID3D11VideoContext,
    video_device: &ID3D11VideoDevice,
    v: &VideoProc,
    dec: &Decoder,
    swap: &IDXGISwapChain1,
    in_w: i32,
    in_h: i32,
    out_w: i32,
    out_h: i32,
) -> Result<(), String> {
    let tex = dec.latest.as_ref().ok_or("no decoded texture")?;
    unsafe {
        let back: ID3D11Texture2D = swap.GetBuffer(0).map_err(|e| format!("GetBuffer: {e}"))?;
        let in_desc = D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC {
            FourCC: 0,
            ViewDimension: D3D11_VPIV_DIMENSION_TEXTURE2D,
            Anonymous: D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC_0 {
                Texture2D: D3D11_TEX2D_VPIV { MipSlice: 0, ArraySlice: dec.latest_sub },
            },
        };
        let mut in_view: Option<ID3D11VideoProcessorInputView> = None;
        video_device
            .CreateVideoProcessorInputView(tex, &v.enumerator, &in_desc, Some(&mut in_view as *mut _))
            .map_err(|e| format!("CreateVideoProcessorInputView: {e}"))?;
        let in_view = in_view.ok_or("no input view")?;

        let out_desc = D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC {
            ViewDimension: D3D11_VPOV_DIMENSION_TEXTURE2D,
            Anonymous: D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC_0 {
                Texture2D: D3D11_TEX2D_VPOV { MipSlice: 0 },
            },
        };
        let mut out_view: Option<ID3D11VideoProcessorOutputView> = None;
        video_device
            .CreateVideoProcessorOutputView(&back, &v.enumerator, &out_desc, Some(&mut out_view as *mut _))
            .map_err(|e| format!("CreateVideoProcessorOutputView: {e}"))?;
        let out_view = out_view.ok_or("no output view")?;

        // Keep the aspect ratio: fit the desktop inside the window.
        let (dw, dh) = fit(in_w, in_h, out_w, out_h);
        let dest = RECT {
            left: (out_w - dw) / 2,
            top: (out_h - dh) / 2,
            right: (out_w + dw) / 2,
            bottom: (out_h + dh) / 2,
        };
        video_ctx.VideoProcessorSetStreamFrameFormat(&v.processor, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
        video_ctx.VideoProcessorSetStreamOutputRate(
            &v.processor,
            0,
            D3D11_VIDEO_PROCESSOR_OUTPUT_RATE_NORMAL,
            true,
            None,
        );
        video_ctx.VideoProcessorSetStreamDestRect(&v.processor, 0, true, Some(&dest));
        let full = RECT { left: 0, top: 0, right: out_w, bottom: out_h };
        video_ctx.VideoProcessorSetOutputTargetRect(&v.processor, true, Some(&full));

        let stream = D3D11_VIDEO_PROCESSOR_STREAM {
            Enable: true.into(),
            OutputIndex: 0,
            InputFrameOrField: 0,
            PastFrames: 0,
            FutureFrames: 0,
            ppPastSurfaces: std::ptr::null_mut(),
            pInputSurface: std::mem::ManuallyDrop::new(Some(in_view)),
            ppFutureSurfaces: std::ptr::null_mut(),
            ppPastSurfacesRight: std::ptr::null_mut(),
            pInputSurfaceRight: std::mem::ManuallyDrop::new(None),
            ppFutureSurfacesRight: std::ptr::null_mut(),
        };
        video_ctx
            .VideoProcessorBlt(&v.processor, &out_view, 0, std::slice::from_ref(&stream))
            .map_err(|e| format!("VideoProcessorBlt: {e}"))?;
        Ok(())
    }
}

/// System-memory NV12 -> BGRA -> back buffer (fallback path, no scaling).
fn blit_soft(d3d_ctx: &ID3D11DeviceContext, dec: &mut Decoder, swap: &IDXGISwapChain1) -> Result<(), String> {
    let (w, h) = (dec.out_w.max(2), dec.out_h.max(2));
    if dec.soft_nv12.is_empty() {
        return Ok(());
    }
    if dec.soft_tex.is_none() || dec.soft_w != w || dec.soft_h != h {
        unsafe {
            let device = d3d_ctx.GetDevice().map_err(|e| format!("GetDevice: {e}"))?;
            let desc = D3D11_TEXTURE2D_DESC {
                Width: w as u32,
                Height: h as u32,
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_DYNAMIC,
                BindFlags: D3D11_BIND_SHADER_RESOURCE.0 as u32,
                CPUAccessFlags: D3D11_CPU_ACCESS_WRITE.0 as u32,
                MiscFlags: 0,
            };
            let mut tex: Option<ID3D11Texture2D> = None;
            device
                .CreateTexture2D(&desc, None, Some(&mut tex as *mut _))
                .map_err(|e| format!("CreateTexture2D(soft): {e}"))?;
            dec.soft_tex = tex;
            dec.soft_w = w;
            dec.soft_h = h;
        }
    }
    let tex = dec.soft_tex.as_ref().ok_or("no soft texture")?;
    unsafe {
        let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
        d3d_ctx
            .Map(tex, 0, D3D11_MAP_WRITE_DISCARD, 0, Some(&mut mapped as *mut _))
            .map_err(|e| format!("Map: {e}"))?;
        let dst = std::slice::from_raw_parts_mut(
            mapped.pData as *mut u8,
            (mapped.RowPitch * h as u32) as usize,
        );
        nv12_to_bgra(&dec.soft_nv12, w as usize, h as usize, mapped.RowPitch as usize, dst);
        d3d_ctx.Unmap(tex, 0);

        let back: ID3D11Texture2D = swap.GetBuffer(0).map_err(|e| format!("GetBuffer: {e}"))?;
        let mut bdesc = D3D11_TEXTURE2D_DESC::default();
        back.GetDesc(&mut bdesc);
        let (bw, bh) = (bdesc.Width as i32, bdesc.Height as i32);
        let (dw, dh) = fit(w, h, bw, bh);
        let src_box = D3D11_BOX { left: 0, top: 0, front: 0, right: w as u32, bottom: h as u32, back: 1 };
        let dst_x = ((bw - dw) / 2).max(0) as u32;
        let dst_y = ((bh - dh) / 2).max(0) as u32;
        d3d_ctx.CopySubresourceRegion(&back, 0, dst_x, dst_y, 0, tex, 0, Some(&src_box));
        Ok(())
    }
}

/// Fit `iw x ih` inside `ow x oh`, keeping the aspect ratio.
fn fit(iw: i32, ih: i32, ow: i32, oh: i32) -> (i32, i32) {
    if iw <= 0 || ih <= 0 || ow <= 0 || oh <= 0 {
        return (ow.max(2), oh.max(2));
    }
    let s = (ow as f64 / iw as f64).min(oh as f64 / ih as f64);
    (((iw as f64 * s) as i32).max(2), ((ih as f64 * s) as i32).max(2))
}

/// Straight BT.601 NV12 -> BGRA (fallback path only).
fn nv12_to_bgra(nv12: &[u8], w: usize, h: usize, row_pitch: usize, dst: &mut [u8]) {
    let y_size = w * h;
    if nv12.len() < y_size + y_size / 2 {
        return;
    }
    let (ys, uv) = nv12.split_at(y_size);
    for row in 0..h {
        let drow = row * row_pitch;
        let urow = (row / 2) * w;
        for col in 0..w {
            let y = ys[row * w + col] as i32;
            let idx = urow + (col & !1);
            let u = uv[idx] as i32 - 128;
            let v = uv[idx + 1] as i32 - 128;
            let c = (y - 16).max(0);
            let r = ((298 * c + 409 * v + 128) >> 8).clamp(0, 255) as u8;
            let g = ((298 * c - 100 * u - 208 * v + 128) >> 8).clamp(0, 255) as u8;
            let b = ((298 * c + 516 * u + 128) >> 8).clamp(0, 255) as u8;
            let d = drow + col * 4;
            if d + 3 < dst.len() {
                dst[d] = b;
                dst[d + 1] = g;
                dst[d + 2] = r;
                dst[d + 3] = 255;
            }
        }
    }
}

fn describe_adapter(device: &ID3D11Device) {
    unsafe {
        let Ok(dxgi) = device.cast::<IDXGIDevice>() else { return };
        let Ok(adapter) = dxgi.GetAdapter() else { return };
        if let Ok(desc) = adapter.GetDesc() {
            let n = desc.Description.iter().position(|c| *c == 0).unwrap_or(desc.Description.len());
            println!(
                "[{TAG}] adapter: {} (video processor: yes)",
                String::from_utf16_lossy(&desc.Description[..n])
            );
        }
    }
}
