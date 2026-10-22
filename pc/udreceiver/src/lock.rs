//! Phase 9: the PC lock.
//!
//! State machine (ALGORITHMS.md section 13): Normal -> Locking -> Locked -> Exiting ->
//! Normal. The lock is a **separate Windows desktop** (`CreateDesktopW` on `WinSta0`):
//! the default desktop, its taskbar and the owner's windows are never touched. A
//! `WH_KEYBOARD_LL` hook installed on the lock desktop swallows Win, Alt+Tab,
//! Alt+Shift+Tab, Alt+Esc, Ctrl+Esc, Ctrl+Shift+Esc and Alt+F4. One topmost Exit
//! overlay calls `request_exit()`; every path (button, WM_CLOSE, phone gone, --seconds,
//! internal error) goes through `Lock::exit()`, which unhooks, switches back to Default
//! and restores the crash journal.
//!
//! Crash safety: the journal (`%LOCALAPPDATA%\unified-desktop-receiver\lock-state.txt`)
//! is written BEFORE every policy change. A killed process does NOT return the input to
//! Default (verified 2026-10-21: the screen stays on the private desktop), so `prepare`
//! starts `udreceiver lock-guard --parent <pid>` on the default desktop; the guard sees
//! the parent die and switches back. The journal also lets the next `view`/`lock-recover`
//! finish the restore if the guard itself was killed.
//!
//! `--policies` (HKLM exam-software values: Task Manager, lock workstation, logoff,
//! fast user switching, change password) is applied only when the process is elevated;
//! it is off by default and was NOT applied in the Phase-9 dev tests (not-admin).

use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use windows::core::{HSTRING, PCWSTR};
use windows::Win32::Foundation::{
    CloseHandle, COLORREF, GetLastError, SetLastError, HANDLE, HINSTANCE, HWND, LPARAM, LRESULT,
    RECT, WAIT_OBJECT_0, WPARAM, ERROR_ALREADY_EXISTS, ERROR_FILE_NOT_FOUND,
    ERROR_INVALID_PARAMETER, ERROR_SUCCESS,
};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateFontW, CreateSolidBrush, DeleteObject, DrawTextW, EndPaint,
    EnumDisplayMonitors, FillRect, InvalidateRect, SelectObject, SetBkMode, SetTextColor,
    CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS, DEFAULT_CHARSET, DEFAULT_PITCH, DT_CENTER,
    DT_SINGLELINE, DT_VCENTER, FF_DONTCARE, FW_BOLD, HDC, HMONITOR, OUT_DEFAULT_PRECIS,
    PAINTSTRUCT, TRANSPARENT,
};
use windows::Win32::Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Registry::{
    RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegOpenKeyExW, RegQueryValueExW,
    RegSetValueExW, HKEY, HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, KEY_READ, KEY_SET_VALUE,
    REG_DWORD, REG_OPTION_NON_VOLATILE, REG_SZ, REG_VALUE_TYPE,
};
use windows::Win32::System::StationsAndDesktops::{
    CloseDesktop, CreateDesktopW, GetUserObjectInformationW, OpenDesktopW, OpenInputDesktop,
    SetThreadDesktop, SwitchDesktop, DESKTOP_ACCESS_FLAGS, DESKTOP_CONTROL_FLAGS, HDESK,
    UOI_NAME,
};
use windows::Win32::System::Threading::{
    CreateEventW, CreateMutexW, GetCurrentProcess, OpenEventW, OpenMutexW, OpenProcessToken,
    ReleaseMutex, SetEvent, WaitForSingleObject, EVENT_MODIFY_STATE, SYNCHRONIZATION_SYNCHRONIZE,
};
use windows::Win32::UI::HiDpi::GetDpiForWindow;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, SendInput, SetFocus, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE,
    KEYBDINPUT, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSEINPUT, MOUSEEVENTF_ABSOLUTE,
    MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MOVE, VIRTUAL_KEY, VK_CONTROL,
    VK_ESCAPE, VK_F4, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT, VK_TAB,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW,
    GetClassNameW, GetClientRect, GetForegroundWindow, GetSystemMetrics, GetWindowTextW,
    GetWindowThreadProcessId,
    KillTimer, LoadCursorW, PeekMessageW, PostMessageW, RegisterClassW, SetCursor,
    SetForegroundWindow, SetTimer, SetWindowPos, SetWindowsHookExW, ShowWindow, TranslateMessage,
    UnhookWindowsHookEx, HC_ACTION, HTCLIENT, HWND_TOPMOST, IDC_ARROW, KBDLLHOOKSTRUCT, MSG,
    PM_REMOVE, SM_CXSCREEN, SM_CYSCREEN, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE,
    SWP_SHOWWINDOW, SW_SHOW, WH_KEYBOARD_LL, WINDOW_EX_STYLE, WM_APP, WM_DPICHANGED,
    WM_ERASEBKGND, WM_LBUTTONUP, WM_MOUSEACTIVATE, WM_PAINT, WM_SETCURSOR, WM_TIMER, WNDCLASSW,
    WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP, WS_VISIBLE,
};

/// Lock is the default for `view` (flipped 2026-10-21 after G4+G5 passed on the phone).
pub const DEFAULT_LOCK: bool = true;
pub const EVENT_NAME: &str = "Local\\udreceiver-lock-exit";
pub const MUTEX_NAME: &str = "Local\\udreceiver-lock-single";
pub const WM_APP_EXIT: u32 = WM_APP + 1;

const TAG: &str = "lock";
const DESKTOP_DEFAULT: &str = "Default";
const DESKTOP_PREFIX: &str = "udreceiver-lock-";
const BLOCKED_COUNT: usize = 8;

// ---------------------------------------------------------------- pure state machine

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LockState {
    Normal,
    Locking,
    Locked,
    Exiting,
}

