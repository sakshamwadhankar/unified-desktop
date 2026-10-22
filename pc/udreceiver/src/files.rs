//! Phase 11 D-d: file transfer over CH_FILE. One logical message per 64 KiB wire frame
//! (no MORE_FOLLOWS assembly needed on the phone):
//!   START: [1][u32 id][u64 size][u16 name_len][name][32-byte SHA-256]
//!   DATA:  [2][u32 id][bytes]           (at most 65528 data bytes)
//!   END:   [3][u32 id]
//! The receiver streams into a `.part` file, verifies the SHA-256, then renames to a name
//! that never overwrites (it adds " (2)"). Names are reduced to a basename.

use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

/// One DATA frame stays inside the 64 KiB wire cap (8-byte header + 8-byte file header left).
pub const MAX_DATA: usize = 65536 - 8;

pub fn encode_start(id: u32, size: u64, name: &str, sha: &[u8; 32]) -> Vec<u8> {
    let n = name.as_bytes();
    let mut v = Vec::with_capacity(1 + 4 + 8 + 2 + n.len() + 32);
    v.push(1);
    v.extend_from_slice(&id.to_be_bytes());
    v.extend_from_slice(&size.to_be_bytes());
    v.extend_from_slice(&(n.len() as u16).to_be_bytes());
    v.extend_from_slice(n);
    v.extend_from_slice(sha);
    v
}

pub fn encode_data(id: u32, data: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(5 + data.len());
    v.push(2);
    v.extend_from_slice(&id.to_be_bytes());
    v.extend_from_slice(data);
    v
}

pub fn encode_end(id: u32) -> Vec<u8> {
    let mut v = Vec::with_capacity(5);
    v.push(3);
    v.extend_from_slice(&id.to_be_bytes());
    v
}

pub enum FileMsg<'a> {
    Start { id: u32, size: u64, name: String, sha: [u8; 32] },
    Data { id: u32, data: &'a [u8] },
    End { id: u32 },
}

pub fn parse(msg: &[u8]) -> Result<FileMsg<'_>, String> {
    if msg.len() < 5 {
        return Err("file message too short".into());
    }
    let id = u32::from_be_bytes([msg[1], msg[2], msg[3], msg[4]]);
    match msg[0] {
        1 => {
            if msg.len() < 1 + 4 + 8 + 2 + 32 {
                return Err("short start".into());
            }
            let size = u64::from_be_bytes(msg[5..13].try_into().map_err(|_| "size")?);
            let nl = u16::from_be_bytes([msg[13], msg[14]]) as usize;
            let end = 15 + nl + 32;
            if msg.len() < end {
                return Err("short name/sha".into());
            }
            let name = String::from_utf8_lossy(&msg[15..15 + nl]).to_string();
            let mut sha = [0u8; 32];
            sha.copy_from_slice(&msg[15 + nl..end]);
            Ok(FileMsg::Start { id, size, name, sha })
        }
        2 => Ok(FileMsg::Data { id, data: &msg[5..] }),
        3 => Ok(FileMsg::End { id }),
        other => Err(format!("unknown file message {other}")),
    }
}

/// Basename only, no path separators, no ".." - the wire must not choose a directory.
pub fn safe_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("file");
    let cleaned: String = base.chars().filter(|c| !c.is_control()).collect();
    if cleaned.is_empty() || cleaned == "." || cleaned == ".." {
        "file".to_string()
    } else {
        cleaned
    }
}

/// `dir/name`, `dir/name (2)`, `dir/name (3)` ... - never overwrites.
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let p = dir.join(name);
    if !p.exists() {
        return p;
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s.to_string(), format!(".{e}")),
        _ => (name.to_string(), String::new()),
    };
    for n in 2..10_000 {
        let cand = dir.join(format!("{stem} ({n}){ext}"));
        if !cand.exists() {
            return cand;
        }
    }
    dir.join(format!("{stem} (overflow){ext}"))
}

