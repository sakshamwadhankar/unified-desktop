//! unified-desktop Phase-4 PC receiver skeleton (D16: Rust).
//! Usage:
//!   udreceiver discover [--seconds <s>] [--to <ip[:port]>]  mDNS browse for the phone (item 7)
//!   udreceiver pair --addr <ip:port|auto> [--label <label>]  first pair (6-digit code compare)
//!   udreceiver bench --addr <ip:port|auto> [--seconds <s>]   RTT + throughput against a paired phone
//!   udreceiver trust list | trust forget <fpr-prefix>       the trusted-phone store
//! The phone side runs the same protocol (Phase4LinkService in the Android app).

#[cfg(windows)]
mod aoa;
mod crypto;
mod mdns;
mod phase5;
mod protocol;
mod session;
mod view;

use std::process::ExitCode;

use crypto::TrustedPhoneStore;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        print_usage();
        return ExitCode::from(2);
    }
    match args[1].as_str() {
        "discover" => cmd_discover(&args[2..]),
        #[cfg(windows)]
        "aoa" => cmd_aoa(&args[2..]),
        "pair" => cmd_pair(&args[2..]),
        "bench" => cmd_bench(&args[2..]),
        "view" => cmd_view(&args[2..]),
        "trust" => cmd_trust(&args[2..]),
        "--help" | "-h" | "help" => {
            print_usage();
            ExitCode::SUCCESS
        }
        other => {
            eprintln!("unknown command '{other}'");
            print_usage();
            ExitCode::from(2)
        }
    }
}

fn flag(args: &[String], name: &str, default: &str) -> String {
    if let Some(i) = args.iter().position(|a| a == name) {
        if let Some(v) = args.get(i + 1) {
            return v.clone();
        }
    }
    default.to_string()
}

fn print_usage() {
    println!(
        "usage:\n  udreceiver discover [--seconds <s>] [--to <ip[:port]>]\n  \
         udreceiver aoa list | probe | start [--vid <hex>]      (Windows host, item 8)\n  \
         udreceiver pair --addr <ip:port|auto> [--label <label>]\n  \
         udreceiver bench --addr <ip:port|auto> [--seconds <s>] [--window-kib <n>]\n  \
         udreceiver view --addr <ip:port|auto> [--seconds <s>] [--soft]   full-screen desktop \
         (Esc quits, F11 windowed)\n  \
         udreceiver trust list\n  udreceiver trust forget <fpr-prefix>"
    );
}

fn ident() -> crypto::ReceiverIdentity {
    let id = crypto::ReceiverIdentity::load_or_create();
    println!(
        "PC identity: {} (dir {})",
        &crypto::fingerprint_hex(&id.fingerprint())[..16],
        crypto::APP_DIR_NAME
    );
    id
}

/// AOA/USB accessory helpers (item 8, Windows-only).
#[cfg(windows)]
fn cmd_aoa(rest: &[String]) -> ExitCode {
    let vid = flag(rest, "--vid", "");
    match rest.first().map(|s| s.as_str()).unwrap_or("") {
        "list" => {
            aoa::cmd_list(&vid);
            ExitCode::SUCCESS
        }
        "probe" => ExitCode::from(u8::from(!aoa::cmd_probe(&vid))),
        "start" => ExitCode::from(u8::from(!aoa::cmd_start(&vid))),
        _ => {
            eprintln!("usage: udreceiver aoa list | probe | start [--vid <hex>]");
            ExitCode::from(2)
        }
    }
}

fn cmd_discover(rest: &[String]) -> ExitCode {
    let secs: u64 = flag(rest, "--seconds", "2").parse().unwrap_or(2);
    let to = flag(rest, "--to", "");
    let to_addr = if to.is_empty() {
        None
    } else {
        let parsed = if to.contains(':') {
            to.parse::<std::net::SocketAddrV4>()
        } else {
            to.parse::<std::net::Ipv4Addr>()
                .map(|ip| std::net::SocketAddrV4::new(ip, 5353))
        };
        match parsed {
            Ok(a) => Some(a),
            Err(_) => {
                eprintln!("--to: '{to}' is not <ip> or <ip:port>");
                return ExitCode::from(2);
            }
        }
    };
    match to_addr {
        None => println!(
            "discover: browsing mDNS for {} (QU to 224.0.0.251:5353 from each local address, {}s)...",
            mdns::SERVICE,
            secs.max(1)
        ),
        Some(a) => println!(
            "discover: unicast query for {} to {a} ({}s)...",
            mdns::SERVICE,
            secs.max(1)
        ),
    }
    let found = mdns::browse_with(std::time::Duration::from_secs(secs.max(1)), to_addr);
    if found.is_empty() {
        eprintln!(
            "DISCOVER none: no answer for {}. Check: phone and PC on the same Wi-Fi, link \
             service running on the phone (open the app, tap Start link), and no VPN or \
             split-tunnel capturing mDNS (e.g. WARP intercepting 224.0.0.251:5353).",
            mdns::SERVICE
        );
        return ExitCode::FAILURE;
    }
    for f in &found {
        println!("DISCOVER {} {}:{} host={}", f.instance, f.ip, f.port, f.host);
    }
    ExitCode::SUCCESS
}