impl LockState {
    pub fn as_u8(self) -> u8 {
        match self {
            LockState::Normal => 0,
            LockState::Locking => 1,
            LockState::Locked => 2,
            LockState::Exiting => 3,
        }
    }

    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(LockState::Normal),
            1 => Some(LockState::Locking),
            2 => Some(LockState::Locked),
            3 => Some(LockState::Exiting),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            LockState::Normal => "Normal",
            LockState::Locking => "Locking",
            LockState::Locked => "Locked",
            LockState::Exiting => "Exiting",
        }
    }

    /// The only legal edges. Locking may abort to Normal (a failed prepare/engage) or
    /// go to Exiting (an exit requested before the switch completed); Exiting only
    /// returns to Normal.
    pub fn transition(self, next: LockState) -> Result<LockState, String> {
        let ok = matches!(
            (self, next),
            (LockState::Normal, LockState::Locking)
                | (LockState::Locking, LockState::Locked)
                | (LockState::Locking, LockState::Exiting)
                | (LockState::Locking, LockState::Normal)
                | (LockState::Locked, LockState::Exiting)
                | (LockState::Exiting, LockState::Normal)
        );
        if ok {
            Ok(next)
        } else {
            Err(format!(
                "invalid lock transition {} -> {}",
                self.name(),
                next.name()
            ))
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockedKey {
    LWin = 0,
    RWin = 1,
    AltTab = 2,
    AltShiftTab = 3,
    AltEsc = 4,
    CtrlEsc = 5,
    CtrlShiftEsc = 6,
    AltF4 = 7,
}

pub const BLOCKED_NAMES: [&str; BLOCKED_COUNT] = [
    "lwin",
    "rwin",
    "alttab",
    "altshifttab",
    "altesc",
    "ctrlesc",
    "ctrlshiftesc",
    "altf4",
];

/// The key filter. Down and up are both swallowed: blocking only the down edge leaves
/// the system believing a modifier is still held. Mouse input is never filtered.
pub fn blocked_key(vk: u32, alt: bool, ctrl: bool, shift: bool) -> Option<BlockedKey> {
    const VK_LWIN_V: u32 = 0x5B;
    const VK_RWIN_V: u32 = 0x5C;
    const VK_TAB_V: u32 = 0x09;
    const VK_ESC_V: u32 = 0x1B;
    const VK_F4_V: u32 = 0x73;
    match vk {
        VK_LWIN_V => Some(BlockedKey::LWin),
        VK_RWIN_V => Some(BlockedKey::RWin),
        VK_TAB_V if alt && shift => Some(BlockedKey::AltShiftTab),
        VK_TAB_V if alt => Some(BlockedKey::AltTab),
        VK_ESC_V if alt => Some(BlockedKey::AltEsc),
        VK_ESC_V if ctrl && shift => Some(BlockedKey::CtrlShiftEsc),
        VK_ESC_V if ctrl => Some(BlockedKey::CtrlEsc),
        VK_F4_V if alt => Some(BlockedKey::AltF4),
        _ => None,
    }
}

// ---------------------------------------------------------------- journal

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PolicyRoot {
    LocalMachine,
    CurrentUser,
}

impl PolicyRoot {
    fn tag(self) -> &'static str {
        match self {
            PolicyRoot::LocalMachine => "HKLM",
            PolicyRoot::CurrentUser => "HKCU",
        }
    }

    fn hkey(self) -> HKEY {
        match self {
            PolicyRoot::LocalMachine => HKEY_LOCAL_MACHINE,
            PolicyRoot::CurrentUser => HKEY_CURRENT_USER,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PolicyValue {
    Missing,
    Dword(u32),
    Sz(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PolicyEntry {
    pub root: PolicyRoot,
    pub path: String,
    pub name: String,
    pub original: PolicyValue,
}

#[derive(Debug, Clone)]
pub struct Journal {
    pub path: PathBuf,
    pub desktop: String,
    pub pid: u32,
    pub policies: Vec<PolicyEntry>,
    clean: bool,
}

impl Journal {
    /// Start a fresh journal only when no dirty or unreadable one is already on disk.
    /// A dirty journal is the only undo record for policy changes; overwriting it would
    /// make those changes impossible to restore. A clean leftover (a failed delete) may
    /// be replaced.
    pub fn start_at(path: PathBuf, desktop: &str) -> Result<Self, String> {
        match Journal::load_at(path.clone()) {
            Ok(Some(existing)) if !existing.is_clean() => {
                return Err(format!(
                    "refusing to overwrite dirty lock journal {} (desktop '{}', pid {}); \
                     recover it first",
                    path.display(),
                    existing.desktop,
                    existing.pid
                ));
            }
            Ok(_) => {}
            Err(e) => {
                return Err(format!(
                    "refusing to overwrite unreadable lock journal {}: {e}",
                    path.display()
                ));
            }
        }
        let j = Journal {
            path,
            desktop: desktop.to_string(),
            pid: std::process::id(),
            policies: Vec::new(),
            clean: false,
        };
        j.save()?;
        Ok(j)
    }

    /// Strict parser: a journal whose `clean=`/`desktop=` line is missing, duplicated or
    /// malformed, or that contains an unknown line, is not guessed at. Recovery must fail
    /// closed rather than lose the undo records.
    pub fn load_at(path: PathBuf) -> Result<Option<Self>, String> {
        let text = match fs::read_to_string(&path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(format!("read {}: {e}", path.display())),
        };
        let mut clean: Option<bool> = None;
        let mut desktop: Option<String> = None;
        let mut pid: Option<u32> = None;
        let mut policies = Vec::new();
        for (i, line) in text.lines().enumerate() {
            let n = i + 1;
            let Some((key, value)) = line.split_once('=') else {
                return Err(format!(
                    "{}:{n}: malformed journal line (no '=')",
                    path.display()
                ));
            };
            match key {
                "clean" => {
                    if clean.is_some() {
                        return Err(format!("{}:{n}: duplicate clean=", path.display()));
                    }
                    clean = Some(match value.trim() {
                        "1" => true,
                        "0" => false,
                        other => {
                            return Err(format!(
                                "{}:{n}: bad clean={other:?} (expected 0 or 1)",
                                path.display()
                            ));
                        }
                    });
                }
                "desktop" => {
                    if desktop.is_some() {
                        return Err(format!("{}:{n}: duplicate desktop=", path.display()));
                    }
                    desktop = Some(value.trim().to_string());
                }
                "pid" => {
                    if pid.is_some() {
                        return Err(format!("{}:{n}: duplicate pid=", path.display()));
                    }
                    pid = Some(value.trim().parse().map_err(|e| {
                        format!("{}:{n}: bad pid={value:?}: {e}", path.display())
                    })?);
                }
                "policy" => policies.push(parse_policy(value)?),
                other => {
                    return Err(format!(
                        "{}:{n}: unknown journal key {other:?}",
                        path.display()
                    ));
                }
            }
        }
        let clean = clean.ok_or_else(|| {
            format!(
                "{}: journal has no clean= line (truncated?); not guessing",
                path.display()
            )
        })?;
        let desktop = desktop.ok_or_else(|| {
            format!(
                "{}: journal has no desktop= line (truncated?); not guessing",
                path.display()
            )
        })?;
        Ok(Some(Journal {
            path,
            desktop,
            pid: pid.unwrap_or(0),
            policies,
            clean,
        }))
    }

    /// True only when this journal was written by `pid` for the desktop of that same PID.
    /// The crash guard uses this to prove the journal is its parent's before restoring.
    pub fn belongs_to(&self, pid: u32) -> bool {
        self.pid == pid && self.desktop == format!("{DESKTOP_PREFIX}{pid}")
    }

    pub fn is_clean(&self) -> bool {
        self.clean
    }

    /// Record a value BEFORE it is changed. The whole journal is rewritten atomically so a
    /// crash can never leave a change without its undo record.
    pub fn record_policy(&mut self, entry: PolicyEntry) -> Result<(), String> {
        self.policies.push(entry);
        self.save()
    }

    pub fn save(&self) -> Result<(), String> {
        let mut text = String::new();
        text.push_str(if self.clean { "clean=1\n" } else { "clean=0\n" });
        text.push_str(&format!("desktop={}\n", self.desktop));
        text.push_str(&format!("pid={}\n", self.pid));
        for p in &self.policies {
            text.push_str("policy=");
            text.push_str(p.root.tag());
            text.push('|');
            text.push_str(&p.path);
            text.push('|');
            text.push_str(&p.name);
            text.push('|');
            match &p.original {
                PolicyValue::Missing => text.push_str("MISSING|"),
                PolicyValue::Dword(v) => text.push_str(&format!("DWORD|{v}")),
                PolicyValue::Sz(s) => {
                    text.push_str("SZ|");
                    text.push_str(&hex_encode(s));
                }
            }
            text.push('\n');
        }
        let tmp = self.path.with_extension("tmp");
        if let Some(dir) = self.path.parent() {
            let _ = fs::create_dir_all(dir);
        }
        fs::write(&tmp, text).map_err(|e| format!("write {}: {e}", tmp.display()))?;
        fs::rename(&tmp, &self.path).map_err(|e| format!("rename journal: {e}"))?;
        Ok(())
    }

    /// Reverse application order: the last value we changed is the first one restored.
    pub fn restore_order(&self) -> Vec<usize> {
        (0..self.policies.len()).rev().collect()
    }

    /// Restore every recorded value in reverse order, then delete the journal. A second
    /// call on the same in-memory journal is a no-op (idempotent restore).
    pub fn restore_and_clean<F>(&mut self, mut apply: F) -> Result<usize, String>
    where
        F: FnMut(&PolicyEntry) -> Result<(), String>,
    {
        if self.clean {
            return Ok(0);
        }
        let order = self.restore_order();
        let mut n = 0;
        for i in order {
            apply(&self.policies[i])?;
            n += 1;
        }
        match fs::remove_file(&self.path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(format!("delete journal: {e}")),
        }
        self.clean = true;
        Ok(n)
    }
}

fn hex_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 4);
    for b in s.encode_utf16() {
        out.push_str(&format!("{b:04x}"));
    }
    out
}

fn hex_decode(s: &str) -> Result<String, String> {
    if !s.len().is_multiple_of(4) {
        return Err("SZ value has an odd hex length".into());
    }
    let mut units = Vec::with_capacity(s.len() / 4);
    for i in (0..s.len()).step_by(4) {
        let u = u16::from_str_radix(&s[i..i + 4], 16).map_err(|e| format!("SZ hex: {e}"))?;
        units.push(u);
    }
    String::from_utf16(&units).map_err(|e| format!("SZ utf16: {e}"))
}

fn parse_policy(v: &str) -> Result<PolicyEntry, String> {
    let parts: Vec<&str> = v.splitn(5, '|').collect();
    if parts.len() != 5 {
        return Err(format!("bad policy journal line: {v}"));
    }
    let root = match parts[0] {
        "HKLM" => PolicyRoot::LocalMachine,
        "HKCU" => PolicyRoot::CurrentUser,
        other => return Err(format!("bad policy root: {other}")),
    };
    let original = match parts[3] {
        "MISSING" => PolicyValue::Missing,
        "DWORD" => PolicyValue::Dword(parts[4].parse().map_err(|e| format!("DWORD: {e}"))?),
        "SZ" => PolicyValue::Sz(hex_decode(parts[4])?),
        other => return Err(format!("bad policy type: {other}")),
    };
    Ok(PolicyEntry {
        root,
        path: parts[1].to_string(),
        name: parts[2].to_string(),
        original,
    })
}

// ---------------------------------------------------------------- registry

fn win_err(what: &str, e: windows::Win32::Foundation::WIN32_ERROR) -> String {
    format!("{what}: win32 error {}", e.0)
}

pub fn read_policy(root: PolicyRoot, path: &str, name: &str) -> Result<PolicyValue, String> {
    unsafe {
        let mut key = HKEY::default();
        let hr = RegOpenKeyExW(root.hkey(), &HSTRING::from(path), None, KEY_READ, &mut key);
        if hr == ERROR_FILE_NOT_FOUND {
            return Ok(PolicyValue::Missing);
        }
        if hr != ERROR_SUCCESS {
            return Err(win_err("RegOpenKeyExW", hr));
        }
        let mut ty = REG_VALUE_TYPE::default();
        let mut len = 0u32;
        let q = RegQueryValueExW(key, &HSTRING::from(name), None, Some(&mut ty), None, Some(&mut len));
        if q == ERROR_FILE_NOT_FOUND {
            let _ = RegCloseKey(key);
            return Ok(PolicyValue::Missing);
        }
        if q != ERROR_SUCCESS {
            let _ = RegCloseKey(key);
            return Err(win_err("RegQueryValueExW(size)", q));
        }
        let mut buf = vec![0u8; len as usize];
        let q = RegQueryValueExW(
            key,
            &HSTRING::from(name),
            None,
            Some(&mut ty),
            if buf.is_empty() { None } else { Some(buf.as_mut_ptr()) },
            Some(&mut len),
        );
        let _ = RegCloseKey(key);
        if q != ERROR_SUCCESS {
            return Err(win_err("RegQueryValueExW(data)", q));
        }
        if ty == REG_DWORD && len >= 4 {
            let v = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
            return Ok(PolicyValue::Dword(v));
        }
        if ty == REG_SZ {
            let units: Vec<u16> = buf
                .chunks_exact(2)
                .map(|c| u16::from_le_bytes([c[0], c[1]]))
                .take_while(|u| *u != 0)
                .collect();
            return Ok(PolicyValue::Sz(String::from_utf16_lossy(&units)));
        }
        // A value type the exam policies never use: report it as missing so a restore
        // deletes what we set rather than corrupting an unknown type.
        Ok(PolicyValue::Missing)
    }
}

pub fn write_policy(root: PolicyRoot, path: &str, name: &str, value: &PolicyValue) -> Result<(), String> {
    unsafe {
        let mut key = HKEY::default();
        let hr = RegCreateKeyExW(
            root.hkey(),
            &HSTRING::from(path),
            None,
            PCWSTR::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            None,
            &mut key,
            None,
        );
        if hr != ERROR_SUCCESS {
            return Err(win_err("RegCreateKeyExW", hr));
        }
        let r = match value {
            PolicyValue::Missing => {
                let d = RegDeleteValueW(key, &HSTRING::from(name));
                if d == ERROR_FILE_NOT_FOUND || d == ERROR_SUCCESS {
                    Ok(())
                } else {
                    Err(win_err("RegDeleteValueW", d))
                }
            }
            PolicyValue::Dword(v) => {
                let bytes = v.to_le_bytes();
                let s = RegSetValueExW(key, &HSTRING::from(name), None, REG_DWORD, Some(&bytes));
                if s == ERROR_SUCCESS {
                    Ok(())
                } else {
                    Err(win_err("RegSetValueExW", s))
                }
            }
            PolicyValue::Sz(text) => {
                let mut units: Vec<u16> = text.encode_utf16().collect();
                units.push(0);
                let bytes: Vec<u8> = units.iter().flat_map(|u| u.to_le_bytes()).collect();
                let s = RegSetValueExW(key, &HSTRING::from(name), None, REG_SZ, Some(&bytes));
                if s == ERROR_SUCCESS {
                    Ok(())
                } else {
                    Err(win_err("RegSetValueExW", s))
                }
            }
        };
        let _ = RegCloseKey(key);
        r
    }
}

/// The exam-software policy values (Ctrl+Alt+Del itself cannot be blocked). Applied only
/// with `--policies` AND an elevated process; never in the Phase-9 dev tests.
const POLICY_VALUES: [(&str, &str); 5] = [
    (
        r"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System",
        "DisableTaskMgr",
    ),
    (
        r"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System",
        "DisableLockWorkstation",
    ),
    (
        r"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System",
        "HideFastUserSwitching",
    ),
    (
        r"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System",
        "DisableChangePassword",
    ),
    (
        r"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\Explorer",
        "NoLogoff",
    ),
];

fn is_elevated() -> bool {
    unsafe {
        let mut token = HANDLE::default();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).is_err() {
            return false;
        }
        let mut elevation = TOKEN_ELEVATION::default();
        let mut ret = 0u32;
        let ok = GetTokenInformation(
            token,
            TokenElevation,
            Some(&mut elevation as *mut _ as *mut std::ffi::c_void),
            std::mem::size_of::<TOKEN_ELEVATION>() as u32,
            &mut ret,
        )
        .is_ok();
        let _ = CloseHandle(token);
        ok && elevation.TokenIsElevated != 0
    }
}

// ---------------------------------------------------------------- paths and status

fn lock_dir() -> PathBuf {
    let base = if let Some(d) = std::env::var_os("UD_LOCK_DIR") {
        PathBuf::from(d)
    } else if let Some(d) = std::env::var_os("LOCALAPPDATA") {
        PathBuf::from(d).join(crate::crypto::APP_DIR_NAME)
    } else {
        std::env::temp_dir().join(crate::crypto::APP_DIR_NAME)
    };
    let _ = fs::create_dir_all(&base);
    base
}

pub fn journal_path() -> PathBuf {
    lock_dir().join("lock-state.txt")
}

fn status_path() -> PathBuf {
    lock_dir().join("lock-status.txt")
}

#[derive(Debug, Clone, Default)]
pub struct StatusFile {
    pub state: String,
    pub desktop: String,
    pub pid: u32,
    pub journal: String,
    pub exit_rect: String,
    pub counts: [u64; BLOCKED_COUNT],
}

pub fn read_status_file() -> StatusFile {
    let mut s = StatusFile::default();
    let Ok(text) = fs::read_to_string(status_path()) else {
        return s;
    };
    for line in text.lines() {
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        match k {
            "state" => s.state = v.trim().to_string(),
            "desktop" => s.desktop = v.trim().to_string(),
            "pid" => s.pid = v.trim().parse().unwrap_or(0),
            "journal" => s.journal = v.trim().to_string(),
            "exit_rect" => s.exit_rect = v.trim().to_string(),
            "hook_lwin" => s.counts[0] = v.trim().parse().unwrap_or(0),
            "hook_rwin" => s.counts[1] = v.trim().parse().unwrap_or(0),
            "hook_alttab" => s.counts[2] = v.trim().parse().unwrap_or(0),
            "hook_altshifttab" => s.counts[3] = v.trim().parse().unwrap_or(0),
            "hook_altesc" => s.counts[4] = v.trim().parse().unwrap_or(0),
            "hook_ctrlesc" => s.counts[5] = v.trim().parse().unwrap_or(0),
            "hook_ctrlshiftesc" => s.counts[6] = v.trim().parse().unwrap_or(0),
            "hook_altf4" => s.counts[7] = v.trim().parse().unwrap_or(0),
            _ => {}
        }
    }
    s
}

// ---------------------------------------------------------------- globals

static EXIT_REQUESTED: AtomicBool = AtomicBool::new(false);
static VIDEO_HWND: AtomicU64 = AtomicU64::new(0);
static EXIT_HWND: AtomicU64 = AtomicU64::new(0);
static LOCK_ACTIVE: AtomicBool = AtomicBool::new(false);
static RUNNING: AtomicBool = AtomicBool::new(false);
static STATE: AtomicU8 = AtomicU8::new(0);
static HOOK_COUNTS: [AtomicU64; BLOCKED_COUNT] = [
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
    AtomicU64::new(0),
];
static DESKTOP_NAME: Mutex<String> = Mutex::new(String::new());
static EXIT_RECT: Mutex<(i32, i32, i32, i32)> = Mutex::new((0, 0, 0, 0));
static ENGAGED_AT: Mutex<Option<Instant>> = Mutex::new(None);

pub fn is_locked() -> bool {
    LOCK_ACTIVE.load(Ordering::SeqCst)
}

/// True from `Lock::prepare` until `Lock::exit`/`abort` completes: this process owns a
/// private desktop, so the thread is no longer on Default and the normal focus rules do not
/// apply.
pub fn is_active() -> bool {
    STATE.load(Ordering::SeqCst) != LockState::Normal.as_u8()
}

pub fn engaged_at() -> Option<Instant> {
    *ENGAGED_AT.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn take_exit_request() -> bool {
    EXIT_REQUESTED.swap(false, Ordering::SeqCst)
}

/// Every exit source funnels here: the Exit overlay, Esc, WM_CLOSE, the watchdog and
/// `lock-exit`. It only raises a flag and wakes the view's message loop, which then calls
/// `Lock::exit()` on the thread that owns the windows and the hook.
pub fn request_exit() {
    EXIT_REQUESTED.store(true, Ordering::SeqCst);
    let h = VIDEO_HWND.load(Ordering::SeqCst);
    if h != 0 {
        unsafe {
            let _ = PostMessageW(
                Some(HWND(h as *mut std::ffi::c_void)),
                WM_APP_EXIT,
                WPARAM(0),
                LPARAM(0),
            );
        }
    }
}

fn hook_counts() -> [u64; BLOCKED_COUNT] {
    let mut out = [0u64; BLOCKED_COUNT];
    for (i, c) in HOOK_COUNTS.iter().enumerate() {
        out[i] = c.load(Ordering::Relaxed);
    }
    out
}

fn exit_rect() -> (i32, i32, i32, i32) {
    *EXIT_RECT.lock().unwrap_or_else(|e| e.into_inner())
}

fn write_status_file(state: LockState) {
    let name = DESKTOP_NAME
        .lock()
        .map(|s| s.clone())
        .unwrap_or_default();
    let counts = hook_counts();
    let (l, t, r, b) = exit_rect();
    let journal = journal_path();
    let dirty = match Journal::load_at(journal.clone()) {
        Ok(Some(j)) => !j.is_clean(),
        _ => false,
    };
    let mut text = format!(
        "state={}\ndesktop={}\npid={}\njournal={}|{}\nexit_rect={l},{t},{r},{b}\n",
        state.name(),
        name,
        std::process::id(),
        journal.display(),
        if dirty { "dirty" } else { "clean" },
    );
    for (i, key) in BLOCKED_NAMES.iter().enumerate() {
        text.push_str(&format!("hook_{key}={}\n", counts[i]));
    }
    let _ = fs::write(status_path(), text);
}

fn status_loop() {
    while RUNNING.load(Ordering::SeqCst) {
        let s = LockState::from_u8(STATE.load(Ordering::SeqCst)).unwrap_or(LockState::Normal);
        write_status_file(s);
        std::thread::sleep(Duration::from_millis(200));
    }
    write_status_file(LockState::Normal);
}

// ---------------------------------------------------------------- desktop helpers

fn desktop_name(desk: HDESK) -> Result<String, String> {
    unsafe {
        let mut buf = [0u16; 256];
        let mut needed = 0u32;
        GetUserObjectInformationW(
            HANDLE(desk.0),
            UOI_NAME,
            Some(buf.as_mut_ptr() as *mut std::ffi::c_void),
            (buf.len() * 2) as u32,
            Some(&mut needed),
        )
        .map_err(|e| format!("GetUserObjectInformationW: {e}"))?;
        let n = (needed as usize / 2).min(buf.len());
        Ok(String::from_utf16_lossy(&buf[..n]).trim_end_matches('\0').to_string())
    }
}

fn input_desktop_name() -> Result<String, String> {
    unsafe {
        let desk = OpenInputDesktop(
            DESKTOP_CONTROL_FLAGS(0),
            false,
            DESKTOP_ACCESS_FLAGS(0x000F_01FF),
        )
        .map_err(|e| format!("OpenInputDesktop: {e}"))?;
        let name = desktop_name(desk);
        let _ = CloseDesktop(desk);
        name
    }
}

fn open_default_desktop() -> Result<HDESK, String> {
    unsafe {
        OpenDesktopW(
            &HSTRING::from(DESKTOP_DEFAULT),
            DESKTOP_CONTROL_FLAGS(0),
            false,
            0x1000_0000, // GENERIC_ALL
        )
        .map_err(|e| format!("OpenDesktopW(Default): {e}"))
    }
}

/// Used by recovery, `lock-recover`, the crash guard and `Lock::exit`: return to Default
/// and verify it, so a caller never deletes the journal while the lock desktop is still the
/// input desktop.
fn ensure_default_input() -> Result<(), String> {
    let current = input_desktop_name().unwrap_or_default();
    if current == DESKTOP_DEFAULT {
        return Ok(());
    }
    let d = open_default_desktop()?;
    let r = unsafe { SwitchDesktop(d) };
    let _ = unsafe { CloseDesktop(d) };
    r.map_err(|e| format!("SwitchDesktop(Default): {e}"))?;
    match input_desktop_name() {
        Ok(n) if n == DESKTOP_DEFAULT => Ok(()),
        Ok(n) => Err(format!(
            "input desktop is still '{n}' after SwitchDesktop(Default)"
        )),
        Err(e) => Err(format!("verify input desktop after SwitchDesktop(Default): {e}")),
    }
}

// ---------------------------------------------------------------- watchdog

struct WatchdogCtx {
    event_raw: isize,
    max_seconds: AtomicU64,
    lock_name: Mutex<String>,
    armed: AtomicBool,
    running: AtomicBool,
}

fn watchdog_loop(ctx: Arc<WatchdogCtx>) {
    let mut start: Option<Instant> = None;
    while ctx.running.load(Ordering::SeqCst) {
        if !ctx.armed.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(50));
            continue;
        }
        if start.is_none() {
            start = Some(Instant::now());
        }
        let event = HANDLE(ctx.event_raw as *mut std::ffi::c_void);
        let r = unsafe { WaitForSingleObject(event, 100) };
        let max = ctx.max_seconds.load(Ordering::SeqCst);
        let expired = max > 0 && start.unwrap().elapsed() >= Duration::from_secs(max);
        if r == WAIT_OBJECT_0 || expired {
            println!(
                "[{TAG}] watchdog: {}",
                if expired { "lock-max-seconds reached" } else { "lock-exit event" }
            );
            request_exit();
            let name = ctx
                .lock_name
                .lock()
                .map(|s| s.clone())
                .unwrap_or_default();
            // Give the owner thread 2 s to restore in order; then force Default. This is
            // the safety net that makes a stuck message loop non-fatal.
            for _ in 0..40 {
                if !ctx.running.load(Ordering::SeqCst) {
                    return;
                }
                if input_desktop_name().map(|n| n != name).unwrap_or(true) {
                    return;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            if let Ok(d) = open_default_desktop() {
                let _ = unsafe { SwitchDesktop(d) };
                let _ = unsafe { CloseDesktop(d) };
                println!("[{TAG}] watchdog: forced SwitchDesktop(Default) after 2 s");
            }
            return;
        }
    }
}

// ---------------------------------------------------------------- key hook / injection

unsafe extern "system" fn keyboard_proc(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code == HC_ACTION as i32 {
        let kb = &*(lparam.0 as *const KBDLLHOOKSTRUCT);
        let alt = GetAsyncKeyState(VK_MENU.0 as i32) < 0;
        let ctrl = GetAsyncKeyState(VK_CONTROL.0 as i32) < 0;
        let shift = GetAsyncKeyState(VK_SHIFT.0 as i32) < 0;
        if let Some(k) = blocked_key(kb.vkCode, alt, ctrl, shift) {
            HOOK_COUNTS[k as usize].fetch_add(1, Ordering::Relaxed);
            return LRESULT(1);
        }
    }
    CallNextHookEx(None, code, wparam, lparam)
}

fn send_key(vk: u16, up: bool) {
    let input = INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: VIRTUAL_KEY(vk),
                wScan: 0,
                dwFlags: if up { KEYEVENTF_KEYUP } else { KEYBD_EVENT_FLAGS(0) },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };
    unsafe {
        SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
    }
}

fn tap(vk: u16) {
    send_key(vk, false);
    std::thread::sleep(Duration::from_millis(25));
    send_key(vk, true);
    std::thread::sleep(Duration::from_millis(25));
}

/// One UTF-16 code unit as a Unicode keystroke (the file dialog's edit control).
fn send_unicode_unit(unit: u16, up: bool) {
    let flags = if up {
        KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
    } else {
        KEYEVENTF_UNICODE
    };
    let input = INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: VIRTUAL_KEY(0),
                wScan: unit,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };
    unsafe {
        SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
    }
}

/// PID of a window, used to prove the foreground belongs to the receiver.
fn window_pid(hwnd: HWND) -> u32 {
    let mut pid = 0u32;
    unsafe {
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
    }
    pid
}

/// All eight blocked combinations, injected from the lock desktop.
pub fn inject_all_keys() {
    tap(VK_LWIN.0);
    tap(VK_RWIN.0);
    // Alt+Tab
    send_key(VK_MENU.0, false);
    tap(VK_TAB.0);
    send_key(VK_MENU.0, true);
    // Alt+Shift+Tab
    send_key(VK_MENU.0, false);
    send_key(VK_SHIFT.0, false);
    tap(VK_TAB.0);
    send_key(VK_SHIFT.0, true);
    send_key(VK_MENU.0, true);
    // Alt+Esc
    send_key(VK_MENU.0, false);
    tap(VK_ESCAPE.0);
    send_key(VK_MENU.0, true);
    // Ctrl+Esc
    send_key(VK_CONTROL.0, false);
    tap(VK_ESCAPE.0);
    send_key(VK_CONTROL.0, true);
    // Ctrl+Shift+Esc
    send_key(VK_CONTROL.0, false);
    send_key(VK_SHIFT.0, false);
    tap(VK_ESCAPE.0);
    send_key(VK_SHIFT.0, true);
    send_key(VK_CONTROL.0, true);
    // Alt+F4
    send_key(VK_MENU.0, false);
    tap(VK_F4.0);
    send_key(VK_MENU.0, true);
    std::thread::sleep(Duration::from_millis(60));
}

fn inject_click(x: i32, y: i32) {
    let sw = unsafe { GetSystemMetrics(SM_CXSCREEN) }.max(2);
    let sh = unsafe { GetSystemMetrics(SM_CYSCREEN) }.max(2);
    let nx = (x.clamp(0, sw - 1) as i64 * 65535 / (sw as i64 - 1)) as i32;
    let ny = (y.clamp(0, sh - 1) as i64 * 65535 / (sh as i64 - 1)) as i32;
    let mk = |flags| INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx: nx,
                dy: ny,
                mouseData: 0,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };
    let inputs = [
        mk(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE),
        mk(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_ABSOLUTE),
        mk(MOUSEEVENTF_LEFTUP | MOUSEEVENTF_ABSOLUTE),
    ];
    unsafe {
        SendInput(&inputs, std::mem::size_of::<INPUT>() as i32);
    }
}

fn foreground_info() -> (String, String, u64) {
    unsafe {
        let h = GetForegroundWindow();
        let mut cls = [0u16; 256];
        let mut title = [0u16; 256];
        let n1 = GetClassNameW(h, &mut cls);
        let n2 = GetWindowTextW(h, &mut title);
        (
            String::from_utf16_lossy(&cls[..n1.max(0) as usize]),
            String::from_utf16_lossy(&title[..n2.max(0) as usize]),
            h.0 as u64,
        )
    }
}

// ---------------------------------------------------------------- window procedures

fn paint_solid(hwnd: HWND, color: COLORREF) {
    unsafe {
        let mut ps = PAINTSTRUCT::default();
        let hdc = BeginPaint(hwnd, &mut ps);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        let brush = CreateSolidBrush(color);
        FillRect(hdc, &rc, brush);
        let _ = DeleteObject(brush.into());
        let _ = EndPaint(hwnd, &ps);
    }
}

/// B-095: a client area always shows the standard arrow, never the app-starting busy cursor.
unsafe fn arrow_cursor(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if (lparam.0 & 0xFFFF) as u32 == HTCLIENT {
        if let Ok(cursor) = LoadCursorW(None, IDC_ARROW) {
            SetCursor(Some(cursor));
        }
        LRESULT(1)
    } else {
        DefWindowProcW(hwnd, msg, wparam, lparam)
    }
}

unsafe extern "system" fn black_wndproc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_ERASEBKGND => LRESULT(1),
        WM_PAINT => {
            paint_solid(hwnd, COLORREF(0x000000));
            LRESULT(0)
        }
        WM_SETCURSOR => arrow_cursor(hwnd, msg, wparam, lparam),
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn paint_exit(hwnd: HWND) {
    unsafe {
        let mut ps = PAINTSTRUCT::default();
        let hdc: HDC = BeginPaint(hwnd, &mut ps);
        let mut rc = RECT::default();
        let _ = GetClientRect(hwnd, &mut rc);
        let brush = CreateSolidBrush(COLORREF(0x1A1A1A)); // BGR: near-black
        FillRect(hdc, &rc, brush);
        let _ = DeleteObject(brush.into());
        let dpi = GetDpiForWindow(hwnd).max(96);
        let font = CreateFontW(
            -(18 * dpi as i32 / 96),
            0,
            0,
            0,
            FW_BOLD.0 as i32,
            0,
            0,
            0,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32,
            &HSTRING::from("Segoe UI"),
        );
        let old = SelectObject(hdc, font.into());
        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, COLORREF(0x00F0_F0F0));
        let mut text: Vec<u16> = "Exit".encode_utf16().collect();
        DrawTextW(hdc, &mut text, &mut rc, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        SelectObject(hdc, old);
        let _ = DeleteObject(font.into());
        let _ = EndPaint(hwnd, &ps);
    }
}

unsafe extern "system" fn exit_wndproc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_ERASEBKGND => LRESULT(1),
        WM_PAINT => {
            paint_exit(hwnd);
            LRESULT(0)
        }
        WM_LBUTTONUP => {
            println!("[{TAG}] Exit button clicked (harness-injected or physical)");
            request_exit();
            LRESULT(0)
        }
        WM_TIMER => {
            let _ = SetWindowPos(
                hwnd,
                Some(HWND_TOPMOST),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
            );
            LRESULT(0)
        }
        WM_DPICHANGED => {
            let _ = InvalidateRect(Some(hwnd), None, true);
            LRESULT(0)
        }
        WM_MOUSEACTIVATE => LRESULT(3), // MA_NOACTIVATE
        WM_SETCURSOR => arrow_cursor(hwnd, msg, wparam, lparam),
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn register_class(name: &str, proc: unsafe extern "system" fn(HWND, u32, WPARAM, LPARAM) -> LRESULT, brush: COLORREF) -> Result<(), String> {
    unsafe {
        let hinstance: HINSTANCE = GetModuleHandleW(None)
            .map_err(|e| format!("GetModuleHandleW: {e}"))?
            .into();
        let class = HSTRING::from(name);
        let wc = WNDCLASSW {
            lpfnWndProc: Some(proc),
            hInstance: hinstance,
            lpszClassName: PCWSTR(class.as_ptr()),
            // B-095: the Exit overlay (and the black lock backgrounds) must not inherit the
            // app-starting busy cursor from a null class cursor.
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hbrBackground: CreateSolidBrush(brush),
            ..Default::default()
        };
        let _ = RegisterClassW(&wc); // a second registration in one process is harmless
        Ok(())
    }
}

fn create_black_window(rc: RECT) -> Result<HWND, String> {
    unsafe {
        let hinstance: HINSTANCE = GetModuleHandleW(None)
            .map_err(|e| format!("GetModuleHandleW: {e}"))?
            .into();
        let class = HSTRING::from("udreceiver-lock-black");
        let hwnd = CreateWindowExW(
            WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
            PCWSTR(class.as_ptr()),
            PCWSTR(class.as_ptr()),
            WS_POPUP | WS_VISIBLE,
            rc.left,
            rc.top,
            (rc.right - rc.left).max(1),
            (rc.bottom - rc.top).max(1),
            None,
            None,
            Some(hinstance),
            None,
        )
        .map_err(|e| format!("CreateWindowExW(black): {e}"))?;
        let _ = ShowWindow(hwnd, SW_SHOW);
        Ok(hwnd)
    }
}

unsafe extern "system" fn monitor_cb(
    _hmon: HMONITOR,
    _hdc: HDC,
    rect: *mut RECT,
    data: LPARAM,
) -> windows::core::BOOL {
    let list = &mut *(data.0 as *mut Vec<HWND>);
    if !rect.is_null() {
        if let Ok(h) = create_black_window(*rect) {
            list.push(h);
        }
    }
    windows::core::BOOL(1)
}

fn create_black_windows() -> Vec<HWND> {
    let mut list: Vec<HWND> = Vec::new();
    if let Err(e) = register_class("udreceiver-lock-black", black_wndproc, COLORREF(0)) {
        eprintln!("[{TAG}] black window class: {e}");
        return list;
    }
    unsafe {
        let _ = EnumDisplayMonitors(
            None,
            None,
            Some(monitor_cb),
            LPARAM(&mut list as *mut Vec<HWND> as isize),
        );
    }
    list
}

fn create_exit_window() -> Result<HWND, String> {
    register_class("udreceiver-lock-exit", exit_wndproc, COLORREF(0x1A1A1A))?;
    unsafe {
        let hinstance: HINSTANCE = GetModuleHandleW(None)
            .map_err(|e| format!("GetModuleHandleW: {e}"))?
            .into();
        let class = HSTRING::from("udreceiver-lock-exit");
        let title = HSTRING::from("Exit");
        let hwnd = CreateWindowExW(
            WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
            PCWSTR(class.as_ptr()),
            PCWSTR(title.as_ptr()),
            WS_POPUP | WS_VISIBLE,
            0,
            0,
            120,
            40,
            None,
            None,
            Some(hinstance),
            None,
        )
        .map_err(|e| format!("CreateWindowExW(exit): {e}"))?;
        let dpi = GetDpiForWindow(hwnd).max(96) as i32;
        let w = 120 * dpi / 96;
        let h = 40 * dpi / 96;
        let margin = 12 * dpi / 96;
        let sw = GetSystemMetrics(SM_CXSCREEN);
        let x = (sw - w - margin).max(0);
        let y = margin;
        let _ = SetWindowPos(
            hwnd,
            Some(HWND_TOPMOST),
            x,
            y,
            w,
            h,
            SWP_SHOWWINDOW | SWP_NOACTIVATE,
        );
        let _ = ShowWindow(hwnd, SW_SHOW);
        *EXIT_RECT.lock().unwrap_or_else(|e| e.into_inner()) = (x, y, x + w, y + h);
        if SetTimer(Some(hwnd), 1, 500, None) == 0 {
            println!("[{TAG}] WARNING: SetTimer for the Exit topmost re-assert failed");
        }
        Ok(hwnd)
    }
}

fn pump_for(ms: u64) {
    let end = Instant::now() + Duration::from_millis(ms);
    unsafe {
        while Instant::now() < end {
            let mut msg = MSG::default();
            while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

// ---------------------------------------------------------------- the lock object

/// Owns the single-instance mutex (and the exit event) from before crash recovery through
/// the first lock window. No process may recover a journal or switch the desktop unless it
/// owns this guard. `view` holds it for the whole session; `lock-recover` and the crash
/// guard take it only for their recovery work.
pub struct Session {
    mutex: HANDLE,
    event: HANDLE,
}

impl Session {
    pub fn acquire() -> Result<Self, String> {
        unsafe {
            let event = CreateEventW(None, false, false, &HSTRING::from(EVENT_NAME))
                .map_err(|e| format!("CreateEventW: {e}"))?;
            // CreateEventW may leave ERROR_ALREADY_EXISTS behind. CreateMutexW only sets
            // it when the mutex existed; clear it so a stale value cannot fake a session.
            SetLastError(ERROR_SUCCESS);
            let mutex = match CreateMutexW(None, true, &HSTRING::from(MUTEX_NAME)) {
                Ok(h) => h,
                Err(e) => {
                    let _ = CloseHandle(event);
                    return Err(format!("CreateMutexW: {e}"));
                }
            };
            if GetLastError() == ERROR_ALREADY_EXISTS {
                // If the existing mutex happened to be unowned, CreateMutexW gave this
                // thread ownership; release it before dropping our handle.
                let _ = ReleaseMutex(mutex);
                let _ = CloseHandle(mutex);
                let _ = CloseHandle(event);
                return Err(format!(
                    "session running: another lock session is already running ({MUTEX_NAME}); \
                     refusing to recover, switch the desktop, or touch its journal"
                ));
            }
            Ok(Session { mutex, event })
        }
    }

    /// Restore a dirty journal / a leftover private desktop. The caller must own this
    /// guard; that is what proves no live lock session exists.
    pub fn recover(&self) -> Result<Option<(PathBuf, usize)>, String> {
        recover_locked()
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        unsafe {
            let _ = ReleaseMutex(self.mutex);
            let _ = CloseHandle(self.mutex);
            let _ = CloseHandle(self.event);
        }
    }
}

pub struct LockOptions {
    pub max_seconds: u64,
    pub policies: bool,
}

pub struct Lock {
    state: LockState,
    lock_desktop: HDESK,
    default_desktop: HDESK,
    hook: Option<windows::Win32::UI::WindowsAndMessaging::HHOOK>,
    exit_hwnd: HWND,
    blacks: Vec<HWND>,
    journal: Journal,
    session: Option<Session>,
    watchdog: Option<Arc<WatchdogCtx>>,
    watchdog_thread: Option<std::thread::JoinHandle<()>>,
    status_thread: Option<std::thread::JoinHandle<()>>,
    engaged_at: Option<Instant>,
    switch_ms: u128,
}

impl Lock {
    /// Locking: create the private desktop and its journal. Must be called on the thread
    /// that will create the video/Exit windows, before any window or hook on it exists.
    /// The caller passes the session guard it acquired (and recovered under) before this.
    pub fn prepare(opts: &LockOptions, session: Session) -> Result<Self, String> {
        unsafe {
            // Spawned while this thread is still on the default desktop, so the guard inherits
            // it (STARTUPINFO.lpDesktop = NULL) and can switch back after a hard kill.
            let _guard_pid = start_guard();
            let default_desktop = open_default_desktop()?;
            let name = format!("{DESKTOP_PREFIX}{}", std::process::id());
            let lock_desktop = match CreateDesktopW(
                &HSTRING::from(&name),
                PCWSTR::null(),
                None,
                DESKTOP_CONTROL_FLAGS(0),
                0x1000_0000, // GENERIC_ALL
                None,
            ) {
                Ok(d) => d,
                Err(e) => {
                    let _ = CloseDesktop(default_desktop);
                    return Err(format!("CreateDesktopW({name}): {e}"));
                }
            };
            RUNNING.store(true, Ordering::SeqCst);
            STATE.store(LockState::Locking.as_u8(), Ordering::SeqCst);
            *DESKTOP_NAME.lock().unwrap_or_else(|e| e.into_inner()) = name.clone();
            *ENGAGED_AT.lock().unwrap_or_else(|e| e.into_inner()) = None;
            EXIT_REQUESTED.store(false, Ordering::SeqCst);

            // Created BEFORE SetThreadDesktop: they stay on the default desktop, which is
            // exactly what the watchdog needs to force a switch back.
            let ctx = Arc::new(WatchdogCtx {
                event_raw: session.event.0 as isize,
                max_seconds: AtomicU64::new(opts.max_seconds),
                lock_name: Mutex::new(name.clone()),
                armed: AtomicBool::new(false),
                running: AtomicBool::new(true),
            });
            let wd = ctx.clone();
            let watchdog_thread = std::thread::Builder::new()
                .name("lock-watchdog".into())
                .spawn(move || watchdog_loop(wd))
                .map_err(|e| format!("spawn watchdog: {e}"))?;
            let status_thread = std::thread::Builder::new()
                .name("lock-status".into())
                .spawn(status_loop)
                .map_err(|e| format!("spawn status: {e}"))?;

            if let Err(e) = SetThreadDesktop(lock_desktop) {
                let _ = CloseDesktop(lock_desktop);
                let _ = CloseDesktop(default_desktop);
                RUNNING.store(false, Ordering::SeqCst);
                return Err(format!("SetThreadDesktop({name}): {e}"));
            }
            let journal = match Journal::start_at(journal_path(), &name) {
                Ok(j) => j,
                Err(e) => {
                    let _ = CloseDesktop(lock_desktop);
                    let _ = CloseDesktop(default_desktop);
                    RUNNING.store(false, Ordering::SeqCst);
                    return Err(e);
                }
            };
            println!("[{TAG}] Locking: desktop={name} journal={}", journal.path.display());
            Ok(Lock {
                state: LockState::Locking,
                lock_desktop,
                default_desktop,
                hook: None,
                exit_hwnd: HWND::default(),
                blacks: Vec::new(),
                journal,
                session: Some(session),
                watchdog: Some(ctx),
                watchdog_thread: Some(watchdog_thread),
                status_thread: Some(status_thread),
                engaged_at: None,
                switch_ms: 0,
            })
        }
    }

    /// Create the lock windows on the private desktop, install the hook, apply optional
    /// policies, then switch. Any failure before the switch is a clean abort: the default
    /// desktop was never left.
    pub fn engage(&mut self, video: HWND, opts: &LockOptions) -> Result<(), String> {
        if self.state != LockState::Locking {
            return Err(format!("engage in state {}", self.state.name()));
        }
        VIDEO_HWND.store(video.0 as u64, Ordering::SeqCst);
        self.blacks = create_black_windows();
        println!("[{TAG}] black lock windows: {}", self.blacks.len());
        unsafe {
            let _ = SetWindowPos(
                video,
                Some(HWND_TOPMOST),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
            );
        }
        self.exit_hwnd = create_exit_window()?;
        EXIT_HWND.store(self.exit_hwnd.0 as u64, Ordering::SeqCst);
        let hook = unsafe {
            SetWindowsHookExW(WH_KEYBOARD_LL, Some(keyboard_proc), None, 0)
                .map_err(|e| format!("SetWindowsHookExW(WH_KEYBOARD_LL): {e}"))?
        };
        self.hook = Some(hook);
        println!("[{TAG}] WH_KEYBOARD_LL installed on the lock desktop");

        if opts.policies {
            if is_elevated() {
                self.apply_policies()?;
            } else {
                println!("[{TAG}] --policies requested but process is NOT elevated: HKLM policies not applied (not-admin)");
            }
        }

        let t0 = Instant::now();
        // Arm the watchdog BEFORE the switch: from this instant the machine is never more
        // than `max_seconds` (+2 s of forced Default) away from an unlocked desktop, whatever
        // happens to this thread next.
        if let Some(w) = &self.watchdog {
            w.max_seconds.store(opts.max_seconds, Ordering::SeqCst);
            w.armed.store(true, Ordering::SeqCst);
        }
        if let Err(e) = unsafe { SwitchDesktop(self.lock_desktop) } {
            // Nothing was switched, but the hook, windows, journal and watchdog exist: run the
            // full teardown so no handle or thread is left behind.
            let _ = self.exit();
            return Err(format!("SwitchDesktop(lock): {e}"));
        }
        self.switch_ms = t0.elapsed().as_millis();
        self.state = LockState::Locked;
        STATE.store(LockState::Locked.as_u8(), Ordering::SeqCst);
        LOCK_ACTIVE.store(true, Ordering::SeqCst);
        self.engaged_at = Some(Instant::now());
        *ENGAGED_AT.lock().unwrap_or_else(|e| e.into_inner()) = self.engaged_at;
        unsafe {
            let _ = SetForegroundWindow(video);
            let _ = SetFocus(Some(video));
        }
        let name = DESKTOP_NAME
            .lock()
            .map(|s| s.clone())
            .unwrap_or_default();
        let actual = input_desktop_name().unwrap_or_else(|e| format!("<{e}>"));
        println!(
            "[{TAG}] Locked: input-desktop={actual} (expected {name}) switch_ms={}",
            self.switch_ms
        );
        write_status_file(LockState::Locked);
        Ok(())
    }

    fn apply_policies(&mut self) -> Result<(), String> {
        for (path, name) in POLICY_VALUES {
            let original = read_policy(PolicyRoot::LocalMachine, path, name)?;
            println!("[{TAG}] policy {name}: original={original:?}");
            self.journal.record_policy(PolicyEntry {
                root: PolicyRoot::LocalMachine,
                path: path.to_string(),
                name: name.to_string(),
                original: original.clone(),
            })?;
            write_policy(
                PolicyRoot::LocalMachine,
                path,
                name,
                &PolicyValue::Dword(1),
            )?;
        }
        println!("[{TAG}] policies applied ({} values)", POLICY_VALUES.len());
        Ok(())
    }

    fn cleanup_windows(&mut self) {
        unsafe {
            if !self.exit_hwnd.0.is_null() {
                let _ = KillTimer(Some(self.exit_hwnd), 1);
                let _ = DestroyWindow(self.exit_hwnd);
                self.exit_hwnd = HWND::default();
            }
            for b in self.blacks.drain(..) {
                let _ = DestroyWindow(b);
            }
        }
        EXIT_HWND.store(0, Ordering::SeqCst);
    }

    fn stop_threads(&mut self) {
        RUNNING.store(false, Ordering::SeqCst);
        if let Some(w) = &self.watchdog {
            w.running.store(false, Ordering::SeqCst);
            w.armed.store(false, Ordering::SeqCst);
        }
        if let Some(t) = self.watchdog_thread.take() {
            let _ = t.join();
        }
        if let Some(t) = self.status_thread.take() {
            let _ = t.join();
        }
    }

    /// Exiting -> Normal. Idempotent: a second call (or a call after a failed engage) is
    /// a no-op. Order: unhook -> switch to Default and verify -> restore the journal ->
    /// close the lock/default desktop handles -> stop the watchdog. If Default cannot be
    /// verified, fail closed: the journal, windows, watchdog and guard stay in place.
    pub fn exit(&mut self) -> Result<(), String> {
        if matches!(self.state, LockState::Normal | LockState::Exiting) {
            return Ok(());
        }
        self.state = self.state.transition(LockState::Exiting)?;
        STATE.store(LockState::Exiting.as_u8(), Ordering::SeqCst);
        let t0 = Instant::now();
        if let Some(h) = self.hook.take() {
            if let Err(e) = unsafe { UnhookWindowsHookEx(h) } {
                eprintln!("[{TAG}] UnhookWindowsHookEx: {e}");
            }
        }
        if let Err(e) = unsafe { SwitchDesktop(self.default_desktop) } {
            eprintln!("[{TAG}] SwitchDesktop(Default) call failed: {e}; verifying input desktop");
        }
        // The return value alone is not enough: a crash or policy failure can leave the
        // private desktop as input. Retry, then verify, before deleting the undo records.
        let mut input = self.input_desktop();
        let mut attempts = 0;
        while input != DESKTOP_DEFAULT && attempts < 3 {
            attempts += 1;
            if let Err(e) = ensure_default_input() {
                eprintln!("[{TAG}] return to Default attempt {attempts}: {e}");
            }
            std::thread::sleep(Duration::from_millis(50));
            input = self.input_desktop();
        }
        let switch_ms = t0.elapsed().as_millis();
        if input != DESKTOP_DEFAULT {
            // Do not delete the only undo record, destroy the Exit button, disarm the
            // watchdog or close the desktops. The guard restores after this process exits;
            // the next view recovers from the dirty journal even if the guard was killed.
            write_status_file(LockState::Exiting);
            // Keep the mutex owned until this process exits: a second view must not start
            // while the still-armed watchdog is alive.
            if let Some(s) = self.session.take() {
                std::mem::forget(s);
            }
            return Err(format!(
                "SwitchDesktop(Default) could not be verified (input desktop is still \
                 '{input}'); journal kept dirty, watchdog and crash guard left armed"
            ));
        }
        let restored = self.journal.restore_and_clean(|p| {
            write_policy(p.root, &p.path, &p.name, &p.value_original())
        });
        let policies_restored = match &restored {
            Ok(n) => {
                println!("[{TAG}] journal clean ({n} values restored)");
                Some(*n)
            }
            Err(e) => {
                eprintln!("[{TAG}] journal restore FAILED (left dirty): {e}");
                None
            }
        };
        self.cleanup_windows();
        LOCK_ACTIVE.store(false, Ordering::SeqCst);
        let _ = unsafe { CloseDesktop(self.lock_desktop) };
        let _ = unsafe { CloseDesktop(self.default_desktop) };
        self.stop_threads();
        self.state = LockState::Normal;
        STATE.store(LockState::Normal.as_u8(), Ordering::SeqCst);
        write_status_file(LockState::Normal);
        println!(
            "[{TAG}] exited: switch_ms={switch_ms} total_ms={} policies={:?}",
            t0.elapsed().as_millis(),
            policies_restored
        );
        match restored {
            Ok(_) => Ok(()),
            Err(e) => Err(format!("journal restore: {e}")),
        }
    }

    /// A failure before `engage` (e.g. create_window failed): no switch happened; just
    /// make the journal clean and release the desktop.
    pub fn abort(&mut self) {
        if self.state != LockState::Locking {
            return;
        }
        self.cleanup_windows();
        if let Some(h) = self.hook.take() {
            let _ = unsafe { UnhookWindowsHookEx(h) };
        }
        let _ = self.journal.restore_and_clean(|p| {
            write_policy(p.root, &p.path, &p.name, &p.value_original())
        });
        let _ = unsafe { CloseDesktop(self.lock_desktop) };
        let _ = unsafe { CloseDesktop(self.default_desktop) };
        self.stop_threads();
        LOCK_ACTIVE.store(false, Ordering::SeqCst);
        self.state = LockState::Normal;
        STATE.store(LockState::Normal.as_u8(), Ordering::SeqCst);
        write_status_file(LockState::Normal);
        println!("[{TAG}] aborted before the desktop switch; Default is untouched");
    }

    pub fn input_desktop(&self) -> String {
        input_desktop_name().unwrap_or_else(|e| format!("<{e}>"))
    }
}

impl Drop for Lock {
    fn drop(&mut self) {
        if matches!(self.state, LockState::Locked | LockState::Locking) {
            eprintln!("[{TAG}] Drop with an active lock: emergency restore");
            let _ = self.exit();
        }
    }
}

// `restore_and_clean` wants the original value; a small helper keeps call sites short.
impl PolicyEntry {
    fn value_original(&self) -> PolicyValue {
        self.original.clone()
    }
}

// ---------------------------------------------------------------- CLI entry points

pub fn cmd_status() -> i32 {
    let input = match input_desktop_name() {
        Ok(n) => n,
        Err(e) => format!("<error: {e}>"),
    };
    let st = read_status_file();
    let mutex_held = lock_session_running();
    let journal = Journal::load_at(journal_path());
    println!("input-desktop={input}");
    println!(
        "lock-state={} (status file: state={} desktop={} pid={})",
        if mutex_held {
            if st.state.is_empty() { "unknown" } else { &st.state }
        } else if input == DESKTOP_DEFAULT {
            "Normal"
        } else {
            "unknown (no lock process holds the mutex)"
        },
        st.state,
        st.desktop,
        st.pid
    );
    println!(
        "lock-process={}",
        if mutex_held { "running" } else { "none" }
    );
    match &journal {
        Ok(Some(j)) => println!(
            "journal={} dirty={} desktop={} pid={} policies={}",
            j.path.display(),
            !j.is_clean(),
            j.desktop,
            j.pid,
            j.policies.len()
        ),
        Ok(None) => println!("journal={} clean (no file)", journal_path().display()),
        Err(e) => println!(
            "journal={} UNREADABLE: {e} (recovery will refuse to overwrite it)",
            journal_path().display()
        ),
    }
    println!("exit-rect={}", if st.exit_rect.is_empty() { "<none>" } else { &st.exit_rect });
    println!(
        "hook lwin={} rwin={} alttab={} altshifttab={} altesc={} ctrlesc={} ctrlshiftesc={} altf4={}",
        st.counts[0],
        st.counts[1],
        st.counts[2],
        st.counts[3],
        st.counts[4],
        st.counts[5],
        st.counts[6],
        st.counts[7]
    );
    if !mutex_held && !st.desktop.is_empty() && st.state == "Locked" {
        println!("note: stale status file from pid {} (no lock process); input is {input}", st.pid);
    }
    0
}

pub fn cmd_exit() -> i32 {
    unsafe {
        match OpenEventW(EVENT_MODIFY_STATE, false, &HSTRING::from(EVENT_NAME)) {
            Ok(h) => {
                let r = SetEvent(h);
                let _ = CloseHandle(h);
                match r {
                    Ok(()) => {
                        println!("lock-exit: event signalled");
                        0
                    }
                    Err(e) => {
                        eprintln!("lock-exit: SetEvent failed: {e}");
                        1
                    }
                }
            }
            Err(e) => {
                eprintln!("lock-exit: no lock session listens on {EVENT_NAME}: {e}");
                1
            }
        }
    }
}

pub fn cmd_recover() -> i32 {
    match recover_if_dirty() {
        Ok(Some((path, n))) => {
            println!("lock-recover: restored {n} policies from {}; journal clean", path.display());
            0
        }
        Ok(None) => {
            println!("lock-recover: journal clean, nothing to restore");
            0
        }
        Err(e) => {
            eprintln!("lock-recover: FAILED: {e}");
            1
        }
    }
}

/// Called by `lock-recover` when it owns no session. It acquires the single-instance guard
/// first, so it can never touch a live lock's journal or desktop; a live session makes it
/// fail before the journal is read. `view` and `lock-selftest` acquire the guard themselves
/// and call `Session::recover` instead.
pub fn recover_if_dirty() -> Result<Option<(PathBuf, usize)>, String> {
    let guard = Session::acquire()?;
    guard.recover()
}

/// The caller must own the mutable `Session` guard. Restores a dirty journal, then returns
/// a leftover `udreceiver-lock-*` input desktop to Default even when the journal is clean
/// (a failed `SwitchDesktop` in `Lock::exit` can leave exactly that).
fn recover_locked() -> Result<Option<(PathBuf, usize)>, String> {
    let path = journal_path();
    let input = input_desktop_name().unwrap_or_default();
    let mut result = None;
    let mut load_error = None;
    match Journal::load_at(path.clone()) {
        Ok(Some(mut j)) if !j.is_clean() => {
            println!(
                "[{TAG}] dirty journal from desktop '{}' (pid {}, {} policies): restoring",
                j.desktop,
                j.pid,
                j.policies.len()
            );
            ensure_default_input()?;
            let n = j.restore_and_clean(|p| {
                write_policy(p.root, &p.path, &p.name, &p.value_original())
            })?;
            println!("[{TAG}] crash recovery restored {n} values; journal clean");
            result = Some((path.clone(), n));
        }
        Ok(Some(j)) => {
            println!(
                "[{TAG}] lock journal is clean (desktop '{}', pid {})",
                j.desktop, j.pid
            );
        }
        Ok(None) => {}
        Err(e) => {
            // Leave the unreadable journal byte-for-byte alone, but still rescue the
            // screen: with the mutex held, no live lock owns the private desktop.
            eprintln!("[{TAG}] journal unreadable, leaving it untouched: {e}");
            load_error = Some(e);
        }
    }
    if input.starts_with(DESKTOP_PREFIX) {
        ensure_default_input()?;
        println!(
            "[{TAG}] input desktop '{input}' was left by a session that no longer runs; \
             returned to Default"
        );
    }
    // The guard proves no lock process is running, so any status file is stale.
    let _ = fs::remove_file(status_path());
    if let Some(e) = load_error {
        return Err(e);
    }
    Ok(result)
}

/// True while any process owns the single-instance lock mutex.
fn lock_session_running() -> bool {
    unsafe {
        match OpenMutexW(SYNCHRONIZATION_SYNCHRONIZE, false, &HSTRING::from(MUTEX_NAME)) {
            Ok(h) => {
                let _ = CloseHandle(h);
                true
            }
            Err(_) => false,
        }
    }
}

fn start_guard() -> Option<u32> {
    // A separate `udreceiver lock-guard` process, started while this thread is still on the
    // default desktop, waits for this PID to die. A killed process leaves its private desktop
    // as the INPUT desktop (verified 2026-10-21: after taskkill /F the screen stayed on an
    // empty lock desktop), so only another process can undo it. Same binary: no new component.
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let exe = std::env::current_exe().ok()?;
    let log_path = lock_dir().join("lock-guard.log");
    let log = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .ok();
    let mut cmd = std::process::Command::new(exe);
    cmd.arg("lock-guard")
        .arg("--parent")
        .arg(std::process::id().to_string())
        .stdin(std::process::Stdio::null())
        .creation_flags(CREATE_NO_WINDOW);
    match log {
        Some(file) => {
            let err = file.try_clone().ok();
            cmd.stdout(std::process::Stdio::from(file));
            cmd.stderr(match err {
                Some(e) => std::process::Stdio::from(e),
                None => std::process::Stdio::null(),
            });
        }
        None => {
            cmd.stdout(std::process::Stdio::null());
            cmd.stderr(std::process::Stdio::null());
        }
    }
    match cmd.spawn() {
        Ok(child) => {
            println!("[lock] crash guard started: pid={}", child.id());
            Some(child.id())
        }
        Err(e) => {
            eprintln!(
                "[lock] WARNING: cannot start the crash guard ({e}); a hard kill would leave \
                 the lock desktop as the input desktop until `lock-recover` runs"
            );
            None
        }
    }
}

/// `lock-guard --parent <pid>`: wait for the lock process to disappear, then undo only
/// what that process left behind. It takes the single-instance mutex after the parent dies,
/// and refuses if a new session already owns it. The journal must name the parent PID and
/// its lock desktop; a clean journal only authorises returning that same desktop to Default.
pub fn cmd_guard(parent_pid: u32) -> i32 {
    use windows::Win32::System::Threading::{OpenProcess, INFINITE, PROCESS_SYNCHRONIZE};
    if parent_pid == 0 {
        eprintln!("[lock-guard] --parent must be a real PID; exiting");
        return 1;
    }
    unsafe {
        match OpenProcess(PROCESS_SYNCHRONIZE, false, parent_pid) {
            Ok(h) => {
                WaitForSingleObject(h, INFINITE);
                let _ = CloseHandle(h);
            }
            Err(e) => {
                if GetLastError() == ERROR_INVALID_PARAMETER {
                    println!("[lock-guard] parent {parent_pid} is already gone");
                } else {
                    eprintln!("[lock-guard] cannot open parent {parent_pid}: {e}; exiting");
                    return 0;
                }
            }
        }
    }
    // Measured from the moment the dead session is observed, not from process start.
    let started = Instant::now();
    let guard = match Session::acquire() {
        Ok(g) => g,
        Err(e) => {
            println!(
                "[lock-guard] parent {parent_pid} gone; {e}; leaving it alone"
            );
            return 0;
        }
    };
    let expected = format!("{DESKTOP_PREFIX}{parent_pid}");
    let input = input_desktop_name().unwrap_or_default();
    let journal_path = journal_path();
    let journal = match Journal::load_at(journal_path) {
        Ok(j) => j,
        Err(e) => {
            eprintln!("[lock-guard] unreadable journal: {e}; not touching it");
            if input == expected {
                if let Err(e) = ensure_default_input() {
                    eprintln!("[lock-guard] SwitchDesktop(Default): {e}");
                    return 1;
                }
                let _ = fs::remove_file(status_path());
                println!(
                    "[lock-guard] parent {parent_pid} gone; returned desktop '{expected}' to \
                     Default; journal left untouched"
                );
                return 0;
            }
            return 1;
        }
    };
    let owned_dirty = journal
        .as_ref()
        .map(|j| !j.is_clean() && j.belongs_to(parent_pid))
        .unwrap_or(false);
    let input_ours = input == expected;
    if !owned_dirty && !input_ours {
        if let Some(j) = &journal {
            if !j.is_clean() {
                println!(
                    "[lock-guard] dirty journal belongs to pid {} desktop '{}', not parent \
                     {parent_pid}; leaving it untouched",
                    j.pid, j.desktop
                );
            }
        }
        println!(
            "[lock-guard] parent {parent_pid} gone; nothing belongs to it (input={input}, \
             journal_dirty={}); nothing to do ({} ms)",
            journal.as_ref().map(|j| !j.is_clean()).unwrap_or(false),
            started.elapsed().as_millis()
        );
        return 0;
    }
    // Only switch a desktop that is the parent's own (or Default, which is already safe).
    // If some other private desktop is input, leave it for its own owner/recovery.
    let switch_input = input_ours || input == DESKTOP_DEFAULT;
    let mut restored = 0;
    if owned_dirty {
        if switch_input {
            if let Err(e) = ensure_default_input() {
                eprintln!("[lock-guard] SwitchDesktop(Default): {e}");
                return 1;
            }
        } else {
            println!(
                "[lock-guard] input desktop is '{input}', not parent {parent_pid}'s; restoring \
                 policies only"
            );
        }
        let mut j = journal.expect("owned_dirty implies a journal");
        match j.restore_and_clean(|p| {
            write_policy(p.root, &p.path, &p.name, &p.value_original())
        }) {
            Ok(n) => restored = n,
            Err(e) => {
                eprintln!("[lock-guard] journal restore FAILED: {e}");
                return 1;
            }
        }
    } else if input_ours {
        if let Err(e) = ensure_default_input() {
            eprintln!("[lock-guard] SwitchDesktop(Default): {e}");
            return 1;
        }
    }
    let after = input_desktop_name().unwrap_or_default();
    if switch_input && after != DESKTOP_DEFAULT {
        eprintln!(
            "[lock-guard] input desktop is still '{after}' after restore; journal={}",
            if owned_dirty { "removed" } else { "clean" }
        );
        return 1;
    }
    // The dead session can no longer write `Normal`, so drop its stale status file.
    let _ = fs::remove_file(status_path());
    println!(
        "[lock-guard] restored input-desktop={after} policies={restored} in {} ms",
        started.elapsed().as_millis()
    );
    drop(guard);
    0
}

fn create_selftest_window() -> Result<HWND, String> {
    register_class("udreceiver-lock-selftest", black_wndproc, COLORREF(0))?;
    unsafe {
        let hinstance: HINSTANCE = GetModuleHandleW(None)
            .map_err(|e| format!("GetModuleHandleW: {e}"))?
            .into();
        let class = HSTRING::from("udreceiver-lock-selftest");
        let title = HSTRING::from("udreceiver lock selftest");
        let sw = GetSystemMetrics(SM_CXSCREEN);
        let sh = GetSystemMetrics(SM_CYSCREEN);
        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            PCWSTR(class.as_ptr()),
            PCWSTR(title.as_ptr()),
            WS_POPUP | WS_VISIBLE,
            0,
            0,
            sw,
            sh,
            None,
            None,
            Some(hinstance),
            None,
        )
        .map_err(|e| format!("CreateWindowExW(selftest): {e}"))?;
        let _ = ShowWindow(hwnd, SW_SHOW);
        let _ = SetForegroundWindow(hwnd);
        let _ = SetFocus(Some(hwnd));
        Ok(hwnd)
    }
}

/// `lock-selftest --seconds N`: switch, inject every blocked combination from inside the
/// lock desktop, print the counters, then restore. It always tries to restore, even when
/// a step failed; if the message loop ever cannot, the watchdog force-switches.
pub fn cmd_selftest(seconds: u64) -> i32 {
    let session = match Session::acquire() {
        Ok(s) => s,
        Err(e) => {
            eprintln!("SELFTEST FAILED at session: {e}");
            return 1;
        }
    };
    if let Err(e) = session.recover() {
        eprintln!("SELFTEST FAILED at recovery (no lock started): {e}");
        return 1;
    }
    let opts = LockOptions {
        max_seconds: seconds + 15,
        policies: false,
    };
    let mut lock = match Lock::prepare(&opts, session) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("SELFTEST FAILED at prepare: {e}");
            return 1;
        }
    };
    let hwnd = match create_selftest_window() {
        Ok(h) => h,
        Err(e) => {
            eprintln!("SELFTEST FAILED at window: {e}");
            lock.abort();
            return 1;
        }
    };
    let mut engage_err = None;
    if let Err(e) = lock.engage(hwnd, &opts) {
        engage_err = Some(e);
    }
    let mut ok = engage_err.is_none();
    if let Some(e) = &engage_err {
        eprintln!("SELFTEST FAILED at engage: {e}");
    } else {
        println!("SELFTEST desktop={}", DESKTOP_NAME.lock().unwrap().clone());
        inject_all_keys();
        pump_for(1200);
        let counts = hook_counts();
        let (cls, title, fg) = foreground_info();
        println!(
            "SELFTEST counters lwin={} rwin={} alttab={} altshifttab={} altesc={} ctrlesc={} ctrlshiftesc={} altf4={}",
            counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7]
        );
        println!("SELFTEST foreground hwnd=0x{fg:x} class='{cls}' title='{title}'");
        if counts.contains(&0) {
            eprintln!("SELFTEST FAILED: at least one blocked-key counter stayed 0");
            ok = false;
        }
        if cls != "udreceiver-view" && cls != "udreceiver-lock-selftest" {
            eprintln!("SELFTEST FAILED: foreground class is '{cls}', expected our window");
            ok = false;
        }
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(seconds) {
            if take_exit_request() {
                println!("SELFTEST exit request (watchdog/lock-exit); restoring early");
                break;
            }
            pump_for(200);
        }
    }
    let ex = lock.exit();
    unsafe {
        let _ = DestroyWindow(hwnd);
    }
    if let Err(e) = &ex {
        eprintln!("SELFTEST restore error: {e}");
        ok = false;
    }
    let after = lock.input_desktop();
    println!("SELFTEST after-exit input-desktop={after}");
    if after != DESKTOP_DEFAULT {
        eprintln!("SELFTEST FAILED: input desktop is not Default after exit");
        let _ = ensure_default_input();
        ok = false;
    }
    let counts = hook_counts();
    if ok && after == DESKTOP_DEFAULT && counts.iter().all(|c| *c > 0) {
        println!("SELFTEST --seconds {seconds}: PASS (desktop switch, 8/8 blocked, restore)");
        0
    } else {
        eprintln!("SELFTEST --seconds {seconds}: FAIL");
        1
    }
}

