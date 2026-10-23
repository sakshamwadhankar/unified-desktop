//! Phase-5 wire formats on the PC side: the mirror of `android/.../Phase5Protocol.kt`.
//! Everything here is pure data so it is unit-testable without a window or a GPU.
//!
//! Video (CH_VIDEO): `header | Annex-B access unit`, header 24 bytes:
//!   0 u8 version(1) | 1 u8 flags (bit0 key, bit1 config) | 2 u16 reserved |
//!   4 u32 seq BE | 8 i64 pts_us BE | 16 i64 encode_done_ns BE
//! Input (CH_INPUT): concatenated events, see `InputEvent`.
//! Control (CH_CONTROL): UTF-8 text - need-keyframe / csq / csr / lat / vstat / istat.

use crate::protocol::{FLAG_MORE_FOLLOWS, MAX_FRAME_PAYLOAD};

pub const VIDEO_VERSION: u8 = 1;
pub const VIDEO_HEADER_LEN: usize = 24;
pub const VFLAG_KEYFRAME: u8 = 0b1;
pub const VFLAG_CONFIG: u8 = 0b10;

pub const DESKTOP_W: i32 = 1920;
pub const DESKTOP_H: i32 = 1080;

pub const EVT_MOVE: u8 = 1;
pub const EVT_BUTTON: u8 = 2;
pub const EVT_WHEEL: u8 = 3;
pub const EVT_KEY: u8 = 4;
pub const EVT_RELEASE_ALL: u8 = 5;

pub const BTN_LEFT: u8 = 0;
pub const BTN_RIGHT: u8 = 1;
pub const BTN_MIDDLE: u8 = 2;

pub const MOD_SHIFT: u8 = 0b1;
pub const MOD_CTRL: u8 = 0b10;
pub const MOD_ALT: u8 = 0b100;
pub const MOD_META: u8 = 0b1000;

pub const CTRL_NEED_KEYFRAME: &str = "need-keyframe";
pub const CTRL_RELEASE_ALL: &str = "release-all";
/// B-047: `view` asks for video with this message; `pair` and `bench` never send it, so a
/// bench run carries no video traffic.
pub const CTRL_VIDEO_ON: &str = "video-on";
/// A frame-less receiver re-asks for video this often (the phone may not have had a resumed
/// activity yet, or the message may have raced the pairing gate).
pub const VIDEO_ON_RETRY_MS: u64 = 2000;

/// Reply to the phone's `ping <stamp_us>` link-RTT probe. The phone times the round trip
/// itself; the PC only echoes the stamp (the control channel is tiny, one probe a second).
/// Returns None for anything that is not a well-formed probe.
pub fn pong_for(control: &[u8]) -> Option<Vec<u8>> {
    let t = std::str::from_utf8(control).ok()?.trim();
    let stamp = t.strip_prefix("ping ")?;
    if stamp.is_empty()
        || stamp.len() > 24
        || !stamp.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    Some(format!("pong {stamp}").into_bytes())
}

/// Decides when the receiver sends `video-on`: never once a frame arrived, and repeated no
/// faster than [VIDEO_ON_RETRY_MS] until then (the phone may have had no resumed activity
/// when the first ask arrived, B-048; this way video starts as soon as it does). Pure
/// logic, unit-tested.
pub struct VideoOnPacer {
    last_ms: Option<u64>,
    sends: u32,
}

impl VideoOnPacer {
    pub fn new() -> Self {
        Self { last_ms: None, sends: 0 }
    }

    pub fn sends(&self) -> u32 {
        self.sends
    }

    pub fn due(&mut self, now_ms: u64, got_frame: bool) -> bool {
        if got_frame {
            return false;
        }
        if let Some(last) = self.last_ms {
            if now_ms.saturating_sub(last) < VIDEO_ON_RETRY_MS {
                return false;
            }
        }
        self.last_ms = Some(now_ms);
        self.sends += 1;
        true
    }
}

