//! A paired session over one TLS 1.3 stream: connect, pair (6-digit compare), silent
//! reconnect, keep-alive handling, and the bench loops (RTT + throughput).

use std::fmt::Write as _;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

use rustls::StreamOwned;

use crate::crypto::{cert_fingerprint, fingerprint_hex, pairing_code, PhoneCertVerifier, ReceiverIdentity, ServerPinMode, TLS_SERVER_NAME};
use crate::protocol::*;

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
pub const PAIR_CONFIRM_TIMEOUT: Duration = Duration::from_secs(300);
pub const BENCH_RTT_SAMPLES: usize = 1000;
pub const BENCH_RTT_PAYLOAD: usize = 64;
pub const BENCH_MESSAGE_LEN: usize = 1 << 20; // 1 MiB logical messages
/// Default in-flight window. The first design used 8 MiB in flight; that deadlocks the
/// adb-forward dev path: the guest TCP queues fill (~1 MB each way) and freeze -- the
/// app blocks writing an echo while the relay blocks writing the next input (verified
/// 2026-10-18 via /proc/net/tcp on the emulator, queues identical minutes apart).
/// 256 KiB stays inside every buffer on that path. `--window-kib` overrides it.
pub const BENCH_WINDOW_BYTES: u64 = 256 << 10;
/// Keep-alive cadence (docs/PHASE4_PROTOCOL.md); every framework callsite logs it, the
/// bench quiescent path messages it from Phase 5.
#[allow(dead_code)]
pub const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(5);
#[allow(dead_code)]
pub const LINK_DEAD_TIMEOUT: Duration = Duration::from_secs(15);

pub struct Session {
    pub tls: StreamOwned<rustls::ClientConnection, TcpStream>,
    pub phone_fp: [u8; 32],
    pub pinned: bool,
}
// phone_fp/pinned are part of the public session API; bin logs them from Phase 5.
impl Session {
    #[allow(dead_code)]
    pub fn describe(&self) -> String {
        format!(
            "phone={} pinned={}",
            &fingerprint_hex(&self.phone_fp)[..16],
            self.pinned
        )
    }
}

fn tls_connect(
    addr: &str,
    identity: &ReceiverIdentity,
    pins: Vec<Vec<u8>>,
) -> Result<StreamOwned<rustls::ClientConnection, TcpStream>, String> {
    let stream = TcpStream::connect_timeout(
        &addr
            .parse()
            .map_err(|e| format!("bad --addr '{addr}': {e}"))?,
        CONNECT_TIMEOUT,
    )
    .map_err(|e| format!("tcp connect {addr}: {e}"))?;
    let _ = stream.set_nodelay(true);
    let mode = if pins.is_empty() {
        ServerPinMode::FirstConnect
    } else {
        ServerPinMode::Pinned(pins)
    };
    let verifier = PhoneCertVerifier::new(mode);
    let cfg = verifier.client_config(identity);
    let name = rustls::pki_types::ServerName::try_from(TLS_SERVER_NAME.to_string())
        .map_err(|e| format!("server name: {e}"))?;
    let conn = rustls::ClientConnection::new(cfg, name)
        .map_err(|e| format!("tls client: {e}"))?;
    // StreamOwned::new is infallible; the handshake completes lazily.
    Ok(StreamOwned::new(conn, stream))
}

/// Read the next protocol frame from the TLS stream (blocking). Frames batched in one
/// network read are held in `pending` so none is lost.
fn next_frame(
    tls: &mut rustls::StreamOwned<rustls::ClientConnection, TcpStream>,
    dec: &mut FrameDecoder,
    pending: &mut std::collections::VecDeque<Message>,
) -> Result<Option<Message>, String> {
    if let Some(m) = pending.pop_front() {
        return Ok(Some(m));
    }
    loop {
        let mut buf = [0u8; 16384];
        let n = tls.read(&mut buf).map_err(|e| match e.kind() {
            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => {
                "stalled: no inbound data within the socket read timeout".to_string()
            }
            _ => format!("tls read: {e}"),
        })?;
        if n == 0 {
            return Ok(None);
        }
        let msgs = dec.feed(&buf[..n]).map_err(|e| format!("protocol: {e}"))?;
        for m in msgs {
            pending.push_back(m);
        }
        if let Some(m) = pending.pop_front() {
            return Ok(Some(m));
        }
    }
}