/// Harness helper: must run ON the lock desktop (SetInputDesktop) for SendInput to reach
/// it. `--keys` injects all eight combinations; `--click x,y` clicks; `--click-exit`
/// computes the Exit centre from the status file.
pub fn cmd_inject(
    keys: bool,
    click: Option<(i32, i32)>,
    click_exit: bool,
    shot: Option<&str>,
    windows: bool,
    expect: Option<&str>,
    import: Option<&str>,
) -> i32 {
    let desk = match unsafe {
        OpenInputDesktop(
            DESKTOP_CONTROL_FLAGS(0),
            false,
            DESKTOP_ACCESS_FLAGS(0x000F_01FF),
        )
    } {
        Ok(d) => d,
        Err(e) => {
            eprintln!("lock-inject: OpenInputDesktop: {e}");
            return 1;
        }
    };
    let name = desktop_name(desk).unwrap_or_default();
    if !name.starts_with(DESKTOP_PREFIX) {
        eprintln!("lock-inject: input desktop is '{name}', not a lock desktop; refusing");
        let _ = unsafe { CloseDesktop(desk) };
        return 1;
    }
    if let Some(e) = expect {
        if e != name {
            eprintln!("lock-inject: input desktop is '{name}', expected '{e}'; refusing");
            let _ = unsafe { CloseDesktop(desk) };
            return 1;
        }
    }
    if let Err(e) = unsafe { SetThreadDesktop(desk) } {
        eprintln!("lock-inject: SetThreadDesktop({name}): {e}");
        let _ = unsafe { CloseDesktop(desk) };
        return 1;
    }
    let (cls0, title0, fg0) = foreground_info();
    println!("lock-inject: desktop={name} foreground-before hwnd=0x{fg0:x} class='{cls0}' title='{title0}'");
    if keys {
        inject_all_keys();
        println!("lock-inject: injected 8 blocked-key combinations (harness-injected)");
    }
    if let Some(path) = import {
        // PC input safety: every synthetic keystroke below is only sent after the
        // foreground window is proven to belong to the receiver process that owns the
        // lock (the status journal's pid). Anything else is refused.
        let st = read_status_file();
        let receivers_pid = st.pid;
        let (_, _, fg) = foreground_info();
        let fg_pid = window_pid(HWND(fg as *mut std::ffi::c_void));
        if receivers_pid == 0 || fg_pid != receivers_pid {
            eprintln!(
                "lock-inject: import refused: foreground pid={fg_pid}, receiver pid={receivers_pid}"
            );
            let _ = unsafe { CloseDesktop(desk) };
            return 1;
        }
        println!(
            "lock-inject: import foreground verified: hwnd=0x{fg:x} pid={fg_pid} == receiver; sending Ctrl+O"
        );
        send_key(VK_CONTROL.0, false);
        std::thread::sleep(Duration::from_millis(25));
        tap(0x4F); // O
        send_key(VK_CONTROL.0, true);
        std::thread::sleep(Duration::from_millis(900));
        let (cls, title, dlg) = foreground_info();
        let dlg_pid = window_pid(HWND(dlg as *mut std::ffi::c_void));
        println!(
            "lock-inject: dialog hwnd=0x{dlg:x} class='{cls}' title='{title}' pid={dlg_pid}"
        );
        if dlg_pid != receivers_pid {
            eprintln!(
                "lock-inject: import refused: dialog pid={dlg_pid}, receiver pid={receivers_pid}"
            );
            let _ = unsafe { CloseDesktop(desk) };
            return 1;
        }
        // The standard file dialog puts focus on the file-name edit; Alt+N re-focuses it
        // explicitly. The dialog is our own process (verified above).
        send_key(VK_MENU.0, false);
        std::thread::sleep(Duration::from_millis(20));
        tap(0x4E); // N
        send_key(VK_MENU.0, true);
        std::thread::sleep(Duration::from_millis(200));
        let units: Vec<u16> = path.encode_utf16().collect();
        for u in &units {
            send_unicode_unit(*u, false);
            send_unicode_unit(*u, true);
            std::thread::sleep(Duration::from_millis(3));
        }
        std::thread::sleep(Duration::from_millis(150));
        tap(0x0D); // Enter
        println!(
            "lock-inject: typed import path ({}) and pressed Enter (dialog pid={dlg_pid})",
            units.len()
        );
        std::thread::sleep(Duration::from_millis(700));
    }
    if let Some(path) = shot {
        match screenshot_bmp(std::path::Path::new(path)) {
            Ok((w, h)) => println!("lock-inject: screenshot {path} {w}x{h} (lock desktop)"),
            Err(e) => eprintln!("lock-inject: screenshot failed: {e}"),
        }
    }
    if windows {
        match enum_desktop_windows(&name) {
            Ok(rows) => {
                println!("lock-inject: windows on '{name}' in z-order (topmost first): {}", rows.len());
                for r in rows {
                    println!("{r}");
                }
            }
            Err(e) => eprintln!("lock-inject: window enumeration failed: {e}"),
        }
        // PrintWindow on the Exit overlay of the *running* lock process: the painted label,
        // independent of how the desktop is composed.
        match find_window_on_desktop(&name, "udreceiver-lock-exit") {
            Ok(Some(hwnd)) => {
                let p = std::path::Path::new("exit-window.bmp");
                match print_window_bmp(hwnd, p) {
                    Ok((w, h)) => {
                        println!("lock-inject: PrintWindow(exit) {w}x{h} -> {}", p.display())
                    }
                    Err(e) => eprintln!("lock-inject: PrintWindow(exit) failed: {e}"),
                }
            }
            Ok(None) => println!("lock-inject: no 'udreceiver-lock-exit' window on '{name}'"),
            Err(e) => eprintln!("lock-inject: find exit window: {e}"),
        }
    }
    if let Some((x, y)) = click {
        inject_click(x, y);
        println!("lock-inject: injected click at ({x},{y}) (harness-injected)");
    }
    if click_exit {
        let st = read_status_file();
        let parts: Vec<i32> = st
            .exit_rect
            .split(',')
            .filter_map(|v| v.trim().parse().ok())
            .collect();
        if parts.len() != 4 {
            eprintln!("lock-inject: no exit-rect in the status file");
            let _ = unsafe { CloseDesktop(desk) };
            return 1;
        }
        let (x, y) = ((parts[0] + parts[2]) / 2, (parts[1] + parts[3]) / 2);
        inject_click(x, y);
        println!("lock-inject: clicked Exit centre ({x},{y}) (harness-injected)");
    }
    std::thread::sleep(Duration::from_millis(600));
    let (cls1, title1, fg1) = foreground_info();
    println!("lock-inject: foreground-after hwnd=0x{fg1:x} class='{cls1}' title='{title1}'");
    let counts = read_status_file().counts;
    println!(
        "lock-inject: counters lwin={} rwin={} alttab={} altshifttab={} altesc={} ctrlesc={} ctrlshiftesc={} altf4={}",
        counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7]
    );
    let _ = unsafe { CloseDesktop(desk) };
    0
}

