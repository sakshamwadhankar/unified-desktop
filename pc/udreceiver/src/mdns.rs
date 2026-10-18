//! Hand-written, std-only mDNS browse for the phone's NSD service `_udlink._tcp.`
//! (manager 21:55: item 7 needs discovery; written by hand so no new dependency and no
//! THIRD_PARTY row).
//!
//! Sends a PTR question for `_udlink._tcp.local` with the unicast-response bit (QU) to
//! 224.0.0.251:5353 and listens. The socket first tries to bind port 5353 so it also
//! hears multicast answers from responders that ignore QU; if the port is taken
//! (another mDNS daemon) it falls back to an ephemeral port and relies on QU. Answers
//! missing the SRV/A records are followed up with direct questions.
//!
//! `parse_answers`/`resolve` are pure and unit-tested against a synthetic packet
//! (compression pointers included); the real proof is item 7 on the phone.

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddrV4, UdpSocket};
use std::time::{Duration, Instant};

pub const SERVICE: &str = "_udlink._tcp.local";
const MDNS: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);
const PORT: u16 = 5353;

#[derive(Debug, Clone, PartialEq)]
pub struct Found {
    pub instance: String,
    pub host: String,
    pub ip: String,
    pub port: u16,
}

#[derive(Default)]
struct Recs {
    ptr: Vec<String>,
    srv: HashMap<String, (u16, String)>,
    a: HashMap<String, String>,
}

fn push_name(p: &mut Vec<u8>, name: &str) {
    for label in name.split('.') {
        if label.is_empty() {
            continue;
        }
        p.push(label.len() as u8);
        p.extend_from_slice(label.as_bytes());
    }
    p.push(0);
}

/// Reads a (possibly compressed) DNS name; returns (lowercase dotted name, next offset).
fn read_name(buf: &[u8], mut off: usize) -> Option<(String, usize)> {
    let mut labels: Vec<String> = Vec::new();
    let mut after: Option<usize> = None;
    let mut jumps = 0;
    loop {
        let len = *buf.get(off)? as usize;
        if len == 0 {
            off += 1;
            break;
        }
        if len & 0xC0 == 0xC0 {
            let ptr = ((len & 0x3F) << 8) | (*buf.get(off + 1)? as usize);
            if after.is_none() {
                after = Some(off + 2);
            }
            jumps += 1;
            if jumps > 32 {
                return None;
            }
            off = ptr;
            continue;
        }
        let label = buf.get(off + 1..off + 1 + len)?;
        labels.push(String::from_utf8_lossy(label).to_ascii_lowercase());
        off += 1 + len;
    }
    Some((labels.join("."), after.unwrap_or(off)))
}

fn query(questions: &[(&str, u16)]) -> Vec<u8> {
    let mut p = Vec::with_capacity(64);
    p.extend_from_slice(&[0, 0, 0, 0]); // id 0 (mDNS uses no id)
    p.extend_from_slice(&(questions.len() as u16).to_be_bytes());
    p.extend_from_slice(&[0, 0, 0, 0, 0, 0]); // an=0 ns=0 ar=0
    for (name, qtype) in questions {
        push_name(&mut p, name);
        p.extend_from_slice(&qtype.to_be_bytes());
        p.extend_from_slice(&0x8001u16.to_be_bytes()); // IN + unicast response
    }
    p
}

fn parse_answers(pkt: &[u8], recs: &mut Recs) -> Option<()> {
    let qd = u16::from_be_bytes([*pkt.get(4)?, *pkt.get(5)?]) as usize;
    let an = u16::from_be_bytes([*pkt.get(6)?, *pkt.get(7)?]) as usize;
    let ns = u16::from_be_bytes([*pkt.get(8)?, *pkt.get(9)?]) as usize;
    let ar = u16::from_be_bytes([*pkt.get(10)?, *pkt.get(11)?]) as usize;
    let mut off = 12usize;
    for _ in 0..qd {
        let (_, next) = read_name(pkt, off)?;
        off = next.checked_add(4)?; // qtype + qclass
    }
    for _ in 0..(an + ns + ar) {
        let (name, next) = read_name(pkt, off)?;
        off = next;
        let rtype = u16::from_be_bytes([*pkt.get(off)?, *pkt.get(off + 1)?]);
        let rdlen = u16::from_be_bytes([*pkt.get(off + 8)?, *pkt.get(off + 9)?]) as usize;
        let rstart = off.checked_add(10)?;
        if rstart.checked_add(rdlen)? > pkt.len() {
            return None;
        }
        match rtype {
            12 => {
                if let Some((target, _)) = read_name(pkt, rstart) {
                    if target.ends_with(SERVICE) {
                        recs.ptr.push(target);
                    }
                }
            }
            33 if rdlen >= 6 => {
                let port = u16::from_be_bytes([*pkt.get(rstart + 4)?, *pkt.get(rstart + 5)?]);
                if let Some((target, _)) = read_name(pkt, rstart + 6) {
                    recs.srv.insert(name, (port, target));
                }
            }
            1 if rdlen == 4 => {
                let ip = format!(
                    "{}.{}.{}.{}",
                    *pkt.get(rstart)?,
                    *pkt.get(rstart + 1)?,
                    *pkt.get(rstart + 2)?,
                    *pkt.get(rstart + 3)?
                );
                recs.a.insert(name, ip);
            }
            _ => {}
        }
        off = rstart + rdlen;
    }
    Some(())
}

