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
//! On a laptop with several adapters (VPN client, hypervisor switches, Wi-Fi Direct,
//! WSL), an mDNS send from a 0.0.0.0-bound socket can leave through the wrong interface
//! and never reach the LAN: the same query from a socket bound to the address the OS
//! uses for the multicast group does reach it (item 7 finding, 2026-10-18). browse
//! therefore binds that address when browsing (`--to` keeps the plain socket).
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

fn exchange(sock: &UdpSocket, pkt: &[u8], dur: Duration, recs: &mut Recs, dest: SocketAddrV4) {
    let _ = sock.send_to(pkt, dest);
    let deadline = Instant::now() + dur;
    let mut buf = [0u8; 4096];
    while Instant::now() < deadline {
        if let Ok((n, _)) = sock.recv_from(&mut buf) {
            let _ = parse_answers(&buf[..n], recs);
        }
    }
}

/// Local IPv4 addresses a phone on the LAN could be reached from: the machine's own
/// addresses from host-name resolution (Windows registers every adapter there), without
/// loopback/link-local, de-duplicated, ordinary 192.168/10/172.16-31 LAN ranges first.
/// WARP, hypervisor switches and WSL adapters stay in the list as later attempts -- they
/// simply do not answer, so the browse costs a short probe each.
fn lan_candidates() -> Vec<Ipv4Addr> {
    use std::net::ToSocketAddrs;
    let mut v: Vec<Ipv4Addr> = Vec::new();
    let host = std::env::var("COMPUTERNAME").unwrap_or_default();
    if !host.is_empty() {
        if let Ok(addrs) = (host.as_str(), 0u16).to_socket_addrs() {
            for a in addrs {
                if let std::net::SocketAddr::V4(v4) = a {
                    let ip = *v4.ip();
                    if !ip.is_loopback()
                        && !ip.is_unspecified()
                        && !ip.is_multicast()
                        && !ip.is_link_local()
                        && !v.contains(&ip)
                    {
                        v.push(ip);
                    }
                }
            }
        }
    }
    v.sort_by_key(|ip| {
        let o = ip.octets();
        if o[0] == 192 && o[1] == 168 {
            0
        } else if o[0] == 10 {
            1
        } else if o[0] == 172 && (16..=31).contains(&o[1]) {
            3
        } else {
            2
        }
    });
    v
}

/// Browse for the phone's NSD service for up to `timeout` (plus follow-ups).
pub fn browse(timeout: Duration) -> Vec<Found> {
    browse_with(timeout, None)
}

/// `to = Some(addr)` sends the query unicast to `addr` instead of 224.0.0.251:5353.
/// Useful when a local process (on Windows, the DNS Client service) owns port 5353, and
/// as a diagnostic of the phone's mDNS answer path (item 7).
///
/// Multicast browse tries each local IPv4 address as the socket's source, because with
/// several adapters (WARP, VMware, WSL, Wi-Fi Direct) an unbound socket's multicast send
/// can leave through a tunnel that never reaches the LAN (item 7 finding: the same
/// query from a socket bound to the Wi-Fi address is answered). First address that
/// answers wins; the unbound multicast path stays as the last attempt.
pub fn browse_with(timeout: Duration, to: Option<SocketAddrV4>) -> Vec<Found> {
    if let Some(dest) = to {
        return browse_once(Ipv4Addr::UNSPECIFIED, timeout, dest, false);
    }
    let dest = SocketAddrV4::new(MDNS, PORT);
    for ip in lan_candidates() {
        let found = browse_once(ip, Duration::from_millis(700), dest, true);
        if !found.is_empty() {
            eprintln!("mdns: answer via {ip}");
            return found;
        }
    }
    let found = browse_once(Ipv4Addr::UNSPECIFIED, timeout, dest, true);
    if !found.is_empty() {
        eprintln!("mdns: answer via 0.0.0.0 (OS-chosen interface)");
    }
    found
}

/// One browse attempt: bind `bind_ip` (0.0.0.0 = let the OS choose), ask for the PTR,
/// follow up on missing SRV/A records, and resolve. Joins the multicast group when the
/// destination is the mDNS group, so multicast answers on port 5353 are heard too.
fn browse_once(
    bind_ip: Ipv4Addr,
    timeout: Duration,
    dest: SocketAddrV4,
    join_group: bool,
) -> Vec<Found> {
    let Ok(sock) = UdpSocket::bind((bind_ip, PORT))
        .or_else(|_| UdpSocket::bind((bind_ip, 0)))
        .or_else(|_| UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)))
    else {
        return Vec::new();
    };
    if join_group {
        let _ = sock.join_multicast_v4(&MDNS, &bind_ip);
    }
    let _ = sock.set_read_timeout(Some(Duration::from_millis(250)));
    let mut recs = Recs::default();
    exchange(&sock, &query(&[(SERVICE, 12)]), timeout, &mut recs, dest);
    let missing: Vec<String> = recs
        .ptr
        .iter()
        .filter(|i| !recs.srv.contains_key(*i))
        .cloned()
        .collect();
    if !missing.is_empty() {
        let q: Vec<(&str, u16)> = missing.iter().map(|n| (n.as_str(), 33u16)).collect();
        exchange(&sock, &query(&q), Duration::from_millis(900), &mut recs, dest);
    }
    let hosts: Vec<String> = recs
        .srv
        .values()
        .map(|(_, h)| h.clone())
        .filter(|h| !recs.a.contains_key(h))
        .collect();
    if !hosts.is_empty() {
        let q: Vec<(&str, u16)> = hosts.iter().map(|n| (n.as_str(), 1u16)).collect();
        exchange(&sock, &query(&q), Duration::from_millis(900), &mut recs, dest);
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

    #[test]
    fn lan_candidates_are_unicast_and_deduped() {
        let v = lan_candidates();
        let mut seen = std::collections::HashSet::new();
        for ip in &v {
            assert!(!ip.is_loopback() && !ip.is_multicast() && !ip.is_unspecified());
            assert!(seen.insert(*ip), "duplicate candidate {ip}");
        }
    }

    /// End-to-end socket path: a loopback "phone" answers one query; browse_with must
    /// bind, send, receive (unicast), parse and hand back the resolved address.
    #[test]
    fn socket_roundtrip_unicast_responder() {
        use std::net::UdpSocket;
        let responder = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let SocketAddrV4 { .. } = match responder.local_addr().unwrap() {
            std::net::SocketAddr::V4(v4) => v4,
            std::net::SocketAddr::V6(_) => panic!("expected v4"),
        };
        let responder_port = responder.local_addr().unwrap().port();
        responder
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let answer = pkt_with(SERVICE);
        let t = std::thread::spawn(move || {
            let mut buf = [0u8; 512];
            if let Ok((n, from)) = responder.recv_from(&mut buf) {
                assert!(n > 12, "expected an mDNS query, got {n} bytes");
                let _ = responder.send_to(&answer, from);
            }
        });
        let found = browse_with(
            Duration::from_millis(700),
            Some(SocketAddrV4::new(Ipv4Addr::LOCALHOST, responder_port)),
        );
        t.join().unwrap();
        assert_eq!(found.len(), 1, "found={found:?}");
        assert_eq!(found[0].instance, "unified-desktop._udlink._tcp.local");
        assert_eq!(found[0].ip, "192.168.1.42");
        assert_eq!(found[0].port, 47600);
    }
}