pub struct Received {
    pub path: PathBuf,
    pub bytes: u64,
    pub sha_ok: bool,
}

struct Incoming {
    id: u32,
    size: u64,
    name: String,
    sha: [u8; 32],
    part: PathBuf,
    file: File,
    ctx: ring::digest::Context,
    got: u64,
}

/// Receives one file at a time into `dir`. Used by both ends (the PC saves to Downloads,
/// the phone saves to its desktop folder).
pub struct FileSink {
    dir: PathBuf,
    current: Option<Incoming>,
    pub completed: u64,
}

impl FileSink {
    pub fn new(dir: PathBuf) -> Result<Self, String> {
        std::fs::create_dir_all(&dir).map_err(|e| format!("mkdir {dir:?}: {e}"))?;
        Ok(FileSink { dir, current: None, completed: 0 })
    }

    pub fn on_message(&mut self, msg: &[u8]) -> Result<Option<Received>, String> {
        match parse(msg)? {
            FileMsg::Start { id, size, name, sha } => {
                let name = safe_name(&name);
                let part = self.dir.join(format!(".{id}.part"));
                let file = File::create(&part).map_err(|e| format!("create {part:?}: {e}"))?;
                self.current = Some(Incoming {
                    id,
                    size,
                    name,
                    sha,
                    part,
                    file,
                    ctx: ring::digest::Context::new(&ring::digest::SHA256),
                    got: 0,
                });
                Ok(None)
            }
            FileMsg::Data { id, data } => {
                let Some(cur) = self.current.as_mut() else { return Err("data before start".into()) };
                if cur.id != id {
                    return Err(format!("data for id {id}, current {}", cur.id));
                }
                cur.file.write_all(data).map_err(|e| format!("write: {e}"))?;
                cur.ctx.update(data);
                cur.got += data.len() as u64;
                Ok(None)
            }
            FileMsg::End { id } => {
                let Some(cur) = self.current.take() else { return Err("end before start".into()) };
                if cur.id != id {
                    return Err(format!("end for id {id}, current {}", cur.id));
                }
                cur.file.sync_all().map_err(|e| format!("sync: {e}"))?;
                drop(cur.file);
                let digest = cur.ctx.finish();
                let sha_ok = digest.as_ref() == cur.sha.as_slice();
                let size_ok = cur.got == cur.size;
                if !sha_ok || !size_ok {
                    let _ = std::fs::remove_file(&cur.part);
                    return Err(format!(
                        "verify failed: got {} / {} bytes, sha_ok={sha_ok}",
                        cur.got, cur.size
                    ));
                }
                let final_path = unique_path(&self.dir, &cur.name);
                std::fs::rename(&cur.part, &final_path)
                    .map_err(|e| format!("rename {final_path:?}: {e}"))?;
                self.completed += 1;
                Ok(Some(Received { path: final_path, bytes: cur.got, sha_ok }))
            }
        }
    }
}

/// SHA-256 of a file on disk (the sender's header).
pub fn file_sha256(path: &Path) -> Result<[u8; 32], String> {
    let mut f = File::open(path).map_err(|e| format!("open {path:?}: {e}"))?;
    let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf).map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            break;
        }
        ctx.update(&buf[..n]);
    }
    let d = ctx.finish();
    let mut out = [0u8; 32];
    out.copy_from_slice(d.as_ref());
    Ok(out)
}