/// `--addr <ip:port>|auto`: "auto" (or a missing --addr) runs mDNS discovery (item 7).
fn resolve_addr(rest: &[String]) -> Result<String, String> {
    let addr = flag(rest, "--addr", "");
    if !addr.is_empty() && addr != "auto" {
        return Ok(addr);
    }
    println!("addr=auto: browsing mDNS for {}...", mdns::SERVICE);
    let found = mdns::browse(std::time::Duration::from_secs(2));
    let Some(f) = found.first() else {
        return Err(
            "no phone discovered via mDNS (link service running? same Wi-Fi? WARP \
             capturing 224.0.0.251:5353?)"
                .to_string(),
        );
    };
    if found.len() > 1 {
        println!("auto: {} phones answered, using the first", found.len());
    }
    println!("auto: discovered {} at {}:{}", f.instance, f.ip, f.port);
    Ok(format!("{}:{}", f.ip, f.port))
}

fn cmd_pair(rest: &[String]) -> ExitCode {
    let addr = match resolve_addr(rest) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("--addr: {e}");
            return ExitCode::from(2);
        }
    };
    let label = flag(rest, "--label", "unified-desktop phone");
    let id = ident();
    let store = TrustedPhoneStore::open();
    crypto::ensure_ring_default();
    match session::connect_and_pair(&addr, &id, &store, &label) {
        Ok((_session, description)) => {
            println!("OK: {description}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("PAIR FAILED: {e}");
            ExitCode::FAILURE
        }
    }
}

fn cmd_bench(rest: &[String]) -> ExitCode {
    let addr = match resolve_addr(rest) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("--addr: {e}");
            return ExitCode::from(2);
        }
    };
    let seconds: u64 = flag(rest, "--seconds", "5").parse().unwrap_or(5);
    let window_default = session::BENCH_WINDOW_BYTES / 1024;
    let window_kib: u64 = flag(rest, "--window-kib", &window_default.to_string())
        .parse()
        .unwrap_or(window_default);
    let id = ident();
    let store = TrustedPhoneStore::open();
    crypto::ensure_ring_default();
    // The bench must only run against an already-pinned phone; if this phone was never
    // paired from THIS run, do a quiet pair first (no code display on a pinned session).
    match session::connect_and_pair(&addr, &id, &store, "unified-desktop phone") {
        Ok((mut s, description)) => {
            println!("link: {description}");
            // A stalled peer (or a dead adb-forward pipe) must fail cleanly, not hang.
            let _ = s
                .tls
                .sock
                .set_read_timeout(Some(std::time::Duration::from_secs(60)));
            match session::bench(&mut s, seconds, window_kib * 1024) {
                Ok(r) => {
                    print!("{}", r.render(&format!("PC -> {addr}, pinned TLS session")));
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("BENCH FAILED: {e}");
                    ExitCode::FAILURE
                }
            }
        }
        Err(e) => {
            eprintln!("CONNECT FAILED: {e}");
            ExitCode::FAILURE
        }
    }
}

/// `view`: the Phase-5 full-screen desktop session (hardware H.264 decode + input capture).
fn cmd_view(rest: &[String]) -> ExitCode {
    let addr = match resolve_addr(rest) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("--addr: {e}");
            return ExitCode::from(2);
        }
    };
    let seconds: u64 = flag(rest, "--seconds", "0").parse().unwrap_or(0);
    let soft = rest.iter().any(|a| a == "--soft");
    let id = ident();
    let store = TrustedPhoneStore::open();
    crypto::ensure_ring_default();
    match view::run(&addr, seconds, soft, &id, &store) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("VIEW FAILED: {e}");
            ExitCode::FAILURE
        }
    }
}

fn cmd_trust(rest: &[String]) -> ExitCode {    let store = TrustedPhoneStore::open();
    match rest.first().map(|s| s.as_str()) {
        Some("list") => {
            let rows = store.entries();
            if rows.is_empty() {
                println!("(no trusted phones)");
            }
            for (fpr, label, added) in rows {
                println!("{}  label={}  added={added}", fpr, label);
            }
            ExitCode::SUCCESS
        }
        Some("forget") => {
            let Some(prefix) = rest.get(1) else {
                eprintln!("usage: trust forget <fpr-prefix>");
                return ExitCode::from(2);
            };
            if store.forget(prefix) {
                println!("forgotten.");
                ExitCode::SUCCESS
            } else {
                println!("no entry matched '{prefix}'.");
                ExitCode::SUCCESS
            }
        }
        _ => {
            print_usage();
            ExitCode::from(2)
        }
    }
}