impl Default for VideoOnPacer {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoFrame {
    pub flags: u8,
    pub seq: u32,
    pub pts_us: i64,
    pub encode_done_ns: i64,
    pub annex_b: Vec<u8>,
}

impl VideoFrame {
    pub fn is_keyframe(&self) -> bool {
        self.flags & VFLAG_KEYFRAME != 0
    }
    pub fn is_config(&self) -> bool {
        self.flags & VFLAG_CONFIG != 0
    }
}

pub fn parse_video_frame(msg: &[u8]) -> Result<VideoFrame, String> {
    if msg.len() < VIDEO_HEADER_LEN {
        return Err(format!("video message {} B < header", msg.len()));
    }
    if msg[0] != VIDEO_VERSION {
        return Err(format!("video version {} != {VIDEO_VERSION}", msg[0]));
    }
    let flags = msg[1];
    let seq = u32::from_be_bytes([msg[4], msg[5], msg[6], msg[7]]);
    let pts_us = i64::from_be_bytes(msg[8..16].try_into().unwrap());
    let encode_done_ns = i64::from_be_bytes(msg[16..24].try_into().unwrap());
    Ok(VideoFrame {
        flags,
        seq,
        pts_us,
        encode_done_ns,
        annex_b: msg[VIDEO_HEADER_LEN..].to_vec(),
    })
}

#[cfg_attr(not(test), allow(dead_code))] // mirror of the phone packer, for the unit tests
pub fn pack_video_frame(flags: u8, seq: u32, pts_us: i64, encode_done_ns: i64, au: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(VIDEO_HEADER_LEN + au.len());
    out.push(VIDEO_VERSION);
    out.push(flags);
    out.extend_from_slice(&[0, 0]);
    out.extend_from_slice(&seq.to_be_bytes());
    out.extend_from_slice(&pts_us.to_be_bytes());
    out.extend_from_slice(&encode_done_ns.to_be_bytes());
    out.extend_from_slice(au);
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputEvent {
    Move { x: i32, y: i32 },
    Button { button: u8, down: bool, x: i32, y: i32, t_pc_us: u32 },
    Wheel { delta: i32, x: i32, y: i32, t_pc_us: u32 },
    Key { down: bool, vk: u8, scan: u8, mods: u8, t_pc_us: u32 },
    ReleaseAll,
}

pub fn pack_input_events(events: &[InputEvent]) -> Vec<u8> {
    let mut out = Vec::with_capacity(events.len() * 11);
    for e in events {
        match *e {
            InputEvent::Move { x, y } => {
                out.push(EVT_MOVE);
                out.extend_from_slice(&(x as u16).to_be_bytes());
                out.extend_from_slice(&(y as u16).to_be_bytes());
            }
            InputEvent::Button { button, down, x, y, t_pc_us } => {
                out.push(EVT_BUTTON);
                out.push(button);
                out.push(if down { 1 } else { 0 });
                out.extend_from_slice(&(x as u16).to_be_bytes());
                out.extend_from_slice(&(y as u16).to_be_bytes());
                out.extend_from_slice(&t_pc_us.to_be_bytes());
            }
            InputEvent::Wheel { delta, x, y, t_pc_us } => {
                out.push(EVT_WHEEL);
                out.extend_from_slice(&(delta as i16).to_be_bytes());
                out.extend_from_slice(&(x as u16).to_be_bytes());
                out.extend_from_slice(&(y as u16).to_be_bytes());
                out.extend_from_slice(&t_pc_us.to_be_bytes());
            }
            InputEvent::Key { down, vk, scan, mods, t_pc_us } => {
                out.push(EVT_KEY);
                out.push(if down { 1 } else { 0 });
                out.push(vk);
                out.push(scan);
                out.push(mods);
                out.extend_from_slice(&t_pc_us.to_be_bytes());
            }
            InputEvent::ReleaseAll => out.push(EVT_RELEASE_ALL),
        }
    }
    out
}

pub fn parse_input_events(msg: &[u8]) -> Result<Vec<InputEvent>, String> {
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < msg.len() {
        let t = msg[i];
        i += 1;
        match t {
            EVT_MOVE => {
                if msg.len() - i < 4 {
                    return Err("truncated move".into());
                }
                let x = u16::from_be_bytes([msg[i], msg[i + 1]]) as i32;
                let y = u16::from_be_bytes([msg[i + 2], msg[i + 3]]) as i32;
                i += 4;
                out.push(InputEvent::Move { x, y });
            }
            EVT_BUTTON => {
                if msg.len() - i < 10 {
                    return Err("truncated button".into());
                }
                let button = msg[i];
                let down = msg[i + 1] != 0;
                let x = u16::from_be_bytes([msg[i + 2], msg[i + 3]]) as i32;
                let y = u16::from_be_bytes([msg[i + 4], msg[i + 5]]) as i32;
                let t_pc_us = u32::from_be_bytes([msg[i + 6], msg[i + 7], msg[i + 8], msg[i + 9]]);
                i += 10;
                out.push(InputEvent::Button { button, down, x, y, t_pc_us });
            }
            EVT_WHEEL => {
                if msg.len() - i < 10 {
                    return Err("truncated wheel".into());
                }
                let delta = i16::from_be_bytes([msg[i], msg[i + 1]]) as i32;
                let x = u16::from_be_bytes([msg[i + 2], msg[i + 3]]) as i32;
                let y = u16::from_be_bytes([msg[i + 4], msg[i + 5]]) as i32;
                let t_pc_us = u32::from_be_bytes([msg[i + 6], msg[i + 7], msg[i + 8], msg[i + 9]]);
                i += 10;
                out.push(InputEvent::Wheel { delta, x, y, t_pc_us });
            }
            EVT_KEY => {
                if msg.len() - i < 8 {
                    return Err("truncated key".into());
                }
                let down = msg[i] != 0;
                let vk = msg[i + 1];
                let scan = msg[i + 2];
                let mods = msg[i + 3];
                let t_pc_us = u32::from_be_bytes([msg[i + 4], msg[i + 5], msg[i + 6], msg[i + 7]]);
                i += 8;
                out.push(InputEvent::Key { down, vk, scan, mods, t_pc_us });
            }
            EVT_RELEASE_ALL => out.push(InputEvent::ReleaseAll),
            other => return Err(format!("unknown input event type {other}")),
        }
    }
    Ok(out)
}

/// Join MORE_FOLLOWS chunks back into whole messages (a 1080p keyframe is often > 64 KiB).
#[derive(Default)]
pub struct ChunkAssembler {
    buf: Vec<u8>,
}

impl ChunkAssembler {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }

    /// Returns a complete message when `flags` closes one, else None.
    pub fn feed(&mut self, flags: u8, payload: &[u8]) -> Result<Option<Vec<u8>>, String> {
        if self.buf.len() + payload.len() > (1 << 20) {
            self.buf.clear();
            return Err("assembled message over cap".into());
        }
        self.buf.extend_from_slice(payload);
        if flags & FLAG_MORE_FOLLOWS != 0 {
            return Ok(None);
        }
        Ok(Some(std::mem::take(&mut self.buf)))
    }

    pub fn reset(&mut self) {
        self.buf.clear();
    }
}

/// Window pixels -> desktop coordinates. Pure so the mapping is unit-tested.
pub fn scale_to_desktop(px: i32, py: i32, client_w: i32, client_h: i32, dw: i32, dh: i32) -> (i32, i32) {
    if client_w <= 0 || client_h <= 0 {
        return (0, 0);
    }
    let x = (px as i64 * dw as i64 / client_w as i64).clamp(0, dw as i64 - 1) as i32;
    let y = (py as i64 * dh as i64 / client_h as i64).clamp(0, dh as i64 - 1) as i32;
    (x, y)
}

/// A client pixel -> desktop pixel through the video letterbox (the same fit() the blit
/// uses). B-064: this offsets the black bars, so a click aimed at a video pixel lands on
/// that desktop pixel; unit-tested below.
pub fn scale_client_to_desktop(px: i32, py: i32, client_w: i32, client_h: i32) -> (i32, i32) {
    scale_client_to_desktop_for(px, py, client_w, client_h, DESKTOP_W, DESKTOP_H)
}

/// Phase 13 (D8): the same mapping for the desktop size the phone is actually rendering,
/// which the PC chooses from its client aspect and sends to the phone before video-on.
pub fn scale_client_to_desktop_for(
    px: i32,
    py: i32,
    client_w: i32,
    client_h: i32,
    dw: i32,
    dh: i32,
) -> (i32, i32) {
    if client_w <= 0 || client_h <= 0 {
        return (0, 0);
    }
    let (vw, vh) = fit_video_for(client_w, client_h, dw, dh);
    let (ox, oy) = ((client_w - vw) / 2, (client_h - vh) / 2);
    scale_to_desktop(px - ox, py - oy, vw, vh, dw, dh)
}

/// D8: pick the phone's desktop size from the PC client so the picture fills the window with
/// no bars. The encoder on the test phone starts reliably at 1080 lines but rejects 1200
/// (`codec.start()` throws), so the height stays 1080 and the width follows the client
/// aspect: 16:10 -> 1728x1080, 16:9 -> 1920x1080. The receiver scales 1728 to 1920 (1.11x),
/// which is sharp enough and keeps the desktop's UI 1:1 relative to itself.
pub fn desktop_for_client(cw: i32, ch: i32) -> (i32, i32) {
    if cw <= 0 || ch <= 0 {
        return (DESKTOP_W, DESKTOP_H);
    }
    let dh = 1080;
    let dw = ((dh as f64 * cw as f64 / ch as f64).round() as i32) & !1;
    (dw.clamp(1280, 1920), dh)
}

/// The size the 1920x1080 desktop is blitted to inside a client of `cw x ch`.
pub fn fit_video(cw: i32, ch: i32) -> (i32, i32) {
    fit_video_for(cw, ch, DESKTOP_W, DESKTOP_H)
}

/// Fit a desktop of `dw x dh` inside the client, keeping its aspect (D8).
pub fn fit_video_for(cw: i32, ch: i32, dw: i32, dh: i32) -> (i32, i32) {
    if cw <= 0 || ch <= 0 {
        return (cw.max(2), ch.max(2));
    }
    let (dw, dh) = (dw.max(2), dh.max(2));
    let sx = cw as f64 / dw as f64;
    let sy = ch as f64 / dh as f64;
    let s = sx.min(sy);
    (
        ((dw as f64 * s) as i32).max(2),
        ((dh as f64 * s) as i32).max(2),
    )
}

/// Windows VK modifier bit (what `GetKeyState` reports) -> the wire's MOD_* bitmask.
pub fn mods_from_shift(shift: bool, ctrl: bool, alt: bool, meta: bool) -> u8 {
    let mut m = 0u8;
    if shift {
        m |= MOD_SHIFT;
    }
    if ctrl {
        m |= MOD_CTRL;
    }
    if alt {
        m |= MOD_ALT;
    }
    if meta {
        m |= MOD_META;
    }
    m
}

/// One clock-sync sample: PC send t1, phone receive t2, phone send t3, PC receive t4
/// (all microseconds; t2/t3 on the phone's monotonic clock). The offset that converts a
/// PC timestamp into the phone's clock is t2 - t1 plus the PC-side half of the path delay.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ClockSample {
    pub t1: i64,
    pub t2: i64,
    pub t3: i64,
    pub t4: i64,
}

