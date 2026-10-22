//! Phase 11 D-c: the PC half of the text clipboard (UTF-8 on the wire, CF_UNICODETEXT
//! in Windows). Text only, at most 1 MiB; a hash stops the echo loop between the two
//! ends (the phone keeps its own last hash, see Phase11Clip.kt).
//!
//! Privacy: nothing here logs or stores clipboard contents. Callers log lengths only.

/// Wire cap for one clipboard message (matches protocol MAX_MESSAGE_LEN).
pub const MAX_CLIP_BYTES: usize = 1 << 20;

/// FNV-1a 64: a stable, cheap loop-guard hash (not a security primitive).
pub fn clip_hash(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// True when a change was caused by our own write to that end.
pub fn is_echo(hash: u64, last: u64) -> bool {
    hash == last
}

/// Decode wire bytes as UTF-8 text, refusing empty or oversize payloads.
pub fn decode_text(bytes: &[u8]) -> Result<String, String> {
    if bytes.is_empty() {
        return Err("empty clip".into());
    }
    if bytes.len() > MAX_CLIP_BYTES {
        return Err(format!("clip {} > {MAX_CLIP_BYTES} bytes", bytes.len()));
    }
    String::from_utf8(bytes.to_vec()).map_err(|_| "clip is not utf-8".into())
}

#[cfg(windows)]
mod win {
    use super::MAX_CLIP_BYTES;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::DataExchange::{
        CloseClipboard, EmptyClipboard, GetClipboardData, OpenClipboard, SetClipboardData,
    };
    use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE};
    use windows::Win32::System::Ole::CF_UNICODETEXT;

    /// Read the PC clipboard as UTF-8. `None` when it holds no text.
    pub fn read_text() -> Option<Vec<u8>> {
        unsafe {
            OpenClipboard(Some(HWND(std::ptr::null_mut()))).ok()?;
            let r = (|| {
                let h = GetClipboardData(u32::from(CF_UNICODETEXT.0)).ok()?;
                if h.is_invalid() {
                    return None;
                }
                let p = GlobalLock(windows::Win32::Foundation::HGLOBAL(h.0)) as *const u16;
                if p.is_null() {
                    return None;
                }
                let mut len = 0usize;
                // The cap also bounds a corrupt clipboard with no terminator.
                while len < MAX_CLIP_BYTES / 2 && *p.add(len) != 0 {
                    len += 1;
                }
                let slice = std::slice::from_raw_parts(p, len);
                let _ = GlobalUnlock(windows::Win32::Foundation::HGLOBAL(h.0));
                let s = String::from_utf16_lossy(slice);
                Some(s.into_bytes())
            })();
            let _ = CloseClipboard();
            r
        }
    }

    /// Replace the PC clipboard with `utf8` as CF_UNICODETEXT.
    pub fn write_text(utf8: &[u8]) -> Result<(), String> {
        if utf8.is_empty() || utf8.len() > MAX_CLIP_BYTES {
            return Err(format!("clip write {} bytes", utf8.len()));
        }
        let text = String::from_utf8(utf8.to_vec()).map_err(|_| "clip is not utf-8")?;
        let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
        let bytes = wide.len() * 2;
        unsafe {
            OpenClipboard(Some(HWND(std::ptr::null_mut())))
                .map_err(|e| format!("OpenClipboard: {e}"))?;
            let r = (|| -> Result<(), String> {
                EmptyClipboard().map_err(|e| format!("EmptyClipboard: {e}"))?;
                let h = GlobalAlloc(GMEM_MOVEABLE, bytes).map_err(|e| format!("GlobalAlloc: {e}"))?;
                let p = GlobalLock(h) as *mut u16;
                if p.is_null() {
                    return Err("GlobalLock null".into());
                }
                std::ptr::copy_nonoverlapping(wide.as_ptr(), p, wide.len());
                let _ = GlobalUnlock(h);
                SetClipboardData(u32::from(CF_UNICODETEXT.0), Some(windows::Win32::Foundation::HANDLE(h.0)))
                    .map_err(|e| format!("SetClipboardData: {e}"))?;
                Ok(())
            })();
            let _ = CloseClipboard();
            r
        }
    }
}

#[cfg(windows)]
pub use win::{read_text, write_text};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_is_stable_and_distinguishes_text() {
        assert_eq!(clip_hash(b"pc2desk-1"), clip_hash(b"pc2desk-1"));
        assert_ne!(clip_hash(b"pc2desk-1"), clip_hash(b"pc2desk-2"));
        assert!(is_echo(clip_hash(b"x"), clip_hash(b"x")));
        assert!(!is_echo(clip_hash(b"x"), clip_hash(b"y")));
    }

    #[test]
    fn decode_rejects_empty_oversize_and_bad_utf8() {
        assert_eq!(decode_text("héllo".as_bytes()).unwrap(), "héllo");
        assert!(decode_text(b"").is_err());
        assert!(decode_text(&vec![b'a'; MAX_CLIP_BYTES + 1]).is_err());
        assert!(decode_text(&[0xFF, 0xFE]).is_err());
    }

    #[test]
    fn unicode_roundtrip_through_utf16_pair() {
        // The wire is UTF-8; Windows is UTF-16. Surrogate pairs must survive.
        let s = "desk2pc-😀-ü";
        let wide: Vec<u16> = s.encode_utf16().collect();
        assert_eq!(String::from_utf16_lossy(&wide), s);
        assert_eq!(decode_text(s.as_bytes()).unwrap(), s);
    }
}