fn write_bmp(path: &std::path::Path, w: i32, h: i32, bits: &[u8]) -> Result<(), String> {
    let mut out = Vec::with_capacity(54 + bits.len());
    out.extend_from_slice(b"BM");
    out.extend_from_slice(&((54 + bits.len()) as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&54u32.to_le_bytes());
    out.extend_from_slice(&40u32.to_le_bytes()); // BITMAPINFOHEADER
    out.extend_from_slice(&w.to_le_bytes());
    out.extend_from_slice(&h.to_le_bytes()); // positive: bottom-up
    out.extend_from_slice(&1u16.to_le_bytes()); // planes
    out.extend_from_slice(&32u16.to_le_bytes()); // bpp
    out.extend_from_slice(&0u32.to_le_bytes()); // BI_RGB
    out.extend_from_slice(&(bits.len() as u32).to_le_bytes());
    for _ in 0..4 {
        out.extend_from_slice(&0u32.to_le_bytes());
    }
    out.extend_from_slice(bits);
    fs::write(path, out).map_err(|e| format!("write {}: {e}", path.display()))
}

/// Harness evidence: capture what the *lock desktop* actually shows. A process on the
/// default desktop cannot do this (GDI returns an invalid screen DC for a desktop that is not
/// the calling thread's), which is why it lives here: `lock-inject` switches its thread to the
/// lock desktop first. Writes an uncompressed 32-bit BMP; the caller converts it if it wants.
fn screenshot_bmp(path: &std::path::Path) -> Result<(i32, i32), String> {
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, GetDC, GetDIBits, ReleaseDC,
        SelectObject as Sel, BI_RGB, BITMAPINFO, BITMAPINFOHEADER, DIB_RGB_COLORS, SRCCOPY,
    };
    unsafe {
        let sw = GetSystemMetrics(SM_CXSCREEN).max(1);
        let sh = GetSystemMetrics(SM_CYSCREEN).max(1);
        let screen = GetDC(None);
        if screen.is_invalid() {
            return Err("GetDC(NULL) failed".into());
        }
        let mem = CreateCompatibleDC(Some(screen));
        if mem.is_invalid() {
            let _ = ReleaseDC(None, screen);
            return Err("CreateCompatibleDC failed".into());
        }
        let bmp = CreateCompatibleBitmap(screen, sw, sh);
        let old = Sel(mem, bmp.into());
        let blt = BitBlt(mem, 0, 0, sw, sh, Some(screen), 0, 0, SRCCOPY);
        let mut info = BITMAPINFO::default();
        info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = sw;
        info.bmiHeader.biHeight = sh;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB.0;
        let mut bits = vec![0u8; (sw as usize) * (sh as usize) * 4];
        let lines = if blt.is_ok() {
            GetDIBits(
                mem,
                bmp,
                0,
                sh as u32,
                Some(bits.as_mut_ptr() as *mut std::ffi::c_void),
                &mut info,
                DIB_RGB_COLORS,
            )
        } else {
            0
        };
        Sel(mem, old);
        let _ = windows::Win32::Graphics::Gdi::DeleteObject(bmp.into());
        let _ = windows::Win32::Graphics::Gdi::DeleteDC(mem);
        let _ = ReleaseDC(None, screen);
        if blt.is_err() {
            return Err(format!("BitBlt: {:?}", blt.err()));
        }
        if lines != sh {
            return Err(format!("GetDIBits returned {lines} of {sh} lines"));
        }
        write_bmp(path, sw, sh, &bits)?;
        Ok((sw, sh))
    }
}

