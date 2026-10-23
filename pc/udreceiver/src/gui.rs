//! Phase 13 step 11: the receiver's small no-argument window.
//!
//! `udreceiver.exe` with no arguments opens this window. The command-line subcommands are
//! untouched (they still write to the parent console). The window owns no protocol code: it
//! discovers a phone through the same mDNS browser as `discover`, pairs through the same
//! session path as `pair`, then starts the existing full-screen `view` child and waits for
//! Exit. When the child exits, this window returns with Reconnect.
//!
//! The screen state is a pure value (`GuiModel`) so the labels, error text, auto-connect rule
//! and button hit boxes are unit-tested without a phone or a window.

use std::fs::File;
use std::os::windows::process::CommandExt;
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use windows::core::{HSTRING, PCWSTR};
use windows::Win32::Foundation::{
    COLORREF, HINSTANCE, HWND, LPARAM, LRESULT, RECT, WPARAM, TRUE,
};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateBitmap, CreateFontW, CreateSolidBrush, DeleteObject, DrawTextW, EndPaint,
    FillRect, GetStockObject, InvalidateRect, RoundRect, SelectObject, SetBkMode, SetTextColor,
    UpdateWindow, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS, DEFAULT_CHARSET, DEFAULT_PITCH,
    DRAW_TEXT_FORMAT, DT_CENTER, DT_LEFT, DT_NOPREFIX, DT_SINGLELINE, DT_VCENTER, DT_WORDBREAK,
    FF_DONTCARE, FW_BOLD, FW_NORMAL, HBRUSH, HDC, HFONT, HGDIOBJ, NULL_PEN, OUT_DEFAULT_PRECIS,
    PAINTSTRUCT, TRANSPARENT,
};
use windows::Win32::System::Console::{FreeConsole, GetConsoleProcessList, GetConsoleWindow};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::CREATE_NO_WINDOW;
use windows::Win32::UI::HiDpi::{GetDpiForSystem, GetDpiForWindow};
use windows::Win32::UI::WindowsAndMessaging::{
    AdjustWindowRectEx, CreateIconIndirect, CreateWindowExW, DefWindowProcW, DestroyWindow,
    DispatchMessageW, GetClientRect, GetMessageW, GetSystemMetrics, LoadCursorW, PostQuitMessage,
    RegisterClassW, SendMessageW, SetCursor, SetForegroundWindow, SetTimer, SetWindowPos,
    ShowWindow, TranslateMessage, CREATESTRUCTW, HTCLIENT, ICONINFO, IDC_ARROW, MSG, SM_CXSCREEN,
    SM_CYSCREEN, SW_HIDE, SW_SHOW, SW_SHOWNORMAL, WM_CLOSE, WM_DESTROY, WM_ERASEBKGND,
    WM_LBUTTONUP, WM_PAINT, WM_SETCURSOR, WM_SETICON, WM_TIMER, WNDCLASSW, WINDOW_EX_STYLE,
    WS_CAPTION, WS_MINIMIZEBOX, WS_OVERLAPPED, WS_SYSMENU,
};

use crate::crypto::{self, TrustedPhoneStore};
use crate::session;

// ------------------------------------------------------------------ palette / layout

const BG: u32 = 0x0B0E14;
const SURFACE: u32 = 0x141922;
const RAISED: u32 = 0x1C2230;
const BORDER: u32 = 0x2A3140;
const TEXT: u32 = 0xE8ECF2;
const SECONDARY: u32 = 0x9AA4B2;
const DIM: u32 = 0x6E7887;
const ACCENT: u32 = 0x4C8DFF;
const ON_ACCENT: u32 = 0xFFFFFF;
const SUCCESS: u32 = 0x39D98A;

/// Logical (96 dpi) client size. The window is scaled by the system DPI.
const CW: i32 = 480;
const CH: i32 = 560;
const PX: i32 = 28;
const PY: i32 = 468;
const PW: i32 = 424;
const PH: i32 = 48;
const SX: i32 = 28;
const SY: i32 = 420;
const SW: i32 = 424;
const SH: i32 = 34;

fn color(hex: u32) -> COLORREF {
    let r = (hex >> 16) & 0xFF;
    let g = (hex >> 8) & 0xFF;
    let b = hex & 0xFF;
    COLORREF(r | (g << 8) | (b << 16))
}