impl ClockSample {
    pub fn rtt_us(&self) -> i64 {
        (self.t4 - self.t1) - (self.t3 - self.t2)
    }

    /// offset so that `phone_us = pc_us + offset` (RFC 5905 shape: `((t2-t1) + (t3-t4)) / 2`,
    /// with the phone's reply assumed to leave as instantly as it arrived: `t2 == t3`).
    pub fn offset_us(&self) -> f64 {
        let up = (self.t2 - self.t1) as f64;
        let down = (self.t4 - self.t3) as f64;
        (up - down) / 2.0
    }
}

/// Keep the sample with the smallest RTT (ALGORITHMS: minimum-RTT sample wins).
#[derive(Default)]
pub struct ClockSync {
    best: Option<ClockSample>,
}

impl ClockSync {
    pub fn new() -> Self {
        Self { best: None }
    }

    pub fn offer(&mut self, s: ClockSample) {
        match self.best {
            Some(b) if b.rtt_us() <= s.rtt_us() => {}
            _ => self.best = Some(s),
        }
    }

    pub fn offset_us(&self) -> Option<f64> {
        self.best.map(|s| s.offset_us())
    }

    pub fn best_rtt_us(&self) -> Option<i64> {
        self.best.map(|s| s.rtt_us())
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn samples(&self) -> usize {
        if self.best.is_some() {
            1
        } else {
            0
        }
    }
}

/// Reject the first bytes of an access unit that are not a start code (defensive).
pub fn annex_b_is_sane(au: &[u8]) -> bool {
    au.len() >= 4 && au[0] == 0 && au[1] == 0 && (au[2] == 1 || (au[2] == 0 && au[3] == 1))
}

/// The maximum a single CH_VIDEO message may be before the writer must chunk it.
#[cfg_attr(not(test), allow(dead_code))] // exercised by the unit tests
pub fn video_message_needs_chunking(msg_len: usize) -> bool {
    msg_len > MAX_FRAME_PAYLOAD
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn video_header_round_trip() {
        let au = vec![0u8, 0, 0, 1, 0x65, 1, 2, 3];
        let wire = pack_video_frame(VFLAG_KEYFRAME, 9, 123_456_789, 987_654_321_000, &au);
        assert_eq!(wire.len(), VIDEO_HEADER_LEN + au.len());
        assert_eq!(wire[0], 1);
        let f = parse_video_frame(&wire).unwrap();
        assert!(f.is_keyframe());
        assert!(!f.is_config());
        assert_eq!(f.seq, 9);
        assert_eq!(f.pts_us, 123_456_789);
        assert_eq!(f.encode_done_ns, 987_654_321_000);
        assert_eq!(f.annex_b, au);
    }

    #[test]
    fn video_header_rejects_short_and_bad_version() {
        assert!(parse_video_frame(&[0u8; VIDEO_HEADER_LEN - 1]).is_err());
        let mut bad = pack_video_frame(0, 0, 0, 0, &[]);
        bad[0] = 7;
        assert!(parse_video_frame(&bad).unwrap_err().contains("version"));
    }

    #[test]
    fn video_header_handles_negative_pts_and_config_flag() {
        let f = parse_video_frame(&pack_video_frame(VFLAG_CONFIG, u32::MAX, -5, -7, &[1, 2])).unwrap();
        assert!(f.is_config());
        assert_eq!(f.seq, u32::MAX);
        assert_eq!(f.pts_us, -5);
        assert_eq!(f.encode_done_ns, -7);
    }

    #[test]
    fn input_round_trip_all_types() {
        let events = vec![
            InputEvent::Move { x: 1920, y: 1080 },
            InputEvent::Button { button: BTN_LEFT, down: true, x: 10, y: 20, t_pc_us: 123456789 },
            InputEvent::Button { button: BTN_LEFT, down: false, x: 10, y: 20, t_pc_us: 123456800 },
            InputEvent::Wheel { delta: -120, x: 1, y: 2, t_pc_us: 42 },
            InputEvent::Key { down: true, vk: 0x41, scan: 0x1E, mods: MOD_SHIFT | MOD_CTRL, t_pc_us: 4294967295 },
            InputEvent::Key { down: false, vk: 0x41, scan: 0x1E, mods: 0, t_pc_us: 0 },
            InputEvent::ReleaseAll,
        ];
        let wire = pack_input_events(&events);
        assert_eq!(wire.len(), 5 + 11 + 11 + 11 + 9 + 9 + 1);
        assert_eq!(parse_input_events(&wire).unwrap(), events);
    }

    #[test]
    fn input_rejects_unknown_and_truncated() {
        assert!(parse_input_events(&[99, 0, 0, 0, 0]).unwrap_err().contains("unknown"));
        assert!(parse_input_events(&[EVT_KEY, 1, 0x41, 0x1E, 0]).unwrap_err().contains("truncated"));
        assert!(parse_input_events(&[EVT_MOVE, 0]).unwrap_err().contains("truncated"));
        assert!(parse_input_events(&[EVT_WHEEL, 0]).unwrap_err().contains("truncated"));
        assert!(parse_input_events(&[EVT_BUTTON, 1]).unwrap_err().contains("truncated"));
    }

    #[test]
    fn empty_input_message_is_empty() {
        assert!(parse_input_events(&[]).unwrap().is_empty());
    }

    // ---- B-047: the receiver asks for video, and only `view` ever does
    #[test]
    fn video_on_constant_matches_the_phone() {
        // Kotlin Phase5Protocol.CTRL_VIDEO_ON must be the same string.
        assert_eq!(CTRL_VIDEO_ON, "video-on");
        assert_eq!(CTRL_VIDEO_ON.as_bytes(), b"video-on");
    }

    #[test]
    fn video_on_pacer_sends_immediately_then_throttles_until_a_frame() {
        let mut p = VideoOnPacer::new();
        assert!(p.due(0, false), "first ask goes out at once");
        assert!(!p.due(1, false));
        assert!(!p.due(VIDEO_ON_RETRY_MS - 1, false));
        assert!(p.due(VIDEO_ON_RETRY_MS, false), "retry after the retry window");
        assert_eq!(p.sends(), 2);
        assert!(p.due(VIDEO_ON_RETRY_MS * 2, false));
        assert_eq!(p.sends(), 3);
    }

    #[test]
    fn video_on_pacer_stops_for_good_once_a_frame_arrived() {
        let mut p = VideoOnPacer::new();
        assert!(p.due(0, false));
        assert!(!p.due(10_000, true), "a frame means video is running");
        // ... and from then on the answer is always no, retry window or not.
        for t in [11_000u64, 20_000, 60_000, 600_000] {
            assert!(!p.due(t, true), "no ask once video flows (t={t})");
        }
        assert_eq!(p.sends(), 1);
    }

    // ---- the Rust and Kotlin test vectors must agree byte for byte
    #[test]
    fn wire_bytes_match_the_kotlin_vectors() {
        // Kotlin Phase5ProtocolTest.inputRoundTripAllEventTypes builds the same list; the
        // 9-byte key event is the shared vector.
        let wire = pack_input_events(&[InputEvent::Key {
            down: true,
            vk: 0x41,
            scan: 0x1E,
            mods: MOD_SHIFT | MOD_CTRL,
            t_pc_us: 4294967295,
        }]);
        assert_eq!(wire, vec![4, 1, 0x41, 0x1E, 0b11, 0xFF, 0xFF, 0xFF, 0xFF]);
        let vf = pack_video_frame(VFLAG_KEYFRAME, 42, 123_456_789, 987_654_321_000, &[0xAA]);
        assert_eq!(
            vf,
            vec![
                1, 1, 0, 0, // version, flags, reserved
                0, 0, 0, 42, // seq
                0, 0, 0, 0, 7, 0x5B, 0xCD, 0x15, // pts 123456789
                0, 0, 0, 0xE5, 0xF4, 0xC8, 0xF3, 0x68, // encode_done 987654321000
                0xAA
            ]
        );
    }

    #[test]
    fn chunk_assembler_joins_and_resets() {
        let mut a = ChunkAssembler::new();
        assert!(a.feed(FLAG_MORE_FOLLOWS, &[1, 2, 3]).unwrap().is_none());
        assert!(a.feed(FLAG_MORE_FOLLOWS, &[4, 5]).unwrap().is_none());
        assert_eq!(a.feed(0, &[6]).unwrap().unwrap(), vec![1, 2, 3, 4, 5, 6]);
        // reusable afterwards
        assert_eq!(a.feed(0, &[9]).unwrap().unwrap(), vec![9]);
    }

    #[test]
    fn chunk_assembler_over_cap_clears() {
        let mut a = ChunkAssembler::new();
        assert!(a.feed(FLAG_MORE_FOLLOWS, &vec![0u8; 600_000]).unwrap().is_none());
        assert!(a.feed(FLAG_MORE_FOLLOWS, &vec![0u8; 600_000]).is_err());
        // buffer was cleared, so a fresh message fits again
        assert_eq!(a.feed(0, &[7]).unwrap().unwrap(), vec![7]);
    }

    #[test]
    fn scaling_maps_corners_and_clamps() {
        assert_eq!(scale_to_desktop(0, 0, 1920, 1080, 1920, 1080), (0, 0));
        assert_eq!(scale_to_desktop(1919, 1079, 1920, 1080, 1920, 1080), (1919, 1079));
        // a 960x540 window stretches onto the 1920x1080 desktop
        assert_eq!(scale_to_desktop(480, 270, 960, 540, 1920, 1080), (960, 540));
        // and a bigger window shrinks the other way
        assert_eq!(scale_to_desktop(1920, 1080, 3840, 2160, 1920, 1080), (960, 540));
        // out-of-range pointers clamp instead of wrapping
        assert_eq!(scale_to_desktop(9999, 9999, 1920, 1080, 1920, 1080), (1919, 1079));
        assert_eq!(scale_to_desktop(-50, -50, 1920, 1080, 1920, 1080), (0, 0));
        assert_eq!(scale_to_desktop(5, 5, 0, 0, 1920, 1080), (0, 0));
    }

    #[test]
    fn letterbox_maps_video_pixels_to_the_desktop() {
        // 1536x960 client: the 16:9 video fits at 1536x864 with a 48 px bar top and bottom.
        assert_eq!(fit_video(1536, 960), (1536, 864));
        // the centre of the video is the centre of the desktop
        assert_eq!(scale_client_to_desktop(768, 480, 1536, 960), (960, 540));
        // a click 48 px lower in the client (the top of the video) is desktop y 0
        assert_eq!(scale_client_to_desktop(0, 48, 1536, 960), (0, 0));
        // clicking the black bar clamps to the desktop edge instead of going negative
        assert_eq!(scale_client_to_desktop(0, 0, 1536, 960), (0, 0));
        // exactly 1:1 when the client is the desktop size
        assert_eq!(scale_client_to_desktop(400, 128, 1920, 1080), (400, 128));
    }

    #[test]
    fn d8_desktop_matches_the_pc_aspect() {
        // The owner's 16:10 screen: a 1728x1080 desktop fills 1920x1200 with a 1.11x
        // scale (the encoder cannot start at 1200 lines on the test phone).
        assert_eq!(desktop_for_client(1920, 1200), (1728, 1080));
        assert_eq!(fit_video_for(1920, 1200, 1728, 1080), (1920, 1200));
        assert_eq!(
            scale_client_to_desktop_for(960, 600, 1920, 1200, 1728, 1080),
            (864, 540)
        );
        // 16:9 stays 1920x1080 (no needless scaling).
        assert_eq!(desktop_for_client(1920, 1080), (1920, 1080));
        assert_eq!(fit_video_for(1920, 1080, 1920, 1080), (1920, 1080));
        // A small 16:10 client still gets the same 16:10 desktop; the receiver downscales.
        assert_eq!(desktop_for_client(1280, 800), (1728, 1080));
        // A 4K client caps the desktop at 1920 wide and keeps 16:9.
        assert_eq!(desktop_for_client(3840, 2160), (1920, 1080));
        // A 3:2 client gets 1620x1080 (and bars in the client are tiny).
        assert_eq!(desktop_for_client(1920, 1280), (1620, 1080));
        // Even dimensions only; junk falls back to the defaults.
        assert_eq!(desktop_for_client(1367, 769), (1920, 1080));
        assert_eq!(desktop_for_client(0, 0), (DESKTOP_W, DESKTOP_H));
    }

    #[test]
    fn mods_bits_match_the_phone() {
        assert_eq!(mods_from_shift(true, false, false, false), MOD_SHIFT);
        assert_eq!(mods_from_shift(false, true, false, false), MOD_CTRL);
        assert_eq!(mods_from_shift(false, false, true, false), MOD_ALT);
        assert_eq!(mods_from_shift(false, false, false, true), MOD_META);
        assert_eq!(mods_from_shift(true, true, true, true), 0b1111);
    }

    #[test]
    fn clock_sample_offset_and_min_rtt_selection() {
        // 2 ms up + 2 ms down (rtt 4 ms), phone clock exactly in step with the PC clock
        let s = ClockSample { t1: 10_000, t2: 12_000, t3: 12_000, t4: 14_000 };
        assert_eq!(s.rtt_us(), 4_000);
        assert!((s.offset_us() - 0.0).abs() < 1e-9);
        // phone clock 1000 us ahead: up = 3000 (theta + d), down = 1000 (d - theta)
        let ahead = ClockSample { t1: 10_000, t2: 13_000, t3: 13_000, t4: 14_000 };
        assert!((ahead.offset_us() - 1_000.0).abs() < 1e-9);
        let mut sync = ClockSync::new();
        assert!(sync.offset_us().is_none());
        sync.offer(ClockSample { t1: 0, t2: 5000, t3: 5000, t4: 30_000 }); // rtt 30000
        sync.offer(s); // rtt 4000 -> wins
        assert_eq!(sync.best_rtt_us().unwrap(), 4_000);
        assert!((sync.offset_us().unwrap() - 0.0).abs() < 1e-9);
        // a worse sample never replaces the best one
        sync.offer(ClockSample { t1: 0, t2: 9000, t3: 9000, t4: 40_000 });
        assert_eq!(sync.best_rtt_us().unwrap(), 4_000);
        assert_eq!(sync.samples(), 1);
    }

    #[test]
    fn rtt_probe_is_echoed_verbatim_and_junk_is_ignored() {
        assert_eq!(pong_for(b"ping 123456").as_deref(), Some(b"pong 123456".as_slice()));
        assert_eq!(pong_for(b"  ping 42  ").as_deref(), Some(b"pong 42".as_slice()));
        assert!(pong_for(b"ping ").is_none());
        assert!(pong_for(b"ping 12x").is_none());
        assert!(pong_for(b"pong 12").is_none());
        assert!(pong_for(b"ping -1").is_none());
    }

    #[test]
    fn annex_b_sanity_and_chunking_threshold() {
        assert!(annex_b_is_sane(&[0, 0, 0, 1, 0x65]));
        assert!(annex_b_is_sane(&[0, 0, 1, 0x65]));
        assert!(!annex_b_is_sane(&[0x65, 1, 2, 3]));
        assert!(!annex_b_is_sane(&[0, 0, 0]));
        assert!(!video_message_needs_chunking(64 * 1024));
        assert!(video_message_needs_chunking(64 * 1024 + 1));
    }
}
