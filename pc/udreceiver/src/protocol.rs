//! Wire framing and channel-priority writer (docs/PHASE4_PROTOCOL.md).
//!
//! Frame: [u8 version][u8 channel][u8 flags][u8 reserved][u32 BE payload len] + payload.
//! On-the-wire payload cap = MAX_FRAME_PAYLOAD (64 KiB, "one chunk"); larger logical
//! messages are split with the MORE_FOLLOWS flag, and the priority writer emits at most
//! one chunk per step so a small frame never waits behind a whole large message.

use std::collections::VecDeque;
use std::io::{self, Write};

pub const PROTOCOL_VERSION: u8 = 1;
pub const HEADER_LEN: usize = 8;
pub const MAX_FRAME_PAYLOAD: usize = 65536;
pub const MAX_MESSAGE_LEN: usize = 1 << 20; // 1 MiB logical message cap this phase

pub const CH_CONTROL: u8 = 1;
pub const CH_INPUT: u8 = 2;
pub const CH_VIDEO: u8 = 3;
pub const CH_BENCH: u8 = 4;

pub const FLAG_KEEPALIVE_ECHO: u8 = 0b0000_0001; // empty keep-alive echo request
// Part of the Phase-4 wire format from day one; first exercised by the pure-Rust
// bench/priority tests, only becomes bin-live with Phase-5 video/pc stream joining.
pub const FLAG_MORE_FOLLOWS: u8 = 0b0000_0010; // logical message continues

/// Strict priority: input > control > video > bench (OPTIMIZATION 5).
pub const CHANNEL_PRIORITY: [u8; 4] = [CH_INPUT, CH_CONTROL, CH_VIDEO, CH_BENCH];

#[allow(dead_code)] // diagnostics/log lines (bin CLI); session logging wires this later
pub fn channel_name(ch: u8) -> &'static str {
    #[allow(clippy::match_same_arms)]
    match ch {
        CH_CONTROL => "control",
        CH_INPUT => "input",
        CH_VIDEO => "video",
        CH_BENCH => "bench",
        _ => "unknown",
    }
}

/// One queued logical message: a named channel plus complete payload bytes.
/// `offset` tracks how many bytes already went out (the front queue entry resumes from
/// there, which keeps same-channel FIFO and lets higher-priority channels preempt
/// between any two chunks).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub channel: u8,
    pub payload: Vec<u8>,
    pub offset: usize,
}

impl Message {
    pub fn new(channel: u8, payload: Vec<u8>) -> Result<Self, &'static str> {
        if !matches!(channel, CH_CONTROL | CH_INPUT | CH_VIDEO | CH_BENCH) {
            return Err("unknown channel");
        }
        if payload.len() > MAX_MESSAGE_LEN {
            return Err("message too large");
        }
        Ok(Message {
            channel,
            payload,
            offset: 0,
        })
    }
}

/// Encode one frame into `out`.
pub fn encode_frame(channel: u8, flags: u8, payload: &[u8]) -> Result<Vec<u8>, String> {
    if !matches!(channel, CH_CONTROL | CH_INPUT | CH_VIDEO | CH_BENCH) {
        return Err(format!("unknown channel {channel}"));
    }
    if payload.len() > MAX_FRAME_PAYLOAD {
        return Err(format!("payload {} > chunk cap", payload.len()));
    }
    let mut buf = Vec::with_capacity(HEADER_LEN + payload.len());
    buf.push(PROTOCOL_VERSION);
    buf.push(channel);
    buf.push(flags);
    buf.push(0);
    buf.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    buf.extend_from_slice(payload);
    Ok(buf)
}

/// Statefully parse frames from a growing adapter over a byte stream.
pub struct FrameDecoder {
    buf: Vec<u8>,
    max_payload: usize,
}

impl FrameDecoder {
    pub fn new() -> Self {
        FrameDecoder {
            buf: Vec::new(),
            max_payload: MAX_FRAME_PAYLOAD,
        }
    }

    /// Append freshly received bytes; returns every frame fully consumed.
    pub fn feed(&mut self, bytes: &[u8]) -> Result<Vec<Message>, String> {
        self.buf.extend_from_slice(bytes);
        let mut out = Vec::new();
        loop {
            if self.buf.len() < HEADER_LEN {
                break;
            }
            let version = self.buf[0];
            let channel = self.buf[1];
            let _flags = self.buf[2];
            let len = u32::from_be_bytes([self.buf[4], self.buf[5], self.buf[6], self.buf[7]])
                as usize;
            if version != PROTOCOL_VERSION {
                return Err(format!("protocol version {version}, expected {PROTOCOL_VERSION}"));
            }
            if len > self.max_payload {
                return Err(format!("payload {len} exceeds chunk cap {}", self.max_payload));
            }
            if self.buf.len() < HEADER_LEN + len {
                break;
            }
            let payload = self.buf[HEADER_LEN..HEADER_LEN + len].to_vec();
            self.buf.drain(..HEADER_LEN + len);
            match Message::new(channel, payload) {
                Ok(m) => out.push(m),
                Err(e) => return Err(e.to_string()),
            }
        }
        Ok(out)
    }
}