fn logical_rect(x: i32, y: i32, w: i32, h: i32, s: f32) -> RECT {
    RECT {
        left: (x as f32 * s) as i32,
        top: (y as f32 * s) as i32,
        right: ((x + w) as f32 * s) as i32,
        bottom: ((y + h) as f32 * s) as i32,
    }
}

// ------------------------------------------------------------------ pure screen state

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Screen {
    Looking,
    Found {
        name: String,
        addr: String,
        after_exit: bool,
    },
    Connecting {
        name: String,
        addr: String,
    },
    Pairing {
        code: String,
        name: String,
        addr: String,
    },
    Error {
        title: String,
        body: String,
        detail: String,
        retry: Option<(String, String)>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    Found { name: String, addr: String },
    NoPhone,
    PairingCode(String),
    Paired { name: String, addr: String },
    Failed(String),
    ChildExited { name: String, addr: String },
    ChildFailed { detail: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    None,
    Connect { name: String, addr: String },
    Launch { name: String, addr: String },
    Discover,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Button {
    Primary,
    Secondary,
}

pub struct GuiModel {
    pub screen: Screen,
    /// True when at least one phone is already pinned; a discovered phone then connects
    /// without another Connect click (R2: remembers the trusted phone).
    pub trusted: bool,
    last_phone: Option<(String, String)>,
}

impl GuiModel {
    pub fn new(trusted: bool) -> Self {
        GuiModel {
            screen: Screen::Looking,
            trusted,
            last_phone: None,
        }
    }

    pub fn apply(&mut self, event: Event) -> Action {
        match event {
            Event::Found { name, addr } => {
                if !matches!(self.screen, Screen::Looking) {
                    return Action::None;
                }
                self.last_phone = Some((name.clone(), addr.clone()));
                if self.trusted {
                    self.screen = Screen::Connecting {
                        name: name.clone(),
                        addr: addr.clone(),
                    };
                    Action::Connect { name, addr }
                } else {
                    self.screen = Screen::Found {
                        name,
                        addr,
                        after_exit: false,
                    };
                    Action::None
                }
            }
            Event::NoPhone => {
                if !matches!(self.screen, Screen::Looking) {
                    return Action::None;
                }
                self.screen = no_phone_error();
                Action::None
            }
            Event::PairingCode(code) => {
                match &self.screen {
                    Screen::Connecting { name, addr } => {
                        let name = name.clone();
                        let addr = addr.clone();
                        self.screen = Screen::Pairing { code, name, addr };
                    }
                    Screen::Pairing { name, addr, .. } => {
                        let name = name.clone();
                        let addr = addr.clone();
                        self.screen = Screen::Pairing { code, name, addr };
                    }
                    _ => {}
                }
                Action::None
            }
            Event::Paired { name, addr } => {
                if matches!(self.screen, Screen::Connecting { .. } | Screen::Pairing { .. }) {
                    self.screen = Screen::Connecting {
                        name: name.clone(),
                        addr: addr.clone(),
                    };
                    Action::Launch { name, addr }
                } else {
                    Action::None
                }
            }
            Event::Failed(detail) => {
                if matches!(self.screen, Screen::Connecting { .. } | Screen::Pairing { .. }) {
                    let (title, body, detail) = connect_error(&detail);
                    self.screen = Screen::Error {
                        title,
                        body,
                        detail,
                        retry: self.last_phone.clone(),
                    };
                }
                Action::None
            }
            Event::ChildExited { name, addr } => {
                self.last_phone = Some((name.clone(), addr.clone()));
                self.screen = Screen::Found {
                    name,
                    addr,
                    after_exit: true,
                };
                Action::None
            }
            Event::ChildFailed { detail } => {
                let (name, addr) = self
                    .last_phone
                    .clone()
                    .unwrap_or_else(|| ("Your phone".to_string(), String::new()));
                self.screen = Screen::Error {
                    title: "The desktop stopped".to_string(),
                    body: "Your PC is back. Try again when the phone is ready.".to_string(),
                    detail,
                    retry: Some((name, addr)),
                };
                Action::None
            }
        }
    }

    pub fn begin_connect(&mut self) -> Option<(String, String)> {
        match &self.screen {
            Screen::Found {
                name,
                addr,
                after_exit: _,
            } => {
                let n = name.clone();
                let a = addr.clone();
                self.screen = Screen::Connecting {
                    name: n.clone(),
                    addr: a.clone(),
                };
                Some((n, a))
            }
            _ => None,
        }
    }

    pub fn begin_retry(&mut self) -> Action {
        self.screen = Screen::Looking;
        Action::Discover
    }

    pub fn primary(&self) -> Option<&'static str> {
        match &self.screen {
            Screen::Found { after_exit, .. } => {
                Some(if *after_exit { "Reconnect" } else { "Connect" })
            }
            Screen::Error { .. } => Some("Try again"),
            _ => None,
        }
    }

    pub fn secondary(&self) -> Option<&'static str> {
        match &self.screen {
            Screen::Found { .. } => Some("Look again"),
            _ => None,
        }
    }

    pub fn button_at(&self, x: i32, y: i32) -> Option<Button> {
        let inside = |rx: i32, ry: i32, rw: i32, rh: i32| {
            x >= rx && x < rx + rw && y >= ry && y < ry + rh
        };
        if self.primary().is_some() && inside(PX, PY, PW, PH) {
            return Some(Button::Primary);
        }
        if self.secondary().is_some() && inside(SX, SY, SW, SH) {
            return Some(Button::Secondary);
        }
        None
    }
}

pub fn no_phone_error() -> Screen {
    Screen::Error {
        title: "No phone found after 30 s".to_string(),
        body: "Check the cable, or that both are on the same Wi-Fi.".to_string(),
        detail: "Start the link on your phone, then try again.".to_string(),
        retry: None,
    }
}

pub fn connect_error(error: &str) -> (String, String, String) {
    let lower = error.to_lowercase();
    if lower.contains("refused") || lower.contains("10061") || lower.contains("actively refused") {
        (
            "The phone refused the connection".to_string(),
            "Unlock the phone and keep the desktop running, then try again.".to_string(),
            error.to_string(),
        )
    } else if lower.contains("timed out")
        || lower.contains("10060")
        || lower.contains("handshake")
        || lower.contains("firewall")
    {
        (
            "Windows Firewall blocked the connection".to_string(),
            "Allow this app through Windows Firewall, then try again.".to_string(),
            error.to_string(),
        )
    } else {
        (
            "Could not connect".to_string(),
            "The connection stopped. Check the phone, then try again.".to_string(),
            error.to_string(),
        )
    }
}

/// "983053" -> "983 053" on the PC screen (the phone shows the six digits unspaced).
pub fn spaced_code(code: &str) -> String {
    let digits: String = code.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() == 6 {
        format!("{} {}", &digits[..3], &digits[3..])
    } else {
        digits
    }
}

// ------------------------------------------------------------------ runtime

static MODEL: OnceLock<Mutex<GuiModel>> = OnceLock::new();
static RX: OnceLock<Mutex<mpsc::Receiver<Event>>> = OnceLock::new();
static TX: OnceLock<mpsc::Sender<Event>> = OnceLock::new();
static DISCOVERY_GEN: AtomicU64 = AtomicU64::new(0);

fn model() -> &'static Mutex<GuiModel> {
    MODEL.get().expect("gui model")
}