/// Connect and pair. `label` is stored with the pin on the PC side. Returns the session
/// and a human description of what happened (paired fresh vs reconnected silently).
pub fn connect_and_pair(
    addr: &str,
    identity: &ReceiverIdentity,
    store: &crate::crypto::TrustedPhoneStore,
    label: &str,
) -> Result<(Session, String), String> {
    let pins = store.entries();
    let pinned_fps = pins
        .iter()
        .filter_map(|(f, _, _)| {
            let mut fp = [0u8; 32];
            if f.len() == 64 && f.chars().all(|c| c.is_ascii_hexdigit()) {
                for (i, chunk) in f.as_bytes().chunks(2).enumerate() {
                    fp[i] = u8::from_str_radix(std::str::from_utf8(chunk).unwrap(), 16)
                        .unwrap_or(0);
                }
                Some(fp.to_vec())
            } else {
                None
            }
        })
        .collect();
    let mut tls = tls_connect(addr, identity, pinned_fps)?;

    // complete the TLS handshake explicitly so we can read the peer cert out
    tls.conn
        .complete_io(&mut tls.sock)
        .map_err(|e| format!("tls handshake: {e}"))?;
    let phone_cert = tls
        .conn
        .peer_certificates()
        .and_then(|c| c.first().cloned())
        .map(|c| c.as_ref().to_vec())
        .ok_or("phone presented no certificate")?;
    let fp = cert_fingerprint(&phone_cert);

    let already_pinned = store.find(&fp).is_some();
    let (description, session) = if already_pinned {
        (
            format!(
                "reconnected silently to pinned phone {} ({})",
                &fingerprint_hex(&fp)[..16],
                addr
            ),
            Session {
                tls,
                phone_fp: fp,
                pinned: true,
            },
        )
    } else {
        // first connect: show the 6-digit code and wait for the phone's Yes
        let my_fp = identity.fingerprint();
        let code = pairing_code(&fp, &my_fp);
        println!("PC code:  {code}  <-- MUST match the code on the phone");
        println!("Waiting for Yes on the phone (up to {PAIR_CONFIRM_TIMEOUT:?}) ...");
        let ok = wait_pair_confirm(&mut tls)?;
        if !ok {
            return Err("phone answered No; nothing was stored".to_string());
        }
        store.remember(&fp, label);
        println!("Phone confirmed. Pin saved to {}", store.path().display());
        (
            format!("paired with phone {} ({addr})", &fingerprint_hex(&fp)[..16]),
            Session {
                tls,
                phone_fp: fp,
                pinned: true,
            },
        )
    };
    Ok((session, description))
}

/// Pump control/keep-alive frames until a pair-ok or pair-no arrives on the control
/// channel; echo keep-alive requests while waiting. The phone sends its own control
/// keep-alives, so a frame (and the deadline check) returns at least every few seconds.
fn wait_pair_confirm(
    tls: &mut StreamOwned<rustls::ClientConnection, TcpStream>,
) -> Result<bool, String> {
    let deadline = Instant::now() + PAIR_CONFIRM_TIMEOUT;
    let mut dec = FrameDecoder::new();
    let mut pending = std::collections::VecDeque::new();
    loop {
        if Instant::now() > deadline {
            return Err("pair confirm timed out".to_string());
        }
        match next_frame(tls, &mut dec, &mut pending) {
            Ok(Some(m)) => {
                if m.channel == CH_CONTROL {
                    if m.payload == b"pair-ok" {
                        return Ok(true);
                    }
                    if m.payload == b"pair-no" {
                        return Ok(false);
                    }
                    if m.payload.is_empty() {
                        echo_keepalive(tls);
                    }
                }
            }
            Ok(None) => return Err("phone closed the connection while pairing".into()),
            Err(e) => return Err(e),
        }
    }
}