/// Z-ordered top-level windows of a desktop, topmost first, as `lock-status`-style lines.
/// This is the direct "taskbar yes/no" evidence: the default desktop's `Shell_TrayWnd` is not
/// in this list, and our Exit overlay is above the video.
fn enum_desktop_windows(desk: &str) -> Result<Vec<String>, String> {
    use windows::Win32::System::StationsAndDesktops::EnumDesktopWindows;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetWindowLongPtrW, GetWindowRect, IsWindowVisible, GWL_EXSTYLE,
    };
    let d = unsafe {
        OpenDesktopW(
            &HSTRING::from(desk),
            DESKTOP_CONTROL_FLAGS(0),
            false,
            // STANDARD_RIGHTS_READ | DESKTOP_READOBJECTS | DESKTOP_ENUMERATE
            0x0002_0000 | 0x0001 | 0x0040,
        )
        .map_err(|e| format!("OpenDesktopW({desk}): {e}"))?
    };
    let mut out: Vec<String> = Vec::new();
    unsafe extern "system" fn cb(hwnd: HWND, lparam: LPARAM) -> windows::core::BOOL {
        let out = &mut *(lparam.0 as *mut Vec<String>);
        let mut cls = [0u16; 256];
        let mut title = [0u16; 256];
        let n1 = GetClassNameW(hwnd, &mut cls);
        let n2 = GetWindowTextW(hwnd, &mut title);
        let mut rc = RECT::default();
        let _ = GetWindowRect(hwnd, &mut rc);
        let ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE) as u32;
        out.push(format!(
            "  hwnd=0x{:x} class='{}' title='{}' rect={},{},{},{} visible={} topmost={} toolwindow={}",
            hwnd.0 as usize,
            String::from_utf16_lossy(&cls[..n1.max(0) as usize]),
            String::from_utf16_lossy(&title[..n2.max(0) as usize]),
            rc.left, rc.top, rc.right, rc.bottom,
            IsWindowVisible(hwnd).as_bool(),
            ex & WS_EX_TOPMOST.0 != 0,
            ex & WS_EX_TOOLWINDOW.0 != 0,
        ));
        windows::core::BOOL(1)
    }
    let r = unsafe {
        EnumDesktopWindows(Some(d), Some(cb), LPARAM(&mut out as *mut Vec<String> as isize))
    };
    let _ = unsafe { CloseDesktop(d) };
    if let Err(e) = r {
        return Err(format!("EnumDesktopWindows: {e}"));
    }
    Ok(out)
}