fn sender() -> mpsc::Sender<Event> {
    TX.get().expect("gui sender").clone()
}

pub fn run() -> i32 {
    run_with_addr(None)
}

/// No-argument entry plus a test-only direct address (`udreceiver --window --addr ...`).
/// The normal user path still discovers the phone with mDNS.
pub fn run_with_addr(addr: Option<String>) -> i32 {
    hide_console_if_owned();
    crypto::ensure_ring_default();
    let trusted = !TrustedPhoneStore::open().entries().is_empty();
    let (tx, rx) = mpsc::channel();
    let _ = TX.set(tx);
    let _ = RX.set(Mutex::new(rx));
    let _ = MODEL.set(Mutex::new(GuiModel::new(trusted)));

    let hwnd = match create_window() {
        Ok(h) => h,
        Err(e) => {
            eprintln!("receiver window: {e}");
            return 1;
        }
    };
    unsafe {
        let _ = SetTimer(Some(hwnd), 1, 100, None);
        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = UpdateWindow(hwnd);
        let _ = SetForegroundWindow(hwnd);
    }
    match addr {
        Some(addr) => {
            let action = {
                let mut m = model().lock().unwrap();
                m.apply(Event::Found {
                    name: "Emulator phone".to_string(),
                    addr,
                })
            };
            if let Action::Connect { name, addr } = action {
                start_connect(name, addr);
            }
        }
        None => start_discovery(),
    }

    unsafe {
        let mut msg = MSG::default();
        loop {
            let r = GetMessageW(&mut msg, None, 0, 0);
            if !r.as_bool() || msg.message == 0 {
                break;
            }
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    0
}

fn hide_console_if_owned() {
    unsafe {
        let mut pids = [0u32; 4];
        let n = GetConsoleProcessList(&mut pids);
        if n <= 1 {
            let cw = GetConsoleWindow();
            if !cw.0.is_null() {
                let _ = ShowWindow(cw, SW_HIDE);
            }
            let _ = FreeConsole();
        }
    }
}

fn create_window() -> Result<HWND, String> {
    unsafe {
        let hinstance: HINSTANCE = GetModuleHandleW(None)
            .map_err(|e| format!("GetModuleHandleW: {e}"))?
            .into();
        let class = HSTRING::from("udreceiver-gui");
        let title = HSTRING::from("Phone desktop");
        let wc = WNDCLASSW {
            lpfnWndProc: Some(wndproc),
            hInstance: HINSTANCE(hinstance.0),
            lpszClassName: PCWSTR(class.as_ptr()),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hbrBackground: CreateSolidBrush(color(BG)),
            ..Default::default()
        };
        let _ = RegisterClassW(&wc);
        let style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
        let dpi = GetDpiForSystem().max(96);
        let s = dpi as f32 / 96.0;
        let mut rc = logical_rect(0, 0, CW, CH, s);
        let _ = AdjustWindowRectEx(&mut rc, style, false, WINDOW_EX_STYLE(0));
        let w = (rc.right - rc.left).max(1);
        let h = (rc.bottom - rc.top).max(1);
        let x = (GetSystemMetrics(SM_CXSCREEN) - w) / 2;
        let y = (GetSystemMetrics(SM_CYSCREEN) - h) / 2;
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            PCWSTR(class.as_ptr()),
            PCWSTR(title.as_ptr()),
            style,
            x,
            y,
            w,
            h,
            None,
            None,
            Some(HINSTANCE(hinstance.0)),
            None,
        )
        .map_err(|e| format!("CreateWindowExW: {e}"))?;
        if let Some(icon) = make_icon() {
            SendMessageW(hwnd, WM_SETICON, Some(WPARAM(0)), Some(LPARAM(icon.0 as isize)));
            SendMessageW(hwnd, WM_SETICON, Some(WPARAM(1)), Some(LPARAM(icon.0 as isize)));
        }
        let _ = SetWindowPos(hwnd, None, x, y, w, h, Default::default());
        Ok(hwnd)
    }
}

fn make_icon() -> Option<windows::Win32::UI::WindowsAndMessaging::HICON> {
    unsafe {
        let n = 32usize;
        let mut pixels = vec![0u32; n * n];
        for y in 0..n {
            for x in 0..n {
                let (r, g, b) = if (8..24).contains(&x) && (6..20).contains(&y) {
                    (0x4C, 0x8D, 0xFF)
                } else if (10..22).contains(&x) && (8..18).contains(&y) {
                    (0x0B, 0x0E, 0x14)
                } else if (14..18).contains(&x) && (20..25).contains(&y) {
                    (0x4C, 0x8D, 0xFF)
                } else if (10..22).contains(&x) && (25..27).contains(&y) {
                    (0x4C, 0x8D, 0xFF)
                } else {
                    (0x0B, 0x0E, 0x14)
                };
                pixels[y * n + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        let mask = vec![0u8; n * n / 8];
        let hbm_color = CreateBitmap(n as i32, n as i32, 1, 32, Some(pixels.as_ptr() as *const _));
        let hbm_mask = CreateBitmap(n as i32, n as i32, 1, 1, Some(mask.as_ptr() as *const _));
        let info = ICONINFO {
            fIcon: TRUE,
            xHotspot: 0,
            yHotspot: 0,
            hbmMask: hbm_mask,
            hbmColor: hbm_color,
        };
        let icon = CreateIconIndirect(&info).ok();
        let _ = DeleteObject(HGDIOBJ(hbm_color.0));
        let _ = DeleteObject(HGDIOBJ(hbm_mask.0));
        icon
    }
}

fn start_discovery() {
    let tx = sender();
    let gen = DISCOVERY_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    std::thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            if DISCOVERY_GEN.load(Ordering::SeqCst) != gen {
                return;
            }
            let found = crate::mdns::browse_with(Duration::from_secs(2), None);
            if let Some(f) = found.into_iter().next() {
                if DISCOVERY_GEN.load(Ordering::SeqCst) != gen {
                    return;
                }
                let name = if f.instance.trim().is_empty() {
                    "Your phone".to_string()
                } else {
                    f.instance
                };
                let _ = tx.send(Event::Found {
                    name,
                    addr: format!("{}:{}", f.ip, f.port),
                });
                return;
            }
            if Instant::now() >= deadline {
                let _ = tx.send(Event::NoPhone);
                return;
            }
            std::thread::sleep(Duration::from_millis(200));
        }
    });
}

fn start_connect(name: String, addr: String) {
    let tx = sender();
    std::thread::spawn(move || {
        crypto::ensure_ring_default();
        let id = crypto::ReceiverIdentity::load_or_create();
        let store = TrustedPhoneStore::open();
        let code_tx = tx.clone();
        let result = session::connect_and_pair_with(
            &addr,
            &id,
            &store,
            &name,
            move |code| {
                let _ = code_tx.send(Event::PairingCode(code.to_string()));
            },
        );
        match result {
            Ok((session, _description)) => {
                drop(session);
                std::thread::sleep(Duration::from_millis(350));
                let _ = tx.send(Event::Paired { name, addr });
            }
            Err(e) => {
                let _ = tx.send(Event::Failed(e));
            }
        }
    });
}

fn launch_desktop(hwnd: HWND, name: String, addr: String) {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            let mut m = model().lock().unwrap();
            m.apply(Event::ChildFailed {
                detail: format!("could not find this program: {e}"),
            });
            return;
        }
    };
    let mut cmd = Command::new(exe);
    cmd.args(["view", "--addr", &addr, "--lock"]);
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW.0);
    if let Some(log) = view_log_path() {
        if let Ok(f) = File::create(&log) {
            if let Ok(f2) = f.try_clone() {
                let _ = cmd.stdout(f2);
            }
            let _ = cmd.stderr(f);
        }
    }
    unsafe {
        let _ = ShowWindow(hwnd, SW_HIDE);
    }
    let result = cmd.spawn().and_then(|mut child| child.wait());
    unsafe {
        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = SetForegroundWindow(hwnd);
    }
    let mut m = model().lock().unwrap();
    match result {
        Ok(status) if status.success() => {
            m.apply(Event::ChildExited { name, addr });
        }
        Ok(status) => {
            m.apply(Event::ChildFailed {
                detail: format!("the desktop window exited with {status}"),
            });
        }
        Err(e) => {
            m.apply(Event::ChildFailed {
                detail: format!("could not start the desktop window: {e}"),
            });
        }
    }
    drop(m);
    unsafe {
        let _ = InvalidateRect(Some(hwnd), None, false);
    }
}

