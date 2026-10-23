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
use std::net::{Shutdown, TcpStream};
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
    DXGI_PRESENT, DXGI_PRESENT_ALLOW_TEARING, DXGI_SWAP_CHAIN_DESC1,
    DXGI_SWAP_CHAIN_FLAG, DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING,
    DXGI_SWAP_EFFECT_FLIP_DISCARD,
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
    LoadCursorW, PeekMessageW, SetCursor,
    GetSystemMetrics, PostMessageW, PostQuitMessage, RegisterClassW, SetForegroundWindow,
    SetWindowLongPtrW, SetWindowPos, ShowWindow, TranslateMessage,
    GWL_STYLE, HTCLIENT, HWND_TOPMOST, IDC_ARROW, MSG, PM_REMOVE, SM_CXSCREEN, SM_CYSCREEN,
    SWP_FRAMECHANGED, SWP_SHOWWINDOW, SW_SHOW, WM_CLOSE, WM_DESTROY, WM_KEYDOWN, WM_KEYUP,
    WM_KILLFOCUS, WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MBUTTONDOWN, WM_MBUTTONUP, WM_MOUSEMOVE,
    WM_MOUSEWHEEL, WM_QUIT, WM_RBUTTONDOWN, WM_RBUTTONUP, WM_SETCURSOR, WM_SIZE,
    WM_SYSKEYDOWN, WM_SYSKEYUP, WNDCLASSW, WS_OVERLAPPEDWINDOW, WS_POPUP, WS_VISIBLE,
    WINDOW_EX_STYLE,
};

use crate::phase5::{self, InputEvent, VideoFrame};
use crate::protocol::{Message, PriorityWriter, CH_AUDIO, CH_CAM, CH_CLIP, CH_CONTROL, CH_FILE, CH_INPUT, CH_VIDEO};
use windows::Win32::System::DataExchange::{AddClipboardFormatListener, RemoveClipboardFormatListener};
use windows::Win32::UI::Shell::{DragAcceptFiles, DragFinish, DragQueryFileW, HDROP};

/// B-067: a Windows socket read can surface a timed-out or still-pending operation as a raw OS
/// error instead of WouldBlock/TimedOut (997 = ERROR_IO_PENDING, 10060 = WSAETIMEDOUT). The
/// view's read loop treats those as "no data this tick" (it checks again next iteration);
/// every other error stays fatal.
#[allow(dead_code)]
fn is_transient_read(e: &std::io::Error) -> bool {
    matches!(
        e.kind(),
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
    ) || matches!(e.raw_os_error(), Some(997) | Some(10060))
}

const TAG: &str = "p5pc";
/// WM_CLIPBOARDUPDATE (add/remove format listener, Phase 11 D-c).
const WM_CLIPBOARDUPDATE: u32 = 0x031D;
/// WM_DROPFILES (files dropped on the view window, Phase 11 D-d).
const WM_DROPFILES: u32 = 0x0233;
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
    win_x: i32,
    win_y: i32,
    win_w: i32,
    win_h: i32,
    client_w: AtomicU32,
    client_h: AtomicU32,
    /// D8: the desktop size the phone renders, chosen from the client aspect before video-on.
    desktop_w: AtomicU32,
    desktop_h: AtomicU32,
    need_keyframe: AtomicU32,
    release_all: AtomicU32,
    presented: AtomicU64,
    decoded: AtomicU64,
    dropped_frames: AtomicU64,
    bytes_in: AtomicU64,
    decode_us_total: AtomicU64,
    last_seq: AtomicU32,
    d3d11: AtomicU32,
    /// A clone of the live TLS socket, so an exit can wake a blocking read
    /// (B-098: shutdown(Both) makes read() return instead of waiting for video).
    wake_socket: Mutex<Option<TcpStream>>,
    /// Phase 11 D-c: text waiting to go to the phone (UTF-8), and the hash of the last
    /// clipboard text this process saw on either end (loop guard).
    clip_out: Mutex<Option<Vec<u8>>>,
    clip_hash: AtomicU64,
    /// Phase 11 D-d: files the user dropped on the window or picked in the Import dialog.
    files_out: Mutex<Vec<std::path::PathBuf>>,
}