/// First top-level window of `desk` whose class name matches, or None.
fn find_window_on_desktop(desk: &str, class: &str) -> Result<Option<HWND>, String> {
    use windows::Win32::System::StationsAndDesktops::EnumDesktopWindows;
    let d = unsafe {
        OpenDesktopW(
            &HSTRING::from(desk),
            DESKTOP_CONTROL_FLAGS(0),
            false,
            0x0002_0000 | 0x0001 | 0x0040,
        )
        .map_err(|e| format!("OpenDesktopW({desk}): {e}"))?
    };
    unsafe extern "system" fn cb(hwnd: HWND, lparam: LPARAM) -> windows::core::BOOL {
        let (found, want) = &mut *(lparam.0 as *mut (Option<HWND>, String));
        if found.is_some() {
            return windows::core::BOOL(1);
        }
        let mut cls = [0u16; 256];
        let n = GetClassNameW(hwnd, &mut cls);
        if String::from_utf16_lossy(&cls[..n.max(0) as usize]) == *want {
            *found = Some(hwnd);
        }
        windows::core::BOOL(1)
    }
    let mut data = (None::<HWND>, class.to_string());
    let r = unsafe {
        EnumDesktopWindows(
            Some(d),
            Some(cb),
            LPARAM(&mut data as *mut _ as isize),
        )
    };
    let _ = unsafe { CloseDesktop(d) };
    if let Err(e) = r {
        return Err(format!("EnumDesktopWindows: {e}"));
    }
    Ok(data.0)
}