fn echo_keepalive(tls: &mut StreamOwned<rustls::ClientConnection, TcpStream>) {
    let _ = write_frame(tls, CH_CONTROL, FLAG_KEEPALIVE_ECHO, &[]);
}

// ------------------------------------------------------------------------- bench

#[derive(Debug, Clone, Copy)]
pub struct Stats {
    pub p50_ms: f64,
    pub p95_ms: f64,
    pub p99_ms: f64,
    pub min_ms: f64,
    pub max_ms: f64,
}

pub fn percentiles(mut samples_ms: Vec<f64>) -> Stats {
    if samples_ms.is_empty() {
        return Stats {
            p50_ms: 0.0,
            p95_ms: 0.0,
            p99_ms: 0.0,
            min_ms: 0.0,
            max_ms: 0.0,
        };
    }
    samples_ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let at = |frac: f64| -> f64 {
        let idx = ((samples_ms.len() as f64) * frac).round() as usize;
        samples_ms[idx.min(samples_ms.len() - 1)]
    };
    Stats {
        p50_ms: at(0.50),
        p95_ms: at(0.95),
        p99_ms: at(0.99),
        min_ms: samples_ms[0],
        max_ms: samples_ms[samples_ms.len() - 1],
    }
}

pub struct BenchResult {
    pub rtt: Stats,
    pub mb_per_s: f64,
    pub seconds: u64,
    pub echoed_bytes: u64,
}

impl BenchResult {
    pub fn render(&self, link_desc: &str) -> String {
        let mut s = String::new();
        let _ = writeln!(s, "=== BENCH ({link_desc}) ===");
        let _ = writeln!(
            s,
            "RTT ({} samples, {} B per sample): p50 {:.2} ms | p95 {:.2} ms | p99 {:.2} ms | min {:.2} | max {:.2}",
            BENCH_RTT_SAMPLES, BENCH_RTT_PAYLOAD, self.rtt.p50_ms, self.rtt.p95_ms, self.rtt.p99_ms, self.rtt.min_ms, self.rtt.max_ms
        );
        let _ = writeln!(
            s,
            "Throughput: {:.2} MB/s over {} s ({} MB echoed)",
            self.mb_per_s,
            self.seconds,
            self.echoed_bytes / (1024 * 1024)
        );
        s
    }
}