fn view_log_path() -> Option<std::path::PathBuf> {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join(crypto::APP_DIR_NAME);
    let _ = std::fs::create_dir_all(&base);
    Some(base.join("view-gui.log"))
}

// ------------------------------------------------------------------ window messages

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_ERASEBKGND => LRESULT(1),
        WM_PAINT => {
            paint(hwnd);
            LRESULT(0)
        }
        WM_SETCURSOR => {
            if (lparam.0 & 0xFFFF) as u32 == HTCLIENT {
                if let Ok(c) = LoadCursorW(None, IDC_ARROW) {
                    let _ = SetCursor(Some(c));
                }
                LRESULT(1)
            } else {
                DefWindowProcW(hwnd, msg, wparam, lparam)
            }
        }
        WM_LBUTTONUP => {
            let x = (lparam.0 & 0xFFFF) as u16 as i16 as i32;
            let y = ((lparam.0 >> 16) & 0xFFFF) as u16 as i16 as i32;
            click(hwnd, x, y);
            LRESULT(0)
        }
        WM_TIMER => {
            drain_events(hwnd);
            LRESULT(0)
        }
        WM_CLOSE => {
            let _ = DestroyWindow(hwnd);
            LRESULT(0)
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn click(hwnd: HWND, px: i32, py: i32) {
    let dpi = unsafe { GetDpiForWindow(hwnd).max(96) };
    let s = dpi as f32 / 96.0;
    let lx = (px as f32 / s) as i32;
    let ly = (py as f32 / s) as i32;
    let button = {
        let m = model().lock().unwrap();
        m.button_at(lx, ly)
    };
    let Some(button) = button else { return };
    match button {
        Button::Primary => {
            let connect = {
                let mut m = model().lock().unwrap();
                if matches!(m.screen, Screen::Error { .. }) {
                    Some(None)
                } else {
                    m.begin_connect().map(Some)
                }
            };
            match connect {
                Some(Some((name, addr))) => start_connect(name, addr),
                Some(None) => {
                    let action = { model().lock().unwrap().begin_retry() };
                    if action == Action::Discover {
                        start_discovery();
                    }
                }
                None => {}
            }
        }
        Button::Secondary => {
            let action = { model().lock().unwrap().begin_retry() };
            if action == Action::Discover {
                start_discovery();
            }
        }
    }
    unsafe {
        let _ = InvalidateRect(Some(hwnd), None, false);
    }
}

fn drain_events(hwnd: HWND) {
    let mut actions = Vec::new();
    let mut changed = false;
    if let Some(rx) = RX.get() {
        if let Ok(rx) = rx.lock() {
            while let Ok(ev) = rx.try_recv() {
                let action = { model().lock().unwrap().apply(ev) };
                changed = true;
                if action != Action::None {
                    actions.push(action);
                }
            }
        }
    }
    for action in actions {
        match action {
            Action::Connect { name, addr } => start_connect(name, addr),
            Action::Launch { name, addr } => launch_desktop(hwnd, name, addr),
            Action::Discover => start_discovery(),
            Action::None => {}
        }
    }
    if changed {
        unsafe {
            let _ = InvalidateRect(Some(hwnd), None, false);
        }
    }
}

// ------------------------------------------------------------------ painting

fn paint(hwnd: HWND) {
    unsafe {
        let mut ps = PAINTSTRUCT::default();
        let hdc = BeginPaint(hwnd, &mut ps);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        let brush = CreateSolidBrush(color(BG));
        FillRect(hdc, &rc, brush);
        let _ = DeleteObject(brush.into());

        let dpi = GetDpiForWindow(hwnd).max(96);
        let s = dpi as f32 / 96.0;
        let screen = model().lock().unwrap().screen.clone();
        draw_header(hdc, s);
        draw_screen(hdc, &screen, s);
        let _ = EndPaint(hwnd, &ps);
    }
}

fn draw_header(hdc: HDC, s: f32) {
    draw_logo(hdc, 28, 24, 28, s);
    draw_text(hdc, "Phone desktop", 68, 28, 300, 26, 16.0, TEXT, true, DT_LEFT | DT_SINGLELINE, s);
    fill_rect(hdc, 28, 72, 424, 1, BORDER, s);
}

fn draw_screen(hdc: HDC, screen: &Screen, s: f32) {
    match screen {
        Screen::Looking => {
            draw_text(
                hdc,
                "Looking for your phone",
                28,
                104,
                424,
                38,
                26.0,
                TEXT,
                true,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                "Plug in your phone, or put both on the same Wi-Fi.",
                28,
                152,
                424,
                56,
                15.0,
                SECONDARY,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            draw_text(
                hdc,
                "Start the link on your phone. This window updates by itself.",
                28,
                218,
                424,
                44,
                13.0,
                DIM,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            chip(hdc, 28, 292, 120, 36, "USB cable", s);
            chip(hdc, 160, 292, 120, 36, "Same Wi-Fi", s);
        }
        Screen::Found {
            name,
            addr,
            after_exit,
        } => {
            draw_text(
                hdc,
                if *after_exit { "Desktop closed" } else { "Phone found" },
                28,
                104,
                424,
                38,
                26.0,
                TEXT,
                true,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                name,
                28,
                152,
                424,
                70,
                22.0,
                TEXT,
                true,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            draw_text(
                hdc,
                addr,
                28,
                214,
                424,
                24,
                12.0,
                DIM,
                false,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                if *after_exit {
                    "Your PC is back. Reconnect when you are ready."
                } else {
                    "Ready to open its desktop on this PC."
                },
                28,
                246,
                424,
                44,
                14.0,
                SECONDARY,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            secondary_button(hdc, "Look again", s);
            primary_button(hdc, if *after_exit { "Reconnect" } else { "Connect" }, s);
        }
        Screen::Connecting { name, addr } => {
            draw_text(
                hdc,
                "Connecting...",
                28,
                104,
                424,
                38,
                26.0,
                TEXT,
                true,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                name,
                28,
                152,
                424,
                70,
                20.0,
                TEXT,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            draw_text(
                hdc,
                addr,
                28,
                214,
                424,
                24,
                12.0,
                DIM,
                false,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                "Opening the desktop on this PC.",
                28,
                252,
                424,
                40,
                14.0,
                SECONDARY,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
        }
        Screen::Pairing { code, name, addr } => {
            draw_text(
                hdc,
                "Pair this phone",
                28,
                104,
                424,
                38,
                26.0,
                TEXT,
                true,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                "Check that your phone shows the same code.",
                28,
                152,
                424,
                48,
                15.0,
                SECONDARY,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            draw_text(
                hdc,
                &spaced_code(code),
                28,
                210,
                424,
                64,
                40.0,
                ACCENT,
                true,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                "Tap Yes on your phone.",
                28,
                286,
                424,
                32,
                15.0,
                TEXT,
                false,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
            draw_text(
                hdc,
                &format!("{name}  ·  {addr}"),
                28,
                330,
                424,
                24,
                12.0,
                DIM,
                false,
                DT_LEFT | DT_SINGLELINE,
                s,
            );
        }
        Screen::Error {
            title,
            body,
            detail,
            ..
        } => {
            draw_text(
                hdc,
                title,
                28,
                104,
                424,
                76,
                23.0,
                TEXT,
                true,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            draw_text(
                hdc,
                body,
                28,
                184,
                424,
                64,
                15.0,
                SECONDARY,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            draw_text(
                hdc,
                detail,
                28,
                254,
                424,
                96,
                12.0,
                DIM,
                false,
                DT_LEFT | DT_WORDBREAK | DT_NOPREFIX,
                s,
            );
            primary_button(hdc, "Try again", s);
        }
    }
}

fn chip(hdc: HDC, x: i32, y: i32, w: i32, h: i32, label: &str, s: f32) {
    round_fill(hdc, x, y, w, h, RAISED, 10.0, s);
    draw_text(
        hdc,
        label,
        x,
        y,
        w,
        h,
        13.0,
        SECONDARY,
        false,
        DT_CENTER | DT_VCENTER | DT_SINGLELINE,
        s,
    );
}

fn primary_button(hdc: HDC, label: &str, s: f32) {
    round_fill(hdc, PX, PY, PW, PH, ACCENT, 12.0, s);
    draw_text(
        hdc,
        label,
        PX,
        PY,
        PW,
        PH,
        16.0,
        ON_ACCENT,
        true,
        DT_CENTER | DT_VCENTER | DT_SINGLELINE,
        s,
    );
}

fn secondary_button(hdc: HDC, label: &str, s: f32) {
    draw_text(
        hdc,
        label,
        SX,
        SY,
        SW,
        SH,
        14.0,
        ACCENT,
        false,
        DT_CENTER | DT_VCENTER | DT_SINGLELINE,
        s,
    );
}

fn draw_logo(hdc: HDC, x: i32, y: i32, size: i32, s: f32) {
    round_fill(hdc, x + 4, y + 6, size - 8, 14, ACCENT, 3.0, s);
    fill_rect(hdc, x + 6, y + 8, size - 12, 10, BG, s);
    fill_rect(hdc, x + 13, y + 20, 2, 4, ACCENT, s);
    fill_rect(hdc, x + 9, y + 24, 10, 2, ACCENT, s);
}

fn fill_rect(hdc: HDC, x: i32, y: i32, w: i32, h: i32, hex: u32, s: f32) {
    let brush = unsafe { CreateSolidBrush(color(hex)) };
    let r = logical_rect(x, y, w, h, s);
    unsafe {
        FillRect(hdc, &r, brush);
        let _ = DeleteObject(brush.into());
    }
}

fn round_fill(hdc: HDC, x: i32, y: i32, w: i32, h: i32, hex: u32, radius: f32, s: f32) {
    unsafe {
        let brush = CreateSolidBrush(color(hex));
        let old_brush = SelectObject(hdc, brush.into());
        let old_pen = SelectObject(hdc, GetStockObject(NULL_PEN));
        let r = logical_rect(x, y, w, h, s);
        let _ = RoundRect(
            hdc,
            r.left,
            r.top,
            r.right,
            r.bottom,
            (radius * s) as i32,
            (radius * s) as i32,
        );
        SelectObject(hdc, old_pen);
        SelectObject(hdc, old_brush);
        let _ = DeleteObject(brush.into());
    }
}

#[allow(clippy::too_many_arguments)]
fn draw_text(
    hdc: HDC,
    text: &str,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    size: f32,
    hex: u32,
    bold: bool,
    flags: DRAW_TEXT_FORMAT,
    s: f32,
) {
    unsafe {
        let name = HSTRING::from("Segoe UI");
        let font: HFONT = CreateFontW(
            -((size * s) as i32).max(1),
            0,
            0,
            0,
            if bold { FW_BOLD.0 as i32 } else { FW_NORMAL.0 as i32 },
            0,
            0,
            0,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32,
            &name,
        );
        let old = SelectObject(hdc, font.into());
        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, color(hex));
        let mut r = logical_rect(x, y, w, h, s);
        let mut v: Vec<u16> = text.encode_utf16().collect();
        DrawTextW(hdc, &mut v, &mut r, flags);
        SelectObject(hdc, old);
        let _ = DeleteObject(font.into());
    }
}

// ------------------------------------------------------------------ tests

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starts_looking() {
        let m = GuiModel::new(false);
        assert_eq!(m.screen, Screen::Looking);
        assert!(m.primary().is_none());
    }

    #[test]
    fn an_untrusted_phone_waits_for_connect() {
        let mut m = GuiModel::new(false);
        let a = m.apply(Event::Found {
            name: "Pixel".into(),
            addr: "192.168.1.2:47600".into(),
        });
        assert_eq!(a, Action::None);
        assert_eq!(m.primary(), Some("Connect"));
        assert_eq!(m.secondary(), Some("Look again"));
    }

    #[test]
    fn a_trusted_phone_connects_by_itself() {
        let mut m = GuiModel::new(true);
        let a = m.apply(Event::Found {
            name: "Pixel".into(),
            addr: "192.168.1.2:47600".into(),
        });
        assert_eq!(
            a,
            Action::Connect {
                name: "Pixel".into(),
                addr: "192.168.1.2:47600".into()
            }
        );
        assert!(matches!(m.screen, Screen::Connecting { .. }));
    }

    #[test]
    fn pairing_shows_the_code_and_then_launches() {
        let mut m = GuiModel::new(false);
        m.apply(Event::Found {
            name: "Pixel".into(),
            addr: "10.0.0.2:47600".into(),
        });
        m.begin_connect();
        m.apply(Event::PairingCode("983053".into()));
        match &m.screen {
            Screen::Pairing { code, .. } => assert_eq!(code, "983053"),
            other => panic!("not pairing: {other:?}"),
        }
        let a = m.apply(Event::Paired {
            name: "Pixel".into(),
            addr: "10.0.0.2:47600".into(),
        });
        assert!(matches!(a, Action::Launch { .. }));
    }

    #[test]
    fn no_phone_after_thirty_seconds_is_plain() {
        let mut m = GuiModel::new(false);
        m.apply(Event::NoPhone);
        match m.screen {
            Screen::Error {
                ref title,
                ref body,
                ..
            } => {
                assert_eq!(title, "No phone found after 30 s");
                assert!(body.contains("Check the cable, or that both are on the same Wi-Fi"));
            }
            other => panic!("not an error: {other:?}"),
        }
        assert_eq!(m.primary(), Some("Try again"));
    }

    #[test]
    fn a_firewall_failure_names_the_firewall() {
        let (title, body, _) = connect_error("tls handshake: connection timed out");
        assert!(title.contains("Firewall"));
        assert!(body.contains("Allow this app through Windows Firewall"));
    }

    #[test]
    fn a_refused_connection_tells_the_user_what_to_do() {
        let (title, body, _) = connect_error("os error 10061: connection refused");
        assert!(title.contains("refused"));
        assert!(body.contains("Unlock the phone"));
    }

    #[test]
    fn code_is_grouped_for_the_pc_screen() {
        assert_eq!(spaced_code("983053"), "983 053");
        assert_eq!(spaced_code("983 053"), "983 053");
        assert_eq!(spaced_code("12"), "12");
    }

    #[test]
    fn exit_returns_to_reconnect() {
        let mut m = GuiModel::new(false);
        m.apply(Event::ChildExited {
            name: "Pixel".into(),
            addr: "10.0.0.2:47600".into(),
        });
        match &m.screen {
            Screen::Found { after_exit, .. } => assert!(after_exit),
            other => panic!("not found: {other:?}"),
        }
        assert_eq!(m.primary(), Some("Reconnect"));
    }

    #[test]
    fn button_hit_boxes_only_exist_when_a_button_is_drawn() {
        let mut m = GuiModel::new(false);
        assert_eq!(m.button_at(PX + 1, PY + 1), None);
        m.apply(Event::Found {
            name: "Pixel".into(),
            addr: "10.0.0.2:47600".into(),
        });
        assert_eq!(m.button_at(PX + 1, PY + 1), Some(Button::Primary));
        assert_eq!(m.button_at(SX + 1, SY + 1), Some(Button::Secondary));
    }
}