/// `PrintWindow` of a window on the lock desktop, so the painted Exit label is on record even
/// though a screen BitBlt of a composed (flip-model video) desktop does not show it.
fn print_window_bmp(hwnd: HWND, path: &std::path::Path) -> Result<(i32, i32), String> {
    use windows::Win32::Graphics::Gdi::{
        CreateCompatibleBitmap, CreateCompatibleDC, GetDC, GetDIBits, ReleaseDC,
        SelectObject as Sel, BI_RGB, BITMAPINFO, BITMAPINFOHEADER, DIB_RGB_COLORS,
    };
    use windows::Win32::Storage::Xps::{PrintWindow, PRINT_WINDOW_FLAGS};
    use windows::Win32::UI::WindowsAndMessaging::{GetWindowRect, PW_RENDERFULLCONTENT};
    unsafe {
        let mut rc = RECT::default();
        let _ = GetWindowRect(hwnd, &mut rc);
        let (w, h) = ((rc.right - rc.left).max(1), (rc.bottom - rc.top).max(1));
        let screen = GetDC(None);
        let mem = CreateCompatibleDC(Some(screen));
        let bmp = CreateCompatibleBitmap(screen, w, h);
        let old = Sel(mem, bmp.into());
        let ok = PrintWindow(hwnd, mem, PRINT_WINDOW_FLAGS(PW_RENDERFULLCONTENT)).as_bool();
        let mut info = BITMAPINFO::default();
        info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = w;
        info.bmiHeader.biHeight = h;
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB.0;
        let mut bits = vec![0u8; (w as usize) * (h as usize) * 4];
        let lines = GetDIBits(
            mem,
            bmp,
            0,
            h as u32,
            Some(bits.as_mut_ptr() as *mut std::ffi::c_void),
            &mut info,
            DIB_RGB_COLORS,
        );
        Sel(mem, old);
        let _ = windows::Win32::Graphics::Gdi::DeleteObject(bmp.into());
        let _ = windows::Win32::Graphics::Gdi::DeleteDC(mem);
        let _ = ReleaseDC(None, screen);
        if !ok {
            return Err("PrintWindow returned 0".into());
        }
        if lines != h {
            return Err(format!("GetDIBits returned {lines} of {h} lines"));
        }
        write_bmp(path, w, h, &bits)?;
        Ok((w, h))
    }
}

