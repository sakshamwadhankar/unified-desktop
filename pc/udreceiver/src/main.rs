//! unified-desktop Phase-4 PC receiver skeleton (D16: Rust).
//! Usage:
//!   udrxcb pair --addr <ip:port> [--label <label>]     first pair (6-digit code compare)
//!   udrxcb bench --addr <ip:port> [--seconds <s>]      RTT + throughput against a paired phone
//!   udrxcb trust list | trust forget <fpr-prefix>      the trusted-phone store
//! The phone side runs the same protocol (PcLinkService in the Android app).

mod crypto;
mod protocol;
mod session;

use std::process::ExitCode;

use crypto::TrustedPhoneStore;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        print_usage();
        return ExitCode::from(2);
    }
    match args[1].as_str() {
        "pair" => cmd_pair(&args[2..]),
        "bench" => cmd_bench(&args[2..]),
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
        "usage:\n  udrxcb pair --addr <ip:port> [--label <label>]\n  \
         udrxcb bench --addr <ip:port> [--seconds <s>]\n  \
         udrxcb trust list\n  udrxcb trust forget <fpr-prefix>"
    );
}

fn ident() -> crypto::ReceiverIdentity {
    let id = crypto::ReceiverIdentity::load_or_create();
    println!(
        "PC identity: {} (dir {})",
        crypto::fingerprint_hex(&id.fingerprint())[..16].to_string(),
        crypto::APP_DIR_NAME
    );
    id
}

fn cmd_pair(rest: &[String]) -> ExitCode {
    let addr = flag(rest, "--addr", "");
    if addr.is_empty() {
        eprintln!("--addr <ip:port> is required");
        return ExitCode::from(2);
    }
    let label = flag(rest, "--label", "unified-desktop phone");
    let id = ident();
    let store = TrustedPhoneStore::open();
    let _ = crypto::ensure_ring_default();
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
    let addr = flag(rest, "--addr", "");
    if addr.is_empty() {
        eprintln!("--addr <ip:port> is required");
        return ExitCode::from(2);
    }
    let seconds: u64 = flag(rest, "--seconds", "5").parse().unwrap_or(5);
    let id = ident();
    let store = TrustedPhoneStore::open();
    let _ = crypto::ensure_ring_default();
    // The bench must only run against an already-pinned phone; if this phone was never
    // paired from THIS run, do a quiet pair first (no code display on a pinned session).
    match session::connect_and_pair(&addr, &id, &store, "unified-desktop phone") {
        Ok((mut s, description)) => {
            println!("link: {description}");
            match session::bench(&mut s, seconds) {
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

fn cmd_trust(rest: &[String]) -> ExitCode {
    let store = TrustedPhoneStore::open();
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