/// Per-channel chunked writer that always emits ONE frame per call: the highest-priority
/// channel waiting, and its next 64 KiB chunk (re-queuing the remainder of an unfinished
/// message so a newly arrived small frame on a higher-priority channel goes out next).
pub struct PriorityWriter<W: Write> {
    inner: W,
    /// In-progress messages stay at their queue FRONT; the priority decision is
    /// re-taken before every chunk, so a newly arrived higher-priority frame preempts
    /// between any two chunks and same-channel FIFO is preserved.
    queues: [VecDeque<Message>; 5], // index = channel id
}

impl<W: Write> PriorityWriter<W> {
    #[allow(dead_code)] // used by this module's tests; the session pumps via emit_one()
    pub fn new(inner: W) -> Self {
        PriorityWriter {
            inner,
            queues: [
                VecDeque::new(),
                VecDeque::new(),
                VecDeque::new(),
                VecDeque::new(),
                VecDeque::new(),
            ],
        }
    }

    #[allow(dead_code)] // tests + the upcoming video path queue frames through this
    pub fn push(&mut self, msg: Message) -> Result<(), &'static str> {
        if !matches!(msg.channel, CH_CONTROL | CH_INPUT | CH_VIDEO | CH_BENCH) {
            return Err("unknown channel");
        }
        if msg.payload.len() > MAX_MESSAGE_LEN {
            return Err("message too large");
        }
        self.queues[msg.channel as usize].push_back(msg);
        Ok(())
    }

    #[allow(dead_code)] // PriorityWriter API completeness; session pumps via emit_one()
    pub fn is_empty(&self) -> bool {
        self.queues.iter().all(|q| q.is_empty())
    }

    /// Emit one frame: the highest-priority channel, one chunk (<= 64 KiB) per call.
    pub fn emit_one(&mut self) -> io::Result<Option<usize>> {
        for &ch in CHANNEL_PRIORITY.iter() {
            let queue = &mut self.queues[ch as usize];
            let frame = {
                let Some(m) = queue.front() else { continue };
                let start = m.offset;
                let end = (m.offset + MAX_FRAME_PAYLOAD).min(m.payload.len());
                let flags = if end < m.payload.len() {
                    FLAG_MORE_FOLLOWS
                } else {
                    0
                };
                encode_frame(ch, flags, &m.payload[start..end]).map_err(io::Error::other)?
            };
            let n = self.inner.write(&frame)?;
            if n != frame.len() {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "short frame write",
                ));
            }
            let chunk_len = frame.len() - HEADER_LEN;
            let m = queue.front_mut().expect("front still exists");
            m.offset += chunk_len;
            if m.offset == m.payload.len() {
                queue.pop_front();
            }
            return Ok(Some(frame.len()));
        }
        Ok(None)
    }

    /// Emit until empty; flush at the end.
    #[allow(dead_code)] // kept for one-shot drains; the session loops on emit_one()
    pub fn emit_all(&mut self) -> io::Result<usize> {
        let mut total = 0;
        while let Some(n) = self.emit_one()? {
            total += n;
        }
        self.inner.flush()?;
        Ok(total)
    }

    #[allow(dead_code)] // access to the inner writer (flush/stat probes)
    pub fn get_mut(&mut self) -> &mut W {
        &mut self.inner
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode_messages(bytes: &[u8]) -> Vec<Message> {
        let mut dec = FrameDecoder::new();
        dec.feed(bytes).unwrap()
    }

    #[test]
    fn frame_roundtrip() {
        let f = encode_frame(CH_INPUT, 0, b"hello").unwrap();
        assert_eq!(f.len(), HEADER_LEN + 5);
        assert_eq!(f[0], PROTOCOL_VERSION);
        assert_eq!(f[1], CH_INPUT);
        assert_eq!((f[4] as u32) << 24 | (f[5] as u32) << 16 | (f[6] as u32) << 8 | f[7] as u32, 5);
        let msgs = decode_messages(&f);
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].channel, CH_INPUT);
        assert_eq!(msgs[0].payload, b"hello");
    }

    #[test]
    fn rejects_oversize_and_unknown_channel() {
        let big = vec![0u8; MAX_FRAME_PAYLOAD + 1];
        assert!(encode_frame(CH_VIDEO, 0, &big).is_err());
        let mut dec = FrameDecoder::new();
        let mut raw = encode_frame(CH_VIDEO, 0, &[0u8; 16]).unwrap();
        raw[4] = 0xFF; // corrupt length to 0xFF100000+ ...
        raw[5] = 0x00; // len = 0xFF00_0010
        assert!(dec.feed(&raw).is_err());
        assert!(Message::new(9, vec![1, 2, 3]).is_err());
    }

    #[test]
    fn large_message_is_chunked_with_more_follows_flag() {
        // 3 chunks: 64k, 64k, 17 bytes
        let msg = vec![0xABu8; MAX_FRAME_PAYLOAD * 2 + 17];
        let mut pw: PriorityWriter<Vec<u8>> = PriorityWriter::new(Vec::new());
        pw.push(Message::new(CH_VIDEO, msg).unwrap()).unwrap();
        while pw.emit_one().unwrap().is_some() {}
        let wire = std::mem::take(pw.get_mut());
        let msgs = decode_messages(&wire);
        assert_eq!(msgs.len(), 3);
        let mut rejoined = Vec::new();
        for (i, m) in msgs.iter().enumerate() {
            assert_eq!(m.channel, CH_VIDEO);
            assert!(m.payload.len() <= MAX_FRAME_PAYLOAD);
            let is_last = i == msgs.len() - 1;
            assert!(is_last || m.payload.len() == MAX_FRAME_PAYLOAD);
            rejoined.extend_from_slice(&m.payload);
        }
        assert_eq!(rejoined.len(), MAX_FRAME_PAYLOAD * 2 + 17);
        assert!(rejoined.iter().all(|&b| b == 0xAB));
    }

    #[test]
    fn input_frame_queued_behind_large_bench_message_waits_at_most_one_chunk() {
        // The manager's required test, modeled as the real concurrency pattern: a 1 MiB
        // bench logical message is being drained chunk by chunk, and the input frame
        // arrives DURING that drain (between two emit calls). The input frame must hit
        // the wire before the SECOND bench chunk - i.e. it waits at most one 64 KiB chunk
        // behind the big message.
        let mut writer_state: PriorityWriter<WriteCollector> =
            PriorityWriter::new(WriteCollector::default());
        writer_state
            .push(Message::new(CH_BENCH, vec![7u8; MAX_MESSAGE_LEN]).unwrap())
            .unwrap();
        // chunk 1 of the bench message leaves the writer...
        writer_state.emit_one().unwrap();
        // ...and now the input frame arrives (new message from the high-priority queue)
        writer_state.push(Message::new(CH_INPUT, vec![1, 2, 3]).unwrap()).unwrap();
        while writer_state.emit_one().unwrap().is_some() {}
        let collector = std::mem::take(writer_state.get_mut());
        let frames = collector.frames;
        assert!(frames.len() >= 3);
        // every writer write() call carries exactly one frame (version byte first)
        for f in &frames {
            assert_eq!(f[0], PROTOCOL_VERSION);
        }
        assert_eq!(frames[0][1], CH_BENCH);
        assert_eq!(
            u32::from_be_bytes([frames[0][4], frames[0][5], frames[0][6], frames[0][7]]) as usize,
            MAX_FRAME_PAYLOAD,
            "first frame is one full chunk"
        );
        assert_ne!(frames[0][2] & FLAG_MORE_FOLLOWS, 0, "bench continues");
        assert_eq!(frames[1][1], CH_INPUT, "input goes out before the next bench chunk");
        assert_eq!(&frames[1][8..], &[1, 2, 3]);
        // the remaining bench chunks by MESSAGE count
        let bench_chunks = frames.iter().filter(|f| f[1] == CH_BENCH).count();
        assert_eq!(bench_chunks, MAX_MESSAGE_LEN.div_ceil(MAX_FRAME_PAYLOAD));
        // and the bench message resumes AFTER the input frame, in order (4 chunks per
        // MiB == 16; its final frame has no MORE_FOLLOWS)
        let last_bench = frames.iter().rfind(|f| f[1] == CH_BENCH).unwrap();
        assert_eq!(last_bench[2] & FLAG_MORE_FOLLOWS, 0, "last bench chunk is final");
    }

    #[test]
    fn strict_priority_across_arrivals() {
        // With all messages queued BEFORE the drain starts, pure priority wins: input,
        // control, video, bench; a same-channel message keeps FIFO order.
        let mut pw: PriorityWriter<WriteCollector> = PriorityWriter::new(WriteCollector::default());
        pw.push(Message::new(CH_BENCH, vec![4]).unwrap()).unwrap();
        pw.push(Message::new(CH_CONTROL, vec![3]).unwrap()).unwrap();
        pw.push(Message::new(CH_INPUT, vec![1]).unwrap()).unwrap();
        pw.push(Message::new(CH_INPUT, vec![2]).unwrap()).unwrap();
        while pw.emit_one().unwrap().is_some() {}
        let frames = std::mem::take(pw.get_mut()).frames;
        let payloads: Vec<Vec<u8>> = frames.iter().map(|f| f[8..].to_vec()).collect();
        assert_eq!(payloads, vec![vec![1], vec![2], vec![3], vec![4]]);
    }

    /// Write collector that records one frame per write() call (the writer performs
    /// exactly one write() per emitted frame).
    #[derive(Default)]
    struct WriteCollector {
        frames: Vec<Vec<u8>>,
    }
    impl Write for WriteCollector {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.frames.push(buf.to_vec());
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
}