// ---------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_journal(tag: &str) -> PathBuf {
        static N: AtomicU64 = AtomicU64::new(0);
        let n = N.fetch_add(1, Ordering::Relaxed);
        let p = std::env::temp_dir().join(format!(
            "udreceiver-lock-test-{}-{tag}-{n}.txt",
            std::process::id()
        ));
        let _ = fs::remove_file(&p);
        p
    }

    #[test]
    fn state_machine_chain_and_invalid_transitions() {
        let s = LockState::Normal;
        let s = s.transition(LockState::Locking).unwrap();
        let s = s.transition(LockState::Locked).unwrap();
        let s = s.transition(LockState::Exiting).unwrap();
        assert_eq!(s.transition(LockState::Normal).unwrap(), LockState::Normal);
        assert!(LockState::Normal.transition(LockState::Locked).is_err());
        assert!(LockState::Locked.transition(LockState::Locking).is_err());
        assert!(LockState::Exiting.transition(LockState::Locked).is_err());
        // aborting a prepare before the switch is legal
        assert_eq!(
            LockState::Locking.transition(LockState::Normal).unwrap(),
            LockState::Normal
        );
    }

    #[test]
    fn hook_filter_blocks_all_eight_combinations_on_down_and_up() {
        assert_eq!(blocked_key(0x5B, false, false, false), Some(BlockedKey::LWin));
        assert_eq!(blocked_key(0x5C, false, false, false), Some(BlockedKey::RWin));
        assert_eq!(blocked_key(0x09, true, false, false), Some(BlockedKey::AltTab));
        assert_eq!(
            blocked_key(0x09, true, false, true),
            Some(BlockedKey::AltShiftTab)
        );
        assert_eq!(blocked_key(0x1B, true, false, false), Some(BlockedKey::AltEsc));
        assert_eq!(blocked_key(0x1B, false, true, false), Some(BlockedKey::CtrlEsc));
        assert_eq!(
            blocked_key(0x1B, false, true, true),
            Some(BlockedKey::CtrlShiftEsc)
        );
        assert_eq!(blocked_key(0x73, true, false, false), Some(BlockedKey::AltF4));
    }

    #[test]
    fn hook_filter_passes_normal_keys() {
        assert_eq!(blocked_key(0x41, false, false, false), None); // A
        assert_eq!(blocked_key(0x09, false, false, false), None); // Tab
        assert_eq!(blocked_key(0x1B, false, false, false), None); // Esc
        assert_eq!(blocked_key(0x73, false, false, false), None); // F4
        assert_eq!(blocked_key(0x1B, false, false, true), None); // Shift+Esc
        assert_eq!(blocked_key(0x09, false, true, false), None); // Ctrl+Tab
    }

    #[test]
    fn journal_round_trip() {
        let path = temp_journal("roundtrip");
        let mut j = Journal::start_at(path.clone(), "udreceiver-lock-42").unwrap();
        assert!(!j.is_clean());
        j.record_policy(PolicyEntry {
            root: PolicyRoot::LocalMachine,
            path: r"SOFTWARE\Policies\Test".into(),
            name: "DisableTaskMgr".into(),
            original: PolicyValue::Dword(0),
        })
        .unwrap();
        j.record_policy(PolicyEntry {
            root: PolicyRoot::CurrentUser,
            path: r"Software\Test".into(),
            name: "Text".into(),
            original: PolicyValue::Sz("hello | world\nline2".into()),
        })
        .unwrap();
        let loaded = Journal::load_at(path.clone()).unwrap().unwrap();
        assert_eq!(loaded.desktop, "udreceiver-lock-42");
        assert_eq!(loaded.policies, j.policies);
        assert!(!loaded.is_clean());
        let mut loaded = loaded;
        let mut applied = Vec::new();
        let n = loaded
            .restore_and_clean(|p| {
                applied.push(p.name.clone());
                Ok(())
            })
            .unwrap();
        assert_eq!(n, 2);
        assert_eq!(applied, vec!["Text", "DisableTaskMgr"]); // reverse order
        assert!(!path.exists());
        assert!(Journal::load_at(path).unwrap().is_none());
    }

    #[test]
    fn journal_restore_is_idempotent() {
        let path = temp_journal("idem");
        let mut j = Journal::start_at(path.clone(), "d").unwrap();
        j.record_policy(PolicyEntry {
            root: PolicyRoot::LocalMachine,
            path: "P".into(),
            name: "N".into(),
            original: PolicyValue::Missing,
        })
        .unwrap();
        let mut first = Vec::new();
        assert_eq!(
            j.restore_and_clean(|p| {
                first.push(p.name.clone());
                Ok(())
            })
            .unwrap(),
            1
        );
        let mut second = Vec::new();
        assert_eq!(
            j.restore_and_clean(|p| {
                second.push(p.name.clone());
                Ok(())
            })
            .unwrap(),
            0
        );
        assert!(second.is_empty());
        assert_eq!(first, vec!["N"]);
    }

    #[test]
    fn journal_start_refuses_dirty_or_unreadable_and_preserves_bytes() {
        let path = temp_journal("refuse-dirty");
        let mut j = Journal::start_at(path.clone(), "udreceiver-lock-7").unwrap();
        j.record_policy(PolicyEntry {
            root: PolicyRoot::CurrentUser,
            path: r"Software\unified-desktop\journal-refuse-test".into(),
            name: "Value".into(),
            original: PolicyValue::Missing,
        })
        .unwrap();
        let before = fs::read(&path).unwrap();
        let err = Journal::start_at(path.clone(), "udreceiver-lock-8").unwrap_err();
        assert!(err.contains("dirty"), "unexpected error: {err}");
        assert_eq!(fs::read(&path).unwrap(), before);
        assert!(!path.with_extension("tmp").exists());

        let bad = temp_journal("refuse-bad");
        fs::write(&bad, "not a journal\n").unwrap();
        let bad_before = fs::read(&bad).unwrap();
        let err = Journal::start_at(bad.clone(), "udreceiver-lock-9").unwrap_err();
        assert!(err.contains("unreadable"), "unexpected error: {err}");
        assert_eq!(fs::read(&bad).unwrap(), bad_before);
        let _ = fs::remove_file(&bad);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn failed_restore_keeps_the_journal_and_a_new_lock_cannot_overwrite_it() {
        let path = temp_journal("failed-restore");
        let mut j = Journal::start_at(path.clone(), "udreceiver-lock-11").unwrap();
        j.record_policy(PolicyEntry {
            root: PolicyRoot::CurrentUser,
            path: r"Software\unified-desktop\journal-failed-restore-test".into(),
            name: "Value".into(),
            original: PolicyValue::Missing,
        })
        .unwrap();
        let before = fs::read(&path).unwrap();
        let mut loaded = Journal::load_at(path.clone()).unwrap().unwrap();
        let err = loaded
            .restore_and_clean(|_| Err("simulated permission error".to_string()))
            .unwrap_err();
        assert!(err.contains("simulated permission error"));
        assert!(!loaded.is_clean());
        assert_eq!(fs::read(&path).unwrap(), before);

        // This is the first durable step of Lock::prepare. It must fail, so no lock
        // session can start and the undo record stays byte-for-byte intact.
        let err = Journal::start_at(path.clone(), "udreceiver-lock-12").unwrap_err();
        assert!(err.contains("dirty"), "unexpected error: {err}");
        assert_eq!(fs::read(&path).unwrap(), before);
        assert!(!path.with_extension("tmp").exists());
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn journal_records_owner_pid_for_the_crash_guard() {
        let path = temp_journal("owner-pid");
        let pid = std::process::id();
        let desktop = format!("{DESKTOP_PREFIX}{pid}");
        let mut j = Journal::start_at(path.clone(), &desktop).unwrap();
        assert_eq!(j.pid, pid);
        assert!(j.belongs_to(pid));
        assert!(!j.belongs_to(pid.wrapping_add(1)));
        let loaded = Journal::load_at(path.clone()).unwrap().unwrap();
        assert_eq!(loaded.pid, pid);
        assert!(loaded.belongs_to(pid));
        assert!(!loaded.belongs_to(pid.wrapping_add(1)));
        let _ = j.restore_and_clean(|_| Ok(()));
        assert!(!path.exists());
    }

    #[test]
    fn recovery_refuses_while_a_live_session_owns_the_mutex() {
        let dir = std::env::temp_dir().join(format!(
            "udreceiver-recover-guard-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        let journal = dir.join("lock-state.txt");
        let mut j = Journal::start_at(journal.clone(), "udreceiver-lock-999").unwrap();
        j.record_policy(PolicyEntry {
            root: PolicyRoot::CurrentUser,
            path: r"Software\unified-desktop\recovery-guard-test".into(),
            name: "Value".into(),
            original: PolicyValue::Missing,
        })
        .unwrap();
        let before = fs::read(&journal).unwrap();
        let status = dir.join("lock-status.txt");
        fs::write(&status, "state=Locked\n").unwrap();

        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let session = Session::acquire().expect("test could not acquire the mutex");
            ready_tx.send(()).unwrap();
            let _ = release_rx.recv();
            drop(session);
        });
        ready_rx.recv().unwrap();

        let old = std::env::var_os("UD_LOCK_DIR");
        std::env::set_var("UD_LOCK_DIR", &dir);
        let result = recover_if_dirty();
        match old {
            Some(v) => std::env::set_var("UD_LOCK_DIR", v),
            None => std::env::remove_var("UD_LOCK_DIR"),
        }

        let _ = release_tx.send(());
        holder.join().unwrap();
        let err = result.expect_err("recovery must refuse while a session owns the mutex");
        assert!(
            err.contains("another lock session"),
            "unexpected error: {err}"
        );
        assert_eq!(fs::read(&journal).unwrap(), before);
        assert_eq!(fs::read_to_string(&status).unwrap(), "state=Locked\n");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn policy_value_round_trip_current_user() {
        let path = format!("Software\\unified-desktop\\lock-test-{}", std::process::id());
        let name = "RoundTrip";
        // clean slate
        let _ = write_policy(PolicyRoot::CurrentUser, &path, name, &PolicyValue::Missing);
        assert_eq!(
            read_policy(PolicyRoot::CurrentUser, &path, name).unwrap(),
            PolicyValue::Missing
        );
        write_policy(
            PolicyRoot::CurrentUser,
            &path,
            name,
            &PolicyValue::Dword(0x1234_5678),
        )
        .unwrap();
        assert_eq!(
            read_policy(PolicyRoot::CurrentUser, &path, name).unwrap(),
            PolicyValue::Dword(0x1234_5678)
        );
        write_policy(
            PolicyRoot::CurrentUser,
            &path,
            name,
            &PolicyValue::Sz("unified".into()),
        )
        .unwrap();
        assert_eq!(
            read_policy(PolicyRoot::CurrentUser, &path, name).unwrap(),
            PolicyValue::Sz("unified".into())
        );
        write_policy(PolicyRoot::CurrentUser, &path, name, &PolicyValue::Missing).unwrap();
        assert_eq!(
            read_policy(PolicyRoot::CurrentUser, &path, name).unwrap(),
            PolicyValue::Missing
        );
        unsafe {
            let mut key = HKEY::default();
            if windows::Win32::System::Registry::RegOpenKeyExW(
                HKEY_CURRENT_USER,
                &HSTRING::from(&path),
                None,
                KEY_SET_VALUE,
                &mut key,
            ) == ERROR_SUCCESS
            {
                let _ = windows::Win32::System::Registry::RegDeleteKeyW(
                    HKEY_CURRENT_USER,
                    &HSTRING::from(&path),
                );
                let _ = RegCloseKey(key);
            }
        }
    }
}