impl Shared {
    fn new(x: i32, y: i32, w: i32, h: i32) -> Arc<Self> {
        let me = Arc::new(Shared {
            hwnd: AtomicU64::new(0),
            events: Mutex::new(Vec::new()),
            pointer: Mutex::new((960, 540)),
            running: AtomicBool::new(true),
            fullscreen: AtomicBool::new(true),
            win_x: x,
            win_y: y,
            win_w: w,
            win_h: h,
            client_w: AtomicU32::new(1920),
            client_h: AtomicU32::new(1080),
            desktop_w: AtomicU32::new(1920),
            desktop_h: AtomicU32::new(1080),
            need_keyframe: AtomicU32::new(0),
            release_all: AtomicU32::new(0),
            presented: AtomicU64::new(0),
            decoded: AtomicU64::new(0),
            dropped_frames: AtomicU64::new(0),
            bytes_in: AtomicU64::new(0),
            decode_us_total: AtomicU64::new(0),
            last_seq: AtomicU32::new(0),
            d3d11: AtomicU32::new(0),
            wake_socket: Mutex::new(None),
            clip_out: Mutex::new(None),
            clip_hash: AtomicU64::new(0),
            files_out: Mutex::new(Vec::new()),
        });
        // Harness/test hook: UD_VIEW_IMPORT=<path> queues one "Import from this PC" file at
        // startup, so the PC->phone file path can be proven without driving the Open dialog.
        if let Some(p) = std::env::var_os("UD_VIEW_IMPORT") {
            let p = std::path::PathBuf::from(p);
            if p.is_file() {
                if let Ok(mut q) = me.files_out.lock() {
                    q.push(p);
                }
            }
        }
        me
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

    /// D8: the desktop the phone asked us to render for. Fixed for the whole session.
    fn desktop(&self) -> (i32, i32) {
        (
            self.desktop_w.load(Ordering::SeqCst) as i32,
            self.desktop_h.load(Ordering::SeqCst) as i32,
        )
    }
}

/// Everything `cmd_view` parsed. A struct keeps `run()` readable as Phase 9 adds the lock.
#[derive(Debug, Clone, Copy)]
pub struct ViewOptions {
    pub seconds: u64,
    pub soft: bool,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    /// Phase 9: switch to a private Windows desktop and block the exam keys.
    pub lock: bool,
    /// 0 = no limit. The watchdog force-restores when it expires.
    pub lock_max_seconds: u64,
    /// 0 = never. Stop presenting after this many seconds of lock (frozen-frame test).
    pub freeze_video_after: u64,
    pub policies: bool,
    /// Phase 12 B2: present without vsync (tearing allowed). Off by default.
    pub no_vsync: bool,
}

pub fn run(
    addr: &str,
    opts: &ViewOptions,
    identity: &crate::crypto::ReceiverIdentity,
    store: &crate::crypto::TrustedPhoneStore,
) -> Result<(), String> {
    // The guard is acquired BEFORE recovery and held through lock creation (and for the
    // whole session when --no-lock is used). A second view therefore fails here, before it
    // connects to the phone, instead of restoring/stealing the live session's journal.
    let lock_session = crate::lock::Session::acquire()?;
    lock_session.recover()?;
    println!("[{TAG}] connecting to {addr}");
    let (mut session, description) = crate::session::connect_and_pair(addr, identity, store, "PC")?;
    println!("[{TAG}] {description}");
    let _ = session.tls.get_mut().set_nodelay(true);
    // Typing latency, Phase 13 (in scope): input must not wait for the next video frame.
    // While the desktop is static the
    // phone only encodes its 1 fps keep-alive frame, and the blocking read in the pipeline
    // (which is also where queued input is flushed) then parks for up to a second: typing in
    // the Browser/VS Code/Notepad++ measured pc>phone 145-676 ms and only 9 of 25 keys got a
    // report, against 8 ms / 25 of 25 when something animated.
    //
    // The wake tick is on the transport channel, NOT a socket read timeout: a timed-out
    // blocking recv on a Windows socket is documented to leave the connection in an
    // indeterminate state, and this project already had stream failures from timed reads
    // (B-067, B-072). A reader thread does blocking socket reads and the TLS state machine
    // reads its bytes from the channel; this tick only bounds how long the loop waits for
    // the next chunk before flushing input. A real frame still arrives as Ok(n).
    session
        .tls
        .get_mut()
        .set_tick(Some(std::time::Duration::from_millis(5)));

    let shared = Shared::new(opts.x, opts.y, opts.w, opts.h);
    SHARED.set(shared.clone()).map_err(|_| "view already running")?;

    // The private desktop must exist, and this thread must be switched onto it, BEFORE any
    // window or hook of this thread is created. Nothing above this line makes a window.
    let lock_opts = crate::lock::LockOptions {
        max_seconds: opts.lock_max_seconds,
        policies: opts.policies,
    };
    let (mut lock, _lock_session) = if opts.lock {
        (
            Some(crate::lock::Lock::prepare(&lock_opts, lock_session)?),
            None,
        )
    } else {
        (None, Some(lock_session))
    };

    let hwnd = match create_window(&shared) {
        Ok(h) => h,
        Err(e) => {
            if let Some(l) = lock.as_mut() {
                l.abort();
            }
            return Err(e);
        }
    };
    shared.hwnd.store(hwnd.0 as u64, Ordering::SeqCst);
    let (cw, ch) = shared.client();
    focus_window(hwnd);
    println!(
        "[{TAG}] window up: hwnd={:?} client={cw}x{ch} foreground={} (Esc quits, F11 windowed)",
        hwnd.0,
        foreground_is(hwnd)
    );

    if let Some(l) = lock.as_mut() {
        if let Err(e) = l.engage(hwnd, &lock_opts) {
            l.abort();
            unsafe {
                let _ = windows::Win32::UI::WindowsAndMessaging::DestroyWindow(hwnd);
            }
            return Err(format!("lock engage: {e}"));
        }
        println!("[{TAG}] LOCK engaged (private Phase 9 desktop)");
    }
    // Phase 11 D-c: PC clipboard changes arrive as WM_CLIPBOARDUPDATE on this window.
    unsafe {
        let _ = AddClipboardFormatListener(hwnd);
        DragAcceptFiles(hwnd, true);
    }

    let sh = shared.clone();
    let (soft, secs, freeze) = (opts.soft, opts.seconds, opts.freeze_video_after);
    let no_vsync = opts.no_vsync;
    let reconnect_addr = addr.to_string();
    let reconnect_identity = identity.clone();
    let pipeline = std::thread::Builder::new()
        .name("p5-pipeline".into())
        .spawn(move || {
            let mut current = session;
            let r = loop {
                let result = pipeline_loop(&mut current, &sh, soft, secs, freeze, no_vsync);
                if !sh.running.load(Ordering::SeqCst) || secs > 0 {
                    break result;
                }
                let deadline = Instant::now() + Duration::from_secs(10);
                println!("[{TAG}] RECONNECT start_us={} target={reconnect_addr}", now_us64());
                let next_session = loop {
                    if !sh.running.load(Ordering::SeqCst) {
                        break Err("view stopped during reconnect".into());
                    }
                    match crate::session::connect_and_pair(
                        &reconnect_addr,
                        &reconnect_identity,
                        &crate::crypto::TrustedPhoneStore::open(),
                        "PC",
                    ) {
                        Ok((next, description)) => {
                            println!(
                                "[{TAG}] RECONNECT ok_us={} {description}",
                                now_us64()
                            );
                            break Ok(next);
                        }
                        Err(e) => {
                            if Instant::now() >= deadline {
                                break Err(format!("reconnect timed out: {e}"));
                            }
                            std::thread::sleep(Duration::from_millis(250));
                        }
                    }
                };
                match next_session {
                    Ok(next) => current = next,
                    Err(e) => break Err(e),
                }
            };
            sh.running.store(false, Ordering::SeqCst);
            // Wake the message loop. On a locked run this is just a wake-up: every exit path
            // ends in Lock::exit() on the owner thread below.
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

    // PeekMessage instead of a blocking GetMessage: the Exit button, the watchdog, `lock-exit`
    // and the pipeline all raise a flag that must be noticed within one tick, and a blocking
    // GetMessage would sit on WM_QUIT until something else woke the queue.
    let mut quit = false;
    while !quit {
        if crate::lock::take_exit_request() {
            println!("[{TAG}] exit requested (button/watchdog/lock-exit/Esc)");
            break;
        }
        let mut msg = MSG::default();
        while unsafe { PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE) }.as_bool() {
            if msg.message == WM_QUIT {
                quit = true;
                break;
            }
            unsafe {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        if quit || !shared.running.load(Ordering::SeqCst) {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    shared.running.store(false, Ordering::SeqCst);
    unsafe {
        let _ = RemoveClipboardFormatListener(hwnd);
    }
    // Order matters (ALGORITHMS.md section 13): restore the PC before the pipeline is joined,
    // so the phone can never be left with a key held while the desktop is still locked.
    if let Some(l) = lock.as_mut() {
        match l.exit() {
            Ok(()) => println!("[{TAG}] LOCK exited, input desktop={}", l.input_desktop()),
            Err(e) => eprintln!("[{TAG}] LOCK exit error: {e}"),
        }
    }
    // B-098: wait up to 2 s for the pipeline to leave on its own; if it is blocked in
    // read() (no video arriving), shut the cloned socket down so it returns immediately.
    let deadline = Instant::now() + Duration::from_secs(2);
    while !pipeline.is_finished() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    if !pipeline.is_finished() {
        println!("[{TAG}] exit: no pipeline exit in 2 s, shutting the socket down");
        if let Ok(mut w) = shared.wake_socket.lock() {
            if let Some(s) = w.take() {
                let _ = s.shutdown(Shutdown::Both);
            }
        }
    }
    let r = pipeline.join().map_err(|_| "pipeline panicked")?;    let _ = stats.join();
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
        // Phase 9: while the lock is active this thread is on the default desktop and cannot
        // see or take the lock desktop's foreground; the lock's own window keeps focus.
        if !crate::lock::is_active() {
            let hwnd = HWND(shared.hwnd.load(Ordering::Relaxed) as *mut std::ffi::c_void);
            if !hwnd.0.is_null()
                && !foreground_is(hwnd)
                && last_focus_claim.elapsed() >= Duration::from_secs(2)
            {
                focus_window(hwnd);
                last_focus_claim = Instant::now();
                println!("[{TAG}] keyboard focus reclaimed by the session window");
            }
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
///
/// Phase 9: on the private lock desktop the process owns the input already, and the ALT trick
/// would be needless synthetic input on a locked machine, so it is skipped.
fn focus_window(hwnd: HWND) {
    unsafe {
        if crate::lock::is_active() {
            let _ = SetForegroundWindow(hwnd);
            let _ = SetFocus(Some(hwnd));
            return;
        }
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
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hbrBackground: windows::Win32::Graphics::Gdi::CreateSolidBrush(COLORREF(0x000000)),
            ..Default::default()
        };
        // Registering twice in one process is harmless; the first result is what matters.
        let _ = RegisterClassW(&wc);
        let sw = GetSystemMetrics(SM_CXSCREEN);
        let sh = GetSystemMetrics(SM_CYSCREEN);
        // A non-zero --w/--h places the full-screen view on an extended display; the
        // default is the primary screen (0,0,primary width/height).
        let (wx, wy, ww, wh) = if shared.win_w > 0 && shared.win_h > 0 {
            (shared.win_x, shared.win_y, shared.win_w, shared.win_h)
        } else {
            (0, 0, sw, sh)
        };
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            PCWSTR(class_name.as_ptr()),
            PCWSTR(title.as_ptr()),
            WS_POPUP | WS_VISIBLE,
            wx,
            wy,
            ww,
            wh,
            None,
            None,
            Some(HINSTANCE(hinstance.0)),
            Some(Arc::as_ptr(shared) as *const std::ffi::c_void),
        )
        .map_err(|e| format!("CreateWindowExW: {e}"))?;
        let _ = SetWindowPos(hwnd, Some(HWND_TOPMOST), wx, wy, ww, wh, SWP_SHOWWINDOW);
        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = SetFocus(Some(hwnd));
        focus_window(hwnd);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        let cw = (rc.right - rc.left).max(1);
        let ch = (rc.bottom - rc.top).max(1);
        shared.client_w.store(cw as u32, Ordering::SeqCst);
        shared.client_h.store(ch as u32, Ordering::SeqCst);
        // D8: one desktop size for the session, from this window's aspect (16:10 -> 1920x1200).
        let (dw, dh) = phase5::desktop_for_client(cw, ch);
        shared.desktop_w.store(dw as u32, Ordering::SeqCst);
        shared.desktop_h.store(dh as u32, Ordering::SeqCst);
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
/// Shared with the audio player so an onset age is in the same frame as the sync offset.
fn now_us64() -> i64 {
    crate::audio::pc_now_us()
}

fn scaled(shared: &Shared, x: i32, y: i32) -> (i32, i32) {
    let (cw, ch) = shared.client();
    // B-064: the same letterbox math the blit uses, now pure and unit-tested in phase5.rs.
    // D8: the target is the negotiated desktop, not the old fixed 1920x1080.
    let (dw, dh) = shared.desktop();
    phase5::scale_client_to_desktop_for(x, y, cw, ch, dw, dh)
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
            // Phase 9: while locked, closing must go through Lock::exit() (unhook, switch to
            // Default, restore the journal) instead of quitting the loop outright.
            if crate::lock::is_locked() {
                crate::lock::request_exit();
            } else {
                PostQuitMessage(0);
            }
            LRESULT(0)
        }
        WM_SIZE => {
            let w = (lparam.0 & 0xFFFF) as u32;
            let h = ((lparam.0 >> 16) & 0xFFFF) as u32;
            if w > 0 && h > 0 {
                println!("[{TAG}] WM_SIZE {w}x{h}");
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
        WM_CLIPBOARDUPDATE => {
            queue_local_clip(&shared);
            LRESULT(0)
        }
        WM_DROPFILES => {
            collect_dropped_files(WPARAM(wparam.0), &shared);
            LRESULT(0)
        }
        WM_SETCURSOR => {
            // B-095: the video is a normal PC client area, so the real mouse must always see
            // the standard arrow here. Without this the class cursor was null and Windows kept
            // the app-starting busy icon over the whole view.
            if (lparam.0 & 0xFFFF) as u32 == HTCLIENT {
                if let Ok(cursor) = LoadCursorW(None, IDC_ARROW) {
                    SetCursor(Some(cursor));
                }
                LRESULT(1)
            } else {
                DefWindowProcW(hwnd, msg, wparam, lparam)
            }
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
            if down && std::env::var_os("UD_KEYLOG").is_some() {
                // Hop 1 of the B-062 trace: what the PC window actually received and sent.
                eprintln!("p5pc key vk=0x{vk:02x} scan=0x{scan:02x} mods=0x{:02x}", mods());
            }
            if vk == 0x1B && down {
                shared.running.store(false, Ordering::SeqCst);
                shared.release_all.store(1, Ordering::SeqCst);
                if crate::lock::is_locked() {
                    crate::lock::request_exit();
                } else {
                    PostQuitMessage(0);
                }
                return LRESULT(0);
            }
            // Phase 11 D-d: "Import from this PC" - the lock desktop has no drag target,
            // so Ctrl+O opens the standard file dialog on whichever desktop we are on.
            if vk == 0x4F && down {
                let ctrl = (GetKeyState(0x11) as u16) & 0x8000 != 0;
                if ctrl {
                    pick_import_file(hwnd, &shared);
                    return LRESULT(0);
                }
            }
            if vk == 0x7A && down {                // F11 is meaningless on the private lock desktop (one window, no frame).
                if !crate::lock::is_locked() {
                    toggle_fullscreen(hwnd, &shared);
                }
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
            let (wx, wy, ww, wh) = if shared.win_w > 0 && shared.win_h > 0 {
                (shared.win_x, shared.win_y, shared.win_w, shared.win_h)
            } else {
                (0, 0, sw, sh)
            };
            let _ = SetWindowLongPtrW(hwnd, GWL_STYLE, (WS_POPUP | WS_VISIBLE).0 as isize);
            let _ = SetWindowPos(hwnd, Some(HWND_TOPMOST), wx, wy, ww, wh,
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
    /// B2 present variant: (SyncInterval, Present flags). (1, 0) is vsync on; (0,
    /// DXGI_PRESENT_ALLOW_TEARING) is the `--no-vsync` run.
    present_sync: u32,
    present_flags: u32,
    awaiting_keyframe: bool,
    geom_logged: bool,
    logged_frames: u64,
    /// B-047: every access unit the phone sent, decoded or not (the video-on pacer stops on
    /// the first one).
    frames_in: u64,
    /// Recent frames: (seq, received, presented), PC microseconds. The phone's latency
    /// report names a frame seq; this ring supplies that frame's PC-side times.
    frames: std::collections::VecDeque<(u32, i64, i64)>,
    /// Latency reports that arrived before their frame.
    pending_lat: Vec<PendingLat>,
    /// Phase 9: 0 = never freeze. After this many seconds of lock, stop presenting so the
    /// last frame stays on screen (the frozen-video exit test).
    freeze_video_after: u64,
    frozen_logged: bool,
    /// Phase 9: the first frame that reached the screen, for `LOCK first_frame_after_engage_ms`.
    first_frame_logged: bool,
}

/// Keep rustls in blocking mode. A timed-out blocking receive can leave Winsock/rustls in an
/// indeterminate state, so link-dead detection closes a cloned socket from a separate thread.
struct ReadWatchdog {
    stop: Arc<AtomicBool>,
    last_read_ms: Arc<AtomicU64>,
    join: Option<std::thread::JoinHandle<()>>,
}

impl ReadWatchdog {
    fn start(socket: TcpStream, shared: &Arc<Shared>) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = stop.clone();
        let thread_shared = shared.clone();
        let started = Instant::now();
        let last_read_ms = Arc::new(AtomicU64::new(0));
        let thread_last_read = last_read_ms.clone();
        let join = std::thread::Builder::new()
            .name("p5-read-watchdog".into())
            .spawn(move || {
                loop {
                    if thread_stop.load(Ordering::SeqCst) {
                        break;
                    }
                    // B-098: lock-exit/Exit sets running=false while the pipeline may be
                    // blocked in read(). Shut the cloned socket down so that read returns
                    // instead of leaving the view process (and the phone session) alive.
                    if !thread_shared.running.load(Ordering::SeqCst) {
                        let _ = socket.shutdown(Shutdown::Both);
                        break;
                    }
                    let elapsed = started.elapsed().as_millis() as u64;
                    let last = thread_last_read.load(Ordering::SeqCst);
                    if elapsed.saturating_sub(last) >= crate::session::LINK_DEAD_TIMEOUT.as_millis() as u64 {
                        println!("[{TAG}] READ watchdog force-close after {}s", crate::session::LINK_DEAD_TIMEOUT.as_secs());
                        let _ = socket.shutdown(Shutdown::Both);
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(250));
                }
            })
            .expect("read watchdog thread");
        Self { stop, last_read_ms, join: Some(join) }
    }
}

impl Drop for ReadWatchdog {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

fn pipeline_loop(
    session: &mut crate::session::Session,
    shared: &Arc<Shared>,
    force_soft: bool,
    seconds: u64,
    freeze_video_after: u64,
    no_vsync: bool,
) -> Result<(), String> {
    let read_socket = session
        .tls
        .sock
        .try_clone()
        .map_err(|e| format!("clone read-watchdog socket: {e}"))?;
    // B-098: keep a second clone reachable from the exit path (see the join below).
    if let Ok(mut w) = shared.wake_socket.lock() {
        *w = session.tls.sock.try_clone().ok();
    }
    let read_watchdog = ReadWatchdog::start(read_socket, shared);
    let read_watchdog_started = Instant::now();
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
    let swap = create_swap_chain(&device, hwnd, no_vsync)?;
    println!(
        "[{TAG}] swap chain {}",
        if no_vsync { "vsync=off tearing=allowed (B2)" } else { "vsync=on" }
    );
    let (present_sync, present_flags) = present_args(no_vsync);
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
        present_sync,
        present_flags,
        awaiting_keyframe: false,
        geom_logged: false,
        logged_frames: 0,
        frames_in: 0,
        frames: std::collections::VecDeque::with_capacity(FRAME_RING),
        pending_lat: Vec::new(),
        freeze_video_after,
        frozen_logged: false,
        first_frame_logged: false,
    };

    let start = Instant::now();
    let mut read_buf = vec![0u8; 65536];
    let mut decoder = crate::protocol::FrameDecoder::new();
    let mut pending: std::collections::VecDeque<Message> = std::collections::VecDeque::new();
    let mut asm_video = phase5::ChunkAssembler::new();
    let mut asm_other = phase5::ChunkAssembler::new();
    let mut asm_clip = phase5::ChunkAssembler::new();
    let mut file_sink = crate::files::FileSink::new(downloads_dir())
        .map_err(|e| format!("file sink: {e}"))?;
    let mut file_out: Option<std::collections::VecDeque<Vec<u8>>> = None;
    let mut next_file_id: u32 = 1;
    let mut last_rx = Instant::now();
    let mut last_csq = Instant::now() - Duration::from_secs(2);
    let mut clock = phase5::ClockSync::new();
    let mut last_need = Instant::now() - Duration::from_secs(9);
    let mut media = MediaBridge::new();
    // B-047: ask for video; keep asking until the first access unit shows up.
    let mut video_on = phase5::VideoOnPacer::new();
    // D8: tell the phone the desktop size once, on the first ask, before video-on. Both
    // messages share the priority writer, so the phone sees the size before it creates
    // the encoder and the shell.
    let mut display_sent = false;
    let started_at = Instant::now();

    'pipeline: while shared.running.load(Ordering::SeqCst) {
        if seconds > 0 && start.elapsed() > Duration::from_secs(seconds) {
            println!("[{TAG}] --seconds {seconds} reached");
            break;
        }
        // ---- outgoing: input, control, clock, keep-alive (all through the priority writer)
        flush_input(&mut session.tls, shared)?;
        flush_clip(&mut session.tls, shared)?;
        service_files(&mut session.tls, shared, &mut file_out, &mut next_file_id)?;
        media.pump_audio()?;
        media.drain_cam(&mut session.tls)?;
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
            if !display_sent {
                let (dw, dh) = shared.desktop();
                let (cw, ch) = shared.client();
                let _ = w.push(Message::new(
                    CH_CONTROL,
                    format!("display {dw} {dh}").into_bytes(),
                )?);
                println!("[{TAG}] -> phone display {dw}x{dh} (client {cw}x{ch})");
                display_sent = true;
            }
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
            let t = now_us64();
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
            // B-098: the receive loop may stay in this inner loop for a whole session while
            // frames/control messages keep arriving. An Exit must stop it too, not only the
            // outer loop. The watchdog shuts the socket in parallel to wake a blocked read.
            if !shared.running.load(Ordering::SeqCst) {
                break 'pipeline;
            }
            if seconds > 0 && start.elapsed() > Duration::from_secs(seconds) {
                println!("[{TAG}] --seconds {seconds} reached");
                break 'pipeline;
            }
            // The inner loop can stay here for the whole session while video is flowing.
            // Input must not wait for a read timeout, or the phone sees clicks seconds late
            // (or only after the next transient read error).
            // B-098: the Exit path clears `running`; leave now instead of serving video
            // forever (the old code only checked it in the outer loop).
            if !shared.running.load(Ordering::SeqCst) {
                break 'pipeline;
            }
            flush_input(&mut session.tls, shared)?;
            flush_clip(&mut session.tls, shared)?;
            service_files(&mut session.tls, shared, &mut file_out, &mut next_file_id)?;
            media.pump_audio()?;
            media.drain_cam(&mut session.tls)?;
            let Some(m) = pending.pop_front() else {
                match std::io::Read::read(&mut session.tls, &mut read_buf) {
                    Ok(0) => {
                        println!("[{TAG}] phone closed the link");
                        return Ok(());
                    }
                    Ok(n) => {
                        last_rx = Instant::now();
                        read_watchdog.last_read_ms.store(
                            read_watchdog_started.elapsed().as_millis() as u64,
                            Ordering::SeqCst,
                        );
                        shared.bytes_in.fetch_add(n as u64, Ordering::Relaxed);
                        let msgs = decoder.feed(&read_buf[..n]).map_err(|e| format!("frame: {e}"))?;
                        pending.extend(msgs);
                        // The blocking reader stays inside this inner loop while video frames
                        // arrive. Keep clock-sync traffic alive here too, otherwise the phone's
                        // 15 s inbound watchdog closes an otherwise healthy stream.
                        if last_csq.elapsed() > Duration::from_secs(1) {
                            let t = now_us64();
                            let mut w = PriorityWriter::new(&mut session.tls);
                            let _ = w.push(Message::new(CH_CONTROL, format!("csq {t}").into_bytes())?);
                            let _ = w.emit_all();
                            last_csq = Instant::now();
                        }
                        continue;
                    }
                    Err(e) if is_transient_read(&e) => {
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
                        let text = String::from_utf8_lossy(&whole);
                        match text.trim() {
                            "cam-on" => media.cam_on(),
                            "cam-off" => media.cam_off("phone asked"),
                            _ if whole.starts_with(b"lat ") => p.handle_lat(&whole, &clock),
                            _ if whole.starts_with(b"ping ") => {
                                if let Some(out) = phase5::pong_for(&whole) {
                                    let mut w = PriorityWriter::new(&mut session.tls);
                                    let _ = w.push(Message::new(CH_CONTROL, out)?);
                                    let _ = w.emit_all();
                                }
                            }
                            _ => handle_control(&whole, &mut clock),
                        }
                    }
                }
                CH_AUDIO => media.on_audio(&m.payload, &clock),
                CH_CAM => { /* phone -> PC camera is not a thing; ignore */ }
                CH_FILE => match file_sink.on_message(&m.payload) {
                    Ok(Some(done)) => println!(
                        "[{TAG}] <- phone file {} {} bytes sha_ok={}",
                        done.path.display(),
                        done.bytes,
                        done.sha_ok
                    ),
                    Ok(None) => {}
                    Err(e) => eprintln!("[{TAG}] file from phone failed: {e}"),
                },
                CH_CLIP => {                    if let Some(whole) = asm_clip.feed(m.flags, &m.payload)? {
                        let n = whole.len();
                        let h = crate::clip::clip_hash(&whole);
                        if crate::clip::is_echo(h, shared.clip_hash.load(Ordering::SeqCst)) {
                            // Our own text, echoed by the phone: never write it again.
                        } else {
                            match crate::clip::decode_text(&whole) {
                                Ok(text) => match crate::clip::write_text(text.as_bytes()) {
                                    Ok(()) => {
                                        shared.clip_hash.store(h, Ordering::SeqCst);
                                        println!("[{TAG}] <- phone clip {n} bytes");
                                    }
                                    Err(e) => eprintln!("[{TAG}] clip -> PC failed: {e}"),
                                },
                                Err(e) => eprintln!("[{TAG}] clip from phone refused: {e}"),
                            }
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
    // Phase 11: the webcam light must go off and the microphone must be released on every
    // exit path, including Exit and link loss.
    media.stop("pipeline end");
    // B-098: the phone must not keep streaming to a process that is leaving; closing the
    // write side also ends the session on its side. Any blocked read is woken here.
    if let Ok(mut w) = shared.wake_socket.lock() {
        if let Some(s) = w.take() {
            let _ = s.shutdown(Shutdown::Both);
        }
    }
    unsafe {
        let _ = MFShutdown();
    }
    Ok(())
}

/// Send every locally queued input event now. Called at both levels of the pipeline loop:
/// the inner loop is the one that runs while video is continuous, so draining only in the
/// outer loop leaves a real mouse click sitting in the queue indefinitely./// Phase 11 D-c: a PC clipboard change. Hash-guarded: a text this process itself put on
/// the clipboard (a phone->PC clip) must not travel back to the phone.
fn queue_local_clip(shared: &Shared) {
    let Some(bytes) = crate::clip::read_text() else { return };
    let h = crate::clip::clip_hash(&bytes);
    if crate::clip::is_echo(h, shared.clip_hash.load(Ordering::SeqCst)) {
        return;
    }
    if bytes.len() > crate::clip::MAX_CLIP_BYTES {
        eprintln!("[{TAG}] PC clipboard {} bytes > cap, not sent", bytes.len());
        return;
    }
    shared.clip_hash.store(h, Ordering::SeqCst);
    if let Ok(mut q) = shared.clip_out.lock() {
        *q = Some(bytes);
    }
}

/// Send a queued PC clipboard text on CH_CLIP (the priority writer does the chunking).
/// Logs the length only: clipboard contents never reach a log.
fn flush_clip<W: std::io::Write>(tls: &mut W, shared: &Shared) -> Result<(), String> {
    let text = match shared.clip_out.lock() {
        Ok(mut q) => q.take(),
        Err(_) => None,
    };
    let Some(text) = text else { return Ok(()) };
    let n = text.len();
    let mut w = PriorityWriter::new(tls);
    w.push(Message::new(CH_CLIP, text).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    w.emit_all().map_err(|e| e.to_string())?;
    println!("[{TAG}] -> phone clip {n} bytes");
    Ok(())
}

/// Phase 11 D-d: files dropped on the view window (WM_DROPFILES).
unsafe fn collect_dropped_files(wparam: WPARAM, shared: &Shared) {
    let hdrop = HDROP(wparam.0 as *mut std::ffi::c_void);
    let count = DragQueryFileW(hdrop, 0xFFFF_FFFF, None);
    for i in 0..count {
        let len = DragQueryFileW(hdrop, i, None) as usize;
        let mut buf = vec![0u16; len + 1];
        DragQueryFileW(hdrop, i, Some(&mut buf));
        let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        let path = std::path::PathBuf::from(String::from_utf16_lossy(&buf[..end]));
        if let Ok(mut q) = shared.files_out.lock() {
            q.push(path);
        }
        println!("[{TAG}] import queued (drop)");
    }
    DragFinish(hdrop);
}

/// "Import from this PC": the standard Open dialog, usable on the lock desktop too.
fn pick_import_file(hwnd: HWND, shared: &Shared) {
    use windows::Win32::UI::Controls::Dialogs::{
        GetOpenFileNameW, OPENFILENAMEW, OFN_FILEMUSTEXIST, OFN_NOCHANGEDIR, OFN_PATHMUSTEXIST,
    };
    let mut buf = vec![0u16; 4096];
    let mut ofn = OPENFILENAMEW {
        lStructSize: std::mem::size_of::<OPENFILENAMEW>() as u32,
        hwndOwner: hwnd,
        lpstrFile: windows::core::PWSTR(buf.as_mut_ptr()),
        nMaxFile: buf.len() as u32,
        lpstrTitle: windows::core::w!("Import from this PC"),
        Flags: OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR,
        ..Default::default()
    };
    let ok = unsafe { GetOpenFileNameW(&mut ofn).as_bool() };
    if !ok {
        return;
    }
    let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    let path = std::path::PathBuf::from(String::from_utf16_lossy(&buf[..end]));
    if let Ok(mut q) = shared.files_out.lock() {
        q.push(path);
    }
    println!("[{TAG}] import queued (dialog)");
}

/// Where files from the phone land: %USERPROFILE%\Downloads\unified-desktop.
fn downloads_dir() -> std::path::PathBuf {
    let home = std::env::var_os("USERPROFILE")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    home.join("Downloads").join("unified-desktop")
}

/// Stream one queued import file in 64 KiB CH_FILE messages, at most 8 per loop turn so a
/// 20 MB file never blocks input, audio, clip or video (D-a priority).
fn service_files<W: std::io::Write>(
    tls: &mut W,
    shared: &Shared,
    q: &mut Option<std::collections::VecDeque<Vec<u8>>>,
    next_id: &mut u32,
) -> Result<(), String> {
    if q.is_none() {
        let path = shared
            .files_out
            .lock()
            .ok()
            .and_then(|mut v| if v.is_empty() { None } else { Some(v.remove(0)) });
        if let Some(p) = path {
            let mut buf = std::collections::VecDeque::new();
            match crate::files::send_file(*next_id, &p, &mut |m| {
                buf.push_back(m);
                Ok(())
            }) {
                Ok((n, sha)) => {
                    println!(
                        "[{TAG}] -> phone file {} {n} bytes sha={}",
                        p.display(),
                        &crate::crypto::fingerprint_hex(&sha)[..16]
                    );
                    *next_id = next_id.wrapping_add(1);
                    *q = Some(buf);
                }
                Err(e) => eprintln!("[{TAG}] import failed {}: {e}", p.display()),
            }
        }
    }
    if let Some(dq) = q.as_mut() {
        let mut w = PriorityWriter::new(tls);
        for _ in 0..8 {
            let Some(m) = dq.pop_front() else { break };
            w.push(Message::new(CH_FILE, m).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        }
        w.emit_all().map_err(|e| e.to_string())?;
        if dq.is_empty() {
            *q = None;
        }
    }
    Ok(())
}

fn flush_input<W: std::io::Write>(tls: &mut W, shared: &Shared) -> Result<(), String> {
    let events = shared.drain_events();
    if events.is_empty() {
        return Ok(());
    }
    let payload = phase5::pack_input_events(&events);
    let mut w = PriorityWriter::new(tls);
    w.push(Message::new(CH_INPUT, payload).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    w.emit_all().map_err(|e| e.to_string())?;
    Ok(())
}

/// Phase 11 D-b/D-e: the PC-side media bridge for one view session. Audio packets become
/// WASAPI playback through the jitter buffer; `cam-on` starts the webcam + microphone and
/// their CH_CAM messages are sent from here. Dropping the cam handle releases both devices.
struct MediaBridge {
    audio: Option<crate::audio::Player>,
    audio_recv: u64,
    audio_bad: u64,
    audio_players: u32,
    audio_log: std::time::Instant,
    audio_age_ms: Vec<f64>,
    cam: Option<(crate::cam::CamStream, std::sync::mpsc::Receiver<Vec<u8>>)>,
    cam_sent: u64,
    cam_dropped: u64,
    cam_log: std::time::Instant,
}

impl MediaBridge {
    fn new() -> Self {
        MediaBridge {
            audio: None,
            audio_recv: 0,
            audio_bad: 0,
            audio_players: 0,
            audio_log: std::time::Instant::now() - std::time::Duration::from_secs(2),
            audio_age_ms: Vec::new(),
            cam: None,
            cam_sent: 0,
            cam_dropped: 0,
            cam_log: std::time::Instant::now(),
        }
    }

    fn on_audio(&mut self, payload: &[u8], clock: &phase5::ClockSync) {
        if self.audio.is_none() {
            match crate::audio::Player::new() {
                Ok(p) => {
                    println!("[{TAG}] AUDIO player {} (shared mode, 48 kHz stereo)", p.describe());
                    self.audio = Some(p);
                    self.audio_players += 1;
                }
                Err(e) => {
                    println!("[{TAG}] AUDIO player failed: {e}");
                    self.audio_bad += 1;
                    return;
                }
            }
        }
        self.audio_recv += 1;
        let Some(p) = self.audio.as_mut() else { return };
        p.set_clock_offset(clock.offset_us());
        if !p.feed(payload) {
            self.audio_bad += 1;
        }
        // Arrival age in the phone->PC clock frame (network + jitter, before the device).
        if let (Some(ts), Some(off)) = (crate::audio::packet_ts_ms(payload), clock.offset_us()) {
            let arrival = now_us64() as f64 - (ts as f64 * 1000.0 - off);
            if (-50_000.0..500_000.0).contains(&arrival) {
                self.audio_age_ms.push(arrival / 1000.0);
            }
        }
        if self.audio_log.elapsed() >= std::time::Duration::from_secs(1) {
            self.audio_log = std::time::Instant::now();
            let jb = &p.jb.stats;
            let age = if self.audio_age_ms.is_empty() {
                "n/a".to_string()
            } else {
                let n = self.audio_age_ms.len() as f64;
                format!("{:.1}", self.audio_age_ms.iter().sum::<f64>() / n)
            };
            println!(
                "[{TAG}] AUDIO recv={} played={} late={} dropped={} underruns={} frames={} depth={} age_ms={} devbuf_ms={:.0} bad={}",
                self.audio_recv, jb.played, jb.late, jb.dropped, jb.underruns,
                p.written_frames, p.depth(), age, p.buffer_ms(), self.audio_bad
            );
            self.audio_age_ms.clear();
        }
    }

    fn pump_audio(&mut self) -> Result<(), String> {
        if let Some(p) = self.audio.as_mut() {
            p.pump()?;
        }
        Ok(())
    }

    fn cam_on(&mut self) {
        if self.cam.is_some() {
            println!("[{TAG}] CAM already on");
            return;
        }
        match crate::cam::start_stream(640, 480, 70) {
            Ok((s, rx)) => {
                self.cam = Some((s, rx));
                println!("[{TAG}] CAM on (phone asked; 640x480 JPEG <=15 fps + 48 kHz mic)");
            }
            Err(e) => println!("[{TAG}] CAM start failed: {e}"),
        }
    }

    fn cam_off(&mut self, why: &str) {
        if let Some((s, _)) = self.cam.take() {
            println!(
                "[{TAG}] CAM off ({why}) frames={} audio_packets={}",
                s.frames.load(std::sync::atomic::Ordering::Relaxed),
                s.audio_packets.load(std::sync::atomic::Ordering::Relaxed)
            );
            drop(s); // joins both threads: camera light off, microphone released
        }
    }

    fn drain_cam<W: std::io::Write>(&mut self, tls: &mut W) -> Result<(), String> {
        let Some((stream, rx)) = self.cam.as_ref() else { return Ok(()) };
        let mut w = PriorityWriter::new(tls);
        let mut n = 0usize;
        loop {
            match rx.try_recv() {
                Ok(msg) => {
                    // Keep the microphone flowing: only camera frames are dropped when the
                    // link is congested (freshness beats completeness for video, never for
                    // 10 ms audio packets).
                    let is_cam = msg.first() == Some(&1u8);
                    if is_cam && (n >= 8 || w.pending_len(CH_CAM) >= 8) {
                        self.cam_dropped += 1;
                        continue;
                    }
                    w.push(Message::new(CH_CAM, msg).map_err(|e| e.to_string())?)?;
                    n += 1;
                    self.cam_sent += 1;
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => break,
            }
        }
        if n > 0 {
            w.emit_all().map_err(|e| e.to_string())?;
        }
        if self.cam_log.elapsed() >= std::time::Duration::from_secs(3) {
            self.cam_log = std::time::Instant::now();
            println!(
                "[{TAG}] CAMSTAT sent={} dropped={} frames={} audio_packets={}",
                self.cam_sent,
                self.cam_dropped,
                stream.frames.load(std::sync::atomic::Ordering::Relaxed),
                stream.audio_packets.load(std::sync::atomic::Ordering::Relaxed)
            );
        }
        Ok(())
    }

    fn stop(&mut self, why: &str) {
        self.cam_off(why);
        self.audio = None;
    }
}

fn handle_control(whole: &[u8], clock: &mut phase5::ClockSync) {
    let text = String::from_utf8_lossy(whole).to_string();
    let t = text.trim();
    if let Some(rest) = t.strip_prefix("csr ") {
        let mut it = rest.split_whitespace();
        if let (Some(a), Some(b)) = (it.next(), it.next()) {
            if let (Ok(t1_raw), Ok(t3)) = (a.parse::<i64>(), b.parse::<i64>()) {
                let t4 = now_us64();
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
                if !self.first_frame_logged {
                    self.first_frame_logged = true;
                    if let Some(t) = crate::lock::engaged_at() {
                        println!(
                            "[{TAG}] LOCK first_frame_after_engage_ms={} seq={}",
                            t.elapsed().as_millis(),
                            vf.seq
                        );
                    }
                }
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
        if produced > 0 && self.frozen() {
            if !self.frozen_logged {
                self.frozen_logged = true;
                println!(
                    "[{TAG}] LOCK video frozen after {}s: presenting stopped, last frame stays",
                    self.freeze_video_after
                );
            }
            return Ok(0);
        }
        if produced > 0 {
            unsafe {
                let _ = self
                    .swap
                    .Present(self.present_sync, DXGI_PRESENT(self.present_flags));
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

    /// Phase 9 frozen-frame test: after `--freeze-video-after` seconds of lock, stop
    /// presenting. The flip-model swap chain keeps the last frame on screen, so the picture
    /// is frozen (not black) and the pipeline keeps consuming the phone's stream.
    fn frozen(&self) -> bool {
        if self.freeze_video_after == 0 {
            return false;
        }
        match crate::lock::engaged_at() {
            Some(t) => t.elapsed() >= Duration::from_secs(self.freeze_video_after),
            None => false,
        }
    }

    /// Drain every decodable frame and put it on screen. Returns how many were presented.
    /// Phase 13: a window resize (F11, a DPI change) changes the client but not the desktop.
    /// Resize the swap chain so the picture still fills the window; without this the old
    /// back buffer keeps only the top-left of a larger dest rect (the 2.2x crop seen on the
    /// 03:56 capture). The video processor output view is created per blit, so it follows.
    fn ensure_swap_size(&mut self, out_w: i32, out_h: i32) -> Result<(), String> {
        let d = unsafe { self.swap.GetDesc1() }.map_err(|e| format!("GetDesc1: {e}"))?;
        if d.Width as i32 == out_w && d.Height as i32 == out_h {
            return Ok(());
        }
        unsafe {
            self.swap
                .ResizeBuffers(
                    0,
                    out_w.max(2) as u32,
                    out_h.max(2) as u32,
                    d.Format,
                    DXGI_SWAP_CHAIN_FLAG(d.Flags as i32),
                )
                .map_err(|e| format!("ResizeBuffers {out_w}x{out_h}: {e}"))?;
        }
        println!(
            "[{TAG}] swap chain resized {}x{} -> {out_w}x{out_h}",
            d.Width, d.Height
        );
        self.vp = None;
        Ok(())
    }

    fn drain_outputs(&mut self) -> Result<usize, String> {
        let mut produced = 0usize;
        loop {
            let mut db = [MFT_OUTPUT_DATA_BUFFER::default()];
            db[0].dwStreamID = 0;
            // B-104: the software decoder does not provide output samples
            // (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES is clear), so it refuses ProcessOutput with
            // E_INVALIDARG ("Null output sample") unless the caller supplies the sample and
            // buffer. The hardware decoder does provide them; allocating there would be wasted
            // memory. Keep our own handle so the sample is released on success and on error.
            let allocated = if output_sample_needed(self.dec.provides_samples) {
                let sample = unsafe {
                    MFCreateSample().map_err(|e| format!("MFCreateSample(output): {e}"))?
                };
                let buffer = unsafe {
                    MFCreateMemoryBuffer(self.dec.out_cb_size.max(1))
                        .map_err(|e| format!("MFCreateMemoryBuffer(output): {e}"))?
                };
                unsafe {
                    sample
                        .AddBuffer(&buffer)
                        .map_err(|e| format!("AddBuffer(output): {e}"))?;
                }
                Some(sample)
            } else {
                None
            };
            db[0].pSample = std::mem::ManuallyDrop::new(allocated.clone());
            let mut status = 0u32;
            let hr = unsafe { self.dec.mft.ProcessOutput(0, &mut db, &mut status) };
            if let Err(e) = hr {
                // Release whatever pointer is in the buffer (ours, or one the MFT replaced it
                // with) before leaving this iteration.
                unsafe {
                    let _ = std::mem::ManuallyDrop::take(&mut db[0].pSample);
                    if db[0].pEvents.is_some() {
                        std::mem::ManuallyDrop::drop(&mut db[0].pEvents);
                    }
                }
                match classify_output_code(e.code().0) {
                    OutputOutcome::NeedInput => break,
                    OutputOutcome::StreamChange => {
                        // B-057: renegotiate the output type IN PLACE and keep calling
                        // ProcessOutput. A stream change is not an error: the decoder wants a new
                        // output type, then continues from the same input. Rebuilding it or
                        // dropping frames until the next keyframe only makes the hole visible
                        // (stuck picture, ~1 fps for a second).
                        println!("[{TAG}] decoder stream change; setting the new output type in place");
                        self.dec.renegotiate_output()?;
                        continue;
                    }
                    OutputOutcome::Fatal => return Err(format!("ProcessOutput: {e}")),
                }
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
            let (desk_w, desk_h) = self.shared.desktop();
            self.ensure_swap_size(out_w, out_h)?;
            if !self.geom_logged {
                let d = unsafe { self.swap.GetDesc1() }.unwrap_or_default();
                println!(
                    "[{TAG}] geom client={out_w}x{out_h} swap={}x{} desk={desk_w}x{desk_h} dec={in_w}x{in_h}",
                    d.Width, d.Height
                );
                self.geom_logged = true;
            }
            if self.dec.d3d11 {
                let need_new = match &self.vp {
                    Some(v) => v.key != (in_w, in_h, out_w, out_h),
                    None => true,
                };
                if need_new {
                    self.vp = Some(VideoProc::build(&self.video_device, in_w, in_h, out_w, out_h)?);
                }
                let v = self.vp.as_ref().ok_or("no video processor")?;
                blit_dxgi(&self.video_ctx, &self.video_device, v, &self.dec, &self.swap, in_w, in_h, out_w, out_h, desk_w, desk_h)?;
            } else {
                blit_soft(&self.d3d_ctx, &mut self.dec, &self.swap, desk_w, desk_h)?;
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
    /// B-104: true when the MFT allocates its own output sample. The software H.264 MFT
    /// reports false, so the caller must hand it a sample and a buffer of `out_cb_size`.
    provides_samples: bool,
    out_cb_size: u32,
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

impl Decoder {
    /// B-057: adopt the output type the decoder now offers, without flushing the input stream or
    /// rebuilding the decoder. Callers keep pulling output immediately afterwards.
    fn renegotiate_output(&mut self) -> Result<(), String> {
        let (w, h) = choose_and_set_output(&self.mft)?;
        self.out_w = w;
        self.out_h = h;
        self.refresh_output_info()?;
        self.soft_tex = None;
        self.soft_w = 0;
        self.soft_h = 0;
        Ok(())
    }

    /// B-104: a stream change can change the required output buffer size. Re-read the flags so
    /// the software path always allocates a sample big enough for the current NV12 surface.
    fn refresh_output_info(&mut self) -> Result<(), String> {
        let info = unsafe {
            self.mft
                .GetOutputStreamInfo(0)
                .map_err(|e| format!("GetOutputStreamInfo: {e}"))?
        };
        self.provides_samples = info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 != 0;
        self.out_cb_size = info.cbSize;
        Ok(())
    }
}

/// B-104: the MS H.264 MFT in software mode has MFT_OUTPUT_STREAM_PROVIDES_SAMPLES clear and
/// returns E_INVALIDARG ("Null output sample") when ProcessOutput gets a null pSample. The
/// hardware path provides samples itself. Split out so the rule is unit-testable.
fn output_sample_needed(provides_samples: bool) -> bool {
    !provides_samples
}

/// The three ProcessOutput outcomes we distinguish. Split out so the B-057 rule - a stream change
/// is handled in place, not as a failure that waits for a keyframe - is testable without a media
/// stack.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OutputOutcome {
    /// MF_E_TRANSFORM_NEED_MORE_INPUT: feed the next access unit.
    NeedInput,
    /// MF_E_TRANSFORM_STREAM_CHANGE: set the new output type, keep calling ProcessOutput.
    StreamChange,
    /// Anything else: a real failure.
    Fatal,
}

fn classify_output_code(code: i32) -> OutputOutcome {
    if code == MF_E_TRANSFORM_NEED_MORE_INPUT.0 {
        OutputOutcome::NeedInput
    } else if code == MF_E_TRANSFORM_STREAM_CHANGE.0 {
        OutputOutcome::StreamChange
    } else {
        OutputOutcome::Fatal
    }
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

/// B2: `DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING` is only legal together with `Present(0, ...)`.
/// Kept as a pure function so the flag pair is testable without a window.
fn swap_chain_flags(allow_tearing: bool) -> u32 {
    if allow_tearing {
        DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING.0 as u32
    } else {
        0
    }
}

/// B2: `(SyncInterval, Present flags)` for the two presentation variants.
fn present_args(no_vsync: bool) -> (u32, u32) {
    if no_vsync {
        (0, DXGI_PRESENT_ALLOW_TEARING.0)
    } else {
        (1, 0)
    }
}

fn create_swap_chain(
    device: &ID3D11Device,
    hwnd: HWND,
    allow_tearing: bool,
) -> Result<IDXGISwapChain1, String> {
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
            Flags: swap_chain_flags(allow_tearing),
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
            provides_samples: provides,
            out_cb_size: info.cbSize,
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
    dec.refresh_output_info()?;
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
    desk_w: i32,
    desk_h: i32,
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

        // Keep the aspect ratio: fit the visible desktop inside the window.
        let (vis_w, vis_h) = visible_size(in_w, in_h, desk_w, desk_h);
        let (dw, dh) = fit(vis_w, vis_h, out_w, out_h);
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
        let src = RECT { left: 0, top: 0, right: vis_w, bottom: vis_h };
        video_ctx.VideoProcessorSetStreamSourceRect(&v.processor, 0, true, Some(&src));
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
fn blit_soft(d3d_ctx: &ID3D11DeviceContext, dec: &mut Decoder, swap: &IDXGISwapChain1, desk_w: i32, desk_h: i32) -> Result<(), String> {
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
        // Crop the decoder's macroblock padding, exactly as the hardware path does.
        let (vis_w, vis_h) = visible_size(w, h, desk_w, desk_h);
        let (dw, dh) = fit(vis_w, vis_h, bw, bh);
        let src_box = D3D11_BOX {
            left: 0,
            top: 0,
            front: 0,
            right: vis_w as u32,
            bottom: vis_h as u32,
            back: 1,
        };
        let dst_x = ((bw - dw) / 2).max(0) as u32;
        let dst_y = ((bh - dh) / 2).max(0) as u32;
        d3d_ctx.CopySubresourceRegion(&back, 0, dst_x, dst_y, 0, tex, 0, Some(&src_box));
        Ok(())
    }
}

/// H.264 codes picture in 16x16 macroblocks, so the decoder pads a 1920x1080 desktop to
/// 1920x1088. Those last eight rows contain no picture data: scaled into the window they leave
/// the green band seen below the taskbar (B-085). The input type declares the real desktop size,
/// so the visible rectangle is the coded size clamped to that size.
/// H.264 codes picture in 16x16 macroblocks, so the decoder pads 1920x1080 to 1920x1088 and a
/// half-size 960x1200 stream to 960x1216. Those extra rows contain no picture data: scaled into
/// the window they leave the green band seen below the taskbar (B-085). The desktop size declares
/// the real aspect, so crop the coded height to that aspect (D8).
fn visible_size(in_w: i32, in_h: i32, desk_w: i32, desk_h: i32) -> (i32, i32) {
    let in_w = in_w.max(2);
    let in_h = in_h.max(2);
    let (dw, dh) = (desk_w.max(2), desk_h.max(2));
    let aspect_h = ((in_w as f64 * dh as f64 / dw as f64).round() as i32).clamp(2, in_h);
    (in_w, aspect_h)
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Typing latency: the pipeline wakes on a 5 ms read tick while the desktop is static;
    /// a timeout/would-block must reach the `break` path (flush input, try again), never the
    /// fatal `return Err`. A real socket error must stay fatal.
    #[test]
    fn read_timeouts_are_transient_but_real_errors_are_not() {
        for kind in [std::io::ErrorKind::WouldBlock, std::io::ErrorKind::TimedOut] {
            assert!(is_transient_read(&std::io::Error::from(kind)), "{kind:?}");
        }
        for kind in [
            std::io::ErrorKind::ConnectionReset,
            std::io::ErrorKind::UnexpectedEof,
            std::io::ErrorKind::BrokenPipe,
        ] {
            assert!(!is_transient_read(&std::io::Error::from(kind)), "{kind:?}");
        }
    }

    /// B-057: a decoder stream change must be renegotiated in place; it is never treated as a
    /// failure that drops frames until the next keyframe.
    #[test]
    fn stream_change_is_handled_in_place() {
        assert_eq!(
            classify_output_code(MF_E_TRANSFORM_STREAM_CHANGE.0),
            OutputOutcome::StreamChange
        );
        assert_eq!(
            classify_output_code(MF_E_TRANSFORM_NEED_MORE_INPUT.0),
            OutputOutcome::NeedInput
        );
        assert_eq!(classify_output_code(-1), OutputOutcome::Fatal);
        assert_eq!(classify_output_code(0x8000_4005u32 as i32), OutputOutcome::Fatal);
    }

    /// B-104: the software H.264 MFT does not provide output samples, so the caller must
    /// allocate one. The hardware MFT provides its own, and allocating there would waste memory.
    #[test]
    fn output_sample_is_allocated_only_when_the_mft_does_not_provide_it() {
        assert!(output_sample_needed(false));
        assert!(!output_sample_needed(true));
    }

    /// B-085: the decoder reports macroblock-padded dimensions (1920x1088 for a 1920x1080
    /// desktop). Only the declared desktop area is picture; the padding must be cropped.
    #[test]
    fn macroblock_padding_is_cropped() {
        assert_eq!(visible_size(1920, 1088, 1920, 1080), (1920, 1080));
        assert_eq!(visible_size(1920, 1080, 1920, 1080), (1920, 1080));
        assert_eq!(visible_size(1280, 736, 1920, 1080), (1280, 720));
        assert_eq!(visible_size(640, 368, 1920, 1080), (640, 360));
        // Never produce an empty rectangle.
        assert_eq!(visible_size(1, 1, 1920, 1080), (2, 2));
        assert_eq!(visible_size(4096, 2160, 1920, 1080), (4096, 2160));
        // D8: a 16:10 desktop keeps all 1200 rows (no 1080 crop, no green band).
        assert_eq!(visible_size(1920, 1216, 1920, 1200), (1920, 1200));
        // The governor's half-size 960x600 stream pads to 960x608: crop to 600, not 1200.
        assert_eq!(visible_size(960, 608, 1920, 1200), (960, 600));
    }

    /// B-067: raw Windows timeout/pending codes from a socket read are transient (the loop
    /// retries); real socket errors stay fatal.
    #[test]
    fn transient_read_errors_are_not_fatal() {
        use std::io::{Error, ErrorKind};
        assert!(is_transient_read(&Error::from(ErrorKind::WouldBlock)));
        assert!(is_transient_read(&Error::from(ErrorKind::TimedOut)));
        assert!(is_transient_read(&Error::from_raw_os_error(997))); // ERROR_IO_PENDING
        assert!(is_transient_read(&Error::from_raw_os_error(10060))); // WSAETIMEDOUT
        assert!(!is_transient_read(&Error::from_raw_os_error(10054))); // WSAECONNRESET: fatal
        assert!(!is_transient_read(&Error::from_raw_os_error(9))); // EBADF: fatal
    }

    /// B-095 follow-up: a real mouse click queued while video is flowing must be written by
    /// the inner pipeline loop, not left until the outer loop gets a transient read.
    #[test]
    fn flush_input_sends_and_clears_queued_events() {
        let shared = Shared::new(0, 0, 1920, 1080);
        let events = vec![
            InputEvent::Move { x: 1, y: 2 },
            InputEvent::Button { button: 0, down: true, x: 1, y: 2, t_pc_us: 3 },
        ];
        for e in &events {
            shared.push(e.clone());
        }
        let mut wire = Vec::new();
        flush_input(&mut wire, &shared).unwrap();
        assert!(!wire.is_empty());
        assert!(shared.drain_events().is_empty(), "flush must consume the queue");

        let mut dec = crate::protocol::FrameDecoder::new();
        let msgs = dec.feed(&wire).unwrap();
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].channel, CH_INPUT);
        assert_eq!(phase5::parse_input_events(&msgs[0].payload).unwrap(), events);
    }

    /// B-098: stopping a view must close the blocking phone socket; otherwise the
    /// process and the phone session survive Exit.
    #[test]
    fn read_watchdog_shuts_socket_when_stopped() {
        use std::io::Read;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let mut peer = std::net::TcpStream::connect(addr).unwrap();
        let (server, _) = listener.accept().unwrap();
        let shared = Arc::new(Shared::new(0, 0, 1920, 1080));
        shared.running.store(false, Ordering::SeqCst);
        let _watchdog = ReadWatchdog::start(server, &shared);

        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let t = Instant::now();
        let mut one = [0u8; 1];
        let n = peer.read(&mut one).expect("read after watchdog shutdown");
        assert_eq!(n, 0);
        assert!(t.elapsed() < Duration::from_secs(2), "read did not wake: {:?}", t.elapsed());
    }

    #[test]
    fn nearest_epoch_handles_forward_wrap() {
        let span = 1_i64 << 32;
        let before = span - 10;
        let wrapped = before & 0xFFFF_FFFF;
        let after = span + 25;
        assert_eq!(nearest_epoch(wrapped, after), before);
    }

    #[test]
    fn nearest_epoch_handles_backward_wrap() {
        let span = 1_i64 << 32;
        let now = span + 40;
        let old_wire = 15_i64;
        assert_eq!(nearest_epoch(old_wire, now), span + 15);
    }

    /// B2: the no-vsync variant must use SyncInterval 0 *and* allow tearing; vsync stays
    /// exactly the Phase 5 pair.
    #[test]
    fn present_variants_are_paired() {
        assert_eq!(present_args(false), (1, 0));
        assert_eq!(present_args(true), (0, 512));
        assert_eq!(swap_chain_flags(false), 0);
        assert_eq!(swap_chain_flags(true), 2048);
    }
}