pub fn bench(
    session: &mut Session,
    seconds: u64,
    window_bytes: u64,
) -> Result<BenchResult, String> {
    // ---- RTT: 1000 small pings, echo per frame
    let mut samples = Vec::with_capacity(BENCH_RTT_SAMPLES);
    {
        let tls = &mut session.tls;
        let mut dec = FrameDecoder::new();
        let mut pending = std::collections::VecDeque::new();
        for _ in 0..BENCH_RTT_SAMPLES {
            let payload = vec![0u8; BENCH_RTT_PAYLOAD];
            let t0 = Instant::now();
            write_frame(tls, CH_BENCH, 0, &payload)?;
            // read until the echo arrives (skip keep-alive/controls)
            loop {
                let m = next_frame(tls, &mut dec, &mut pending)?
                    .ok_or("phone closed during RTT bench")?;
                if m.channel == CH_BENCH && m.payload.len() == BENCH_RTT_PAYLOAD {
                    break;
                }
                if m.channel == CH_CONTROL && m.payload.is_empty() {
                    echo_keepalive(tls);
                }
            }
            samples.push(t0.elapsed().as_secs_f64() * 1000.0);
        }
    }
    let rtt = percentiles(samples);

    // ---- Throughput: 1 MiB logical messages, chunked to 64 KiB by the writer,
    //      chunk-echoed by the phone, window-limited so the phone can keep up.
    let deadline = Instant::now() + Duration::from_secs(seconds);
    let mut echoed_bytes: u64 = 0;
    let mut dec = FrameDecoder::new();
    let mut pending = std::collections::VecDeque::new();
    {
        let tls = &mut session.tls;
        let send_start = Instant::now();
        let mut in_flight: u64 = 0;
        let mut seq: u64 = 0;
        loop {
            let now = Instant::now();
            if now >= deadline && in_flight == 0 {
                break;
            }
            if in_flight < window_bytes && now < deadline {
                let payload = message_for(seq);
                for chunk in payload.chunks(MAX_FRAME_PAYLOAD) {
                    let frame = encode_frame(CH_BENCH, 0, chunk).map_err(|e| e.to_string())?;
                    tls.write_all(&frame).map_err(|e| format!("bench write: {e}"))?;
                    in_flight += chunk.len() as u64;
                }
                tls.flush().map_err(|e| format!("bench flush: {e}"))?;
                seq += 1;
                continue; // keep pumping before reading (window allows pipelining)
            }
            if in_flight > 0 {
                let m = next_frame(tls, &mut dec, &mut pending)?
                    .ok_or("phone closed during throughput")?;
                if m.channel == CH_BENCH {
                    echoed_bytes += m.payload.len() as u64;
                    in_flight -= m.payload.len() as u64;
                } else if m.channel == CH_CONTROL && m.payload.is_empty() {
                    echo_keepalive(tls);
                }
                continue;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = tls.flush();
        let elapsed = send_start.elapsed().as_secs_f64().max(0.001);
        Ok(BenchResult {
            rtt,
            mb_per_s: (echoed_bytes as f64 / (1024.0 * 1024.0)) / elapsed,
            seconds,
            echoed_bytes,
        })
    }
}

fn message_for(seq: u64) -> Vec<u8> {
    // deterministic pattern to allow corruption checks later if needed
    let mut v = Vec::with_capacity(BENCH_MESSAGE_LEN);
    for i in 0..BENCH_MESSAGE_LEN {
        let b = ((seq as usize).wrapping_mul(0x9E3779B9) ^ i) as u8;
        v.push(b);
    }
    v
}

fn write_frame(
    tls: &mut rustls::StreamOwned<rustls::ClientConnection, TcpStream>,
    channel: u8,
    flags: u8,
    payload: &[u8],
) -> Result<(), String> {
    let frame = encode_frame(channel, flags, payload).map_err(|e| e.to_string())?;
    tls.write_all(&frame).map_err(|e| format!("write: {e}"))?;
    tls.flush().map_err(|e| format!("flush: {e}"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percentiles_basic() {
        let s: Vec<f64> = (0..100).map(|x| x as f64).collect();
        let st = percentiles(s);
        assert_eq!(st.p50_ms, 50.0);
        assert_eq!(st.p95_ms, 95.0);
        assert_eq!(st.p99_ms, 99.0);
        assert_eq!(st.min_ms, 0.0);
        assert_eq!(st.max_ms, 99.0);
    }

    #[test]
    fn percentiles_empty_and_single() {
        let st = percentiles(Vec::new());
        assert_eq!(st.p50_ms, 0.0);
        let st1 = percentiles(vec![7.5]);
        assert_eq!(st1.p50_ms, 7.5);
        assert_eq!(st1.p99_ms, 7.5);
    }

    #[test]
    fn bench_result_render_has_numbers() {
        let r = BenchResult {
            rtt: percentiles(vec![1.0, 2.0]),
            mb_per_s: 42.5,
            seconds: 5,
            echoed_bytes: 5_000,
        };
        let s = r.render("unit-test");
        assert!(s.contains("p50"));
        assert!(s.contains("42.50"));
    }
}