fn resolve(recs: &Recs) -> Vec<Found> {
    let mut out: Vec<Found> = Vec::new();
    for inst in &recs.ptr {
        if out.iter().any(|f| f.instance == *inst) {
            continue;
        }
        let Some((port, host)) = recs.srv.get(inst) else {
            continue;
        };
        let Some(ip) = recs.a.get(host) else {
            continue;
        };
        out.push(Found {
            instance: inst.clone(),
            host: host.clone(),
            ip: ip.clone(),
            port: *port,
        });
    }
    out
}

fn exchange(sock: &UdpSocket, pkt: &[u8], dur: Duration, recs: &mut Recs) {
    let _ = sock.send_to(pkt, SocketAddrV4::new(MDNS, PORT));
    let deadline = Instant::now() + dur;
    let mut buf = [0u8; 4096];
    while Instant::now() < deadline {
        if let Ok((n, _)) = sock.recv_from(&mut buf) {
            let _ = parse_answers(&buf[..n], recs);
        }
    }
}

/// Browse for the phone's NSD service for up to `timeout` (plus follow-ups).
pub fn browse(timeout: Duration) -> Vec<Found> {
    let Ok(sock) = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, PORT))
        .or_else(|_| UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)))
    else {
        return Vec::new();
    };
    let _ = sock.join_multicast_v4(&MDNS, &Ipv4Addr::UNSPECIFIED);
    let _ = sock.set_read_timeout(Some(Duration::from_millis(250)));
    let mut recs = Recs::default();
    exchange(&sock, &query(&[(SERVICE, 12)]), timeout, &mut recs);
    let missing: Vec<String> = recs
        .ptr
        .iter()
        .filter(|i| !recs.srv.contains_key(*i))
        .cloned()
        .collect();
    if !missing.is_empty() {
        let q: Vec<(&str, u16)> = missing.iter().map(|n| (n.as_str(), 33u16)).collect();
        exchange(&sock, &query(&q), Duration::from_millis(900), &mut recs);
    }
    let hosts: Vec<String> = recs
        .srv
        .values()
        .map(|(_, h)| h.clone())
        .filter(|h| !recs.a.contains_key(h))
        .collect();
    if !hosts.is_empty() {
        let q: Vec<(&str, u16)> = hosts.iter().map(|n| (n.as_str(), 1u16)).collect();
        exchange(&sock, &query(&q), Duration::from_millis(900), &mut recs);
    }
    resolve(&recs)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Synthetic mDNS response: PTR(SERVICE) -> instance, SRV(instance) -> host:47600,
    /// A(host) -> 192.168.1.42, with record names encoded as compression pointers.
    fn pkt_with(service: &str) -> Vec<u8> {
        let instance = format!("unified-desktop.{service}");
        let host = "android.local";
        let mut rdata_ptr = Vec::new();
        push_name(&mut rdata_ptr, &instance);
        let mut rdata_srv = Vec::new();
        rdata_srv.extend_from_slice(&[0, 0, 0, 0]); // priority + weight
        rdata_srv.extend_from_slice(&47600u16.to_be_bytes());
        let host_in_srv = rdata_srv.len();
        push_name(&mut rdata_srv, host);

        let mut p: Vec<u8> = Vec::new();
        p.extend_from_slice(&[0, 0, 0x84, 0, 0, 0, 0, 3, 0, 0, 0, 0]); // an=3
        push_name(&mut p, service);
        let inst_off = p.len() + 10; // instance name sits in the PTR rdata
        p.extend_from_slice(&12u16.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&120u32.to_be_bytes());
        p.extend_from_slice(&(rdata_ptr.len() as u16).to_be_bytes());
        p.extend_from_slice(&rdata_ptr);
        p.extend_from_slice(&(0xC000u16 | inst_off as u16).to_be_bytes()); // SRV name = ptr
        p.extend_from_slice(&33u16.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&120u32.to_be_bytes());
        p.extend_from_slice(&(rdata_srv.len() as u16).to_be_bytes());
        let host_off = p.len() + host_in_srv;
        p.extend_from_slice(&rdata_srv);
        p.extend_from_slice(&(0xC000u16 | host_off as u16).to_be_bytes()); // A name = ptr
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&120u32.to_be_bytes());
        p.extend_from_slice(&4u16.to_be_bytes());
        p.extend_from_slice(&[192, 168, 1, 42]);
        p
    }

    #[test]
    fn parses_ptr_srv_a_with_compression() {
        let pkt = pkt_with(SERVICE);
        let mut recs = Recs::default();
        assert!(parse_answers(&pkt, &mut recs).is_some());
        let found = resolve(&recs);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].instance, "unified-desktop._udlink._tcp.local");
        assert_eq!(found[0].host, "android.local");
        assert_eq!(found[0].ip, "192.168.1.42");
        assert_eq!(found[0].port, 47600);
    }

    #[test]
    fn ignores_other_services() {
        let pkt = pkt_with("_other._tcp.local");
        let mut recs = Recs::default();
        assert!(parse_answers(&pkt, &mut recs).is_some());
        assert!(recs.srv.len() == 1); // SRV parsed, but the PTR filter drops it
        assert!(resolve(&recs).is_empty());
    }

    #[test]
    fn truncated_packet_is_rejected() {
        let pkt = pkt_with(SERVICE);
        let mut recs = Recs::default();
        assert!(parse_answers(&pkt[..pkt.len() - 3], &mut recs).is_none());
    }

    #[test]
    fn query_sets_qu_and_question_count() {
        let q = query(&[(SERVICE, 12)]);
        assert_eq!(&q[4..6], &1u16.to_be_bytes()); // qd=1
        assert_eq!(*q.last().unwrap(), 0x01); // unicast-response bit in qclass
        assert_eq!(q[12], 7); // first label "_udlink" (7 bytes)
    }
}