/// Stream a file as CH_FILE messages through `push` (called once per message).
pub fn send_file(
    id: u32,
    path: &Path,
    push: &mut dyn FnMut(Vec<u8>) -> Result<(), String>,
) -> Result<(u64, [u8; 32]), String> {
    let meta = std::fs::metadata(path).map_err(|e| format!("stat {path:?}: {e}"))?;
    if !meta.is_file() {
        return Err("not a file".into());
    }
    let sha = file_sha256(path)?;
    let name = path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".into());
    push(encode_start(id, meta.len(), &name, &sha))?;
    let mut f = File::open(path).map_err(|e| format!("open: {e}"))?;
    let mut buf = vec![0u8; MAX_DATA];
    let mut total = 0u64;
    loop {
        let n = f.read(&mut buf).map_err(|e| format!("read: {e}"))?;
        if n == 0 {
            break;
        }
        push(encode_data(id, &buf[..n]))?;
        total += n as u64;
    }
    push(encode_end(id))?;
    Ok((total, sha))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("ud-p11-files-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn messages_roundtrip_and_reject_garbage() {
        let sha = [7u8; 32];
        let s = encode_start(9, 12345, "a.txt", &sha);
        match parse(&s).unwrap() {
            FileMsg::Start { id, size, name, sha: got } => {
                assert_eq!((id, size, name.as_str(), got), (9, 12345, "a.txt", sha));
            }
            _ => panic!("not a start"),
        }
        assert!(matches!(parse(&encode_data(9, b"hi")).unwrap(), FileMsg::Data { id: 9, .. }));
        assert!(matches!(parse(&encode_end(9)).unwrap(), FileMsg::End { id: 9 }));
        assert!(parse(b"").is_err());
        assert!(parse(&[9, 0, 0, 0, 0]).is_err());
    }

    #[test]
    fn names_cannot_escape_the_directory() {
        assert_eq!(safe_name("../../etc/passwd"), "passwd");
        assert_eq!(safe_name("C:\\Windows\\x.dll"), "x.dll");
        assert_eq!(safe_name(".."), "file");
    }

    #[test]
    fn unique_path_never_overwrites() {
        let d = tmp("unique");
        std::fs::write(d.join("a.txt"), b"1").unwrap();
        assert_eq!(unique_path(&d, "a.txt").file_name().unwrap(), "a (2).txt");
        std::fs::write(d.join("a (2).txt"), b"2").unwrap();
        assert_eq!(unique_path(&d, "a.txt").file_name().unwrap(), "a (3).txt");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn twenty_megabyte_file_arrives_with_matching_sha() {
        let src_dir = tmp("src");
        let dst_dir = tmp("dst");
        // Deterministic pseudo-random bytes, generated here (never the owner's files).
        let mut data = vec![0u8; 20 * 1024 * 1024];
        let mut x: u32 = 0x1234_5678;
        for b in data.iter_mut() {
            x = x.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            *b = (x >> 24) as u8;
        }
        let src = src_dir.join("twenty.bin");
        std::fs::write(&src, &data).unwrap();
        let mut sink = FileSink::new(dst_dir.clone()).unwrap();
        let mut frames = 0usize;
        let (sent, sha) = send_file(42, &src, &mut |msg| {
            frames += 1;
            assert!(msg.len() <= 65536);
            if let Some(r) = sink.on_message(&msg)? {
                assert!(r.sha_ok);
            }
            Ok(())
        })
        .unwrap();
        assert_eq!(sent, data.len() as u64);
        assert_eq!(file_sha256(&src).unwrap(), sha);
        let saved = std::fs::read(dst_dir.join("twenty.bin")).unwrap();
        assert_eq!(saved.len(), data.len());
        assert_eq!(ring::digest::digest(&ring::digest::SHA256, &saved).as_ref(), sha.as_slice());
        assert!(frames > 300, "20 MB in 64 KiB frames: {frames}");
        let _ = std::fs::remove_dir_all(src_dir);
        let _ = std::fs::remove_dir_all(dst_dir);
    }

    #[test]
    fn corrupt_transfer_is_rejected_and_leaves_no_file() {
        let d = tmp("corrupt");
        let mut sink = FileSink::new(d.clone()).unwrap();
        let sha = [1u8; 32];
        sink.on_message(&encode_start(1, 4, "x.bin", &sha)).unwrap();
        sink.on_message(&encode_data(1, b"abcd")).unwrap();
        assert!(sink.on_message(&encode_end(1)).is_err(), "wrong sha must fail");
        assert!(!d.join("x.bin").exists());
        let _ = std::fs::remove_dir_all(&d);
    }
}
